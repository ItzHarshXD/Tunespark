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
import com.metrolist.lrclib.LrcLib
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext

data class LyricLine(val timestampMs: Long, val text: String)

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d.%02d", minutes, seconds)
}

fun cleanYouTubeTitle(title: String): String {
    val cleanupPatterns = listOf(
        Regex("""\s*\(.*?((?i)official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)"""),
        Regex("""\s*\[.*?((?i)official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]"""),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*((?i)official|video|audio|lyrics|lyric|visualizer).*$"""),
        Regex("""\s*\((?i)feat\..*?\)"""),
        Regex("""\s*\((?i)ft\..*?\)"""),
        Regex("""\s*(?i)feat\..*$"""),
        Regex("""\s*(?i)ft\..*$""")
    )
    var cleaned = title.trim()
    for (pattern in cleanupPatterns) {
        cleaned = cleaned.replace(pattern, "")
    }
    return cleaned.trim()
}

fun parseLyricsToLines(rawLyrics: String): List<LyricLine> {
    val lines = rawLyrics.lines()
    val cleanLines = mutableListOf<LyricLine>()
    val timestampRegex = Regex("""^\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?]""")
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
            trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
            trimmed.startsWith("[length:") || trimmed.startsWith("[re:") ||
            trimmed.startsWith("[ve:")) {
            continue
        }
        val matched = timestampRegex.find(trimmed)
        if (matched != null) {
            val min = matched.groupValues[1].toLongOrNull() ?: 0L
            val sec = matched.groupValues[2].toLongOrNull() ?: 0L
            val hundredthsOrMs = matched.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
            val ms = when (hundredthsOrMs?.length) {
                2 -> hundredthsOrMs.toLong() * 10
                3 -> hundredthsOrMs.toLong()
                else -> 0L
            }
            val timestampMs = min * 60 * 1000 + sec * 1000 + ms
            val lyricPart = trimmed.substring(matched.range.last + 1).trim()
            if (lyricPart.isNotEmpty()) {
                cleanLines.add(LyricLine(timestampMs, lyricPart))
            }
        } else {
            cleanLines.add(LyricLine(-1L, trimmed))
        }
    }
    return cleanLines
}

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
    lyricsLines: List<LyricLine>,
    isLyricsLoading: Boolean,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigate(AppScreen.HOME)
    }

    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    LaunchedEffect(exoPlayer, currentTrackIndex) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            val d = exoPlayer.duration
            duration = if (d == androidx.media3.common.C.TIME_UNSET || d < 0) 0L else d
            delay(250)
        }
    }

    val activeLyricIndex = remember(lyricsLines, currentPosition) {
        val syncedLines = lyricsLines.filter { it.timestampMs >= 0 }
        if (syncedLines.isEmpty()) {
            -1
        } else {
            var bestIndex = -1
            for (i in lyricsLines.indices) {
                val line = lyricsLines[i]
                if (line.timestampMs in 0..currentPosition) {
                    bestIndex = i
                }
            }
            bestIndex
        }
    }

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
            .padding(horizontal = 24.dp, vertical = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // FIX: Changed Arrangement.SpaceBetween → Arrangement.Top
            // SpaceBetween was distributing extra space between every child (including Spacers),
            // creating large unpredictable gaps — especially visible below the top bar.
            // Explicit Spacers below now own all the vertical rhythm.
            verticalArrangement = Arrangement.Top
        ) {
            // 1. Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play/Pause Button Capsule (Left Half of split capsule)
                    Box(
                        modifier = Modifier
                            .height(56.dp)
                            .width(80.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 28.dp,
                                    bottomStart = 28.dp,
                                    topEnd = 8.dp,
                                    bottomEnd = 8.dp
                                )
                            )
                            .background(primaryColor)
                            .clickable {
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    exoPlayer.play()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = onPrimaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Skip Next Button Capsule (Right Half of split capsule)
                    Box(
                        modifier = Modifier
                            .height(56.dp)
                            .width(80.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 8.dp,
                                    bottomStart = 8.dp,
                                    topEnd = 28.dp,
                                    bottomEnd = 28.dp
                                )
                            )
                            .background(primaryColor)
                            .clickable {
                                if (exoPlayer.hasNextMediaItem()) {
                                    exoPlayer.seekToNext()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Skip Next",
                            tint = onPrimaryColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3B30))
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

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Dot-Matrix Sound Visualizer
            RadioEqualizerWaveform(exoPlayer = exoPlayer, isPlaying = isPlaying)

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Current Song Details Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = currentSongTitle,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
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

            Spacer(modifier = Modifier.height(16.dp))

            // 3.5. Music Progress Slider and Times
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Slider(
                    value = currentPosition.toFloat().coerceIn(0f, if (duration > 0) duration.toFloat() else 1f),
                    onValueChange = { newValue ->
                        currentPosition = newValue.toLong()
                        exoPlayer.seekTo(currentPosition)
                    },
                    valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                    colors = SliderDefaults.colors(
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = secondaryColor,
                        thumbColor = primaryColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDuration(currentPosition),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatDuration(duration),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Tab Selector
            var activeTab by remember { mutableStateOf("lyrics") }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (activeTab == "lyrics") primaryColor else secondaryColor)
                        .clickable { activeTab = "lyrics" }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Lyrics",
                        color = if (activeTab == "lyrics") onPrimaryColor else onSecondaryColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (activeTab == "queue") primaryColor else secondaryColor)
                        .clickable { activeTab = "queue" }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Up Next",
                        color = if (activeTab == "queue") onPrimaryColor else onSecondaryColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 5. Content Area (Lyrics or Queue)
            if (activeTab == "lyrics") {
                if (isLyricsLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = primaryColor)
                    }
                } else {
                    val listState = rememberLazyListState()

                    LaunchedEffect(activeLyricIndex) {
                        if (activeLyricIndex >= 0 && activeLyricIndex < lyricsLines.size) {
                            listState.animateScrollToItem(activeLyricIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(lyricsLines) { index, line ->
                            val isPlaceholder = lyricsLines.size == 1 && (line.text.startsWith("No lyrics") || line.text.startsWith("Could not") || line.text.startsWith("No track"))
                            val isActive = index == activeLyricIndex

                            val lineTextColor = when {
                                isPlaceholder -> textColor.copy(alpha = 0.5f)
                                isActive -> textColor
                                activeLyricIndex == -1 -> {
                                    when {
                                        index < 2 -> textColor
                                        index in 2..4 -> textColor.copy(alpha = 0.8f)
                                        index in 5..7 -> textColor.copy(alpha = 0.6f)
                                        index in 8..10 -> textColor.copy(alpha = 0.4f)
                                        else -> textColor.copy(alpha = 0.3f)
                                    }
                                }
                                else -> {
                                    val distance = kotlin.math.abs(index - activeLyricIndex)
                                    when {
                                        distance == 1 -> textColor.copy(alpha = 0.7f)
                                        distance == 2 -> textColor.copy(alpha = 0.5f)
                                        distance == 3 -> textColor.copy(alpha = 0.35f)
                                        else -> textColor.copy(alpha = 0.2f)
                                    }
                                }
                            }
                            val fontWeight = if (isActive || (activeLyricIndex == -1 && index < 5 && !isPlaceholder)) FontWeight.Medium else FontWeight.Normal
                            val fontSize = if (isActive || (activeLyricIndex == -1 && index < 5 && !isPlaceholder)) 22.sp else 18.sp

                            Text(
                                text = line.text,
                                color = lineTextColor,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                lineHeight = 28.sp,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = if (isPlaceholder) TextAlign.Center else TextAlign.Start
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp),
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

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isCommentary) Color(0xFF5856D6) else textColor,
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

        }
    }
}

@Composable
fun RadioEqualizerWaveform(exoPlayer: Player, isPlaying: Boolean) {
    val baseHeights = listOf(
        1, 1, 2, 3, 4, 3, 2, 1, 1, 1, 4, 5, 4, 1, 1, 2, 3, 2, 1, 1, 1
    )

    var currentPosition by remember { mutableStateOf(0L) }

    if (isPlaying) {
        LaunchedEffect(Unit) {
            while (true) {
                currentPosition = exoPlayer.currentPosition
                delay(25)
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
        modifier = Modifier
            .height(80.dp)
            .padding(vertical = 12.dp)
    ) {
        baseHeights.forEachIndexed { index, baseHeight ->
            val height = if (isPlaying && currentPosition > 0) {
                val wave = when {
                    index < 7 -> {
                        val bassPeriod = 500.0
                        val phase = (currentPosition % bassPeriod) / bassPeriod
                        val beatAttack = if (phase < 0.15) phase / 0.15 else 1.0 - ((phase - 0.15) / 0.85)
                        val extraNoise = kotlin.math.sin(currentPosition / 50.0 + index) * 0.5
                        baseHeight + (beatAttack * 3.5 + extraNoise).toInt()
                    }
                    index in 7..14 -> {
                        val midWave1 = kotlin.math.sin(currentPosition / 80.0 + index * 0.5) * 1.5
                        val midWave2 = kotlin.math.cos(currentPosition / 150.0 - index * 0.3) * 1.0
                        baseHeight + (midWave1 + midWave2).toInt()
                    }
                    else -> {
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