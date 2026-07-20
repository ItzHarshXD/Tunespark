package com.tunespark.music.ui.screens

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tunespark.music.AppScreen
import com.tunespark.music.WeatherInfo
import com.tunespark.music.WeatherService
import com.tunespark.music.ui.theme.BitcountSingleFontFamily
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Sensors
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.media.AudioManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.tunespark.music.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

data class CommunityPlaylistData(
    val playlist: PlaylistItem,
    val songs: List<SongItem>
)

fun calculateRelevanceScore(
    playlist: PlaylistItem,
    songs: List<SongItem>,
    favoriteArtists: Set<String>,
    recentSongIds: Set<String>
): Double {
    var score = 0.0
    if (songs.isEmpty()) return score

    // 1. Exact song match from recent/history - highly personalized!
    val playlistSongIds = songs.map { it.id }.toSet()
    val songOverlap = playlistSongIds.intersect(recentSongIds).size
    score += songOverlap * 100.0 // Massive weight (100 points per song overlap) to playlists containing actual listened songs

    // 2. Artist relevance based on listening history
    // Count the total number of songs in this playlist that are by the user's favorite artists
    var artistMatchCount = 0
    songs.forEach { song ->
        val matches = song.artists.any { artist ->
            favoriteArtists.contains(artist.name.lowercase().trim())
        }
        if (matches) {
            artistMatchCount++
        }
    }
    score += artistMatchCount * 15.0 // 15 points per song by a favorite artist

    // 3. Favorite artist name matches in playlist title
    val lowerTitle = playlist.title.lowercase()
    var titleArtistMatchCount = 0
    favoriteArtists.forEach { artist ->
        if (artist.isNotBlank() && lowerTitle.contains(artist)) {
            titleArtistMatchCount++
        }
    }
    score += titleArtistMatchCount * 50.0 // 50 points per artist match in the playlist title

    // 4. User-made community curators / collaboration popularity boost
    val authorName = playlist.author?.name?.lowercase() ?: ""
    val isUserMade = authorName.isNotEmpty() &&
            !authorName.contains("official") &&
            !authorName.contains("vevo") &&
            !authorName.contains("records") &&
            !authorName.contains("topic") &&
            !playlist.title.lowercase().contains("official")

    if (isUserMade) {
        score += 20.0 // Boost community curators
    }

    // Curated keyword boost
    val isCuratedKeyword = playlist.title.lowercase().let {
        it.contains("favorites") || it.contains("my") || it.contains("mine") ||
                it.contains("best of") || it.contains("vibes") || it.contains("study") ||
                it.contains("workout") || it.contains("relax") || it.contains("chill")
    }
    if (isCuratedKeyword) {
        score += 10.0
    }

    // 5. Slightly popular factor among similar listeners
    // If songCountText contains view / play counts, extract the value as a tie-breaker
    val popularityText = playlist.songCountText?.lowercase() ?: ""
    var popularityBoost = 0.0
    if (popularityText.contains("view") || popularityText.contains("play") || popularityText.contains("like")) {
        val numberPart = popularityText.split(" ").firstOrNull()?.filter { it.isDigit() || it == '.' || it == 'k' || it == 'm' } ?: ""
        val value = try {
            if (numberPart.endsWith("m")) {
                (numberPart.removeSuffix("m").toDoubleOrNull() ?: 0.0) * 1000.0
            } else if (numberPart.endsWith("k")) {
                numberPart.removeSuffix("k").toDoubleOrNull() ?: 0.0
            } else {
                (numberPart.toDoubleOrNull() ?: 0.0) / 1000.0
            }
        } catch (e: Exception) {
            0.0
        }
        popularityBoost = Math.min(value / 100.0, 10.0) // Log-like smaller boost so it does not override personal history matches
    }
    score += popularityBoost

    // 6. Stable collaborative factor
    val idHash = playlist.id.hashCode()
    val collaborativeFactor = (Math.abs(idHash) % 100) / 20.0 // 0.0 to 5.0
    score += collaborativeFactor

    return score
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    currentSongTitle: String,
    currentSongArtist: String,
    currentSongArtwork: String?,
    isPlaying: Boolean,
    onPlayPauseToggle: () -> Unit,
    isShuffling: Boolean,
    onNavigate: (AppScreen) -> Unit,
    onShufflePlay: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit,
    onPlaylistClick: (CommunityPlaylistData) -> Unit,
    onRefresh: () -> Unit,
    
    // Hoisted HomeScreen states passed from MainActivity.kt
    speedDialSongs: List<SongItem>,
    isSpeedDialLoading: Boolean,
    communityPlaylists: List<CommunityPlaylistData>,
    isCommunityLoading: Boolean,
    recentsSongs: List<SongItem>,
    isRecentsLoading: Boolean,
    dailyDiscoverSongs: List<SongItem>,
    isDailyDiscoverLoading: Boolean,
    
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    var weatherInfo by remember { mutableStateOf<WeatherInfo?>(null) }
    var isWeatherLoading by remember { mutableStateOf(false) }
    var weatherError by remember { mutableStateOf<String?>(null) }
    var timeString by remember { mutableStateOf("12:00") }

    var isRefreshing by remember { mutableStateOf(false) }
    var weatherRefreshTrigger by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    val handleRefresh = {
        isRefreshing = true
        coroutineScope.launch {
            onRefresh()
            weatherRefreshTrigger++
            delay(1500)
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val cal = java.util.Calendar.getInstance()
            val hourVal = cal.get(java.util.Calendar.HOUR)
            val hour = if (hourVal == 0) 12 else hourVal
            val minute = String.format("%02d", cal.get(java.util.Calendar.MINUTE))
            timeString = "$hour:$minute"
            delay(1000)
        }
    }

    val sharedPrefs = remember {
        context.getSharedPreferences(
            "tunespark_location_prefs",
            Context.MODE_PRIVATE
        )
    }
    val locationEnabled = sharedPrefs.getBoolean("location_enabled", false)

    LaunchedEffect(weatherRefreshTrigger) {
        if (!locationEnabled) {
            weatherInfo = null
            return@LaunchedEffect
        }
        val locationDisplay = sharedPrefs.getString(
            "location_display",
            "San Francisco, CA (37.7749, -122.4194)"
        ) ?: "San Francisco, CA (37.7749, -122.4194)"

        isWeatherLoading = true
        weatherError = null
        try {
            val info = withContext(Dispatchers.IO) {
                WeatherService.fetchWeather(locationDisplay)
            }
            weatherInfo = info
            if (info == null) weatherError = "Failed to fetch weather data"
        } catch (e: Exception) {
            weatherError = "Error loading weather"
        } finally {
            isWeatherLoading = false
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val isTrackLoaded = currentSongTitle != "No Track Loaded"

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = WindowInsets(0.dp)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 12.dp)
            ) {
                // Scrollable container for everything except BottomDock
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { handleRefresh() },
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Tunespark",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = BitcountSingleFontFamily,
                                color = textColor
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        playSoundAndHaptic()
                                        onNavigate(AppScreen.ACCOUNT)
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(textColor.copy(alpha = 0.1f), CircleShape)
                                        .clip(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = "Account",
                                        tint = textColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        playSoundAndHaptic()
                                        onNavigate(AppScreen.SETTINGS)
                                    },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(textColor.copy(alpha = 0.1f), CircleShape)
                                        .clip(CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings",
                                        tint = textColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Hero block
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = timeString,
                                fontSize = 68.sp,
                                lineHeight = 68.sp,
                                fontWeight = FontWeight.Black,
                                color = textColor,
                                fontFamily = FontFamily.SansSerif,
                                textAlign = TextAlign.Center
                            )

                            if (locationEnabled) {
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = weatherInfo?.emoji ?: "☁️",
                                        fontSize = 30.sp,
                                        modifier = Modifier.padding(end = 10.dp)
                                    )

                                    Column(
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            text = "${weatherInfo?.temperature?.toInt() ?: 35}°C",
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textColor
                                        )
                                        Text(
                                            text = weatherInfo?.description ?: "Cloudy",
                                            fontSize = 13.sp,
                                            color = textColor.copy(alpha = 0.55f)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        IdleContent(
                            textColor = textColor,
                            primaryColor = primaryColor,
                            onPrimaryColor = onPrimaryColor,
                            speedDialSongs = speedDialSongs,
                            isSpeedDialLoading = isSpeedDialLoading,
                            communityPlaylists = communityPlaylists,
                            isCommunityLoading = isCommunityLoading,
                            recentsSongs = recentsSongs,
                            isRecentsLoading = isRecentsLoading,
                            dailyDiscoverSongs = dailyDiscoverSongs,
                            isDailyDiscoverLoading = isDailyDiscoverLoading,
                            onShowAllClick = {
                                playSoundAndHaptic()
                                onNavigate(AppScreen.RECENTS)
                            },
                            onPlaySong = { song ->
                                playSoundAndHaptic()
                                onPlaySong(song)
                                onNavigate(AppScreen.RADIO)
                            },
                            onStartRadio = {
                                playSoundAndHaptic()
                                onNavigate(AppScreen.RADIO)
                                onShufflePlay()
                            },
                            onPlayPlaylist = { name, songs, index ->
                                playSoundAndHaptic()
                                onPlayPlaylist(name, songs, index)
                                onNavigate(AppScreen.RADIO)
                            },
                            onPlaylistClick = { data ->
                                playSoundAndHaptic()
                                onPlaylistClick(data)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                BottomDock(
                    isTrackLoaded = isTrackLoaded,
                    currentSongTitle = currentSongTitle,
                    currentSongArtist = currentSongArtist,
                    currentSongArtwork = currentSongArtwork,
                    primaryColor = primaryColor,
                    onPrimaryColor = onPrimaryColor,
                    onNavigate = onNavigate
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    textColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    speedDialSongs: List<SongItem>,
    isSpeedDialLoading: Boolean,
    communityPlaylists: List<CommunityPlaylistData>,
    isCommunityLoading: Boolean,
    recentsSongs: List<SongItem>,
    isRecentsLoading: Boolean,
    dailyDiscoverSongs: List<SongItem>,
    isDailyDiscoverLoading: Boolean,
    onShowAllClick: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onStartRadio: () -> Unit,
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit,
    onPlaylistClick: (CommunityPlaylistData) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        RecentsView(
            songs = recentsSongs,
            isLoading = isRecentsLoading,
            textColor = textColor,
            primaryColor = primaryColor,
            onPlaySong = onPlaySong,
            onShowAllClick = onShowAllClick
        )

        Spacer(modifier = Modifier.height(28.dp))

        DailyDiscoverView(
            songs = dailyDiscoverSongs,
            isLoading = isDailyDiscoverLoading,
            textColor = textColor,
            primaryColor = primaryColor,
            onPlayPlaylist = onPlayPlaylist
        )

        Spacer(modifier = Modifier.height(28.dp))

        SpeedDialView(
            songs = speedDialSongs,
            isLoading = isSpeedDialLoading,
            textColor = textColor,
            primaryColor = primaryColor,
            onPlaySong = onPlaySong
        )

        Spacer(modifier = Modifier.height(28.dp))

        CommunityPlaylistsView(
            playlists = communityPlaylists,
            isLoading = isCommunityLoading,
            textColor = textColor,
            primaryColor = primaryColor,
            onPlayPlaylist = onPlayPlaylist,
            onPlaySong = onPlaySong,
            onPlaylistClick = onPlaylistClick
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SpeedDialView(
    songs: List<SongItem>,
    isLoading: Boolean,
    textColor: Color,
    primaryColor: Color,
    onPlaySong: (SongItem) -> Unit
) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Speed dial",
            color = primaryColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (isLoading || songs.isEmpty()) {
            SpeedDialSkeleton()
        } else {
            val pages = remember(songs) { songs.chunked(9) }
            val pagerState = rememberPagerState(pageCount = { pages.size })

            HorizontalPager(
                state = pagerState,
                pageSpacing = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
            ) { pageIndex ->
                val pageSongs = pages[pageIndex]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (rowIndex in 0..2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (colIndex in 0..2) {
                                val songIndex = rowIndex * 3 + colIndex
                                if (songIndex < pageSongs.size) {
                                    val song = pageSongs[songIndex]
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                playSoundAndHaptic()
                                                onPlaySong(song)
                                            }
                                    ) {
                                        AsyncImage(
                                            model = song.thumbnail,
                                            contentDescription = song.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )

                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                                        startY = 50f
                                                    )
                                                )
                                        )

                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(8.dp)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                                }
                            }
                        }
                    }
                }
            }

            // Pager Dots Indicator
            if (pages.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pages.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (isSelected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) primaryColor else textColor.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedDialSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Gray.copy(alpha = alpha))
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayingContent(
    currentSongTitle: String,
    currentSongArtist: String,
    onPlayPauseToggle: () -> Unit,
    isPlaying: Boolean,
    primaryColor: Color,
    onPrimaryColor: Color
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(primaryColor)
                .clickable { onPlayPauseToggle() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPlaying) "⏸" else "▶",
                fontSize = 30.sp,
                color = onPrimaryColor
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        EqualizerWaveform()
        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { onPlayPauseToggle() },
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryColor,
                contentColor = onPrimaryColor
            ),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Stop Radio", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BottomDock(
    isTrackLoaded: Boolean,
    currentSongTitle: String,
    currentSongArtist: String,
    currentSongArtwork: String?,
    primaryColor: Color,
    onPrimaryColor: Color,
    onNavigate: (AppScreen) -> Unit
) {
    if (isTrackLoaded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularDockButton(
                icon = Icons.Default.Search,
                contentDescription = "Search",
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor
            ) { onNavigate(AppScreen.SEARCH) }

            Row(
                modifier = Modifier
                    .height(56.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(primaryColor)
                    .clickable { onNavigate(AppScreen.RADIO) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!currentSongArtwork.isNullOrEmpty()) {
                    AsyncImage(
                        model = currentSongArtwork,
                        contentDescription = "Artwork",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🎵", fontSize = 18.sp)
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentSongTitle,
                        color = onPrimaryColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSongArtist,
                        color = onPrimaryColor.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            CircularDockButton(
                icon = Icons.Default.List,
                contentDescription = "Playlist",
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor
            ) { onNavigate(AppScreen.PLAYLISTS) }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BottomActionButton(
                label = "Search",
                icon = Icons.Default.Search,
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor,
                modifier = Modifier.weight(1f)
            ) {
                onNavigate(AppScreen.SEARCH)
            }

            BottomActionButton(
                label = "Playlist",
                icon = Icons.Default.List,
                primaryColor = primaryColor,
                onPrimaryColor = onPrimaryColor,
                modifier = Modifier.weight(1f)
            ) {
                onNavigate(AppScreen.PLAYLISTS)
            }
        }
    }
}

@Composable
private fun BottomActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryColor: Color,
    onPrimaryColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(primaryColor)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = onPrimaryColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = onPrimaryColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CircularDockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    primaryColor: Color,
    onPrimaryColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(primaryColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = onPrimaryColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun EqualizerWaveform() {
    val dotHeights = listOf(
        1, 1, 2, 1, 3, 1, 4, 1, 5, 1, 3, 1, 2, 1, 2, 1, 2, 1, 5, 1, 4, 1, 2, 1, 3, 1, 2, 1, 1
    )
    val primaryColor = MaterialTheme.colorScheme.primary

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(vertical = 12.dp)
    ) {
        dotHeights.forEach { height ->
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(height) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(primaryColor)
                    )
                }
            }
        }
    }
}

@Composable
fun CommunityPlaylistsView(
    playlists: List<CommunityPlaylistData>,
    isLoading: Boolean,
    textColor: Color,
    primaryColor: Color,
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onPlaylistClick: (CommunityPlaylistData) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "From the community",
            color = primaryColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (isLoading || playlists.isEmpty()) {
            CommunityPlaylistSkeleton()
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(end = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(playlists) { data ->
                    CommunityPlaylistCard(
                        data = data,
                        textColor = textColor,
                        primaryColor = primaryColor,
                        onPlayPlaylist = onPlayPlaylist,
                        onPlaySong = onPlaySong,
                        onPlaylistClick = onPlaylistClick
                    )
                }
            }
        }
    }
}

