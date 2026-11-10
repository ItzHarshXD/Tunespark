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
import com.tunespark.music.LocalPlaylistManager
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.WatchEndpoint
import com.tunespark.music.ui.theme.TunesparkTheme
import com.tunespark.music.ui.screens.*
import com.tunespark.music.cast.CastManager
import com.tunespark.music.provider.AudioProviderIds
import com.tunespark.music.provider.SongLinks
import com.tunespark.music.provider.StreamMetadata
import com.tunespark.music.ui.screens.LyricLine
import com.tunespark.music.ui.screens.cleanYouTubeTitle
import com.tunespark.music.ui.screens.parseLyricsToLines
import com.tunespark.music.lyrics.LyricsRepository
import com.tunespark.music.LikedSongManager
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        AppScreen.LYRICS,
        AppScreen.PLAYER_AUDIO,
        AppScreen.LOCATION,
        AppScreen.UPDATES,
        AppScreen.ACCOUNT,
        AppScreen.DISCOVER_FEED -> 2
        else -> 1
    }
}

/**
 * Determines whether an accountInfo() failure genuinely means the saved
 * YouTube session is dead (the sign-in should be cleared), as opposed to a
 * transient network/server problem.
 *
 * IMPORTANT: plain network errors must NEVER be treated as sign-out — doing
 * so logged users out permanently whenever a launch happened to coincide
 * with a bad connection. The session is only cleared when YouTube itself
 * rejects the credentials (HTTP 401/403) or returns a 200 body that contains
 * no active account header (which is what a dead cookie looks like).
 */
private fun isGenuineAuthFailure(throwable: Throwable?): Boolean {
    return when (throwable) {
        null -> false
        // Ktor wraps non-2xx responses in ResponseException (expectSuccess = true).
        is io.ktor.client.plugins.ResponseException -> {
            val code = throwable.response.status.value
            code == 401 || code == 403
        }
        // Timeouts, DNS failures, connection resets, HTTP 5xx (ServerResponseException
        // is handled above and only matches 401/403) — all transient, keep the session.
        is java.io.IOException -> false
        // A 200 response whose account menu has no active account header throws
        // one of these while parsing — this is what an expired cookie looks like.
        is IndexOutOfBoundsException, is NullPointerException -> true
        // Malformed/unexpected body (serialization failures) — dead session.
        else -> true
    }
}

/**
 * Builds a queue-ready MediaItem for a song using the app's standard
 * "tunespark://unresolved/<videoId>" placeholder URI. The PlaybackService
 * lazily resolves the real stream URL when the item becomes current (and
 * proactively prefetches it), so adding to the queue is instant.
 *
 * [mediaId] must be unique across the whole timeline (a Media3 session
 * requirement). Use [MainPlayerScreen]s uniqueQueueMediaId helper to derive
 * it so the same song can be queued multiple times without crashing.
 */
private fun songMediaMetadata(song: SongItem): MediaMetadata {
    val base = MediaMetadata.Builder()
        .setTitle(song.title)
        .setArtist(song.artists.joinToString(", ") { it.name })
        .setArtworkUri(android.net.Uri.parse(song.thumbnail))
        .setAlbumTitle(song.album?.name)
        .build()
    return StreamMetadata.withSongInfo(base, song.album?.name, song.duration)
}

