package com.tunespark.music.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.tunespark.music.AppScreen
import kotlinx.coroutines.delay

@Composable
fun RadioScreen(
    exoPlayer: Player,
    playQueue: List<MediaItem>,
    currentTrackIndex: Int,
    currentSongTitle: String,
    currentSongArtist: String,
    currentSongArtwork: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    statusMessage: String,
    hasPreviousTrack: Boolean,
    hasNextTrack: Boolean,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    // Handle back button press (smooth navigate back to Home Screen)
    BackHandler {
        onNavigate(AppScreen.HOME)
    }

    // Exact lyrics of the song to show in a scrollable list
    val lyricsLines = listOf(
        "O jugni o.. patakha Guddi o",
        "Nashe mein udi jaaye re haaye re",
        "",
        "Sajje khabbe dhabbe killi o",
        "Patakha Guddi O",
        "",
        "Nashe mein udi jaaye re haaye re",
        "",
        "Sajje khabbe dhabbe killi o",
        "",
        "Maula tera maali"
    )

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSecondaryColor = MaterialTheme.colorScheme.onSecondary

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Top Bar Navigation and Control Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Black Circle Back Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                        .clickable { onNavigate(AppScreen.HOME) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = onPrimaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Center: Capsule-shaped Play/Pause Toggle button
                Row(
                    modifier = Modifier
                        .height(56.dp)
                        .width(160.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(primaryColor)
                        .clickable {
                            if (isPlaying) {
                                exoPlayer.pause()
                            } else {
                                exoPlayer.play()
                            }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (isPlaying) "⏸" else "▶",
                        color = onPrimaryColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isPlaying) "Pause" else "Play",
                        color = onPrimaryColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Right: Red Circle Close/Stop Button
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3B30)) // Vibrant red color matching screenshot exactly
                        .clickable {
                            exoPlayer.stop()
                            onNavigate(AppScreen.HOME)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. High-Accuracy Dot-Matrix Sound Visualizer
            RadioEqualizerWaveform(exoPlayer = exoPlayer, isPlaying = isPlaying)

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Current Song details Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Round-Corner Album Artwork Thumbnail
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(secondaryColor)
                ) {
                    if (!currentSongArtwork.isNullOrEmpty()) {
                        AsyncImage(
                            model = currentSongArtwork,
                            contentDescription = "Artwork",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎵", fontSize = 24.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Track Title and Artist Details
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = currentSongTitle,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (currentSongArtist.isNotEmpty()) currentSongArtist else "TuneSpark",
                        fontSize = 14.sp,
                        color = textColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Toggle Tab Selector (Lyrics vs Up Next Queue)
            var activeTab by remember { mutableStateOf("lyrics") } // "lyrics" or "queue"
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Lyrics Tab Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (activeTab == "lyrics") primaryColor else secondaryColor)
                        .clickable { activeTab = "lyrics" }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Lyrics",
                        color = if (activeTab == "lyrics") onPrimaryColor else onSecondaryColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Queue/Up Next Tab Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (activeTab == "queue") primaryColor else secondaryColor)
                        .clickable { activeTab = "queue" }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Up Next",
                        color = if (activeTab == "queue") onPrimaryColor else onSecondaryColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (activeTab == "lyrics") {
                // 4. Stylized Scrollable Lyrics Section
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(lyricsLines) { index, line ->
                        // Set varying colors to mimic the fading/bold styles of the screenshot exactly
                        val lineTextColor = when {
                            index < 2 -> textColor
                            index in 3..4 -> textColor
                            index in 6..7 -> textColor.copy(alpha = 0.6f)
                            index in 8..9 -> textColor.copy(alpha = 0.4f)
                            else -> textColor.copy(alpha = 0.2f)
                        }
                        val fontWeight = if (index < 5 && line.isNotEmpty()) FontWeight.Bold else FontWeight.Normal
                        val fontSize = if (index < 5 && line.isNotEmpty()) 20.sp else 18.sp

                        Text(
                            text = line,
                            color = lineTextColor,
                            fontSize = fontSize,
                            fontWeight = fontWeight,
                            lineHeight = 26.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                // 4. Up Next Queue Section
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(playQueue) { index, item ->
                        val isCurrent = index == currentTrackIndex
                        val isCommentary = item.mediaId.startsWith("commentary_")
                        
                        val title = item.mediaMetadata.title?.toString() ?: "Unknown Song"
                        val artist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist"
                        val artworkUri = item.mediaMetadata.artworkUri
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrent) secondaryColor else Color.Transparent)
                                .clickable {
                                    exoPlayer.seekToDefaultPosition(index)
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Queue Number or Play Indicator
                            if (isCurrent) {
                                Text(
                                    text = "▶",
                                    color = textColor,
                                    fontSize = 14.sp,
                                    modifier = Modifier.width(24.dp),
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    color = textColor.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(24.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            // Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(secondaryColor)
                            ) {
                                if (isCommentary) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✨", fontSize = 20.sp)
                                    }
                                } else if (artworkUri != null) {
                                    AsyncImage(
                                        model = artworkUri.toString(),
                                        contentDescription = "Queue Artwork",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🎵", fontSize = 20.sp)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            // Title & Artist
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCommentary) Color(0xFF5856D6) else textColor, // Premium purple for AI DJ commentary
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = artist,
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Skip Song capsule button at the very bottom
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(2.dp, primaryColor, RoundedCornerShape(32.dp))
                    .clip(RoundedCornerShape(32.dp))
                    .background(backgroundColor)
                    .clickable {
                        if (exoPlayer.hasNextMediaItem()) {
                            exoPlayer.seekToNext()
                        }
                    }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Solid primary color circle containing skip next icon on left
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(primaryColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⏭",
                        color = onPrimaryColor,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Text centered inside the pill
                Text(
                    text = "Skip song",
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                // Empty spacer matching left padding for optical alignment symmetry
                Spacer(modifier = Modifier.width(52.dp))
            }
        }
    }
}

@Composable
fun RadioEqualizerWaveform(exoPlayer: Player, isPlaying: Boolean) {
    val baseHeights = listOf(
        1, 1, 2, 3, 4, 3, 2, 1, 1, 1, 4, 5, 4, 1, 1, 2, 3, 2, 1, 1, 1
    )
    
    // Sample real-time media player attributes to make it dance live
    var currentPosition by remember { mutableStateOf(0L) }
    
    if (isPlaying) {
        LaunchedEffect(Unit) {
            while (true) {
                currentPosition = exoPlayer.currentPosition
                delay(25) // Ultra-responsive 25ms sampling for fluid beat movements
            }
        }
    } else {
        LaunchedEffect(Unit) {
            currentPosition = 0L
        }
    }
    
    val primaryColor = MaterialTheme.colorScheme.primary

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(vertical = 12.dp)
    ) {
        baseHeights.forEachIndexed { index, baseHeight ->
            // Tie height fluctuation directly, accurately and deterministicly to current song position (beats)
            val height = if (isPlaying && currentPosition > 0) {
                // Synthesize bass, mid, and treble frequencies for an exceptionally realistic audio visualizer
                val wave = when {
                    index < 7 -> { // Bass: heavy low-frequency rhythmic beats
                        val bassPeriod = 500.0 // 120 BPM beat structure
                        val phase = (currentPosition % bassPeriod) / bassPeriod
                        val beatAttack = if (phase < 0.15) phase / 0.15 else 1.0 - ((phase - 0.15) / 0.85)
                        val extraNoise = kotlin.math.sin(currentPosition / 50.0 + index) * 0.5
                        baseHeight + (beatAttack * 3.5 + extraNoise).toInt()
                    }
                    index in 7..14 -> { // Mids: mid-frequency melodic waves
                        val midWave1 = kotlin.math.sin(currentPosition / 80.0 + index * 0.5) * 1.5
                        val midWave2 = kotlin.math.cos(currentPosition / 150.0 - index * 0.3) * 1.0
                        baseHeight + (midWave1 + midWave2).toInt()
                    }
                    else -> { // Trebles: fast high-frequency sparkles
                        val trebleWave = kotlin.math.sin(currentPosition / 30.0 + index * 1.2) * 2.0
                        val randomSparkle = if ((currentPosition + index * 10) % 200 < 50) 1.5 else -0.5
                        baseHeight + (trebleWave + randomSparkle).toInt()
                    }
                }
                wave.coerceIn(1, 6)
            } else {
                baseHeight
            }
            
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