@Composable
fun CommunityPlaylistCard(
    data: CommunityPlaylistData,
    textColor: Color,
    primaryColor: Color,
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onPlaylistClick: (CommunityPlaylistData) -> Unit
) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    val coroutineScope = rememberCoroutineScope()
    val playlist = data.playlist
    val songs = data.songs

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF141414) else Color(0xFFF7F7F7)
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)

    Card(
        modifier = Modifier
            .width(310.dp)
            .border(1.dp, cardBorderColor, RoundedCornerShape(24.dp))
            .clickable {
                playSoundAndHaptic()
                onPlaylistClick(data)
            },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: 2x2 Collage and Title/Subtitle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Collage Artwork Box
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Gray.copy(alpha = 0.1f))
                ) {
                    val collageThumbs = songs.take(4).map { it.thumbnail }
                    if (collageThumbs.size >= 4) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f)) {
                                AsyncImage(
                                    model = collageThumbs[0],
                                    contentDescription = null,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentScale = ContentScale.Crop
                                )
                                AsyncImage(
                                    model = collageThumbs[1],
                                    contentDescription = null,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Row(modifier = Modifier.weight(1f)) {
                                AsyncImage(
                                    model = collageThumbs[2],
                                    contentDescription = null,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentScale = ContentScale.Crop
                                )
                                AsyncImage(
                                    model = collageThumbs[3],
                                    contentDescription = null,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    } else {
                        // Fallback to single thumbnail
                        AsyncImage(
                            model = playlist.thumbnail ?: songs.firstOrNull()?.thumbnail,
                            contentDescription = playlist.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = playlist.title,
                        color = textColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = playlist.songCountText ?: "${songs.size} songs",
                        color = textColor.copy(alpha = 0.55f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playlist songs list (up to 3 tracks)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                songs.take(3).forEach { song ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playSoundAndHaptic()
                                onPlaySong(song)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = song.thumbnail,
                            contentDescription = song.title,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = song.title,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artists.joinToString(", ") { it.name },
                                color = textColor.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Centered Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Play Button
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        onPlayPlaylist(playlist.title, songs, 0)
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Playlist",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Shuffle Button (Shuffle and play)
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        if (songs.isNotEmpty()) {
                            onPlayPlaylist(playlist.title, songs.shuffled(), 0)
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(44.dp)
                        .border(1.dp, textColor.copy(alpha = 0.35f), CircleShape)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Shuffle and Play",
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Save to library Button
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                YouTube.likePlaylist(playlist.id, true)
                            }
                            if (result.isSuccess) {
                                Toast.makeText(context, "Saved '${playlist.title}' to library!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to save playlist to library.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(44.dp)
                        .border(1.dp, textColor.copy(alpha = 0.35f), CircleShape)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlaylistAdd,
                        contentDescription = "Save Playlist to Library",
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CommunityPlaylistSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(2) {
            Card(
                modifier = Modifier
                    .width(310.dp)
                    .border(
                        1.dp,
                        Color.Gray.copy(alpha = 0.15f),
                        RoundedCornerShape(24.dp)
                    ),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.05f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header skeleton
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Gray.copy(alpha = alpha))
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Box(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Gray.copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Gray.copy(alpha = alpha))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3 song rows skeleton
                    repeat(3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Gray.copy(alpha = alpha))
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Box(
                                    modifier = Modifier
                                        .width(140.dp)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Gray.copy(alpha = alpha))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Gray.copy(alpha = alpha))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Controls skeleton
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp)
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.Gray.copy(alpha = alpha))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CommunityPlaylistDetailView(
    data: CommunityPlaylistData,
    onBack: () -> Unit,
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    textColor: Color,
    backgroundColor: Color,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val playlist = data.playlist
    val songs = data.songs

    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = androidx.compose.ui.platform.LocalView.current

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        onBack()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(textColor, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = backgroundColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Playlist View",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Artwork Collage/Single
                        Box(
                            modifier = Modifier
                                .size(180.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(textColor.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val collageThumbs = songs.take(4).map { it.thumbnail }
                            if (collageThumbs.size >= 4) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.weight(1f)) {
                                        AsyncImage(
                                            model = collageThumbs[0],
                                            contentDescription = null,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            contentScale = ContentScale.Crop
                                        )
                                        AsyncImage(
                                            model = collageThumbs[1],
                                            contentDescription = null,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Row(modifier = Modifier.weight(1f)) {
                                        AsyncImage(
                                            model = collageThumbs[2],
                                            contentDescription = null,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            contentScale = ContentScale.Crop
                                        )
                                        AsyncImage(
                                            model = collageThumbs[3],
                                            contentDescription = null,
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            } else {
                                AsyncImage(
                                    model = playlist.thumbnail ?: songs.firstOrNull()?.thumbnail,
                                    contentDescription = playlist.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = playlist.title,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        val authorName = playlist.author?.name ?: "Community Curator"
                        Text(
                            text = "By $authorName • ${songs.size} songs",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                        )

                        // Action Buttons (Play / Shuffle)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Button(
                                onClick = {
                                    playSoundAndHaptic()
                                    if (songs.isNotEmpty()) {
                                        onPlayPlaylist(playlist.title, songs, 0)
                                        onNavigate(AppScreen.RADIO)
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = textColor,
                                    contentColor = backgroundColor
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Play", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    playSoundAndHaptic()
                                    if (songs.isNotEmpty()) {
                                        val shuffled = songs.shuffled()
                                        onPlayPlaylist(playlist.title, shuffled, 0)
                                        onNavigate(AppScreen.RADIO)
                                    }
                                },
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Gray.copy(alpha = 0.2f),
                                    contentColor = textColor
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Shuffle", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Shuffle", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                itemsIndexed(songs) { index, song ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                playSoundAndHaptic()
                                onPlayPlaylist(playlist.title, songs, index)
                                onNavigate(AppScreen.RADIO)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Gray.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!song.thumbnail.isNullOrEmpty()) {
                                AsyncImage(
                                    model = song.thumbnail,
                                    contentDescription = song.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("🎵", fontSize = 18.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artists.joinToString(", ") { it.name },
                                fontSize = 13.sp,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DailyDiscoverView(
    songs: List<SongItem>,
    isLoading: Boolean,
    textColor: Color,
    primaryColor: Color,
    onPlayPlaylist: (String, List<SongItem>, Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Your daily discover",
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            if (songs.isNotEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(textColor)
                        .clickable { onPlayPlaylist("Daily Discover", songs, 0) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Play all",
                        color = MaterialTheme.colorScheme.background,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isLoading || songs.isEmpty()) {
            DailyDiscoverSkeleton()
        } else {
            val pagerState = rememberPagerState(pageCount = { songs.size })

            // Auto-scroll every 10 seconds
            LaunchedEffect(songs) {
                if (songs.isNotEmpty()) {
                    while (true) {
                        delay(10000)
                        val nextPage = (pagerState.currentPage + 1) % songs.size
                        pagerState.animateScrollToPage(
                            page = nextPage,
                            animationSpec = tween(durationMillis = 2000, easing = LinearOutSlowInEasing)
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(end = 48.dp),
                pageSpacing = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) { index ->
                val song = songs[index]
                val isSnapped = pagerState.currentPage == index

                // Very very slow, subtle zoom micro-animation (1.05f max scale, 4000ms animation duration)
                val zoomScale by animateFloatAsState(
                    targetValue = if (isSnapped) 1.05f else 1.00f,
                    animationSpec = tween(durationMillis = 4000, easing = LinearOutSlowInEasing),
                    label = "zoomScale"
                )

                DailyDiscoverCard(
                    song = song,
                    textColor = textColor,
                    primaryColor = primaryColor,
                    scale = zoomScale,
                    onClick = { onPlayPlaylist("Daily Discover", songs, index) }
                )
            }
        }
    }
}

private fun String.toHighResThumbnail(): String {
    return when {
        contains("googleusercontent.com") -> {
            var url = this
            val wHRegex = "=[ws]\\d+-h\\d+.*".toRegex()
            if (url.contains(wHRegex)) {
                url = url.replace(wHRegex, "=w544-h544")
            } else {
                val sRegex = "=s\\d+.*".toRegex()
                if (url.contains(sRegex)) {
                    url = url.replace(sRegex, "=w544-h544")
                }
            }
            url
        }
        contains("ytimg.com") -> {
            if (contains("/default.jpg")) {
                replace("/default.jpg", "/hqdefault.jpg")
            } else {
                this
            }
        }
        else -> this
    }
}

@Composable
fun DailyDiscoverCard(
    song: SongItem,
    textColor: Color,
    primaryColor: Color,
    scale: Float = 1.0f,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = song.thumbnail?.toHighResThumbnail(),
            contentDescription = song.title,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black.copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        endY = 300f
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = song.artists.joinToString(", ") { it.name },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun DailyDiscoverSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .width(260.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Gray.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun RecentsView(
    songs: List<SongItem>,
    isLoading: Boolean,
    textColor: Color,
    primaryColor: Color,
    onPlaySong: (SongItem) -> Unit,
    onShowAllClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recents",
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(textColor)
                    .clickable { onShowAllClick() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Show all",
                    color = MaterialTheme.colorScheme.background,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (isLoading) {
            RecentsSkeleton()
        } else if (songs.isEmpty()) {
            Text(
                text = "No recently listened songs.",
                color = textColor.copy(alpha = 0.4f),
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(end = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(songs.take(10)) { song ->
                    RecentsCard(
                        song = song,
                        textColor = textColor,
                        onPlaySong = onPlaySong
                    )
                }
            }
        }
    }
}

@Composable
fun RecentsCard(
    song: SongItem,
    textColor: Color,
    onPlaySong: (SongItem) -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onPlaySong(song) }
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = song.title,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = song.title,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "Song • ${song.artists.joinToString(", ") { it.name }}",
            color = textColor.copy(alpha = 0.55f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RecentsSkeleton() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        repeat(3) {
            Column(modifier = Modifier.width(120.dp)) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Gray.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Gray.copy(alpha = alpha))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Gray.copy(alpha = alpha))
                )
            }
        }
    }
}

@Composable
fun RecentsHistoryDetailView(
    songs: List<SongItem>,
    onBack: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    textColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = androidx.compose.ui.platform.LocalView.current

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        onBack()
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(textColor, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = backgroundColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Listening History",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }

            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No listening history found.",
                        color = textColor.copy(alpha = 0.55f),
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(songs) { index, song ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playSoundAndHaptic()
                                    onPlaySong(song)
                                }
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.Gray.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!song.thumbnail.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = song.thumbnail,
                                        contentDescription = song.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text("🎵", fontSize = 24.sp)
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Song • ${song.artists.joinToString(", ") { it.name }}",
                                    fontSize = 13.sp,
                                    color = textColor.copy(alpha = 0.55f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}