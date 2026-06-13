package com.tunespark.music

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.tunespark.music.ui.theme.TunesparkTheme
import com.tunespark.music.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null

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

            TunesparkTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    controller?.let { player ->
                        MainPlayerScreen(
                            exoPlayer = player,
                            modifier = Modifier.padding(innerPadding)
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
    NOTIFICATIONS,
    LOCATION,
    UPDATES
}

@Composable
fun MainPlayerScreen(exoPlayer: Player, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var accountInfo by remember { mutableStateOf<com.metrolist.innertube.models.AccountInfo?>(SessionManager.getCachedAccountInfo(context)) }
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

    // The tapped song is resolved eagerly so playback starts immediately. The
    // service seeds and resolves the rest of the autoplay queue in the background.
    val playSong = { song: SongItem ->
        isLoading = true
        errorMessage = null
        currentSongTitle = song.title
        currentSongArtist = song.artists.joinToString(", ") { it.name }
        statusMessage = "Fetching stream..."

        coroutineScope.launch {
            val fetchedUrl = withContext(Dispatchers.IO) {
                StreamUrlResolver.resolveStreamUrl(song.id)
            }
            if (fetchedUrl != null) {
                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(fetchedUrl)
                        .setMediaId(song.id)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(song.title)
                                .setArtist(song.artists.joinToString(", ") { it.name })
                                .build()
                        )
                        .build()

                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    exoPlayer.setMediaItem(mediaItem)
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

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (currentScreen) {
            AppScreen.HOME -> {
                HomeScreen(
                    currentSongTitle = currentSongTitle,
                    isShuffling = isShuffling,
                    onNavigate = { currentScreen = it },
                    onShufflePlay = shufflePlay
                )
            }
            AppScreen.SETTINGS -> {
                SettingsScreen(
                    onNavigate = { currentScreen = it }
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
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.SEARCH -> {
                SearchScreen(
                    searchQuery = searchQuery,
                    searchResults = searchResults,
                    isSearching = isSearching,
                    onSearchQueryChange = { searchQuery = it },
                    onTriggerSearch = triggerSearch,
                    onPlaySong = { playSong(it) },
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.RADIO -> {
                RadioScreen(
                    exoPlayer = exoPlayer,
                    playQueue = playQueue,
                    currentTrackIndex = currentTrackIndex,
                    currentSongTitle = currentSongTitle,
                    currentSongArtist = currentSongArtist,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    statusMessage = statusMessage,
                    hasPreviousTrack = hasPreviousTrack,
                    hasNextTrack = hasNextTrack,
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.APPEARANCE -> {
                AppearanceScreen(
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.AI_VOICE -> {
                AiVoiceScreen(
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.COMMENTARY -> {
                CommentaryScreen(
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.NOTIFICATIONS -> {
                NotificationsScreen(
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.LOCATION -> {
                LocationScreen(
                    onNavigate = { currentScreen = it }
                )
            }
            AppScreen.UPDATES -> {
                UpdatesScreen(
                    onNavigate = { currentScreen = it }
                )
            }
        }
    }
}
