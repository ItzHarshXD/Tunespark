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
import com.metrolist.innertube.models.SongItem
import com.tunespark.music.LikedSongManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

                // Five quick action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
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
            }
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(66.dp)
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


