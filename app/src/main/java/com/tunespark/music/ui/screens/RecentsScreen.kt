package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.HistoryPage
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    onPlaySong: (SongItem) -> Unit,
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = LocalView.current

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    var allSongs by remember { mutableStateOf<List<Pair<SongItem, Long>>>(emptyList()) }
    var visibleSongsCount by remember { mutableStateOf(50) }
    var isLoading by remember { mutableStateOf(true) }

    val paginatedSongs = remember(allSongs, visibleSongsCount) {
        allSongs.take(visibleSongsCount)
    }

    val sections = remember(paginatedSongs) {
        groupLocalHistoryByDate(paginatedSongs)
    }

    BackHandler {
        onNavigate(AppScreen.HOME)
    }

    LaunchedEffect(Unit) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val localSongs = SessionManager.getLocalHistoryWithTimestamps(context)
                withContext(Dispatchers.Main) {
                    allSongs = localSongs
                    isLoading = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = backgroundColor,
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        playSoundAndHaptic()
                        onNavigate(AppScreen.HOME)
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
                    text = "Recents",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = primaryColor)
                }
            } else if (sections.isEmpty()) {
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
                val listState = rememberLazyListState()

                val shouldLoadMore = remember {
                    derivedStateOf {
                        val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        if (lastVisibleItem == null) {
                            false
                        } else {
                            lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 5
                        }
                    }
                }

                LaunchedEffect(shouldLoadMore.value) {
                    if (shouldLoadMore.value && visibleSongsCount < allSongs.size) {
                        visibleSongsCount = minOf(visibleSongsCount + 50, allSongs.size)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    sections.forEach { section ->
                        if (section.songs.isNotEmpty()) {
                            item {
                                Text(
                                    text = section.title,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }

                            items(section.songs) { song ->
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
    }
}

private fun groupLocalHistoryByDate(localHistory: List<Pair<SongItem, Long>>): List<HistoryPage.HistorySection> {
    val today = java.time.LocalDate.now()
    val yesterday = today.minusDays(1)
    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", java.util.Locale.ENGLISH)

    return localHistory.groupBy { pair ->
        val date = java.time.Instant.ofEpochMilli(pair.second)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        when (date) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> formatter.format(date)
        }
    }.map { (title, pairs) ->
        HistoryPage.HistorySection(
            title = title,
            songs = pairs.map { it.first }
        )
    }
}