package com.tunespark.music.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    // Dynamic Clock State
    var timeString by remember { mutableStateOf("12:00") }

    LaunchedEffect(Unit) {
        // Fetch current system time dynamically
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
        val sharedPrefs = context.getSharedPreferences("tunespark_location_prefs", Context.MODE_PRIVATE)
        val locationDisplay = sharedPrefs.getString("location_display", "San Francisco, CA (37.7749, -122.4194)") ?: "San Francisco, CA (37.7749, -122.4194)"
        
        isWeatherLoading = true
        weatherError = null
        try {
            val info = withContext(Dispatchers.IO) {
                WeatherService.fetchWeather(locationDisplay)
            }
            if (info != null) {
                weatherInfo = info
            } else {
                weatherError = "Failed to fetch weather data"
            }
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Tunespark",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textColor
            )
            IconButton(
                onClick = { onNavigate(AppScreen.SETTINGS) },
                modifier = Modifier
                    .size(40.dp)
                    .border(1.dp, textColor, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Settings, // Settings gear icon
                    contentDescription = "Settings",
                    tint = textColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Center Content Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Upper middle: Large Clock and Localized Weather Info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = timeString,
                    fontSize = 84.sp,
                    fontWeight = FontWeight.W900,
                    color = textColor,
                    fontFamily = FontFamily.SansSerif,
                    textAlign = TextAlign.Center
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = weatherInfo?.emoji ?: "☁️",
                        fontSize = 38.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column {
                        Text(
                            text = "${weatherInfo?.temperature?.toInt() ?: 35}°C",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Text(
                            text = weatherInfo?.description ?: "Cloudy",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            // Middle section depends on play state
            if (currentSongTitle != "No Track Loaded") {
                // SONG IS PLAYING (IMAGE 1 STATE)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Circular Play/Pause Toggle Button
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(primaryColor)
                            .clickable { onPlayPauseToggle() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isPlaying) "⏸" else "▶",
                            fontSize = 32.sp,
                            color = onPrimaryColor
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Stunning Waveform Visualizer
                    EqualizerWaveform()

                    Spacer(modifier = Modifier.height(24.dp))

                    // Stop Radio pill button
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
            } else {
                // SONG IS NOT PLAYING (IMAGE 2 STATE)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Good Evening",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        text = "Harsh",
                        fontSize = 22.sp,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    // Horizontal tags scroll/row
                    val tags = listOf("Chill", "Feel good", "Commute", "Party")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    ) {
                        items(tags) { tag ->
                            Box(
                                modifier = Modifier
                                    .border(1.dp, textColor, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 16.getDpOrPx(), vertical = 8.getDpOrPx())
                            ) {
                                Text(text = tag, color = textColor, fontSize = 14.sp)
                            }
                        }
                    }

                    // Start Radio pill button with SkipNext/Start icon in left black circle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .border(1.5.dp, textColor, RoundedCornerShape(28.dp))
                            .clickable {
                                onNavigate(AppScreen.RADIO)
                                onShufflePlay()
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(48.dp)
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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.width(48.dp)) // symmetry balancing
                    }
                }
            }
        }

        // Bottom Bars depend on play state
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .fillMaxWidth()
        ) {
            if (currentSongTitle != "No Track Loaded") {
                // Bottom bar when playing (IMAGE 1 Bottom)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Search Circle Button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(primaryColor)
                            .clickable { onNavigate(AppScreen.SEARCH) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = onPrimaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Compact Mini-Player Card
                    Row(
                        modifier = Modifier
                            .height(56.dp)
                            .weight(1f)
                            .padding(horizontal = 12.dp)
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
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentSongArtist,
                                color = onPrimaryColor.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Playlist Circle Button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(primaryColor)
                            .clickable { onNavigate(AppScreen.PLAYLISTS) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Playlist",
                            tint = onPrimaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else {
                // Bottom bar when NOT playing (IMAGE 2 Bottom)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Search Pill Button
                    Row(
                        modifier = Modifier
                            .height(56.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .background(primaryColor)
                            .clickable { onNavigate(AppScreen.SEARCH) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = onPrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Search",
                            color = onPrimaryColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Playlist Pill Button
                    Row(
                        modifier = Modifier
                            .height(56.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .background(primaryColor)
                            .clickable { onNavigate(AppScreen.PLAYLISTS) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Playlist",
                            tint = onPrimaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Playlist",
                            color = onPrimaryColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Utility extensions
private fun Int.getDpOrPx(): androidx.compose.ui.unit.Dp = this.dp

@Composable
fun EqualizerWaveform() {
    val dotHeights = listOf(
        1, 1, 2, 1, 3, 1, 4, 1, 5, 1, 3, 1, 2, 1, 2, 1, 2, 1, 5, 1, 4, 1, 2, 1, 3, 1, 2, 1, 1
    )
    val primaryColor = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(vertical = 16.dp)
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
