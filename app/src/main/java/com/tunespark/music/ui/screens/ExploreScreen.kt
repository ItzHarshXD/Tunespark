package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.BrowseResult
import com.tunespark.music.ExploreFeed

/**
 * The **Explore** feed shown on the Search screen whenever there is no active query:
 * a two-column grid of YouTube Music moods / genres. Each tile carries the banner
 * artwork derived from that mood / genre's first section's first playlist, over a
 * per-tile randomly chosen colour, with the name laid over the bottom.
 *
 * Scroll state is owned by the caller ([gridState]) so returning from a mood /
 * genre page — or from a playlist opened on top of it — restores the exact position.
 */
@Composable
fun ExploreGridView(
    entries: List<ExploreFeed.ExploreEntry>,
    isLoading: Boolean,
    gridState: LazyGridState,
    onGenreClick: (ExploreFeed.ExploreEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onBackground

    when {
        isLoading && entries.isEmpty() -> ExploreGridSkeleton(modifier)
        entries.isEmpty() -> ExploreErrorState(onRetry, modifier)
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(bottom = 2.dp)) {
                    Text(
                        text = "Explore",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Moods, genres and curated playlists",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }

            items(entries, key = { it.key }) { entry ->
                ExploreCard(entry = entry, onClick = { onGenreClick(entry) })
            }
        }
    }
}

/** One Explore tile: banner artwork over the tile's colour, with the name overlaid. */
@Composable
private fun ExploreCard(
    entry: ExploreFeed.ExploreEntry,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(entry.color))
            .clickable {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
    ) {
        // Banner artwork — the first section's first playlist of that mood / genre.
        // While it is still being resolved (or when the genre genuinely has none),
        // the tile's random colour shows through.
        if (entry.bannerUrl != null) {
            AsyncImage(
                model = entry.bannerUrl,
                contentDescription = entry.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.30f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.74f)
                    )
                )
        )

        Text(
            text = entry.title,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/** Shimmering placeholder grid shown while the mood / genre list loads. */
@Composable
private fun ExploreGridSkeleton(modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onBackground
    val shimmer = exploreShimmerColor(textColor)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .width(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmer)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(180.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmer)
                )
            }
        }
        items(8) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(shimmer)
            )
        }
    }
}