private fun buildQueueMediaItem(song: SongItem, mediaId: String): MediaItem {
    return MediaItem.Builder()
        .setUri(android.net.Uri.parse("tunespark://unresolved/${song.id}"))
        .setMediaId(mediaId)
        .setMediaMetadata(songMediaMetadata(song))
        .build()
}

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    var onNewIntentListener: ((Intent) -> Unit)? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onNewIntentListener?.invoke(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            com.tunespark.music.update.UpdateManager.checkOnStartup(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize saved YouTube session cookie
        SessionManager.initialize(applicationContext)

        // Initialize update notification channel and check for updates on app launch
        com.tunespark.music.update.UpdateNotificationHelper.createNotificationChannel(applicationContext)
        
        // Request POST_NOTIFICATIONS runtime permission on Android 13+ (API 33+) if not already granted
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
        
        com.tunespark.music.update.UpdateManager.checkOnStartup(applicationContext)

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
    LYRICS,
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

    // True when the Library playlist detail was opened via a deep link from another
    // screen (e.g. a Home community playlist card). The Playlists detail's back then
    // returns HOME instead of leaving the user on the Library grid.
    var libraryPlaylistFromExternal by remember { mutableStateOf(false) }

    // Initial artist navigation state for the Playlists screen (Search artist taps
    // and the Quick Action sheet's "Go to artist" / Info "View" action). Declared
    // before the overlay controls so `closeLibrary()` can clear it on close.
    var initialArtistId by remember { mutableStateOf<String?>(null) }
    var initialArtistName by remember { mutableStateOf<String?>(null) }
    var initialArtistThumbnail by remember { mutableStateOf<String?>(null) }
    // Bumped on every artist navigation request so an already-composed
    // PlaylistsScreen re-applies initialArtistId while the library stays open.
    var initialArtistNavRequest by remember { mutableStateOf(0) }

    val fullPlayerProgress = remember { Animatable(if (isFullPlayerOpen) 1f else 0f) }
    val searchProgress = remember { Animatable(if (isSearchOpen) 1f else 0f) }
    val libraryProgress = remember { Animatable(if (isLibraryOpen) 1f else 0f) }

    val openFullPlayer: () -> Unit = {
        isFullPlayerOpen = true
        // The Search / Library overlays that are already open stay composed and
        // remain visible underneath the player (which slides in over them). This
        // preserves their exact state — query results, scroll positions, open
        // playlist/artist layers — so closing the player (system Back or the
        // player's back button) returns to the exact previous screen.
        coroutineScope.launch {
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
        isLibraryOpen = true
        coroutineScope.launch {
            launch {
                fullPlayerProgress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
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
        // Any pending external deep-link context is consumed once the library closes.
        libraryPlaylistFromExternal = false
        // Drop stale artist deep-links so reopening the library starts at the grid
        // instead of resurrecting the last artist that was opened.
        initialArtistId = null
        initialArtistName = null
        initialArtistThumbnail = null
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
            if (searchProgress.value > 0.001f && libraryProgress.value > 0.001f) {
                // The Library sits over an open Search overlay: leaving the library
                // reveals the search that was beneath it (its state was preserved),
                // instead of dumping the user on an unrelated screen.
                closeLibrary()
            } else {
                closeFullPlayer()
                closeSearch()
                closeLibrary()
            }
        } else {
            currentScreen = screen
            closeFullPlayer()
            closeSearch()
            closeLibrary()
        }
    }

    BackHandler(enabled = fullPlayerProgress.value > 0f || searchProgress.value > 0f || libraryProgress.value > 0f || currentScreen != AppScreen.HOME) {
        // Overlay pop order matches the visual z-order: the player is topmost,
        // then the library (which may sit over the search overlay), then search.
        if (fullPlayerProgress.value > 0f) {
            closeFullPlayer()
        } else if (libraryProgress.value > 0f) {
            closeLibrary()
        } else if (searchProgress.value > 0f) {
            closeSearch()
        } else if (currentScreen != AppScreen.HOME) {
            when (currentScreen) {
                AppScreen.SETTINGS, AppScreen.RECENTS, AppScreen.DISCOVER -> {
                    navigateHandler(AppScreen.HOME)
                }
                AppScreen.APPEARANCE, AppScreen.AI_VOICE, AppScreen.COMMENTARY,
                AppScreen.LYRICS, AppScreen.PLAYER_AUDIO, AppScreen.LOCATION, AppScreen.UPDATES,
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
            navigateHandler(AppScreen.RADIO)
            initialIntent.action = null // Clear/consume action
        } else if (initialIntent?.action == "com.tunespark.music.action.SHOW_UPDATES" || initialIntent?.getStringExtra("open_screen") == "UPDATES") {
            navigateHandler(AppScreen.UPDATES)
            initialIntent.action = null // Clear/consume action
        }
        
        // 2. Set listener for new intents (warm starts)
        activity?.onNewIntentListener = { intent ->
            if (intent.action == "com.tunespark.music.action.SHOW_PLAYER") {
                navigateHandler(AppScreen.RADIO)
                intent.action = null // Clear/consume action
            } else if (intent.action == "com.tunespark.music.action.SHOW_UPDATES" || intent.getStringExtra("open_screen") == "UPDATES") {
                navigateHandler(AppScreen.UPDATES)
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

    // Opens a playlist / album tapped inside the Search screen's **Explore** feed in
    // the app's dedicated playlist screen. The Library overlay slides in *over* the
    // Search overlay (which stays composed), and back from the detail closes the
    // Library again — revealing the mood / genre page exactly where it was left,
    // just like the Home community playlist deep links.
    val openExplorePlaylist: (
        rawItem: com.metrolist.innertube.models.YTItem,
        id: String,
        name: String,
        thumbnail: String?,
        subtitle: String?
    ) -> Unit = { rawItem, itemId, name, thumbnail, subtitle ->
        val playlistItem = rawItem as? PlaylistItem
        initialPlaylistId = itemId
        initialPlaylistName = name
        initialPlaylistThumbnail = thumbnail
        initialPlaylistSongCountText = subtitle ?: ""
        initialPlaylistIsLiked = false
        initialPlaylistRawItem = rawItem
        initialPlaylistAuthorName = playlistItem?.author?.name
        initialPlaylistAuthorAvatarUrl = playlistItem?.authorAvatarUrl
        initialPlaylistSongs = emptyList()
        // Back from the detail must return to the Explore page underneath, not to
        // the Library grid — the "external deep link" behaviour.
        libraryPlaylistFromExternal = true
        currentScreen = AppScreen.PLAYLISTS
    }

    LaunchedEffect(Unit) {
        if (SessionManager.isUserSignedIn(context)) {
            isLoadingProfile = true
            profileError = null
            coroutineScope.launch(Dispatchers.IO) {
                var fetched: com.metrolist.innertube.models.AccountInfo? = null
                var authFailure = false

                // Retry transient network failures with backoff; never treat
                // them as sign-out. Only clear the session when YouTube itself
                // rejects the saved credentials.
                for (attempt in 1..3) {
                    val result = YouTube.accountInfo()
                    val info = result.getOrNull()
                    if (info != null) {
                        fetched = info
                        break
                    }
                    if (isGenuineAuthFailure(result.exceptionOrNull())) {
                        authFailure = true
                        break
                    }
                    if (attempt < 3) delay(1500L * attempt)
                }

                withContext(Dispatchers.Main) {
                    isLoadingProfile = false
                    when {
                        fetched != null -> {
                            accountInfo = fetched
                            SessionManager.saveAccountInfo(context, fetched)
                            profileError = null
                        }
                        authFailure -> {
                            // Confirmed dead session: YouTube rejected the cookie
                            // or returned a logged-out account menu. Safe to clear.
                            profileError = "Session expired. Please sign in again."
                            SessionManager.clearSession(context)
                            accountInfo = null
                        }
                        else -> {
                            // Network/server trouble after retries: the user is
                            // still signed in — keep the session and stay quiet.
                            // The cached profile remains visible in the UI.
                            profileError = null
                        }
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
    // Verified stream details of the current item ("stats for nerds" line).
    var nowPlayingMetadata by remember { mutableStateOf<MediaMetadata?>(null) }
    var statsForNerdsEnabled by remember {
        mutableStateOf(SessionManager.isStatsForNerdsEnabled(context))
    }
    // Hidden entirely when stats-for-nerds is off.
    val nowPlayingSourceLine = nowPlayingMetadata?.let {
        StreamMetadata.displayLine(it, includeStats = statsForNerdsEnabled)
    }

    var hoistedLyricsLines by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var hoistedIsLyricsLoading by remember { mutableStateOf(false) }
    // Which provider actually supplied the displayed lyrics (null when none).
    var hoistedLyricsProviderId by remember { mutableStateOf<String?>(null) }
    // On-the-spot provider choice made from the Now Playing indicator. It is
    // scoped to the current track and never touches the saved settings.
    var lyricsProviderOverride by remember { mutableStateOf<String?>(null) }
    var lyricsOverrideSongKey by remember { mutableStateOf("") }
    // Bumped when the user changes lyrics providers, so the current song is
    // re-fetched immediately instead of waiting for the next track.
    var lyricsSettingsVersion by remember { mutableStateOf(0) }
    var lyricsFetchVersion by remember { mutableStateOf(0) }

    val currentSongKey = "$currentSongTitle|$currentSongArtist"
    val effectiveLyricsOverride = lyricsProviderOverride
        .takeIf { lyricsOverrideSongKey == currentSongKey }

    LaunchedEffect(currentSongTitle, currentSongArtist, lyricsSettingsVersion, lyricsFetchVersion) {
        if (currentSongTitle.startsWith("AI") || currentSongTitle == "Music Context" || currentSongTitle.startsWith("commentary_")) {
            val description = exoPlayer.currentMediaItem?.mediaMetadata?.description?.toString() ?: ""
            if (description.isNotBlank()) {
                hoistedLyricsLines = listOf(LyricLine(-1L, description))
            } else {
                hoistedLyricsLines = listOf(LyricLine(-1L, "AI is speaking..."))
            }
            hoistedLyricsProviderId = null
            hoistedIsLyricsLoading = false
            return@LaunchedEffect
        }

        if (currentSongTitle.isEmpty() || currentSongTitle == "No Track Loaded") {
            hoistedLyricsLines = listOf(LyricLine(-1L, "No lyrics available for this track."))
            hoistedLyricsProviderId = null
            hoistedIsLyricsLoading = false
            return@LaunchedEffect
        }

        hoistedIsLyricsLoading = true
        hoistedLyricsLines = emptyList()
        hoistedLyricsProviderId = null

        // Read playback values on the Main/UI thread before switching dispatchers.
        val durationMs = exoPlayer.duration
        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else -1
        val videoId = exoPlayer.currentMediaItem?.mediaId?.substringBefore('#')

        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                // Clean track metadata to eliminate YouTube specific promotional garbage.
                val cleanedTitle = cleanYouTubeTitle(currentSongTitle)
                val cleanedArtist = currentSongArtist.trim()

                // The repository walks the user's enabled providers in priority
                // order and returns the first synced result (or a plain fallback).
                val result = LyricsRepository.fetch(
                    context = context,
                    title = cleanedTitle,
                    artist = cleanedArtist,
                    durationSec = durationSec,
                    videoId = videoId,
                    overrideProviderId = effectiveLyricsOverride,
                )
                val rawLyrics = result?.rawLyrics

                withContext(Dispatchers.Main) {
                    if (rawLyrics != null) {
                        val parsed = parseLyricsToLines(rawLyrics)
                        hoistedLyricsLines = if (parsed.isNotEmpty()) parsed else listOf(LyricLine(-1L, "No lyrics found."))
                    } else {
                        hoistedLyricsLines = listOf(LyricLine(-1L, "No lyrics found for this song."))
                    }
                    hoistedLyricsProviderId = result?.providerId
                    hoistedIsLyricsLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hoistedLyricsLines = listOf(LyricLine(-1L, "Could not load lyrics: ${e.localizedMessage ?: "Unknown error"}"))
                    hoistedLyricsProviderId = null
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
                val mediaId = currentMediaItem.mediaId.substringBefore('#')
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
                nowPlayingMetadata = mediaMetadata
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
        nowPlayingMetadata = exoPlayer.mediaMetadata
        updateQueue()

        if (playbackState == Player.STATE_READY) {
            isLoading = false
            statusMessage = "Playing"
        }

        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // ---- Cast mirroring ---------------------------------------------------
    // The local player stays the source of truth (see CastManager): it keeps
    // advancing the queue muted while connected, and these hooks push every
    // transport change to the Cast receiver.
    val castCurrentItem: (Boolean) -> Unit = { playWhenReady ->
        val item = exoPlayer.currentMediaItem
        val mediaId = item?.mediaId?.substringBefore('#')
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
        val isCommentary = mediaId.isNullOrBlank() ||
            mediaId.startsWith("commentary_") ||
            title.startsWith("AI") ||
            title == "Music Context"
        if (!isCommentary) {
            CastManager.castTrack(
                videoId = mediaId!!,
                title = title,
                artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
                artwork = item?.mediaMetadata?.artworkUri?.toString(),
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
                playWhenReady = playWhenReady,
            )
        }
    }

    DisposableEffect(exoPlayer) {
        CastManager.initialize(context)
        // Let the cast controller snapshot the play intent before a session
        // starts (route hand-off can transiently pause local playback).
        CastManager.playWhenReadyProvider = { exoPlayer.playWhenReady }
        val castSyncListener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!CastManager.isCasting) return
                // The phone is muted while casting; don't let a brief audio
                // focus hand-off pause the TV/stereo.
                if (!playWhenReady &&
                    (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                ) {
                    return
                }
                CastManager.setRemotePlayback(playWhenReady)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (CastManager.isCasting && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    CastManager.seekTo(newPosition.positionMs)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (CastManager.isCasting) {
                    castCurrentItem(exoPlayer.playWhenReady)
                }
            }
        }
        exoPlayer.addListener(castSyncListener)

        // Receiver → app: a TV remote / Google Home transport change updates
        // the phone player so the in-app UI (and notification) follow along.
        CastManager.onRemoteSeek = { positionMs ->
            if (CastManager.isCasting) {
                exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
            }
        }

        onDispose {
            exoPlayer.removeListener(castSyncListener)
            CastManager.onRemoteSeek = null
            CastManager.playWhenReadyProvider = null
        }
    }

    val isCastingNow = CastManager.isCasting
    LaunchedEffect(isCastingNow) {
        if (isCastingNow) {
            // Keep the phone silent while the receiver plays, and stop ExoPlayer
            // from reacting to audio-focus hand-offs during the session.
            PlaybackService.instance?.setCastingMute(true)
            exoPlayer.volume = 0f
            // Re-assert the play intent captured before the session started so
            // casting never leaves the song paused.
            if (CastManager.connectPlayIntent == true && !exoPlayer.playWhenReady) {
                exoPlayer.playWhenReady = true
            }
            if (exoPlayer.currentMediaItem != null) {
                castCurrentItem(exoPlayer.playWhenReady)
            }
        } else {
            PlaybackService.instance?.setCastingMute(false)
            exoPlayer.volume = 1f
        }
    }

    // Receiver → app: play/pause pressed on the TV remote, Google Home or the
    // Cast notification must be reflected in the phone player (and therefore
    // in the Radio screen, notification and Android Auto). Wait until the
    // receiver has actually reported playback so the empty initial session
    // status can never pause the song.
    LaunchedEffect(CastManager.castIsPlaying, CastManager.remotePlaybackStateKnown) {
        if (
            CastManager.isCasting &&
            CastManager.remoteTrackLoaded &&
            CastManager.remotePlaybackStateKnown &&
            exoPlayer.playWhenReady != CastManager.castIsPlaying
        ) {
            exoPlayer.playWhenReady = CastManager.castIsPlaying
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
            nowPlayingMetadata = null

            val resolvedStream = withContext(Dispatchers.IO) {
                StreamUrlResolver.resolveStream(
                    songId = targetSong.id,
                    title = targetSong.title,
                    artist = targetSong.artists.joinToString(", ") { it.name },
                    album = targetSong.album?.name,
                    durationSeconds = targetSong.duration,
                    jioSaavnEnabled = SessionManager.isJioSaavnEnabled(context),
                )
            }

            if (resolvedStream != null) {
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
                                            .setArtworkUri(commentaryArtworkUri(context, "session_opener"))
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

                    // Media3 requires unique mediaIds across the timeline, and a
                    // playlist may legitimately contain the same song more than
                    // once — repeats get a "#q<n>" qualifier (stripped by the
                    // service's videoIdFromMediaId for all playback routing).
                    val queuedIdCounts = HashMap<String, Int>()
                    val uniquePlaylistMediaId = { videoId: String ->
                        val n = queuedIdCounts.getOrDefault(videoId, 0)
                        queuedIdCounts[videoId] = n + 1
                        if (n == 0) videoId else "$videoId#q$n"
                    }

                    // Add all songs and insert startCommentaryItem at startIndex
                    songs.forEachIndexed { index, song ->
                        if (index == startIndex && startCommentaryItem != null) {
                            exoPlayer.addMediaItem(startCommentaryItem)
                        }

                        val item = if (index == startIndex) {
                            MediaItem.Builder()
                                .setUri(resolvedStream.url)
                                .setMediaId(uniquePlaylistMediaId(song.id))
                                .setMediaMetadata(
                                    StreamMetadata.withStreamInfo(songMediaMetadata(song), resolvedStream)
                                )
                                .build()
                        } else {
                            MediaItem.Builder()
                                .setUri(android.net.Uri.parse("tunespark://unresolved/${song.id}"))
                                .setMediaId(uniquePlaylistMediaId(song.id))
                                .setMediaMetadata(songMediaMetadata(song))
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
        nowPlayingMetadata = null
        statusMessage = "Fetching stream..."

        coroutineScope.launch {
            val resolvedStream = withContext(Dispatchers.IO) {
                StreamUrlResolver.resolveStream(
                    songId = song.id,
                    title = song.title,
                    artist = song.artists.joinToString(", ") { it.name },
                    album = song.album?.name,
                    durationSeconds = song.duration,
                    jioSaavnEnabled = SessionManager.isJioSaavnEnabled(context),
                )
            }
            if (resolvedStream != null) {
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
                                            .setArtworkUri(commentaryArtworkUri(context, "session_opener"))
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
                        .setUri(resolvedStream.url)
                        .setMediaId(song.id)
                        .setMediaMetadata(
                            StreamMetadata.withStreamInfo(songMediaMetadata(song), resolvedStream)
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

    // Personalization bucket for the heavy Home feeds. `localHistorySize` grows
    // every time a song has been listened to for 10 seconds, which during
    // background playback used to re-key the Quick Picks / "From the community"
    // / Speed Dial loaders on EVERY song — wiping their lists to skeletons and
    // re-issuing dozens of network requests once per track (the "Home screen
    // repeatedly reloads many times" bug). Bucketing the history size by 5
    // keeps the intended taste-refresh behavior (personalization follows
    // listening) but only every 5 *new* songs, instead of every song change.
    val homePersonalizationBucket = localHistorySize / 5

    // The community playlist engine makes many more network calls (genre
    // shelves + playlist pages), so it refreshes on a coarser cadence than the
    // other Home sections to avoid hammering the network every few songs.
    val homeCommunityBucket = localHistorySize / 15

    var homeSpeedDialSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isHomeSpeedDialLoading by remember { mutableStateOf(false) }

    var homeCommunityPlaylists by remember { mutableStateOf<List<CommunityPlaylistData>>(emptyList()) }
    var isHomeCommunityLoading by remember { mutableStateOf(false) }

    var homeRecentsSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isHomeRecentsLoading by remember { mutableStateOf(false) }

    var homeQuickPicksSongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isHomeQuickPicksLoading by remember { mutableStateOf(false) }

    // Home "New releases" section (YouTube Music's new-releases album shelf)
    var homeNewReleaseAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var isHomeNewReleasesLoading by remember { mutableStateOf(false) }

    // Home "From your library" section (on-device + account playlists merged)
    var homeLibraryPlaylists by remember { mutableStateOf<List<HomeLibraryPlaylist>>(emptyList()) }
    var isHomeLibraryLoading by remember { mutableStateOf(false) }

    // Discover feed enabled state — gates the Home screen's launcher-style
    // swipe-to-Discover gesture (the carousel itself was removed from Home;
    // the full Discover screen opens on the swipe).
    var isDiscoverEnabled by remember { mutableStateOf(SessionManager.isDiscoverFeedEnabled(context)) }

    // Re-read the discover enabled state whenever the user navigates back to Home
    // (e.g., after changing it in the Discover Feed settings screen)
    LaunchedEffect(currentScreen) {
        if (currentScreen == AppScreen.HOME) {
            isDiscoverEnabled = SessionManager.isDiscoverFeedEnabled(context)
        }
    }

    LaunchedEffect(userSignedIn, homePersonalizationBucket, homeRefreshTrigger) {
        // Only show the skeleton while there is nothing to show yet (initial
        // load or a failed earlier fetch). Automatic refreshes triggered by the
        // personalization bucket swap the lists in place — no wipe, no flicker.
        if (homeQuickPicksSongs.isEmpty()) isHomeQuickPicksLoading = true
        withContext(Dispatchers.IO) {
            try {
                val songs = mutableListOf<SongItem>()
                val localHistory = SessionManager.getLocalHistory(context)
                if (localHistory.size >= 3) {
                    val seedTracks = localHistory.distinctBy { it.id }
                        // JioSaavn ids have no YouTube watch-next page.
                        .filter { !AudioProviderIds.isJioSaavnId(it.id) }
                        .take(3)
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

    // ── Home "New releases" section loader ──
    // Uses the :innertube module's existing newReleaseAlbums() browse
    // (FEmusic_new_releases_albums). The shelf is identical signed in or out.
    // Re-fetches on pull-to-refresh; automatic refreshes swap the list in place.
    LaunchedEffect(homeRefreshTrigger) {
        if (homeNewReleaseAlbums.isEmpty()) isHomeNewReleasesLoading = true
        withContext(Dispatchers.IO) {
            try {
                val albums = YouTube.newReleaseAlbums().getOrNull().orEmpty().take(10)
                withContext(Dispatchers.Main) {
                    homeNewReleaseAlbums = albums
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                isHomeNewReleasesLoading = false
            }
        }
    }

    // ── Home "From your library" section loader ──
    // Merges on-device playlists (works fully signed out) with the signed-in
    // account's own YouTube Music playlists (auto-lists excluded). Re-runs
    // whenever the user returns to Home so local playlist changes (created /
    // renamed / deleted in the Library) are reflected without a pull-to-refresh.
    // The section itself only renders when there are 3 or more playlists.
    LaunchedEffect(currentScreen, userSignedIn, homeRefreshTrigger) {
        if (currentScreen != AppScreen.HOME) return@LaunchedEffect
        if (homeLibraryPlaylists.isEmpty()) isHomeLibraryLoading = true
        withContext(Dispatchers.IO) {
            try {
                val local = LocalPlaylistManager.getPlaylists(context).map {
                    HomeLibraryPlaylist(
                        id = it.id,
                        name = it.name,
                        thumbnail = it.thumbnail,
                        songCountText = it.songCountText,
                        isLocal = true,
                        songs = it.songs
                    )
                }

                var account = emptyList<HomeLibraryPlaylist>()
                if (userSignedIn) {
                    try {
                        val result = YouTube.library("FEmusic_liked_playlists")
                        account = result.getOrNull()?.items.orEmpty()
                            .filterIsInstance<PlaylistItem>()
                            // Exclude YouTube's auto-lists (Liked is surfaced in the Library grid itself)
                            .filter {
                                val titleLower = it.title.lowercase()
                                titleLower != "liked music" && titleLower != "episodes for later"
                            }
                            .map {
                                HomeLibraryPlaylist(
                                    id = it.id.removePrefix("VL"),
                                    name = it.title,
                                    thumbnail = it.thumbnail,
                                    songCountText = it.songCountText ?: "",
                                    isLocal = false,
                                    songs = emptyList()
                                )
                            }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                val merged = local + account
                withContext(Dispatchers.Main) {
                    homeLibraryPlaylists = merged
                    isHomeLibraryLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isHomeLibraryLoading = false
                }
            }
        }
    }

    // Recents is a cheap local read that mirrors the listening history, so it
    // re-keys on the history size (which already grows as songs are captured)
    // instead of `currentSongTitle` — the old key re-ran on every track change,
    // wiping the section to its skeleton even when nothing new had been added.
    LaunchedEffect(userSignedIn, localHistorySize, homeRefreshTrigger) {
        if (homeRecentsSongs.isEmpty()) isHomeRecentsLoading = true
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

    LaunchedEffect(userSignedIn, homeCommunityBucket, homeRefreshTrigger) {
        if (homeCommunityPlaylists.isEmpty()) isHomeCommunityLoading = true
        try {
            val recommendations = CommunityRecommendations.recommend(context, userSignedIn)
            withContext(Dispatchers.Main) {
                if (recommendations.isNotEmpty()) {
                    homeCommunityPlaylists = recommendations
                }
                isHomeCommunityLoading = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                isHomeCommunityLoading = false
            }
        }
    }

    LaunchedEffect(userSignedIn, homePersonalizationBucket, homeRefreshTrigger) {
        if (homeSpeedDialSongs.isEmpty()) isHomeSpeedDialLoading = true
        withContext(Dispatchers.IO) {
            try {
                val songs = mutableListOf<SongItem>()
                val localHistory = SessionManager.getLocalHistory(context)
                if (localHistory.size >= 3) {
                    val seeds = localHistory.distinctBy { it.id }
                        // JioSaavn ids have no YouTube watch-next page.
                        .filter { !AudioProviderIds.isJioSaavnId(it.id) }
                        .take(10)
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

    // ── Quick Action View (long-press song context sheet) state & handlers ──
    var quickActionSong by remember { mutableStateOf<SongItem?>(null) }
    // Playlist context of the long-pressed song — non-null only when the song was
    // opened from a playlist the user owns (enables the "Remove song" action).
    var quickActionPlaylistId by remember { mutableStateOf<String?>(null) }
    var quickActionPlaylistName by remember { mutableStateOf<String?>(null) }
    // Bumped after a successful removal so PlaylistsScreen reloads its song list.
    var playlistContentVersion by remember { mutableStateOf(0) }

    val showQuickActions: (SongItem) -> Unit = { song ->
        // Dismiss the keyboard if it is active so the sheet is fully visible.
        focusManager.clearFocus(force = true)
        quickActionPlaylistId = null
        quickActionPlaylistName = null
        quickActionSong = song
    }

    // PlaylistsScreen variant: records which owned playlist the song belongs to
    // before opening the sheet (context is cleared again by every plain open).
    val showQuickActionsInPlaylist: (String, String, SongItem) -> Unit = { playlistId, playlistName, song ->
        showQuickActions(song)
        quickActionPlaylistId = playlistId
        quickActionPlaylistName = playlistName
    }

    // Media3 requires unique mediaIds across the timeline. The same song can
    // legitimately be queued multiple times, so repeats get a "#q<n>"
    // qualifier appended to the videoId. Playback routing always strips the
    // qualifier (see PlaybackService.videoIdFromMediaId) before use.
    val uniqueQueueMediaId: (String) -> String = { videoId ->
        val bareId = videoId.substringBefore('#')
        var candidate = videoId
        var counter = 0
        while ((0 until exoPlayer.mediaItemCount).any { exoPlayer.getMediaItemAt(it).mediaId == candidate }) {
            counter++
            candidate = "$bareId#q$counter"
        }
        candidate
    }

    // Keep the liked-songs cache fresh so the heart state inside the quick
    // action sheet is always accurate (idempotent initializer).
    LaunchedEffect(Unit) {
        LikedSongManager.init(context)
    }

    val onQuickActionLike: (SongItem) -> Unit = { song ->
        if (AudioProviderIds.isJioSaavnId(song.id)) {
            Toast.makeText(context, "JioSaavn tracks can't be liked in YouTube Music", Toast.LENGTH_SHORT).show()
        } else if (!SessionManager.isUserSignedIn(context)) {
            Toast.makeText(context, "Sign in to like songs", Toast.LENGTH_SHORT).show()
        } else {
            val wasLiked = LikedSongManager.isLiked(song.id)
            coroutineScope.launch {
                val success = LikedSongManager.toggleLike(song.id, context)
                Toast.makeText(
                    context,
                    when {
                        success && !wasLiked -> "Added to Liked songs"
                        success -> "Removed from Liked songs"
                        else -> "Couldn't update like. Please try again."
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Start radio: plays the song immediately and lets the PlaybackService seed
    // the continuous autoplay radio queue, then opens the Radio player screen.
    val onQuickActionStartRadio: (SongItem) -> Unit = { song ->
        quickActionSong = null
        playSong(song)
        openFullPlayer()
    }

    // Play next: inserts the song right after the currently playing track.
    val onQuickActionPlayNext: (SongItem) -> Unit = { song ->
        quickActionSong = null
        if (exoPlayer.mediaItemCount == 0) {
            PlaybackService.isPlaylistMode = false
            exoPlayer.addMediaItem(buildQueueMediaItem(song, uniqueQueueMediaId(song.id)))
            exoPlayer.prepare()
            exoPlayer.play()
            Toast.makeText(context, "Playing ${song.title}", Toast.LENGTH_SHORT).show()
        } else {
            exoPlayer.addMediaItem(exoPlayer.currentMediaItemIndex + 1, buildQueueMediaItem(song, uniqueQueueMediaId(song.id)))
            Toast.makeText(context, "Playing next: ${song.title}", Toast.LENGTH_SHORT).show()
        }
    }

    // Add to queue: appends the song at the end of the queue.
    val onQuickActionAddToQueue: (SongItem) -> Unit = { song ->
        quickActionSong = null
        if (exoPlayer.mediaItemCount == 0) {
            PlaybackService.isPlaylistMode = false
            exoPlayer.addMediaItem(buildQueueMediaItem(song, uniqueQueueMediaId(song.id)))
            exoPlayer.prepare()
            exoPlayer.play()
            Toast.makeText(context, "Playing ${song.title}", Toast.LENGTH_SHORT).show()
        } else {
            exoPlayer.addMediaItem(buildQueueMediaItem(song, uniqueQueueMediaId(song.id)))
            Toast.makeText(context, "Added to queue: ${song.title}", Toast.LENGTH_SHORT).show()
        }
    }

    // Share song: native Android share sheet with the YouTube Music track link.
    val onQuickActionShare: (SongItem) -> Unit = { song ->
        quickActionSong = null
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "${song.title} - ${song.artists.joinToString(", ") { it.name }}")
            putExtra(Intent.EXTRA_TEXT, SongLinks.shareUrl(song.id, song.title, song.artists.joinToString(", ") { it.name }))
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share song"))
    }

    // Go to artist: opens the artist's profile view inside the Library overlay.
    // Some feeds (Home quick picks etc.) parse artists without a browse id — in
    // that case the artist is resolved through a FILTER_ARTIST search first, with
    // a sentinel-id fallback (the artist screen itself falls back to a song search).
    val onQuickActionOpenArtist: (String, String) -> Unit = { artistId, artistName ->
        quickActionSong = null
        if (artistName.isBlank()) {
            Toast.makeText(context, "Artist information unavailable", Toast.LENGTH_SHORT).show()
        } else if (artistId.isNotBlank()) {
            initialArtistId = artistId
            initialArtistName = artistName
            initialArtistThumbnail = null
            initialArtistNavRequest++
            navigateHandler(AppScreen.PLAYLISTS)
        } else {
            coroutineScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    YouTube.search(
                        query = artistName,
                        filter = YouTube.SearchFilter.FILTER_ARTIST
                    ).getOrNull()?.items?.filterIsInstance<ArtistItem>()?.firstOrNull()
                }
                if (resolved != null) {
                    initialArtistId = resolved.id
                    initialArtistName = resolved.title
                    initialArtistThumbnail = resolved.thumbnail
                } else {
                    initialArtistId = "search:$artistName"
                    initialArtistName = artistName
                    initialArtistThumbnail = null
                }
                initialArtistNavRequest++
                navigateHandler(AppScreen.PLAYLISTS)
            }
        }
    }

    // Remove from playlist: drops the song from the owned playlist it was opened
    // from (the sheet only offers this action in that context).
    val onQuickActionRemoveFromPlaylist: (SongItem) -> Unit = { song ->
        quickActionSong = null
        val playlistId = quickActionPlaylistId
        val playlistName = quickActionPlaylistName
        if (playlistId == null) {
            Toast.makeText(context, "Couldn't remove the song from this playlist.", Toast.LENGTH_SHORT).show()
        } else if (LocalPlaylistManager.isLocalPlaylistId(playlistId)) {
            // On-device playlist — no setVideoId needed, just drop the id locally.
            coroutineScope.launch {
                val removed = withContext(Dispatchers.IO) {
                    LocalPlaylistManager.removeSong(context, playlistId, song.id)
                }
                if (removed) {
                    Toast.makeText(context, "Removed '${song.title}' from '${playlistName ?: "playlist"}'", Toast.LENGTH_SHORT).show()
                    // Tell PlaylistsScreen to reload the playlist (and grid counts).
                    playlistContentVersion++
                } else {
                    Toast.makeText(context, "Couldn't remove the song from this playlist.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val setVideoId = song.setVideoId
            if (setVideoId == null) {
                Toast.makeText(context, "Couldn't remove the song from this playlist.", Toast.LENGTH_SHORT).show()
            } else {
                coroutineScope.launch(Dispatchers.IO) {
                    val result = YouTube.removeFromPlaylist(playlistId, song.id, setVideoId)
                    withContext(Dispatchers.Main) {
                        if (result.isSuccess) {
                            Toast.makeText(context, "Removed '${song.title}' from '${playlistName ?: "playlist"}'", Toast.LENGTH_SHORT).show()
                            // Tell PlaylistsScreen to reload the playlist (and grid counts).
                            playlistContentVersion++
                        } else {
                            Toast.makeText(context, "Couldn't remove the song from this playlist.", Toast.LENGTH_SHORT).show()
                        }
                    }
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
            // Width used by the Home <-> Discover swipe stage (px).
            val stageScreenWidthPx = with(LocalDensity.current) { maxWidth.toPx() }

            // ---------- Home <-> Discover interactive swipe transition ----------
            // Home and Discover behave like two adjacent launcher pages: the
            // finger moves them 1:1 during the swipe, and on release the pair
            // either commits (springs to full) or falls back (springs home).
            // Positive offset = Home pushed right (Discover revealed from the
            // left); negative offset = Discover pushed left (Home revealed
            // from the right).
            val discoverSwipeAnimatable = remember { Animatable(0f) }
            var isDiscoverSwipeActive by remember { mutableStateOf(false) }
            val discoverSwipeSpring = spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
            val discoverSwipeFlingPx = with(LocalDensity.current) { 700.dp.toPx() }
            val swipeFeedbackView = androidx.compose.ui.platform.LocalView.current
            val playSwipeCommitFeedback = {
                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 1.0f)
                swipeFeedbackView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }

            // Forward swipe (Home -> Discover), driven by HomeScreen's gesture.
            val onHomeDiscoverSwipeStart: () -> Unit = {
                if (currentScreen == AppScreen.HOME) isDiscoverSwipeActive = true
            }
            val onHomeDiscoverSwipeDrag: (Float) -> Unit = { delta ->
                if (currentScreen == AppScreen.HOME) {
                    val target = (discoverSwipeAnimatable.value + delta).coerceIn(0f, stageScreenWidthPx)
                    coroutineScope.launch { discoverSwipeAnimatable.snapTo(target) }
                }
            }
            val onHomeDiscoverSwipeSettle: (Float) -> Unit = { velocityX ->
                if (currentScreen == AppScreen.HOME && isDiscoverSwipeActive) {
                    val commit = discoverSwipeAnimatable.value > stageScreenWidthPx * 0.4f ||
                            (velocityX > discoverSwipeFlingPx && discoverSwipeAnimatable.value > stageScreenWidthPx * 0.06f)
                    coroutineScope.launch {
                        if (commit) {
                            playSwipeCommitFeedback()
                            discoverSwipeAnimatable.animateTo(stageScreenWidthPx, discoverSwipeSpring)
                            currentScreen = AppScreen.DISCOVER
                        } else {
                            discoverSwipeAnimatable.animateTo(0f, discoverSwipeSpring)
                        }
                        isDiscoverSwipeActive = false
                        discoverSwipeAnimatable.snapTo(0f)
                    }
                } else if (currentScreen == AppScreen.HOME) {
                    isDiscoverSwipeActive = false
                    coroutineScope.launch { discoverSwipeAnimatable.snapTo(0f) }
                }
            }

            // Back swipe (Discover -> Home), attached to the Discover page.
            val onDiscoverBackSwipeStart: () -> Unit = {
                if (currentScreen == AppScreen.DISCOVER) isDiscoverSwipeActive = true
            }
            val onDiscoverBackSwipeDrag: (Float) -> Unit = { delta ->
                if (currentScreen == AppScreen.DISCOVER) {
                    val target = (discoverSwipeAnimatable.value + delta).coerceIn(-stageScreenWidthPx, 0f)
                    coroutineScope.launch { discoverSwipeAnimatable.snapTo(target) }
                }
            }
            val onDiscoverBackSwipeSettle: (Float) -> Unit = { velocityX ->
                if (currentScreen == AppScreen.DISCOVER && isDiscoverSwipeActive) {
                    val commit = -discoverSwipeAnimatable.value > stageScreenWidthPx * 0.4f ||
                            (velocityX < -discoverSwipeFlingPx && -discoverSwipeAnimatable.value > stageScreenWidthPx * 0.06f)
                    coroutineScope.launch {
                        if (commit) {
                            playSwipeCommitFeedback()
                            discoverSwipeAnimatable.animateTo(-stageScreenWidthPx, discoverSwipeSpring)
                            currentScreen = AppScreen.HOME
                        } else {
                            discoverSwipeAnimatable.animateTo(0f, discoverSwipeSpring)
                        }
                        isDiscoverSwipeActive = false
                        discoverSwipeAnimatable.snapTo(0f)
                    }
                } else if (currentScreen == AppScreen.DISCOVER) {
                    isDiscoverSwipeActive = false
                    coroutineScope.launch { discoverSwipeAnimatable.snapTo(0f) }
                }
            }

            // Animated slide-back used by the Discover screen's back button and
            // the system back gesture — the same motion as the back swipe.
            val navigateFromDiscover: (AppScreen) -> Unit = { screen ->
                if (screen == AppScreen.HOME && currentScreen == AppScreen.DISCOVER && !isDiscoverSwipeActive) {
                    isDiscoverSwipeActive = true
                    coroutineScope.launch {
                        discoverSwipeAnimatable.animateTo(-stageScreenWidthPx, discoverSwipeSpring)
                        currentScreen = AppScreen.HOME
                        isDiscoverSwipeActive = false
                        discoverSwipeAnimatable.snapTo(0f)
                    }
                } else {
                    navigateHandler(screen)
                }
            }

            val homeDiscoverStageActive = currentScreen == AppScreen.HOME || currentScreen == AppScreen.DISCOVER

            // Shared screen content, so the swipe stage and the AnimatedContent
            // render identical Home / Discover instances.
            val homeScreenContent: @Composable () -> Unit = {
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
                    onSongLongPress = showQuickActions,
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
                        // Opening from Home: back from the detail should return
                        // HOME, not strand the user on the Library grid.
                        libraryPlaylistFromExternal = true
                        currentScreen = AppScreen.PLAYLISTS
                    },
                    onRefresh = { homeRefreshTrigger++ },
                    speedDialSongs = homeSpeedDialSongs,
                    isSpeedDialLoading = isHomeSpeedDialLoading,
                    communityPlaylists = homeCommunityPlaylists,
                    isCommunityLoading = isHomeCommunityLoading,
                    newReleaseAlbums = homeNewReleaseAlbums,
                    isNewReleasesLoading = isHomeNewReleasesLoading,
                    libraryPlaylists = homeLibraryPlaylists,
                    isLibraryLoading = isHomeLibraryLoading,
                    recentsSongs = homeRecentsSongs,
                    isRecentsLoading = isHomeRecentsLoading,
                    quickPicksSongs = homeQuickPicksSongs,
                    isQuickPicksLoading = isHomeQuickPicksLoading,
                    isDiscoverEnabled = isDiscoverEnabled,
                    onDiscoverSwipeStart = onHomeDiscoverSwipeStart,
                    onDiscoverSwipeDrag = onHomeDiscoverSwipeDrag,
                    onDiscoverSwipeSettle = onHomeDiscoverSwipeSettle
                )
            }
            // The back handler is only enabled while Discover is genuinely the
            // current screen (the pre-warmed off-screen instance must not
            // swallow the system back press meant for Home).
            val discoverScreenContent: @Composable () -> Unit = {
                DiscoverScreen(
                    onNavigate = navigateFromDiscover,
                    isBackHandlerEnabled = currentScreen == AppScreen.DISCOVER
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                // ---------- Home <-> Discover interactive swipe stage ----------
                // Renders the Home and Discover screens as two adjacent launcher-
                // style pages that track the finger 1:1 during the swipe gesture.
                // Drawn beneath the AnimatedContent (which renders nothing for
                // HOME/DISCOVER while the stage is active), so every other screen
                // transition works exactly as before.
                if (homeDiscoverStageActive) {
                    val swipeOffset = discoverSwipeAnimatable.value
                    val swipeProgress = (kotlin.math.abs(swipeOffset) / stageScreenWidthPx).coerceIn(0f, 1f)
                    val forwardSwipe = swipeOffset >= 0f
                    // The idle counterpart page is kept composed (pre-warmed) so a
                    // swipe starts with real content instead of skeletons.
                    val homeLayerVisible = currentScreen == AppScreen.HOME ||
                            currentScreen == AppScreen.DISCOVER ||
                            (isDiscoverSwipeActive && swipeOffset < -0.5f)
                    val discoverLayerVisible = currentScreen == AppScreen.HOME ||
                            currentScreen == AppScreen.DISCOVER ||
                            (isDiscoverSwipeActive && swipeOffset > 0.5f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Exactly the same system-bar insets the
                            // AnimatedContent applies to every other screen, so
                            // the stage pages sit precisely where the screens
                            // always did.
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    ) {
                        // Home page layer — pushed right on the forward swipe,
                        // slides back in from the right on the way home.
                        if (homeLayerVisible) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (swipeOffset < 0f) 0f else 1f)
                                    .graphicsLayer {
                                        translationX = when {
                                            !isDiscoverSwipeActive -> if (currentScreen == AppScreen.HOME) 0f else stageScreenWidthPx
                                            forwardSwipe -> swipeOffset
                                            else -> stageScreenWidthPx + swipeOffset
                                        }
                                        // Dim the departing page slightly for depth.
                                        alpha = if (isDiscoverSwipeActive && forwardSwipe) 1f - 0.3f * swipeProgress else 1f
                                    }
                            ) {
                                homeScreenContent()
                            }
                        }
                        // Discover page layer — revealed from the left on the
                        // forward swipe, pushed left on the way back.
                        if (discoverLayerVisible) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (swipeOffset > 0f) 0f else 1f)
                                    .graphicsLayer {
                                        translationX = when {
                                            !isDiscoverSwipeActive -> if (currentScreen == AppScreen.DISCOVER) 0f else -stageScreenWidthPx
                                            forwardSwipe -> swipeOffset - stageScreenWidthPx
                                            else -> swipeOffset
                                        }
                                        alpha = if (isDiscoverSwipeActive && !forwardSwipe) 1f - 0.3f * swipeProgress else 1f
                                    }
                            ) {
                                // Back-swipe gesture: dragging left slides Home in
                                // from the right, tracking the finger 1:1.
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(Unit) {
                                            var lastTimeMillis = 0L
                                            var velocityX = 0f
                                            detectHorizontalDragGestures(
                                                onDragStart = {
                                                    lastTimeMillis = 0L
                                                    velocityX = 0f
                                                    onDiscoverBackSwipeStart()
                                                },
                                                onDragCancel = { onDiscoverBackSwipeSettle(0f) },
                                                onDragEnd = {
                                                    onDiscoverBackSwipeSettle(velocityX)
                                                }
                                            ) { change, dragAmount ->
                                                change.consume()
                                                val timeMillis = change.uptimeMillis
                                                if (lastTimeMillis != 0L) {
                                                    val deltaSeconds = (timeMillis - lastTimeMillis).coerceAtLeast(1L) / 1000f
                                                    // Exponentially-smoothed fling velocity estimate (px/s).
                                                    velocityX = 0.8f * velocityX + 0.2f * (dragAmount / deltaSeconds)
                                                }
                                                lastTimeMillis = timeMillis
                                                onDiscoverBackSwipeDrag(dragAmount)
                                            }
                                        }
                                ) {
                                    discoverScreenContent()
                                }
                            }
                        }
                    }
                }
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
                                // Rendered by the interactive swipe stage while
                                // Home/Discover are active; AnimatedContent only
                                // renders Home here for transitions to/from the
                                // other screens (so their slide animations work).
                                if (!homeDiscoverStageActive) {
                                    homeScreenContent()
                                }
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
                                onSongLongPress = showQuickActions,
                                onArtistClick = { artist ->
                                    initialArtistId = artist.id
                                    initialArtistName = artist.title
                                    initialArtistThumbnail = artist.thumbnail
                                    navigateHandler(AppScreen.PLAYLISTS)
                                },
                                onOpenExploreItem = openExplorePlaylist,
                                isUserSignedIn = userSignedIn,
                                onPlayNextSong = onQuickActionPlayNext,
                                onAddToQueueSong = onQuickActionAddToQueue,
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
                        AppScreen.LYRICS -> {
                            LyricsScreen(
                                onNavigate = navigateHandler,
                                onSettingsChanged = { lyricsSettingsVersion++ }
                            )
                        }
                        AppScreen.PLAYER_AUDIO -> {
                            PlayerAndAudioScreen(
                                onNavigate = navigateHandler,
                                onStatsForNerdsChanged = { statsForNerdsEnabled = it }
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
                                initialArtistId = initialArtistId,
                                initialArtistName = initialArtistName,
                                initialArtistThumbnail = initialArtistThumbnail,
                                initialArtistNavRequest = initialArtistNavRequest,
                                playlistDetailExitsLibrary = libraryPlaylistFromExternal,
                                onPlayPlaylist = { name, songs, startIndex ->
                                    playPlaylist(name, songs, startIndex)
                                    openFullPlayer()
                                },
                                onPlaySong = { song ->
                                    playSong(song)
                                    openFullPlayer()
                                },
                                onSongLongPress = showQuickActions,
                                onPlaylistSongLongPress = showQuickActionsInPlaylist,
                                playlistContentVersion = playlistContentVersion,
                                onPlayNextSong = onQuickActionPlayNext,
                                onAddToQueueSong = onQuickActionAddToQueue,
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
                                        initialArtistId = null
                                        initialArtistName = null
                                        initialArtistThumbnail = null
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
                                    onSongLongPress = showQuickActions,
                                    onPlayNextSong = onQuickActionPlayNext,
                                    onAddToQueueSong = onQuickActionAddToQueue,
                                    onNavigate = navigateHandler
                                )
                            }
                            AppScreen.DISCOVER -> {
                                // Rendered by the swipe stage while Home/Discover
                                // are active (see the HOME branch note).
                                if (!homeDiscoverStageActive) {
                                    discoverScreenContent()
                                }
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
                        onSongLongPress = showQuickActions,
                        onPlayNextSong = onQuickActionPlayNext,
                        onAddToQueueSong = onQuickActionAddToQueue,
                        onArtistClick = { artist ->
                            initialArtistId = artist.id
                            initialArtistName = artist.title
                            initialArtistThumbnail = artist.thumbnail
                            navigateHandler(AppScreen.PLAYLISTS)
                        },
                        onOpenExploreItem = openExplorePlaylist,
                        isUserSignedIn = userSignedIn,
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
                        initialArtistId = initialArtistId,
                        initialArtistName = initialArtistName,
                        initialArtistThumbnail = initialArtistThumbnail,
                        initialArtistNavRequest = initialArtistNavRequest,
                        playlistDetailExitsLibrary = libraryPlaylistFromExternal,
                        onPlayPlaylist = { name, songs, startIndex ->
                            playPlaylist(name, songs, startIndex)
                            openFullPlayer()
                        },
                        onPlaySong = { song ->
                            playSong(song)
                            openFullPlayer()
                        },
                        onSongLongPress = showQuickActions,
                        onPlaylistSongLongPress = showQuickActionsInPlaylist,
                        playlistContentVersion = playlistContentVersion,
                        onPlayNextSong = onQuickActionPlayNext,
                        onAddToQueueSong = onQuickActionAddToQueue,
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
                                initialArtistId = null
                                initialArtistName = null
                                initialArtistThumbnail = null
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
                            if (CastManager.isCasting) {
                                CastManager.disconnect()
                            }
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            currentSongTitle = "No Track Loaded"
                            currentSongArtist = ""
                            currentSongArtwork = null
                            nowPlayingMetadata = null
                            playQueue = emptyList()
                            currentTrackIndex = -1
                            hasPreviousTrack = false
                            hasNextTrack = false
                            closeFullPlayer()
                        },
                        lyricsLines = hoistedLyricsLines,
                        isLyricsLoading = hoistedIsLyricsLoading,
                        lyricsProviderId = hoistedLyricsProviderId,
                        lyricsProviderOverride = effectiveLyricsOverride,
                        onLyricsProviderSelected = { providerId ->
                            // Applied to the current track only; the saved
                            // Settings order is left untouched.
                            lyricsProviderOverride = providerId
                            lyricsOverrideSongKey = currentSongKey
                            lyricsFetchVersion++
                        },
                        nowPlayingSourceLine = nowPlayingSourceLine,
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
                                nowPlayingMetadata = null
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

            // Quick Action View — long-press song context sheet, drawn last so it
            // floats on very top of every screen, overlay and the bottom dock.
            QuickActionView(
                song = quickActionSong,
                onDismiss = { quickActionSong = null },
                onLike = onQuickActionLike,
                onStartRadio = onQuickActionStartRadio,
                onPlayNext = onQuickActionPlayNext,
                onAddToQueue = onQuickActionAddToQueue,
                onShare = onQuickActionShare,
                onOpenArtist = onQuickActionOpenArtist,
                onRemoveFromPlaylist = if (quickActionPlaylistId != null) onQuickActionRemoveFromPlaylist else null
            )
        }
    }
}
