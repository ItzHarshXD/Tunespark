package com.tunespark.music

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.WatchEndpoint
import com.tunespark.music.ui.theme.TunesparkTheme
import com.tunespark.music.ui.screens.*
import com.tunespark.music.ui.screens.LyricLine
import com.tunespark.music.ui.screens.cleanYouTubeTitle
import com.tunespark.music.ui.screens.parseLyricsToLines
import com.metrolist.lrclib.LrcLib
import com.tunespark.music.rss.Article
import com.tunespark.music.rss.RssRepository
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

private fun getScreenDepth(screen: AppScreen): Int {
    return when (screen) {
        AppScreen.HOME -> 0
        AppScreen.SETTINGS,
        AppScreen.RECENTS,
        AppScreen.PLAYLISTS,
        AppScreen.SEARCH,
        AppScreen.DISCOVER -> 1
        AppScreen.APPEARANCE,
        AppScreen.AI_VOICE,
        AppScreen.COMMENTARY,
        AppScreen.PLAYER_AUDIO,
        AppScreen.LOCATION,
        AppScreen.UPDATES,
        AppScreen.ACCOUNT,
        AppScreen.DISCOVER_FEED -> 2
        else -> 1
    }
}

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var onNewIntentListener: ((Intent) -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onNewIntentListener?.invoke(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize saved YouTube session cookie
        SessionManager.initialize(applicationContext)

        // The UI talks to PlaybackService through MediaController; the service owns
        // ExoPlayer so playback survives activity recreation and backgrounding.
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        setContent {
            val context = LocalContext.current
            var themeSetting by remember { mutableStateOf(SessionManager.getTheme(context)) }
            var controller by remember { mutableStateOf<MediaController?>(null) }

            DisposableEffect(Unit) {
                val listener = Runnable {
                    try {
                        controller = controllerFuture?.get()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                controllerFuture?.addListener(listener, ContextCompat.getMainExecutor(this@MainActivity))
                onDispose {
                    controllerFuture?.let { MediaController.releaseFuture(it) }
                }
            }

            TunesparkTheme(themeSetting = themeSetting) {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    controller?.let { player ->
                        MainPlayerScreen(
                            exoPlayer = player,
                            themeSetting = themeSetting,
                            onThemeSettingChange = { newTheme ->
                                themeSetting = newTheme
                                SessionManager.saveTheme(context, newTheme)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

enum class AppScreen {
    HOME,
    SEARCH,
    RADIO,
    SETTINGS,
    ACCOUNT,
    APPEARANCE,
    AI_VOICE,
    COMMENTARY,
    PLAYER_AUDIO,
    LOCATION,
    UPDATES,
    PLAYLISTS,
    RECENTS,
    DISCOVER,
    DISCOVER_FEED
}

@Composable
fun MainPlayerScreen(
    exoPlayer: Player,
    themeSetting: String,
    onThemeSettingChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.HOME) }
    var isAccountWebViewShowing by remember { mutableStateOf(false) }

    var isFullPlayerOpen by rememberSaveable { mutableStateOf(false) }
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    var isLibraryOpen by rememberSaveable { mutableStateOf(false) }

    val fullPlayerProgress = remember { Animatable(if (isFullPlayerOpen) 1f else 0f) }
    val searchProgress = remember { Animatable(if (isSearchOpen) 1f else 0f) }
    val libraryProgress = remember { Animatable(if (isLibraryOpen) 1f else 0f) }

    val openFullPlayer: () -> Unit = {
        isFullPlayerOpen = true
        isSearchOpen = false
        isLibraryOpen = false
        coroutineScope.launch {
            launch {
                searchProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            launch {
                libraryProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            fullPlayerProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val closeFullPlayer: () -> Unit = {
        isFullPlayerOpen = false
        coroutineScope.launch {
            fullPlayerProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val openSearch: () -> Unit = {
        isFullPlayerOpen = false
        isSearchOpen = true
        isLibraryOpen = false
        coroutineScope.launch {
            launch {
                fullPlayerProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            launch {
                libraryProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            searchProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val closeSearch: () -> Unit = {
        isSearchOpen = false
        coroutineScope.launch {
            searchProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val openLibrary: () -> Unit = {
        isFullPlayerOpen = false
        isSearchOpen = false
        isLibraryOpen = true
        coroutineScope.launch {
            launch {
                fullPlayerProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            launch {
                searchProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            }
            libraryProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val closeLibrary: () -> Unit = {
        isLibraryOpen = false
        coroutineScope.launch {
            libraryProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val navigateHandler: (AppScreen) -> Unit = { screen ->
        if (screen == AppScreen.RADIO) {
            openFullPlayer()
        } else if (screen == AppScreen.SEARCH) {
            openSearch()
        } else if (screen == AppScreen.PLAYLISTS) {
            openLibrary()
        } else if (screen == AppScreen.HOME) {
            currentScreen = AppScreen.HOME
            closeFullPlayer()
            closeSearch()
            closeLibrary()
        } else {
            currentScreen = screen
            closeFullPlayer()
            closeSearch()
            closeLibrary()
        }
    }

    BackHandler(enabled = fullPlayerProgress.value > 0f || searchProgress.value > 0f || libraryProgress.value > 0f || currentScreen != AppScreen.HOME) {
        if (fullPlayerProgress.value > 0f) {
            closeFullPlayer()
        } else if (searchProgress.value > 0f) {
            closeSearch()
        } else if (libraryProgress.value > 0f) {
            closeLibrary()
        } else if (currentScreen != AppScreen.HOME) {
            when (currentScreen) {
                AppScreen.SETTINGS, AppScreen.RECENTS, AppScreen.DISCOVER -> {
                    navigateHandler(AppScreen.HOME)
                }
                AppScreen.APPEARANCE, AppScreen.AI_VOICE, AppScreen.COMMENTARY,
                AppScreen.PLAYER_AUDIO, AppScreen.LOCATION, AppScreen.UPDATES,
                AppScreen.ACCOUNT, AppScreen.DISCOVER_FEED -> {
                    navigateHandler(AppScreen.SETTINGS)
                }
                else -> {
                    navigateHandler(AppScreen.HOME)
                }
            }
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == AppScreen.RADIO) {
            currentScreen = AppScreen.HOME
            openFullPlayer()
        } else if (currentScreen == AppScreen.SEARCH) {
            currentScreen = AppScreen.HOME
            openSearch()
        } else if (currentScreen == AppScreen.PLAYLISTS) {
            currentScreen = AppScreen.HOME
            openLibrary()
        }
        if (currentScreen != AppScreen.ACCOUNT) {
            isAccountWebViewShowing = false
        }
    }

    DisposableEffect(context) {
        val activity = context as? MainActivity
        
        // 1. Check for cold start action if present
        val initialIntent = activity?.intent
        if (initialIntent?.action == "com.tunespark.music.action.SHOW_PLAYER") {
            currentScreen = AppScreen.RADIO
            initialIntent.action = null // Clear/consume action
        }
        
        // 2. Set listener for new intents (warm starts)
        activity?.onNewIntentListener = { intent ->
            if (intent.action == "com.tunespark.music.action.SHOW_PLAYER") {
                currentScreen = AppScreen.RADIO
                intent.action = null // Clear/consume action
            }
        }
        
        onDispose {
            activity?.onNewIntentListener = null
        }
    }
    var accountInfo by remember { mutableStateOf<com.metrolist.innertube.models.AccountInfo?>(SessionManager.getCachedAccountInfo(context)) }

    // State variables for initial playlist navigation to Playlists screen
    var initialPlaylistId by remember { mutableStateOf<String?>(null) }
    var initialPlaylistName by remember { mutableStateOf("") }
    var initialPlaylistThumbnail by remember { mutableStateOf<String?>(null) }
    var initialPlaylistSongCountText by remember { mutableStateOf("") }
    var initialPlaylistIsLiked by remember { mutableStateOf(false) }
    var initialPlaylistRawItem by remember { mutableStateOf<com.metrolist.innertube.models.YTItem?>(null) }
    var initialPlaylistAuthorName by remember { mutableStateOf<String?>(null) }
    var initialPlaylistAuthorAvatarUrl by remember { mutableStateOf<String?>(null) }
    var initialPlaylistSongs by remember { mutableStateOf<List<com.metrolist.innertube.models.SongItem>>(emptyList()) }
    var isLoadingProfile by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (SessionManager.isUserSignedIn(context)) {
            isLoadingProfile = true
            coroutineScope.launch(Dispatchers.IO) {
                val result = YouTube.accountInfo()
                withContext(Dispatchers.Main) {
                    isLoadingProfile = false
                    if (result.isSuccess) {
                        val info = result.getOrNull()
                        if (info != null) {
                            accountInfo = info
                            SessionManager.saveAccountInfo(context, info)
                        }
                    } else {
                        profileError = "Session expired. Please sign in again."
                        SessionManager.clearSession(context)
                        accountInfo = null
                    }
                }
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var isShuffling by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Ready to search and play!") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var currentSongTitle by remember { mutableStateOf("No Track Loaded") }
    var currentSongArtist by remember { mutableStateOf("") }
    var currentSongArtwork by remember { mutableStateOf<String?>(null) }

    var hoistedLyricsLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var hoistedIsLyricsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentSongTitle, currentSongArtist) {
        if (currentSongTitle.startsWith("AI") || currentSongTitle == "Music Context" || currentSongTitle.startsWith("commentary_")) {
            val description = exoPlayer.currentMediaItem?.mediaMetadata?.description?.toString() ?: ""
            if (description.isNotBlank()) {
                hoistedLyricsLines = listOf(LyricLine(-1L, description))
            } else {
                hoistedLyricsLines = listOf(LyricLine(-1L, "AI is speaking..."))
            }
            hoistedIsLyricsLoading = false
            return@LaunchedEffect
        }

        if (currentSongTitle.isEmpty() || currentSongTitle == "No Track Loaded") {
            hoistedLyricsLines = listOf(LyricLine(-1L, "No lyrics available for this track."))
            hoistedIsLyricsLoading = false
            return@LaunchedEffect
        }

        hoistedIsLyricsLoading = true
        hoistedLyricsLines = emptyList()

        // Get duration on the Main/UI thread before switching context to IO dispatcher
        val durationMs = exoPlayer.duration
        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else -1

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                // 1. Clean track metadata to eliminate YouTube specific promotional garbage
                val cleanedTitle = cleanYouTubeTitle(currentSongTitle)
                val cleanedArtist = currentSongArtist.trim()

                // 2. Primary Query: Cleaned Title + Artist + Duration (most precise)
                var result = LrcLib.getLyrics(
                    title = cleanedTitle,
                    artist = cleanedArtist,
                    duration = durationSec
                )
                var rawLyrics = result.getOrNull()

                // 3. Fallback 1: Drop strict duration constraints (search name only)
                if (rawLyrics == null) {
                    result = LrcLib.getLyrics(
                        title = cleanedTitle,
                        artist = cleanedArtist,
                        duration = -1
                    )
                    rawLyrics = result.getOrNull()
                }

                // 4. Fallback 2: General text query (drop standard fields, perform text lookup)
                if (rawLyrics == null) {
                    val searchResult = LrcLib.lyrics(artist = cleanedArtist, title = cleanedTitle)
                    val tracks = searchResult.getOrNull()
                    if (!tracks.isNullOrEmpty()) {
                        val matchedTrack = tracks.firstOrNull { it.syncedLyrics != null || it.plainLyrics != null }
                        rawLyrics = matchedTrack?.syncedLyrics ?: matchedTrack?.plainLyrics
                    }
                }

                withContext(Dispatchers.Main) {
                    if (rawLyrics != null) {
                        val parsed = parseLyricsToLines(rawLyrics)
                        hoistedLyricsLines = if (parsed.isNotEmpty()) parsed else listOf(LyricLine(-1L, "No lyrics found."))
                    } else {
                        hoistedLyricsLines = listOf(LyricLine(-1L, "No lyrics found for this song."))
                    }
                    hoistedIsLyricsLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hoistedLyricsLines = listOf(LyricLine(-1L, "Could not load lyrics: ${e.localizedMessage ?: "Unknown error"}"))
                    hoistedIsLyricsLoading = false
                }
            }
        }
    }

    var localHistorySize by remember { mutableStateOf(SessionManager.getLocalHistory(context).size) }

    LaunchedEffect(currentSongTitle, currentSongArtist) {
        if (currentSongTitle != "No Track Loaded" &&
            currentSongTitle.isNotBlank() &&
            !currentSongTitle.startsWith("AI") &&
            currentSongTitle != "Music Context" &&
            !currentSongTitle.startsWith("commentary_") &&
            currentSongArtist != "Tunespark Radio") {
            
            var secondsPlayed = 0
            while (secondsPlayed < 10) {
                kotlinx.coroutines.delay(1000)
                if (isPlaying && playbackState == Player.STATE_READY) {
                    secondsPlayed++
                }
            }
            
            // Re-verify that it is still playing the same song and in READY state
            val currentMediaItem = exoPlayer.currentMediaItem
            if (currentMediaItem != null) {
                val mediaId = currentMediaItem.mediaId
                val title = currentMediaItem.mediaMetadata.title?.toString() ?: ""
                val artist = currentMediaItem.mediaMetadata.artist?.toString() ?: ""
                val artwork = currentMediaItem.mediaMetadata.artworkUri?.toString() ?: ""
                
                if (mediaId.isNotBlank() &&
                    !mediaId.startsWith("commentary_") &&
                    title != "No Track Loaded" &&
                    title.isNotBlank() &&
                    artist != "Tunespark Radio" &&
                    title == currentSongTitle) { // Ensure same song is still playing
                    
                    val artistList = artist.split(", ").map {
                        Artist(name = it, id = null)
                    }
                    val songItem = SongItem(
                        id = mediaId,
                        title = title,
                        artists = artistList,
                        thumbnail = artwork
                    )
                    SessionManager.addSongToHistory(context, songItem)
                    localHistorySize = SessionManager.getLocalHistory(context).size
                }
            }
        }
    }

    var playQueue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var currentTrackIndex by remember { mutableStateOf(-1) }
    var hasPreviousTrack by remember { mutableStateOf(false) }
    var hasNextTrack by remember { mutableStateOf(false) }

    // Mirror the MediaController state into Compose state so the queue tab,
    // metadata, and transport buttons stay in sync with the background service.
    DisposableEffect(exoPlayer) {
        val updateQueue = {
            val items = mutableListOf<MediaItem>()
            for (i in 0 until exoPlayer.mediaItemCount) {
                items.add(exoPlayer.getMediaItemAt(i))
            }
            playQueue = items
            currentTrackIndex = exoPlayer.currentMediaItemIndex
            hasPreviousTrack = exoPlayer.hasPreviousMediaItem()
            hasNextTrack = exoPlayer.hasNextMediaItem()
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                when (state) {
                    Player.STATE_BUFFERING -> {
                        isLoading = true
                        statusMessage = "Buffering song..."
                    }
                    Player.STATE_READY -> {
                        isLoading = false
                        statusMessage = "Ready to Play"
                    }
                    Player.STATE_ENDED -> {
                        isLoading = false
                        statusMessage = "Playback Ended"
                    }
                    Player.STATE_IDLE -> {
                        isLoading = false
                        statusMessage = "Ready to search and play!"
                    }
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                currentSongTitle = mediaMetadata.title?.toString() ?: "No Track Loaded"
                currentSongArtist = mediaMetadata.artist?.toString() ?: ""
                currentSongArtwork = mediaMetadata.artworkUri?.toString()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                updateQueue()
            }
        }
        exoPlayer.addListener(listener)
        isPlaying = exoPlayer.isPlaying
        playbackState = exoPlayer.playbackState

        // Initialize state from current player session
        currentSongTitle = exoPlayer.mediaMetadata.title?.toString() ?: "No Track Loaded"
        currentSongArtist = exoPlayer.mediaMetadata.artist?.toString() ?: ""
        currentSongArtwork = exoPlayer.mediaMetadata.artworkUri?.toString()
        updateQueue()

        if (playbackState == Player.STATE_READY) {
            isLoading = false
            statusMessage = "Playing"
        }

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Search uses InnerTube song results only; stream URLs are resolved later
    // when the user chooses a specific track.
    val triggerSearch = {
        if (searchQuery.isNotBlank()) {
            isSearching = true
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val result = YouTube.search(query = searchQuery, filter = YouTube.SearchFilter.FILTER_SONG)
                    withContext(Dispatchers.Main) {
                        isSearching = false
                        if (result.isSuccess) {
                            val items = result.getOrNull()?.items ?: emptyList()
                            searchResults = items.filterIsInstance<SongItem>()
                            if (searchResults.isEmpty()) {
                                statusMessage = "No songs found for '$searchQuery'"
                            }
                        } else {
                            errorMessage = "Search failed: ${result.exceptionOrNull()?.message}"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isSearching = false
                        errorMessage = "Error searching: ${e.message}"
                    }
                }
            }
        }
    }

    // Play list of songs in playlist mode starting from a specific index
    fun playPlaylist(playlistName: String, songs: List<SongItem>, startIndex: Int) {
        isLoading = true
        errorMessage = null
        statusMessage = "Loading playlist '$playlistName'..."
        PlaybackService.isPlaylistMode = true

        coroutineScope.launch {
            if (songs.isEmpty() || startIndex !in songs.indices) {
                isLoading = false
                statusMessage = "Playlist is empty or invalid index."
                return@launch
            }

            val targetSong = songs[startIndex]
            currentSongTitle = targetSong.title
            currentSongArtist = targetSong.artists.joinToString(", ") { it.name }
            currentSongArtwork = targetSong.thumbnail

            val fetchedUrl = withContext(Dispatchers.IO) {
                StreamUrlResolver.resolveStreamUrl(targetSong.id)
            }

            if (fetchedUrl != null) {
                try {
                    var commentaryException: Exception? = null
                    val geminiKey = SessionManager.getGeminiApiKey(context)
                    val startCommentaryItem = if (geminiKey.isNotBlank() && SessionManager.isCommentaryEnabled(context) && SessionManager.isSessionOpenerEnabled(context)) {
                        statusMessage = "AI is warming up..."
                        withContext(Dispatchers.IO) {
                            try {
                                // Build the centralized daily context for the session opener
                                val contextPrompt = CommentaryContextManager.buildContextPrompt(
                                    CommentaryContextManager.getCurrentContext(context)
                                )
                                // Get the user's selected commentary elements (e.g. Humour)
                                val selectedElements = SessionManager.getSelectedCommentary(context)
                                val (audioFile, script) = TtsService.generateCommentaryAudio(
                                    context = context,
                                    currentSong = null,
                                    upcomingSongs = listOf("'${targetSong.title}' by ${targetSong.artists.joinToString(", ") { it.name }}"),
                                    contextPrompt = contextPrompt,
                                    commentaryElements = selectedElements,
                                    isSessionOpener = true
                                )
                                // Record the session opener so the AI has context
                                // about what was already covered today.
                                CommentaryContextManager.recordCommentary(context, script)
                                MediaItem.Builder()
                                    .setUri(android.net.Uri.fromFile(audioFile))
                                    .setMediaId("commentary_${System.currentTimeMillis()}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("AI Welcome")
                                            .setArtist("Tunespark Radio")
                                            .setDescription(script)
                                            .build()
                                    )
                                    .build()
                            } catch (e: Exception) {
                                commentaryException = e
                                null
                            }
                        }
                    } else null

                    if (commentaryException != null) {
                        Toast.makeText(context, "AI Commentary failed: ${commentaryException?.message}", Toast.LENGTH_LONG).show()
                    }

                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()

                    // Add all songs and insert startCommentaryItem at startIndex
                    songs.forEachIndexed { index, song ->
                        if (index == startIndex && startCommentaryItem != null) {
                            exoPlayer.addMediaItem(startCommentaryItem)
                        }

                        val item = if (index == startIndex) {
                            MediaItem.Builder()
                                .setUri(fetchedUrl)
                                .setMediaId(song.id)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(song.title)
                                        .setArtist(song.artists.joinToString(", ") { it.name })
                                        .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                                        .build()
                                )
                                .build()
                        } else {
                            MediaItem.Builder()
                                .setUri(android.net.Uri.parse("tunespark://unresolved/${song.id}"))
                                .setMediaId(song.id)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(song.title)
                                        .setArtist(song.artists.joinToString(", ") { it.name })
                                        .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                                        .build()
                                )
                                .build()
                        }
                        exoPlayer.addMediaItem(item)
                    }

                    exoPlayer.seekTo(startIndex, 0)
                    exoPlayer.prepare()
                    exoPlayer.play()
                    isLoading = false
                    statusMessage = "Playing Playlist: $playlistName"
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = "Playlist playback failed: ${e.message}"
                }
            } else {
                if (startIndex + 1 < songs.size) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Song '${targetSong.title}' unplayable, trying next...", Toast.LENGTH_SHORT).show()
                        playPlaylist(playlistName, songs, startIndex + 1)
                    }
                } else {
                    isLoading = false
                    errorMessage = "Failed to fetch stream URL for playlist songs"
                }
            }
        }
    }

    // The tapped song is resolved eagerly so playback starts immediately. The
    // service seeds and resolves the rest of the autoplay queue in the background.
    val playSong = { song: SongItem ->
        PlaybackService.isPlaylistMode = false
        isLoading = true
        errorMessage = null
        currentSongTitle = song.title
        currentSongArtist = song.artists.joinToString(", ") { it.name }
        currentSongArtwork = song.thumbnail
        statusMessage = "Fetching stream..."

        coroutineScope.launch {
            val fetchedUrl = withContext(Dispatchers.IO) {
                StreamUrlResolver.resolveStreamUrl(song.id)
            }
            if (fetchedUrl != null) {
                try {
                    // Generate introductory start commentary if Gemini key is present
                    // and the "Session opener" element is checked
                    val geminiKey = SessionManager.getGeminiApiKey(context)
                    val startCommentaryItem = if (geminiKey.isNotBlank() && SessionManager.isCommentaryEnabled(context) && SessionManager.isSessionOpenerEnabled(context)) {
                        statusMessage = "AI is warming up..."
                        withContext(Dispatchers.IO) {
                            try {
                                // Build the centralized daily context for the session opener
                                val contextPrompt = CommentaryContextManager.buildContextPrompt(
                                    CommentaryContextManager.getCurrentContext(context)
                                )
                                // Get the user's selected commentary elements (e.g. Humour)
                                val selectedElements = SessionManager.getSelectedCommentary(context)
                                val (audioFile, script) = TtsService.generateCommentaryAudio(
                                    context = context,
                                    currentSong = null,
                                    upcomingSongs = listOf("'${song.title}' by ${song.artists.joinToString(", ") { it.name }}"),
                                    contextPrompt = contextPrompt,
                                    commentaryElements = selectedElements,
                                    isSessionOpener = true
                                )
                                // Record the session opener so the AI has context
                                // about what was already covered today.
                                CommentaryContextManager.recordCommentary(context, script)
                                MediaItem.Builder()
                                    .setUri(android.net.Uri.fromFile(audioFile))
                                    .setMediaId("commentary_${System.currentTimeMillis()}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("AI Welcome")
                                            .setArtist("Tunespark Radio")
                                            .setDescription(script)
                                            .build()
                                    )
                                    .build()
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                    } else null

                    val mediaItem = MediaItem.Builder()
                        .setUri(fetchedUrl)
                        .setMediaId(song.id)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artists.joinToString(", ") { it.name })
                                .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                                .build()
                        )
                        .build()

                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    
                    if (startCommentaryItem != null) {
                        exoPlayer.addMediaItem(startCommentaryItem)
                    }
                    exoPlayer.addMediaItem(mediaItem)
                    
                    exoPlayer.prepare()
                    exoPlayer.play()
                    isLoading = false
                    statusMessage = "Playing"
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = "Playback failed: ${e.message}"
                }
            } else {
                isLoading = false
                errorMessage = "Failed to fetch stream URL"
            }
        }
    }

    val shufflePlay: () -> Unit = {
        PlaybackService.isPlaylistMode = false
        isShuffling = true
        errorMessage = null
        statusMessage = "Loading regional mix..."
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val songList = mutableListOf<SongItem>()

                // 1. Prioritize real-time regional trending and popular charts (FEmusic_charts)
                val chartsResult = YouTube.getChartsPage()
                if (chartsResult.isSuccess) {
                    val chartSongs = chartsResult.getOrNull()?.sections?.flatMap { section ->
                        section.items.filterIsInstance<SongItem>()
                    }.orEmpty()
                    songList.addAll(chartSongs)
                }
                // 2. Fetch from YouTube Home (editorial / community collections)
                val homeResult = YouTube.home()
                if (homeResult.isSuccess) {
                    val homeSongs = homeResult.getOrNull()?.sections?.flatMap { section ->
                        section.items.filterIsInstance<SongItem>()
                    }.orEmpty()
                    songList.addAll(homeSongs)
                }

                // 3. Fallback/Supplement with generic popular, trending, and shorts-like search to maximize pool
                if (songList.size < 15) {
                    val popularResult = YouTube.search("popular trending music hits", YouTube.SearchFilter.FILTER_SONG)
                    if (popularResult.isSuccess) {
                        val popularSongs = popularResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                        songList.addAll(popularSongs)
                    }
                }

                withContext(Dispatchers.Main) {
                    isShuffling = false
                    // Shuffle the distinct songs to guarantee complete randomness on every click
                    val finalUniqueSongs = songList.distinctBy { it.id }.shuffled()
                    if (finalUniqueSongs.isNotEmpty()) {
                        val randomSong = finalUniqueSongs.random() // Pick a random seed track to start the radio
                        playSong(randomSong)
                    } else {
                        errorMessage = "Could not load regional trending songs. Please try again."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isShuffling = false
                    errorMessage = "Error shuffling: ${e.message}"
                }
            }
        }
    }

    // Hoisted HomeScreen states
    val userSignedIn = remember(accountInfo) { SessionManager.isUserSignedIn(context) }

    var homeRefreshTrigger by remember { mutableStateOf(0) }

    var homeSpeedDialSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isHomeSpeedDialLoading by remember { mutableStateOf(false) }

    var homeCommunityPlaylists by remember { mutableStateOf<List<CommunityPlaylistData>>(emptyList()) }
    var isHomeCommunityLoading by remember { mutableStateOf(false) }

    var homeRecentsSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isHomeRecentsLoading by remember { mutableStateOf(false) }

    var homeQuickPicksSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isHomeQuickPicksLoading by remember { mutableStateOf(false) }

    // RSS Discover feed state
    var discoverArticles by remember { mutableStateOf<List<Article>>(emptyList()) }
    var isDiscoverLoading by remember { mutableStateOf(false) }
    var isDiscoverEnabled by remember { mutableStateOf(SessionManager.isDiscoverFeedEnabled(context)) }

    // Pending AI summary article (set when user taps AI icon on Home carousel)
    var pendingAiSummaryArticle by remember { mutableStateOf<Article?>(null) }

    // Re-read the discover enabled state whenever the user navigates back to Home
    // (e.g., after changing it in the Discover Feed settings screen)
    LaunchedEffect(currentScreen) {
        if (currentScreen == AppScreen.HOME) {
            isDiscoverEnabled = SessionManager.isDiscoverFeedEnabled(context)
        }
    }

    LaunchedEffect(homeRefreshTrigger) {
        isDiscoverLoading = true
        withContext(Dispatchers.IO) {
            // Force refresh when the user pulls-to-refresh (homeRefreshTrigger > 0),
            // otherwise use cached data on initial load
            val articles = RssRepository.getArticles(context, forceRefresh = homeRefreshTrigger > 0)
            withContext(Dispatchers.Main) {
                discoverArticles = articles
                isDiscoverLoading = false
            }
        }
    }

    LaunchedEffect(userSignedIn, localHistorySize, homeRefreshTrigger) {
        isHomeQuickPicksLoading = true
        homeQuickPicksSongs = emptyList()
        withContext(Dispatchers.IO) {
            try {
                val songs = mutableListOf<SongItem>()
                val localHistory = SessionManager.getLocalHistory(context)
                if (localHistory.size >= 3) {
                    val seedTracks = localHistory.distinctBy { it.id }.take(3)
                    if (seedTracks.isNotEmpty()) {
                        val jobs = seedTracks.map { seed ->
                            async {
                                val nextResult = YouTube.next(WatchEndpoint(videoId = seed.id))
                                if (nextResult.isSuccess) {
                                    nextResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                                } else emptyList()
                            }
                        }
                        val recommendedLists = jobs.awaitAll()
                        recommendedLists.forEach { songs.addAll(it) }
                    }
                    val seedIds = seedTracks.map { it.id }.toSet()
                    val localIds = localHistory.map { it.id }.toSet()
                    var finalPersonalSongs = songs.distinctBy { it.id }
                        .filter { it.id !in seedIds && it.id !in localIds }
                        .shuffled()
                        .take(10)
                    if (finalPersonalSongs.size < 10) {
                        val remainingNeeded = 10 - finalPersonalSongs.size
                        val extraSongs = songs.distinctBy { it.id }
                            .filter { it.id !in finalPersonalSongs.map { s -> s.id } }
                            .shuffled()
                            .take(remainingNeeded)
                        finalPersonalSongs = finalPersonalSongs + extraSongs
                    }
                    if (finalPersonalSongs.size < 10) {
                        val remainingNeeded = 10 - finalPersonalSongs.size
                        val extraSongs = localHistory.distinctBy { it.id }
                            .filter { it.id !in finalPersonalSongs.map { s -> s.id } }
                            .shuffled()
                            .take(remainingNeeded)
                        finalPersonalSongs = finalPersonalSongs + extraSongs
                    }
                    songs.clear()
                    songs.addAll(finalPersonalSongs.take(10))
                } else {
                    if (userSignedIn) {
                        val seedTracks = mutableListOf<SongItem>()
                        
                        // Fetch library activity and home concurrently to reduce latency
                        val recentDeferred = async { YouTube.libraryRecentActivity() }
                        val homeDeferred = async { YouTube.home() }
                        
                        val recentResult = recentDeferred.await()
                        if (recentResult.isSuccess) {
                            val items = recentResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                            seedTracks.addAll(items.take(2)) // Optimized from take(3) to take(2) to speed up load time
                        }
                        if (seedTracks.isEmpty()) {
                            val historyResult = YouTube.musicHistory()
                            if (historyResult.isSuccess) {
                                val historySongs = historyResult.getOrNull()?.sections?.flatMap { it.songs }.orEmpty()
                                seedTracks.addAll(historySongs.distinctBy { it.id }.take(2)) // Optimized to take(2)
                            }
                        }
                        if (seedTracks.isNotEmpty()) {
                            val jobs = seedTracks.map { seed ->
                                async {
                                    val nextResult = YouTube.next(WatchEndpoint(videoId = seed.id))
                                    if (nextResult.isSuccess) {
                                        nextResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                                    } else emptyList()
                                }
                            }
                            val recommendedLists = jobs.awaitAll()
                            recommendedLists.forEach { songs.addAll(it) }
                        }
                        val homeResult = homeDeferred.await()
                        if (homeResult.isSuccess) {
                            val homeSongs = homeResult.getOrNull()?.sections.orEmpty().flatMap { section ->
                                section.items.filterIsInstance<SongItem>()
                            }
                            songs.addAll(homeSongs)
                        }
                        val seedIds = seedTracks.map { it.id }.toSet()
                        var finalPersonalSongs = songs.distinctBy { it.id }
                            .filter { it.id !in seedIds }
                            .shuffled()
                            .take(10)
                        if (finalPersonalSongs.size < 10) {
                            val remainingNeeded = 10 - finalPersonalSongs.size
                            val extraSongs = songs.distinctBy { it.id }
                                .filter { it.id !in finalPersonalSongs.map { s -> s.id } }
                                .take(remainingNeeded)
                            finalPersonalSongs = finalPersonalSongs + extraSongs
                        }
                        songs.clear()
                        songs.addAll(finalPersonalSongs.take(10))
                    } else {
                        val chartsResult = YouTube.getChartsPage()
                        if (chartsResult.isSuccess) {
                            val chartSongs = chartsResult.getOrNull()?.sections?.flatMap { section ->
                                section.items.filterIsInstance<SongItem>()
                            }.orEmpty()
                            songs.addAll(chartSongs)
                        }
                        if (songs.size < 15) {
                            val searchResult = YouTube.search("trending music billboard charts", YouTube.SearchFilter.FILTER_SONG)
                            if (searchResult.isSuccess) {
                                val searchSongs = searchResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                                songs.addAll(searchSongs)
                            }
                        }
                        val finalSignedOutSongs = songs.distinctBy { it.id }.shuffled().take(10)
                        songs.clear()
                        songs.addAll(finalSignedOutSongs)
                    }
                }
                withContext(Dispatchers.Main) {
                    homeQuickPicksSongs = songs
                    isHomeQuickPicksLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isHomeQuickPicksLoading = false
                }
            }
        }
    }

    LaunchedEffect(userSignedIn, currentSongTitle, homeRefreshTrigger) {
        isHomeRecentsLoading = true
        homeRecentsSongs = emptyList()
        withContext(Dispatchers.IO) {
            try {
                val songs = mutableListOf<SongItem>()
                // Always treat our own app's listening history as the sole source of truth
                val localSongs = SessionManager.getLocalHistory(context)
                songs.addAll(localSongs)
                
                withContext(Dispatchers.Main) {
                    homeRecentsSongs = songs.take(10)
                    isHomeRecentsLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isHomeRecentsLoading = false
                }
            }
        }
    }

    LaunchedEffect(userSignedIn, localHistorySize, homeRefreshTrigger) {
        isHomeCommunityLoading = true
        homeCommunityPlaylists = emptyList()
        withContext(Dispatchers.IO) {
            try {
                val playlistDataList = mutableListOf<CommunityPlaylistData>()
                val fetchedPlaylists = mutableListOf<PlaylistItem>()

                val favoriteArtists = mutableSetOf<String>()
                val recentSongIds = mutableSetOf<String>()

                val localHistory = SessionManager.getLocalHistory(context)
                if (localHistory.size >= 3) {
                    localHistory.forEach { song ->
                        recentSongIds.add(song.id)
                        song.artists.forEach { artist ->
                            if (artist.name.isNotBlank()) {
                                favoriteArtists.add(artist.name.lowercase().trim())
                            }
                        }
                    }

                    val queriesToRun = favoriteArtists.take(3).ifEmpty { listOf("Chill Mix", "Today's Hits") }
                    val searchJobs = queriesToRun.map { query ->
                        async {
                            val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                            if (searchResult.isSuccess) {
                                searchResult.getOrNull()?.items?.filterIsInstance<PlaylistItem>().orEmpty()
                            } else emptyList()
                        }
                    }
                    val searchResultsList = searchJobs.awaitAll()
                    searchResultsList.forEach { fetchedPlaylists.addAll(it) }

                    val candidates = fetchedPlaylists.distinctBy { it.id }.take(3)
                    val jobs = candidates.map { playlist ->
                        async {
                            val pageResult = YouTube.playlist(playlist.id)
                            if (pageResult.isSuccess) {
                                val page = pageResult.getOrNull()
                                if (page != null && page.songs.isNotEmpty()) {
                                    CommunityPlaylistData(playlist = playlist, songs = page.songs)
                                } else null
                            } else null
                        }
                    }
                    val fetchedCandidates = jobs.awaitAll().filterNotNull()

                    val rankedCandidates = fetchedCandidates.map { data ->
                        val score = calculateRelevanceScore(data.playlist, data.songs, favoriteArtists, recentSongIds)
                        data to score
                    }.sortedByDescending { it.second }.map { it.first }

                    playlistDataList.addAll(rankedCandidates)
                } else {
                    if (userSignedIn) {
                        // Personalized: Extract favorite artists & recent song IDs from user's actual recent activity
                        val recentResult = YouTube.libraryRecentActivity()
                        if (recentResult.isSuccess) {
                            val items = recentResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                            items.forEach { song ->
                                recentSongIds.add(song.id)
                                song.artists.forEach { artist ->
                                    if (artist.name.isNotBlank()) {
                                        favoriteArtists.add(artist.name.lowercase().trim())
                                    }
                                }
                            }
                        }

                        // Supplement with Home recommended feeds
                        val homeResult = YouTube.home()
                        if (homeResult.isSuccess) {
                            val homeSongs = homeResult.getOrNull()?.sections.orEmpty().flatMap { section ->
                                section.items.filterIsInstance<SongItem>()
                            }
                            homeSongs.forEach { song ->
                                song.artists.forEach { artist ->
                                    if (artist.name.isNotBlank()) {
                                        favoriteArtists.add(artist.name.lowercase().trim())
                                    }
                                }
                            }
                        }

                        // Broader queries based on user's actual listening history for higher variety candidate retrieval (fetched concurrently)
                        val queriesToRun = favoriteArtists.take(3).ifEmpty { listOf("Chill Mix", "Today's Hits") }
                        val searchJobs = queriesToRun.map { query ->
                            async {
                                val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                                if (searchResult.isSuccess) {
                                    searchResult.getOrNull()?.items?.filterIsInstance<PlaylistItem>().orEmpty()
                                } else emptyList()
                            }
                        }
                        val searchResultsList = searchJobs.awaitAll()
                        searchResultsList.forEach { fetchedPlaylists.addAll(it) }

                        // Retrieve unique candidate playlists up to 3 (Optimized from 5 to 3 to reduce network load/latency)
                        val candidates = fetchedPlaylists.distinctBy { it.id }.take(3)

                        // Fetch candidate playlist details concurrently using async coroutines
                        val jobs = candidates.map { playlist ->
                            async {
                                val pageResult = YouTube.playlist(playlist.id)
                                if (pageResult.isSuccess) {
                                    val page = pageResult.getOrNull()
                                    if (page != null && page.songs.isNotEmpty()) {
                                        CommunityPlaylistData(playlist = playlist, songs = page.songs)
                                    } else null
                                } else null
                            }
                        }
                        val fetchedCandidates = jobs.awaitAll().filterNotNull()

                        // Compute algorithmic relevance score based on listening history & collaborative niche listener factors
                        val rankedCandidates = fetchedCandidates.map { data ->
                            val score = calculateRelevanceScore(data.playlist, data.songs, favoriteArtists, recentSongIds)
                            data to score
                        }.sortedByDescending { it.second }.map { it.first }

                        // Take all ranked candidate playlists
                        playlistDataList.addAll(rankedCandidates)

                    } else {
                        // Signed-out flow: fetch general popular community playlists (fetched concurrently)
                        val generalQueries = listOf("Pop Hits Mix", "Chill Acoustic", "Lofi Study Beats")
                        val searchJobs = generalQueries.map { query ->
                            async {
                                val searchResult = YouTube.search(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST)
                                if (searchResult.isSuccess) {
                                    searchResult.getOrNull()?.items?.filterIsInstance<PlaylistItem>().orEmpty()
                                } else emptyList()
                            }
                        }
                        val searchResultsList = searchJobs.awaitAll()
                        searchResultsList.forEach { fetchedPlaylists.addAll(it) }

                        // Retrieve unique candidate playlists up to 3 (Optimized from 5 to 3)
                        val candidates = fetchedPlaylists.distinctBy { it.id }.take(3)
                        val jobs = candidates.map { playlist ->
                            async {
                                val pageResult = YouTube.playlist(playlist.id)
                                if (pageResult.isSuccess) {
                                    val page = pageResult.getOrNull()
                                    if (page != null && page.songs.isNotEmpty()) {
                                        CommunityPlaylistData(playlist = playlist, songs = page.songs)
                                    } else null
                                } else null
                            }
                        }
                        val fetchedCandidates = jobs.awaitAll().filterNotNull()
                        playlistDataList.addAll(fetchedCandidates)
                    }
                }

                withContext(Dispatchers.Main) {
                    homeCommunityPlaylists = playlistDataList
                    isHomeCommunityLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isHomeCommunityLoading = false
                }
            }
        }
    }

    LaunchedEffect(userSignedIn, localHistorySize, homeRefreshTrigger) {
        isHomeSpeedDialLoading = true
        homeSpeedDialSongs = emptyList()
        withContext(Dispatchers.IO) {
            try {
                val songs = mutableListOf<SongItem>()
                val localHistory = SessionManager.getLocalHistory(context)
                if (localHistory.size >= 3) {
                    val seeds = localHistory.distinctBy { it.id }.take(10)
                    val jobs = seeds.map { seed ->
                        async {
                            val nextResult = YouTube.next(WatchEndpoint(videoId = seed.id))
                            if (nextResult.isSuccess) {
                                nextResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                            } else emptyList()
                        }
                    }
                    val recommendedLists = jobs.awaitAll()
                    
                    val combinedSongs = mutableListOf<SongItem>()
                    combinedSongs.addAll(localHistory.distinctBy { it.id })
                    
                    recommendedLists.flatten().distinctBy { it.id }.forEach { song ->
                        if (song.id !in combinedSongs.map { i -> i.id }) {
                            combinedSongs.add(song)
                        }
                    }
                    
                    val localArtists = localHistory.flatMap { it.artists }.map { it.name }.filter { it.isNotBlank() }.distinct()
                    if (combinedSongs.size < 45 && localArtists.isNotEmpty()) {
                        val searchJobs = localArtists.take(3).map { artist ->
                            async {
                                val res = YouTube.search(artist, YouTube.SearchFilter.FILTER_SONG)
                                if (res.isSuccess) {
                                    res.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                                } else emptyList()
                            }
                        }
                        val searchResults = searchJobs.awaitAll().flatten()
                        searchResults.forEach { song ->
                            if (song.id !in combinedSongs.map { i -> i.id }) {
                                combinedSongs.add(song)
                            }
                        }
                    }
                    songs.addAll(combinedSongs)
                } else {
                    if (userSignedIn) {
                        // Try to load user's actual recent activity
                        val recentResult = YouTube.libraryRecentActivity()
                        if (recentResult.isSuccess) {
                            val recentSongs = recentResult.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                            songs.addAll(recentSongs)
                        }
                        
                        // Supplement with user's Home recommended feeds (Listen again, Quick picks, etc.)
                        val homeResult = YouTube.home()
                        if (homeResult.isSuccess) {
                            val homeSections = homeResult.getOrNull()?.sections.orEmpty()
                            val homeSongs = homeSections.flatMap { section ->
                                section.items.filterIsInstance<SongItem>()
                            }
                            songs.addAll(homeSongs)
                        }
                        
                        // Fallback to liked playlist LM songs if we are short on tracks
                        if (songs.distinctBy { it.id }.size < 45) {
                            val likedPlaylistResult = YouTube.playlist("LM")
                            if (likedPlaylistResult.isSuccess) {
                                val likedSongs = likedPlaylistResult.getOrNull()?.songs.orEmpty()
                                songs.addAll(likedSongs)
                            }
                        }
                    } else {
                        // Unsigned-in flows: grab charts and public home recommendations
                        val homeResult = YouTube.home()
                        if (homeResult.isSuccess) {
                            val homeSongs = homeResult.getOrNull()?.sections.orEmpty().flatMap { section ->
                                section.items.filterIsInstance<SongItem>()
                            }
                            songs.addAll(homeSongs)
                        }
                        if (songs.distinctBy { it.id }.size < 45) {
                            val chartsResult = YouTube.getChartsPage()
                            if (chartsResult.isSuccess) {
                                val chartSongs = chartsResult.getOrNull()?.sections.orEmpty().flatMap { section ->
                                    section.items.filterIsInstance<SongItem>()
                                }
                                songs.addAll(chartSongs)
                            }
                        }
                    }
                }

                // Keep querying extra sources concurrently until we hit at least 45 unique songs
                if (songs.distinctBy { it.id }.size < 45) {
                    val fallbackQueries = listOf(
                        "popular trending music hits",
                        "billboard hot 100"
                    )
                    val fallbackJobs = fallbackQueries.map { q ->
                        async {
                            val res = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG)
                            if (res.isSuccess) {
                                res.getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                            } else emptyList()
                        }
                    }
                    val results = fallbackJobs.awaitAll()
                    results.forEach { songs.addAll(it) }
                }

                // Extract max 45 tracks (up to 5 pages of 3x3 grids)
                val finalSongs = songs.distinctBy { it.id }.take(45)
                withContext(Dispatchers.Main) {
                    homeSpeedDialSongs = finalSongs
                    isHomeSpeedDialLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isHomeSpeedDialLoading = false
                }
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
            Box(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            val initialDepth = getScreenDepth(initialState)
                            val targetDepth = getScreenDepth(targetState)
                            if (targetDepth > initialDepth) {
                                slideInHorizontally(animationSpec = tween(300)) { width -> width } togetherWith
                                        slideOutHorizontally(animationSpec = tween(300)) { width -> -width }
                            } else {
                                slideInHorizontally(animationSpec = tween(300)) { width -> -width } togetherWith
                                        slideOutHorizontally(animationSpec = tween(300)) { width -> width }
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        label = "ScreenTransition"
                    ) { targetScreen ->
                        when (targetScreen) {
                            AppScreen.HOME -> {
                            HomeScreen(
                                currentSongTitle = currentSongTitle,
                                currentSongArtist = currentSongArtist,
                                currentSongArtwork = currentSongArtwork,
                                isPlaying = isPlaying,
                                onPlayPauseToggle = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                },
                                isShuffling = isShuffling,
                                onNavigate = navigateHandler,
                                onShufflePlay = shufflePlay,
                                onPlaySong = { song ->
                                    playSong(song)
                                    openFullPlayer()
                                },
                                onPlayPlaylist = { name, songs, startIndex ->
                                    playPlaylist(name, songs, startIndex)
                                    openFullPlayer()
                                },
                                onPlaylistClick = { data ->
                                    initialPlaylistId = data.playlist.id
                                    initialPlaylistName = data.playlist.title
                                    initialPlaylistThumbnail = data.playlist.thumbnail ?: data.songs.firstOrNull()?.thumbnail
                                    initialPlaylistSongCountText = data.playlist.songCountText ?: "${data.songs.size} songs"
                                    initialPlaylistIsLiked = false
                                    initialPlaylistRawItem = data.playlist
                                    initialPlaylistAuthorName = data.playlist.author?.name
                                    initialPlaylistAuthorAvatarUrl = data.playlist.authorAvatarUrl
                                    initialPlaylistSongs = data.songs
                                    currentScreen = AppScreen.PLAYLISTS
                                },
                                onRefresh = { homeRefreshTrigger++ },
                                speedDialSongs = homeSpeedDialSongs,
                                isSpeedDialLoading = isHomeSpeedDialLoading,
                                communityPlaylists = homeCommunityPlaylists,
                                isCommunityLoading = isHomeCommunityLoading,
                                recentsSongs = homeRecentsSongs,
                                isRecentsLoading = isHomeRecentsLoading,
                                quickPicksSongs = homeQuickPicksSongs,
                                isQuickPicksLoading = isHomeQuickPicksLoading,
                                discoverArticles = discoverArticles,
                                isDiscoverLoading = isDiscoverLoading,
                                isDiscoverEnabled = isDiscoverEnabled,
                                onAiSummaryClick = { article ->
                                    pendingAiSummaryArticle = article
                                    navigateHandler(AppScreen.DISCOVER)
                                }
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.ACCOUNT -> {
                            AccountScreen(
                                accountInfo = accountInfo,
                                isLoadingProfile = isLoadingProfile,
                                profileError = profileError,
                                onAccountInfoChange = { accountInfo = it },
                                onIsLoadingProfileChange = { isLoadingProfile = it },
                                onProfileErrorChange = { profileError = it },
                                onNavigate = navigateHandler,
                                onWebViewShowingChange = { isAccountWebViewShowing = it }
                            )
                        }
                        AppScreen.SEARCH -> {
                            SearchScreen(
                                searchQuery = searchQuery,
                                searchResults = searchResults,
                                isSearching = isSearching,
                                onSearchQueryChange = { searchQuery = it },
                                onTriggerSearch = triggerSearch,
                                onPlaySong = { song ->
                                    playSong(song)
                                    openFullPlayer()
                                },
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.RADIO -> {
                            // Rendered as global sliding overlay instead
                        }
                        AppScreen.APPEARANCE -> {
                            AppearanceScreen(
                                currentTheme = themeSetting,
                                onThemeChange = onThemeSettingChange,
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.AI_VOICE -> {
                            AiVoiceScreen(
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.COMMENTARY -> {
                            CommentaryScreen(
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.PLAYER_AUDIO -> {
                            PlayerAndAudioScreen(
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.LOCATION -> {
                            LocationScreen(
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.UPDATES -> {
                            UpdatesScreen(
                                onNavigate = navigateHandler
                            )
                        }
                        AppScreen.PLAYLISTS -> {
                            PlaylistsScreen(
                                initialPlaylistId = initialPlaylistId,
                                initialPlaylistName = initialPlaylistName,
                                initialPlaylistThumbnail = initialPlaylistThumbnail,
                                initialPlaylistSongCountText = initialPlaylistSongCountText,
                                initialPlaylistIsLiked = initialPlaylistIsLiked,
                                initialPlaylistRawItem = initialPlaylistRawItem,
                                initialPlaylistAuthorName = initialPlaylistAuthorName,
                                initialPlaylistAuthorAvatarUrl = initialPlaylistAuthorAvatarUrl,
                                initialPlaylistSongs = initialPlaylistSongs,
                                onPlayPlaylist = { name, songs, startIndex ->
                                    playPlaylist(name, songs, startIndex)
                                    openFullPlayer()
                                },
                                onNavigate = { screen ->
                                    if (screen != AppScreen.PLAYLISTS) {
                                        initialPlaylistId = null
                                        initialPlaylistName = ""
                                        initialPlaylistThumbnail = null
                                        initialPlaylistSongCountText = ""
                                        initialPlaylistIsLiked = false
                                        initialPlaylistRawItem = null
                                        initialPlaylistAuthorName = null
                                        initialPlaylistAuthorAvatarUrl = null
                                        initialPlaylistSongs = emptyList()
                                    }
                                    if (screen == AppScreen.RADIO) {
                                        openFullPlayer()
                                    } else {
                                        navigateHandler(screen)
                                    }
                                }
                            )
                        }
                            AppScreen.RECENTS -> {
                                RecentsScreen(
                                    onPlaySong = { song ->
                                        playSong(song)
                                        openFullPlayer()
                                    },
                                    onNavigate = navigateHandler
                                )
                            }
                            AppScreen.DISCOVER -> {
                                DiscoverScreen(
                                    onNavigate = navigateHandler,
                                    pendingAiSummaryArticle = pendingAiSummaryArticle,
                                    onPendingAiSummaryConsumed = {
                                        pendingAiSummaryArticle = null
                                    }
                                )
                            }
                            AppScreen.DISCOVER_FEED -> {
                                DiscoverFeedScreen(
                                    onNavigate = navigateHandler
                                )
                            }
                        }
                    }
                }

                // First BottomDock was deleted to resolve duplicate rendering, misalignment, double-shadows, and to allow the single bottom bar to render natively on top of all overlay screens (Search, Playlists, etc.).
            }

            // SearchScreen premium sliding overlay
            val searchYOffset = with(LocalDensity.current) { screenHeight * (1f - searchProgress.value) }
            if (searchProgress.value > 0.001f) {
                val searchAlpha = (searchProgress.value / 0.5f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .graphicsLayer {
                            translationY = searchYOffset.toPx()
                            alpha = searchAlpha
                        }
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    SearchScreen(
                        searchQuery = searchQuery,
                        searchResults = searchResults,
                        isSearching = isSearching,
                        onSearchQueryChange = { searchQuery = it },
                        onTriggerSearch = triggerSearch,
                        onPlaySong = { song ->
                            playSong(song)
                            openFullPlayer()
                        },
                        onNavigate = navigateHandler
                    )
                }
            }

            // PlaylistsScreen premium sliding overlay
            val libraryYOffset = with(LocalDensity.current) { screenHeight * (1f - libraryProgress.value) }
            if (libraryProgress.value > 0.001f) {
                val libraryAlpha = (libraryProgress.value / 0.5f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .graphicsLayer {
                            translationY = libraryYOffset.toPx()
                            alpha = libraryAlpha
                        }
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    PlaylistsScreen(
                        initialPlaylistId = initialPlaylistId,
                        initialPlaylistName = initialPlaylistName,
                        initialPlaylistThumbnail = initialPlaylistThumbnail,
                        initialPlaylistSongCountText = initialPlaylistSongCountText,
                        initialPlaylistIsLiked = initialPlaylistIsLiked,
                        initialPlaylistRawItem = initialPlaylistRawItem,
                        initialPlaylistAuthorName = initialPlaylistAuthorName,
                        initialPlaylistAuthorAvatarUrl = initialPlaylistAuthorAvatarUrl,
                        initialPlaylistSongs = initialPlaylistSongs,
                        onPlayPlaylist = { name, songs, startIndex ->
                            playPlaylist(name, songs, startIndex)
                            openFullPlayer()
                        },
                        onNavigate = { screen ->
                            if (screen != AppScreen.PLAYLISTS) {
                                initialPlaylistId = null
                                initialPlaylistName = ""
                                initialPlaylistThumbnail = null
                                initialPlaylistSongCountText = ""
                                initialPlaylistIsLiked = false
                                initialPlaylistRawItem = null
                                initialPlaylistAuthorName = null
                                initialPlaylistAuthorAvatarUrl = null
                                initialPlaylistSongs = emptyList()
                            }
                            if (screen == AppScreen.RADIO) {
                                openFullPlayer()
                            } else {
                                navigateHandler(screen)
                            }
                        }
                    )
                }
            }

            // RadioScreen premium sliding overlay
            val yOffset = with(LocalDensity.current) { screenHeight * (1f - fullPlayerProgress.value) }
            if (fullPlayerProgress.value > 0.001f) {
                val radioAlpha = (fullPlayerProgress.value / 0.5f).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .graphicsLayer {
                            translationY = yOffset.toPx()
                            alpha = radioAlpha
                        }
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    RadioScreen(
                        exoPlayer = exoPlayer,
                        playQueue = playQueue,
                        currentTrackIndex = currentTrackIndex,
                        currentSongTitle = currentSongTitle,
                        currentSongArtist = currentSongArtist,
                        currentSongArtwork = currentSongArtwork,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        statusMessage = statusMessage,
                        hasPreviousTrack = hasPreviousTrack,
                        hasNextTrack = hasNextTrack,
                        onNavigate = { screen ->
                            if (screen == AppScreen.HOME) {
                                closeFullPlayer()
                            } else {
                                navigateHandler(screen)
                            }
                        },
                        onStopRadio = {
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            currentSongTitle = "No Track Loaded"
                            currentSongArtist = ""
                            currentSongArtwork = null
                            playQueue = emptyList()
                            currentTrackIndex = -1
                            hasPreviousTrack = false
                            hasNextTrack = false
                            closeFullPlayer()
                        },
                        lyricsLines = hoistedLyricsLines,
                        isLyricsLoading = hoistedIsLyricsLoading,
                        fullPlayerProgress = fullPlayerProgress
                    )
                }
            }

            if (!(currentScreen == AppScreen.ACCOUNT && isAccountWebViewShowing) && fullPlayerProgress.value < 0.99f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .graphicsLayer {
                            alpha = 1f - fullPlayerProgress.value
                        }
                        .offset(y = (-10).dp) // Moved slightly up to give a beautiful floating look
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                ) {
                    val touchEnabled = fullPlayerProgress.value < 0.1f
                    Box(modifier = if (touchEnabled) Modifier else Modifier.graphicsLayer { alpha = 0f }) {
                        val nextSong = playQueue.getOrNull(currentTrackIndex + 1)
                        val prevSong = playQueue.getOrNull(currentTrackIndex - 1)
                        BottomDock(
                            isTrackLoaded = currentSongTitle != "No Track Loaded",
                            currentSongTitle = currentSongTitle,
                            currentSongArtist = currentSongArtist,
                            currentSongArtwork = currentSongArtwork,
                            primaryColor = MaterialTheme.colorScheme.primary,
                            onPrimaryColor = MaterialTheme.colorScheme.onPrimary,
                            onNavigate = navigateHandler,
                            currentScreen = currentScreen,
                            nextSongTitle = nextSong?.mediaMetadata?.title?.toString(),
                            nextSongArtist = nextSong?.mediaMetadata?.artist?.toString(),
                            nextSongArtwork = nextSong?.mediaMetadata?.artworkUri?.toString(),
                            prevSongTitle = prevSong?.mediaMetadata?.title?.toString(),
                            prevSongArtist = prevSong?.mediaMetadata?.artist?.toString(),
                            prevSongArtwork = prevSong?.mediaMetadata?.artworkUri?.toString(),
                            onNextSong = {
                                if (exoPlayer.hasNextMediaItem()) {
                                    exoPlayer.seekToNextMediaItem()
                                }
                            },
                            onPreviousSong = {
                                if (exoPlayer.hasPreviousMediaItem()) {
                                    exoPlayer.seekToPreviousMediaItem()
                                }
                            },
                            onDismiss = {
                                exoPlayer.stop()
                                exoPlayer.clearMediaItems()
                                currentSongTitle = "No Track Loaded"
                                currentSongArtist = ""
                                currentSongArtwork = null
                                playQueue = emptyList()
                                currentTrackIndex = -1
                                hasPreviousTrack = false
                                hasNextTrack = false
                            },
                            fullPlayerProgress = fullPlayerProgress,
                            onOpenFullPlayer = openFullPlayer,
                            searchProgress = searchProgress,
                            onOpenSearch = openSearch,
                            libraryProgress = libraryProgress,
                            onOpenLibrary = openLibrary,
                            exoPlayer = exoPlayer
                        )
                    }
                }
            }
        }
    }
}
