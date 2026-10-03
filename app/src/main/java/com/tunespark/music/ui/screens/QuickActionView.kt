package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YouTubeClient
import com.tunespark.music.LikedSongManager
import com.tunespark.music.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Action View — a premium bottom-sheet context menu that pops up when the
 * user long-presses any song anywhere in the app (Home, Search, Playlists, Recents).
 *
 * It contains the song artwork, title and artist names plus 5 fully functional
 * quick actions: Like song, Start radio, Play next, Add to queue and Share song.
 *
 * UX details:
 * - Slides up from the bottom of the screen with a bouncy-free spring animation.
 * - Can be dismissed by swiping the sheet down (past a threshold), by tapping the
 *   dimmed scrim behind it, or by pressing the system back button.
 * - Fully theme aware (Light: off-white surfaces / Dark: off-black surfaces) with
 *   the app's signature pure red accent, subtle shadows and 1.dp hairline borders.
 */
@Composable
fun QuickActionView(
    song: SongItem?,
    onDismiss: () -> Unit,
    onLike: (SongItem) -> Unit,
    onStartRadio: (SongItem) -> Unit,
    onPlayNext: (SongItem) -> Unit,
    onAddToQueue: (SongItem) -> Unit,
    onShare: (SongItem) -> Unit,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    // Non-null only when the song sits in a playlist the user owns — enables the
    // "Remove song" action (see PlaylistsScreen's playlist-detail rows).
    onRemoveFromPlaylist: ((SongItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {

    // Keep the last non-null song around so the content stays composed while
    // the exit animation plays after the parent clears the selection.
    var displayedSong by remember { mutableStateOf<SongItem?>(null) }

    // 0f -> fully hidden below the screen edge, 1f -> fully visible.
    val progress = remember { Animatable(0f) }
    val dragOffsetPx = remember { mutableFloatStateOf(0f) }
    var dragAnimJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val context = LocalContext.current

    // ── "Add to playlist" picker + "Song info" dialog state ─────────────────
    // null = idle; otherwise the key of the row currently being added to (a
    // playlist's id, or newPlaylistPickerKey for the inline create row) so only
    // THAT row shows a spinner — never every playlist at once.
    var addingToPlaylistId by remember { mutableStateOf<String?>(null) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var pickerPlaylists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var isPickerLoading by remember { mutableStateOf(false) }
    // Row key used by the picker's inline "New playlist" creation entry.
    val newPlaylistPickerKey = "__new_playlist__"

    // Song-info enrichment: some feeds parse songs without an album or duration,
    // so the Info popup fills those gaps on demand via a lightweight watch-next
    // lookup (same video id) and a player-duration fallback.
    var infoAlbumName by remember { mutableStateOf<String?>(null) }
    var infoDurationSeconds by remember { mutableStateOf<Int?>(null) }
    var isLoadingInfoDetails by remember { mutableStateOf(false) }

    LaunchedEffect(showInfoDialog, displayedSong?.id) {
        val infoTarget = displayedSong
        if (!showInfoDialog || infoTarget == null) return@LaunchedEffect
        infoAlbumName = infoTarget.album?.name
        infoDurationSeconds = infoTarget.duration
        isLoadingInfoDetails = infoTarget.album?.name == null || infoTarget.duration == null
        if (!isLoadingInfoDetails) return@LaunchedEffect
        try {
            val enriched = withContext(Dispatchers.IO) {
                runCatching {
                    YouTube.next(WatchEndpoint(videoId = infoTarget.id))
                        .getOrNull()?.items
                        ?.firstOrNull { it.id == infoTarget.id }
                }.getOrNull()
            }
            if (enriched?.album?.name != null) infoAlbumName = enriched.album?.name
            if (enriched?.duration != null) infoDurationSeconds = enriched.duration
            if (infoAlbumName == null || infoDurationSeconds == null) {
                val playerSeconds = withContext(Dispatchers.IO) {
                    runCatching {
                        YouTube.player(
                            videoId = infoTarget.id,
                            client = YouTubeClient.WEB_REMIX,
                        ).getOrNull()?.videoDetails?.lengthSeconds?.toIntOrNull()
                    }.getOrNull()
                }
                if (infoDurationSeconds == null && playerSeconds != null && playerSeconds > 0) {
                    infoDurationSeconds = playerSeconds
                }
            }
            // Album fallback: a title + artist search usually returns the same track
            // with the album field parsed.
            if (infoAlbumName == null) {
                val searchAlbum = withContext(Dispatchers.IO) {
                    runCatching {
                        val query = (listOf(infoTarget.title) + infoTarget.artists.map { it.name })
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        YouTube.search(
                            query = query,
                            filter = YouTube.SearchFilter.FILTER_SONG,
                        ).getOrNull()?.items
                            ?.filterIsInstance<SongItem>()
                            ?.firstOrNull { it.id == infoTarget.id || it.title.equals(infoTarget.title, ignoreCase = true) }
                            ?.album?.name
                    }.getOrNull()
                }
                if (!searchAlbum.isNullOrBlank()) infoAlbumName = searchAlbum
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isLoadingInfoDetails = false
    }

    // Loads the user's library playlists the first time the picker opens.
    LaunchedEffect(showPlaylistPicker) {
        if (!showPlaylistPicker) return@LaunchedEffect
        isPickerLoading = true
        pickerPlaylists = emptyList()
        try {
            val result = withContext(Dispatchers.IO) { YouTube.library("FEmusic_liked_playlists") }
            // Only the user's OWN playlists: songs can't be added to someone else's
            // playlist. The EDIT menu flag marks owned playlists; the author-name
            // match covers parses where that flag is missing.
            val accountName = SessionManager.getCachedAccountInfo(context)?.name
            pickerPlaylists = result.getOrNull()?.items.orEmpty()
                .filterIsInstance<PlaylistItem>()
                .filter {
                    val titleLower = it.title.lowercase()
                    val isAutoList = titleLower == "liked music" || titleLower == "episodes for later"
                    val isOwn = it.isEditable || (accountName != null && it.author?.name == accountName)
                    !isAutoList && isOwn
                }
        } catch (e: Exception) {
            e.printStackTrace()
            pickerPlaylists = emptyList()
        }
        isPickerLoading = false
    }
    // Slide distance source; seeded with a sensible estimate so the very first
    // enter frame already starts from below the screen edge before real size arrives.
    val sheetHeightPx = remember { mutableIntStateOf(with(density) { 480.dp.roundToPx() }) }

    val isDarkTheme = MaterialTheme.colorScheme.background == Color.Black
    val sheetBgColor = if (isDarkTheme) Color(0xFF141414) else Color(0xFFF2F2F5)
    val textColor = MaterialTheme.colorScheme.onBackground
    val buttonBgColor = if (isDarkTheme) Color(0xFF232327) else Color(0xFFFFFFFF)
    val buttonBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    val buttonIconTint = if (isDarkTheme) Color.White else Color.Black
    val accentColor = Color(0xFFFF0000)

    LaunchedEffect(song) {
        // A different song (or a closed sheet) always starts with fresh dialogs.
        showPlaylistPicker = false
        showInfoDialog = false
        if (song != null) {
            displayedSong = song
            dragOffsetPx.floatValue = 0f
            dragAnimJob?.cancel()
            dragAnimJob = null
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else if (displayedSong != null) {
            // Animate the sheet back below the screen edge (keeping any partial
            // drag offset so a swipe-down dismissal continues from its position).
            progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            displayedSong = null
            dragOffsetPx.floatValue = 0f
        }
    }

    BackHandler(enabled = song != null) {
        onDismiss()
    }

    val currentSong = displayedSong
    if (currentSong != null) {
        val isLiked = LikedSongManager.isLiked(currentSong.id)
        val artistNames = currentSong.artists.joinToString(", ") { it.name }
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

        Box(modifier = modifier.fillMaxSize()) {
            // Dimmed scrim — tap to dismiss
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value }
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onDismiss()
                    }
            )

            // Bottom sheet
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { size -> if (size.height > 0) sheetHeightPx.intValue = size.height }
                    .graphicsLayer {
                        translationY = (1f - progress.value) * sheetHeightPx.intValue + dragOffsetPx.floatValue
                    }
                    .clip(sheetShape)
                    .background(sheetBgColor)
                    .navigationBarsPadding()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                dragAnimJob?.cancel()
                                dragAnimJob = null
                                dragOffsetPx.floatValue =
                                    (dragOffsetPx.floatValue + dragAmount).coerceAtLeast(0f)
                            },
                            onDragEnd = {
                                val dismissThreshold = with(density) { 120.dp.toPx() }
                                if (dragOffsetPx.floatValue > dismissThreshold) {
                                    onDismiss()
                                } else {
                                    springBackToRest(scope, dragOffsetPx) { job -> dragAnimJob = job }
                                }
                            },
                            onDragCancel = {
                                springBackToRest(scope, dragOffsetPx) { job -> dragAnimJob = job }
                            }
                        )
                    }
                    .padding(top = 10.dp, bottom = 18.dp)
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(textColor.copy(alpha = 0.25f))
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Song details header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = currentSong.thumbnail,
                        contentDescription = currentSong.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(textColor.copy(alpha = 0.08f))
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong.title,
                            color = textColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = artistNames,
                            color = textColor.copy(alpha = 0.55f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Hairline divider
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(textColor.copy(alpha = 0.07f))
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Quick actions — five per row, each row spanning the full sheet
                // width, so buttons wrap below left-aligned instead of scrolling.
                val quickActionButtons: MutableList<@Composable () -> Unit> = mutableListOf()
                quickActionButtons.add {
                    QuickActionButton(
                        icon = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = if (isLiked) "Liked" else "Like",
                        iconTint = if (isLiked) accentColor else buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        onLike(currentSong)
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Rounded.Sensors,
                        label = "Start radio",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        onStartRadio(currentSong)
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Rounded.QueuePlayNext,
                        label = "Play next",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        onPlayNext(currentSong)
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Outlined.Queue,
                        label = "Add to queue",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        onAddToQueue(currentSong)
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Filled.PlaylistAdd,
                        label = "Add to playlist",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        if (!SessionManager.isUserSignedIn(context)) {
                            Toast.makeText(context, "Sign in to add songs to playlists", Toast.LENGTH_SHORT).show()
                        } else {
                            showPlaylistPicker = true
                        }
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Rounded.Person,
                        label = "Go to artist",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        val artist = currentSong.artists.firstOrNull { it.name.isNotBlank() }
                        if (artist == null) {
                            Toast.makeText(context, "Artist information unavailable", Toast.LENGTH_SHORT).show()
                        } else {
                            // Some feeds parse artists without a browse id — the handler
                            // resolves those via an artist search before navigating.
                            onOpenArtist(artist.id ?: "", artist.name)
                        }
                    }
                }

                val removeAction = onRemoveFromPlaylist
                if (removeAction != null) {
                    quickActionButtons.add {
                        QuickActionButton(
                            icon = Icons.Rounded.RemoveCircleOutline,
                            label = "Remove song",
                            iconTint = buttonIconTint,
                            buttonBgColor = buttonBgColor,
                            buttonBorderColor = buttonBorderColor,
                            labelColor = textColor
                        ) {
                            removeAction(currentSong)
                        }
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Outlined.Info,
                        label = "Info",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        showInfoDialog = true
                    }
                }

                quickActionButtons.add {
                    QuickActionButton(
                        icon = Icons.Rounded.Share,
                        label = "Share",
                        iconTint = buttonIconTint,
                        buttonBgColor = buttonBgColor,
                        buttonBorderColor = buttonBorderColor,
                        labelColor = textColor
                    ) {
                        onShare(currentSong)
                    }
                }
                // Five full-width buttons per row; the remainder pile into a
                // second row, left aligned.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    quickActionButtons.chunked(5).forEach { rowButtons ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowButtons.forEach { button ->
                                Box(modifier = Modifier.weight(1f)) {
                                    button()
                                }
                            }
                            // Pad short rows so the last-row buttons stay the same
                            // width as the rest and everything stays left aligned.
                            repeat(5 - rowButtons.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // ── "Add to playlist" picker + "Song info" credits dialogs ───────────
        // Elevated surfaces (rounded corners + hairline border + distinct fill)
        // so they always contrast against the sheet and the app background.
        val dialogContainerColor = if (isDarkTheme) Color(0xFF1E1E22) else Color(0xFFF7F7FA)
        val dialogBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

        if (showPlaylistPicker) {
            val songToAdd = currentSong
            val startAddToPlaylist: (PlaylistItem) -> Unit = { playlist ->
                if (addingToPlaylistId == null) {
                    addingToPlaylistId = playlist.id
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            YouTube.addToPlaylist(playlist.id, songToAdd.id)
                        }
                        addingToPlaylistId = null
                        if (result.isSuccess) {
                            showPlaylistPicker = false
                            Toast.makeText(context, "Added to '${playlist.title}'", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Couldn't add to '${playlist.title}'", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            AlertDialog(
                onDismissRequest = { if (addingToPlaylistId == null) showPlaylistPicker = false },
                modifier = Modifier.border(1.dp, dialogBorderColor, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                containerColor = dialogContainerColor,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Add to playlist",
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { if (addingToPlaylistId == null) showPlaylistPicker = false },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Close",
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Create a brand-new playlist and drop the song straight into it.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(buttonBgColor)
                                .clickable(enabled = addingToPlaylistId == null) {
                                    if (addingToPlaylistId == null) {
                                        addingToPlaylistId = newPlaylistPickerKey
                                        scope.launch {
                                            val createdId = withContext(Dispatchers.IO) {
                                                runCatching { YouTube.createPlaylist("New playlist").removePrefix("VL") }.getOrNull()
                                            }
                                            if (createdId.isNullOrBlank()) {
                                                addingToPlaylistId = null
                                                Toast.makeText(context, "Couldn't create playlist", Toast.LENGTH_SHORT).show()
                                            } else {
                                                val added = withContext(Dispatchers.IO) {
                                                    YouTube.addToPlaylist(createdId, songToAdd.id)
                                                }
                                                addingToPlaylistId = null
                                                if (added.isSuccess) {
                                                    showPlaylistPicker = false
                                                    Toast.makeText(context, "Added to 'New playlist'", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Couldn't add to the new playlist", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                }
                                .padding(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Gray.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (addingToPlaylistId == newPlaylistPickerKey) {
                                    CircularProgressIndicator(color = textColor, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Add, contentDescription = "New playlist", tint = textColor, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "New playlist",
                                color = textColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(textColor.copy(alpha = 0.07f)))
                        Spacer(modifier = Modifier.height(8.dp))

                        when {
                            isPickerLoading -> {
                                Box(modifier = Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = textColor, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                                }
                            }
                            pickerPlaylists.isEmpty() -> {
                                Text(
                                    text = "No playlists found in your library yet.",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                                )
                            }
                            else -> {
                                pickerPlaylists.forEach { playlist ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(buttonBgColor)
                                            .clickable(enabled = addingToPlaylistId == null) { startAddToPlaylist(playlist) }
                                            .padding(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color.Gray.copy(alpha = 0.25f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!playlist.thumbnail.isNullOrEmpty()) {
                                                AsyncImage(
                                                    model = playlist.thumbnail,
                                                    contentDescription = playlist.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(Icons.Filled.PlaylistAdd, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = playlist.title,
                                                color = textColor,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = playlist.songCountText ?: "Playlist",
                                                color = Color.Gray,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (addingToPlaylistId == playlist.id) {
                                            CircularProgressIndicator(color = textColor, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }

        if (showInfoDialog) {
            val infoSong = currentSong
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                modifier = Modifier.border(1.dp, dialogBorderColor, RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                containerColor = dialogContainerColor,
                title = { Text(text = "Song info", color = textColor, fontWeight = FontWeight.Bold) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = infoSong.thumbnail,
                                contentDescription = infoSong.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(textColor.copy(alpha = 0.08f))
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = infoSong.title,
                                    color = textColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = infoSong.album?.name ?: artistNames,
                                    color = textColor.copy(alpha = 0.55f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(textColor.copy(alpha = 0.07f)))
                        Spacer(modifier = Modifier.height(14.dp))

                        // Credits — every artist is clickable and opens that artist's
                        // profile view inside the Library screen.
                        Text(text = "ARTISTS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))

                        infoSong.artists.forEach { artist ->
                            // Songs from some feeds parse artists without a browse id —
                            // View still works: the handler resolves those via search.
                            val canOpenArtist = artist.name.isNotBlank()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (canOpenArtist) {
                                            Modifier.clickable {
                                                showInfoDialog = false
                                                onOpenArtist(artist.id ?: "", artist.name)
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(vertical = 7.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.Gray.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = artist.name.take(1).uppercase(),
                                        color = textColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = artist.name,
                                    color = if (canOpenArtist) textColor else textColor.copy(alpha = 0.5f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (canOpenArtist) {
                                    Text(text = "View", color = Color(0xFFFF0000), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(textColor.copy(alpha = 0.07f)))

                        val albumName = infoAlbumName ?: infoSong.album?.name
                        val durationSeconds = infoDurationSeconds ?: infoSong.duration
                        val durationText = durationSeconds?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Text(text = "Album", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                            if (isLoadingInfoDetails && albumName == null) {
                                CircularProgressIndicator(
                                    color = textColor.copy(alpha = 0.7f),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Text(
                                    text = if (albumName.isNullOrBlank()) "—" else albumName,
                                    color = if (albumName.isNullOrBlank()) Color.Gray else textColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(text = "Duration", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                            if (isLoadingInfoDetails && durationText == null) {
                                CircularProgressIndicator(
                                    color = textColor.copy(alpha = 0.7f),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Text(
                                    text = durationText ?: "—",
                                    color = if (durationText == null) Color.Gray else textColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (infoSong.id.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
                                Text(text = "Video ID", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                                Text(
                                    text = infoSong.id,
                                    color = textColor.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text(text = "Close", color = Color(0xFFFF0000), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

/**
 * Springs the sheet back to its resting position after a short swipe that
 * did not cross the dismissal threshold.
 */
private fun springBackToRest(
    scope: kotlinx.coroutines.CoroutineScope,
    dragOffsetPx: androidx.compose.runtime.MutableFloatState,
    onJobCreated: (Job) -> Unit
) {
    onJobCreated(
        scope.launch {
            animate(
                initialValue = dragOffsetPx.floatValue,
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ ->
                dragOffsetPx.floatValue = value
            }
        }
    )
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    buttonBgColor: Color,
    buttonBorderColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .shadow(elevation = 4.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(buttonBgColor)
                .border(1.dp, buttonBorderColor, CircleShape)
                .clickable {
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = label,
            color = labelColor.copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}


