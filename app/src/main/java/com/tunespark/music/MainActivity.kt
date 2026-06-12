package com.tunespark.music

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.tunespark.music.ui.theme.TunesparkTheme
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
    ACCOUNT
}

@Composable
fun MainPlayerScreen(exoPlayer: Player, modifier: Modifier = Modifier) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var showWebView by remember { mutableStateOf(false) }
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
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = { currentScreen = AppScreen.SETTINGS },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "TuneSpark Player",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 32.dp)
                        )

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "🔀 Quick Shuffle Play",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Instantly generate a continuous, dynamic playback mix based on regional charts, popular trending music, and home collections!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))

                                if (isShuffling) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            currentScreen = AppScreen.RADIO
                                            shufflePlay()
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier.fillMaxWidth(0.8f)
                                    ) {
                                        Text("🔀 Shuffle Play", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { currentScreen = AppScreen.SEARCH },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Text("🔍 Search Music", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        if (currentSongTitle != "No Track Loaded") {
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { currentScreen = AppScreen.RADIO },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary
                                ),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                Text("📻 Go to Radio Screen", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                    }
                }
            }

            AppScreen.SETTINGS -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 32.dp)
                    ) {
                        IconButton(
                            onClick = { currentScreen = AppScreen.HOME },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFFFF0000), shape = CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Settings",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val settingsItems = listOf(
                        "Appearance",
                        "Account",
                        "AI and Voice",
                        "Commentary",
                        "Notifications",
                        "Location",
                        "Updates"
                    )

                    settingsItems.forEach { item ->
                        Text(
                            text = item,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item == "Account") {
                                        currentScreen = AppScreen.ACCOUNT
                                    }
                                }
                                .padding(vertical = 16.dp)
                        )
                    }
                }
            }

            AppScreen.ACCOUNT -> {
                if (showWebView) {
                    YouTubeSignInWebView(
                        onCookieExtracted = { cookies ->
                            SessionManager.saveCookie(context, cookies)
                            isLoadingProfile = true
                            profileError = null
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
                                        profileError = "Authentication failed. Please sign in again."
                                    }
                                }
                            }
                            showWebView = false
                        },
                        onCancel = {
                            showWebView = false
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 32.dp)
                        ) {
                            IconButton(
                                onClick = { currentScreen = AppScreen.SETTINGS },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0xFFFF0000), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Back to Settings",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Account",
                                color = Color.White,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (profileError != null) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = profileError ?: "",
                                        color = Color.White,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "✕",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .clickable { profileError = null }
                                            .padding(horizontal = 8.dp)
                                    )
                                }
                            }
                        }

                        if (isLoadingProfile) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFFFF0000))
                            }
                        } else {
                            val info = accountInfo
                            if (info != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Colored avatar with initials
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .background(Color(0xFFFF0000), shape = CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = info.name.take(1).uppercase(),
                                            color = Color.White,
                                            fontSize = 36.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = info.name,
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    if (!info.email.isNullOrEmpty()) {
                                        Text(
                                            text = info.email ?: "",
                                            color = Color.Gray,
                                            fontSize = 16.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    if (!info.channelHandle.isNullOrEmpty()) {
                                        Text(
                                            text = info.channelHandle ?: "",
                                            color = Color.Gray,
                                            fontSize = 16.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(32.dp))

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp)
                                        ) {
                                            Text(
                                                text = "Subscription Status",
                                                color = Color.Gray,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Linked to YouTube Music via active session",
                                                color = Color.White,
                                                fontSize = 16.sp
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    Button(
                                        onClick = {
                                            SessionManager.clearSession(context)
                                            accountInfo = null
                                            CookieManager.getInstance().removeAllCookies(null)
                                            CookieManager.getInstance().flush()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp)
                                    ) {
                                        Text("Sign Out", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Spacer(modifier = Modifier.height(32.dp))

                                    // Placeholder Avatar Icon
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .background(Color(0xFF222222), shape = CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "👤",
                                            fontSize = 40.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(24.dp))

                                    Text(
                                        text = "Unlock Your Personal Library",
                                        color = Color.White,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = "Sign in to YouTube Music optionally to stream your liked songs, custom playlists, listening history, and enjoy tailored recommended feeds.",
                                        color = Color.Gray,
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    Button(
                                        onClick = { showWebView = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 16.dp)
                                    ) {
                                        Text("Sign In with YouTube Music", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AppScreen.SEARCH -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { currentScreen = AppScreen.HOME },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("← Home", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Search Music",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search songs...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            triggerSearch()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                triggerSearch()
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text("Search", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Search for your favorite tracks above!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(searchResults) { song ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                playSong(song)
                                                currentScreen = AppScreen.RADIO
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = song.title,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = song.artists.joinToString(", ") { it.name },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text("▶", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AppScreen.RADIO -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { currentScreen = AppScreen.HOME },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("← Home", fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Radio & Playback",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (playQueue.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Queue is empty.\nGo to Home or Search to play a song!",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Text(
                                    text = "Up Next ⏭ (${playQueue.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(playQueue) { index, item ->
                                        val isCurrent = index == currentTrackIndex
                                        val containerColor = if (isCurrent) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                        val textColor = if (isCurrent) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(containerColor)
                                                .clickable { exoPlayer.seekTo(index, 0L) }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${index + 1}.",
                                                fontWeight = FontWeight.Bold,
                                                color = textColor,
                                                modifier = Modifier.width(32.dp)
                                            )
                                            Column(
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = item.mediaMetadata.title?.toString() ?: "Unknown Title",
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = textColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.mediaMetadata.artist?.toString() ?: "Unknown Artist",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = textColor.copy(alpha = 0.8f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isCurrent) {
                                                Text("🔊", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Current Playing / Streaming Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = currentSongTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (currentSongArtist.isNotEmpty()) {
                                Text(
                                    text = currentSongArtist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else if (errorMessage != null) {
                                Text(
                                    text = errorMessage ?: "Playback error occurred",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            } else if (currentSongTitle != "No Track Loaded") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            if (hasPreviousTrack) {
                                                exoPlayer.seekToPreviousMediaItem()
                                            }
                                        },
                                        enabled = hasPreviousTrack,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Text("⏮", fontSize = 18.sp)
                                    }

                                    Button(
                                        onClick = {
                                            if (isPlaying) {
                                                exoPlayer.pause()
                                            } else {
                                                exoPlayer.play()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            contentColor = MaterialTheme.colorScheme.primaryContainer
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        Text(
                                            text = if (isPlaying) "⏸ Pause" else "▶ Play",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            if (hasNextTrack) {
                                                exoPlayer.seekToNextMediaItem()
                                            }
                                        },
                                        enabled = hasNextTrack,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    ) {
                                        Text("⏭", fontSize = 18.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YouTubeSignInWebView(
    onCookieExtracted: (String) -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url != null && (url.contains("music.youtube.com") || url.contains("youtube.com"))) {
                                val cookieManager = CookieManager.getInstance()
                                val cookies = cookieManager.getCookie("https://music.youtube.com")
                                if (cookies != null && cookies.contains("SAPISID")) {
                                    onCookieExtracted(cookies)
                                }
                            }
                        }
                    }
                    loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https://music.youtube.com/")
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Button(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000))
        ) {
            Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