/** Quiet failure state for the grid / a mood page, with a retry affordance. */
@Composable
private fun ExploreErrorState(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val textColor = MaterialTheme.colorScheme.onBackground
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Couldn't load Explore.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Retry", color = textColor, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Shared pulsing shimmer tint for the Explore skeletons. */
@Composable
private fun exploreShimmerColor(base: Color): Color {
    val transition = rememberInfiniteTransition(label = "explore_shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "explore_shimmer_alpha"
    )
    return base.copy(alpha = alpha)
}

/**
 * A mood / genre page: every section YouTube Music returns for it (Trending now,
 * Playlists, New releases, Artists, …) — songs as playable rows, everything else as
 * a horizontal shelf of cards. Playlist and album cards open the app's dedicated
 * playlist screen when tapped; artists open the Artist Profile View.
 *
 * [listState] is hoisted by the caller, so coming back from a playlist keeps the
 * page exactly where it was.
 */
@Composable
fun ExploreGenreView(
    entry: ExploreFeed.ExploreEntry,
    page: BrowseResult?,
    isLoading: Boolean,
    listState: LazyListState,
    onBack: () -> Unit,
    onPlaySong: (SongItem) -> Unit,
    onSongLongPress: (SongItem) -> Unit,
    onPlayNextSong: (SongItem) -> Unit,
    onAddToQueueSong: (SongItem) -> Unit,
    onOpenItem: (rawItem: YTItem, id: String, name: String, thumbnail: String?, subtitle: String?) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                text = entry.title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            page == null && isLoading -> ExploreGenreSkeleton(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            page == null -> ExploreErrorState(
                onRetry = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            else -> ExploreGenreSections(
                page = page,
                listState = listState,
                onPlaySong = onPlaySong,
                onSongLongPress = onSongLongPress,
                onPlayNextSong = onPlayNextSong,
                onAddToQueueSong = onAddToQueueSong,
                onOpenItem = onOpenItem,
                onOpenArtist = onOpenArtist,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

/** The scrolling body of a mood / genre page — one block per returned section. */
@Composable
private fun ExploreGenreSections(
    page: BrowseResult,
    listState: LazyListState,
    onPlaySong: (SongItem) -> Unit,
    onSongLongPress: (SongItem) -> Unit,
    onPlayNextSong: (SongItem) -> Unit,
    onAddToQueueSong: (SongItem) -> Unit,
    onOpenItem: (rawItem: YTItem, id: String, name: String, thumbnail: String?, subtitle: String?) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        page.items.forEachIndexed { sectionIndex, section ->
            val songs = section.items.filterIsInstance<SongItem>()
            val shelfItems = section.items.filterNot { it is SongItem }

            if (shelfItems.isNotEmpty()) {
                item(key = "shelf_$sectionIndex") {
                    Column {
                        ExploreSectionHeader(section.title)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            itemsIndexed(
                                items = shelfItems,
                                key = { index, _ -> "shelf_${sectionIndex}_$index" }
                            ) { _, item ->
                                ExploreShelfCard(
                                    item = item,
                                    onOpen = { openExploreShelfItem(item, onOpenItem, onOpenArtist) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }

            if (songs.isNotEmpty()) {
                item(key = "songs_header_$sectionIndex") {
                    ExploreSectionHeader(section.title)
                }
                itemsIndexed(
                    items = songs,
                    key = { index, song -> "song_${sectionIndex}_${song.id}_$index" }
                ) { _, song ->
                    ExploreSongRow(
                        song = song,
                        onPlay = { onPlaySong(song) },
                        onLongPress = { onSongLongPress(song) },
                        onPlayNext = { onPlayNextSong(song) },
                        onAddToQueue = { onAddToQueueSong(song) }
                    )
                }
                item(key = "songs_gap_$sectionIndex") {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

/** Routes a tapped shelf card to its playlist / album / artist destination. */
private fun openExploreShelfItem(
    item: YTItem,
    onOpenItem: (rawItem: YTItem, id: String, name: String, thumbnail: String?, subtitle: String?) -> Unit,
    onOpenArtist: (ArtistItem) -> Unit
) {
    when (item) {
        is ArtistItem -> onOpenArtist(item)
        is AlbumItem -> onOpenItem(
            item,
            item.browseId,
            item.title,
            item.thumbnail,
            item.year?.toString() ?: "Album"
        )

        is PlaylistItem -> onOpenItem(
            item,
            item.id.removePrefix("VL"),
            item.title,
            item.thumbnail,
            item.songCountText ?: item.author?.name ?: "Playlist"
        )

        else -> onOpenItem(
            item,
            item.id.removePrefix("VL"),
            item.title,
            item.thumbnail,
            null
        )
    }
}

/** Bold DM Sans section heading used across the mood / genre page. */
@Composable
private fun ExploreSectionHeader(title: String?) {
    if (title.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        return
    }
    Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/**
 * One card of a mood / genre shelf — a playlist, album (square) or artist (circle).
 * Tapping it opens the app's dedicated playlist / album screen or the artist page.
 */
@Composable
private fun ExploreShelfCard(
    item: YTItem,
    onOpen: () -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onBackground
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val isArtist = item is ArtistItem
    val subtitle = when (item) {
        is PlaylistItem -> item.songCountText ?: item.author?.name ?: "Playlist"
        is AlbumItem -> item.year?.toString() ?: "Album"
        is ArtistItem -> "Artist"
        is PodcastItem -> item.episodeCountText ?: "Podcast"
        else -> ""
    }

    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onOpen()
            },
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondary)
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start
        )

        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = textColor.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start
            )
        }
    }
}

/**
 * A playable song row inside a mood / genre section. Mirrors every other song list
 * in the app: tap to play, long-press (or ⋮) for the Quick Action View, and the
 * swipe-to-queue gesture (right = play next, left = add to queue).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreSongRow(
    song: SongItem,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onBackground
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    SwipeQueueContainer(onPlayNext = onPlayNext, onAddToQueue = onAddToQueue) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onLongPress()
                    }
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = "Artwork",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondary),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artists.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onLongPress()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = textColor.copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Placeholder rows for a mood / genre page while its sections are loading. */
@Composable
private fun ExploreGenreSkeleton(modifier: Modifier = Modifier) {
    val shimmer = exploreShimmerColor(MaterialTheme.colorScheme.onBackground)

    Column(
        modifier = modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(shimmer)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(3) {
                    Column {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(shimmer)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .height(12.dp)
                                .width(100.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(shimmer)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
