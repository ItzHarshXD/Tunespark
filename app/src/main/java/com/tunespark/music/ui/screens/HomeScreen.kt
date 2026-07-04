package com.tunespark.music.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var weatherInfo by remember { mutableStateOf<WeatherInfo?>(null) }
    var isWeatherLoading by remember { mutableStateOf(false) }
    var weatherError by remember { mutableStateOf<String?>(null) }
    var timeString by remember { mutableStateOf("12:00") }

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

    LaunchedEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences(
            "tunespark_location_prefs",
            Context.MODE_PRIVATE
        )
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
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 12.dp)
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

                    IconButton(
                        onClick = { onNavigate(AppScreen.SETTINGS) },
                        modifier = Modifier
                            .size(44.dp)
                            .border(1.dp, textColor.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = textColor,
                            modifier = Modifier.size(22.dp)
                        )
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

                Spacer(modifier = Modifier.height(24.dp))

                if (isTrackLoaded) {
                    PlayingContent(
                        currentSongTitle = currentSongTitle,
                        currentSongArtist = currentSongArtist,
                        onPlayPauseToggle = onPlayPauseToggle,
                        isPlaying = isPlaying,
                        primaryColor = primaryColor,
                        onPrimaryColor = onPrimaryColor
                    )
                } else {
                    IdleContent(
                        textColor = textColor,
                        primaryColor = primaryColor,
                        onPrimaryColor = onPrimaryColor,
                        onStartRadio = {
                            onNavigate(AppScreen.RADIO)
                            onShufflePlay()
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

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
    onStartRadio: () -> Unit
) {
    val tags = listOf("Chill", "Feel good", "Commute", "Party")

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Good Evening",
            fontSize = 40.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )

        Text(
            text = "Harsh",
            fontSize = 21.sp,
            color = textColor.copy(alpha = 0.85f),
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(tags) { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            1.dp,
                            textColor.copy(alpha = 0.7f),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = tag,
                        color = textColor,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(30.dp))
                .border(1.5.dp, textColor.copy(alpha = 0.8f), RoundedCornerShape(30.dp))
                .clickable { onStartRadio() }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(primaryColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start",
                    tint = onPrimaryColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "Start Radio",
                color = textColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.width(46.dp))
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