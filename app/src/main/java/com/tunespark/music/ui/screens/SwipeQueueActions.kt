package com.tunespark.music.ui.screens

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared swipe trigger threshold: the larger of 90dp or 38% of screen width. */
@Composable
private fun swipeTriggerThresholdPx(): Float {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { config.screenWidthDp.dp.toPx() }
    return maxOf(with(density) { 90.dp.toPx() }, widthPx * 0.38f)
}

/**
 * Swipe-to-queue gesture container for song rows.
 *
 * - Swipe LEFT → RIGHT (positive drag): PLAY NEXT (inserted right after the
 *   currently playing track).
 * - Swipe RIGHT → LEFT (negative drag): ADD TO QUEUE (appended at the end).
 *
 * A deliberate drag is required to trigger an action: the row must be dragged
 * past the trigger threshold (the larger of 90dp or 38% of the screen width),
 * which prevents accidental swipes while scrolling vertically. The background
 * reveals an icon + label hint that grows in opacity as the drag progresses,
 * and a haptic tick fires when the threshold is crossed.
 */
@Composable
fun SwipeQueueContainer(
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val coroutineScope = rememberCoroutineScope()

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val accentColor = Color(0xFFFF0000)

    val offsetX = remember { Animatable(0f) }
    var triggerArmed by remember { mutableStateOf(false) }

    val thresholdPx = swipeTriggerThresholdPx()
    val maxDragPx = thresholdPx * 1.35f

    Box(modifier = modifier.fillMaxSize()) {
        // ── Action hint background (visible while dragging) ──
        val dragX = offsetX.value
        if (abs(dragX) > 1f) {
            val isPlayNext = dragX > 0f
            val progress = (abs(dragX) / thresholdPx).coerceIn(0f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isPlayNext) Arrangement.Start else Arrangement.End
            ) {
                SwipeActionHint(
                    icon = if (isPlayNext) Icons.Default.PlaylistPlay else Icons.Default.PlaylistAdd,
                    label = if (isPlayNext) "Play next" else "Add to queue",
                    tint = if (triggerArmed) accentColor else textColor.copy(alpha = 0.55f),
                    alpha = 0.25f + 0.75f * progress,
                    scale = 0.7f + 0.3f * progress,
                    modifier = if (isPlayNext) Modifier.padding(start = 24.dp) else Modifier.padding(end = 24.dp)
                )
            }
        }

        // ── Foreground song row, follows the finger horizontally ──
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerHorizontalDrag(
                    onStart = { triggerArmed = false },
                    onDrag = { delta ->
                        val target = offsetX.value + delta
                        // Resistance cap so it feels effort-taking.
                        val clamped = if (abs(target) > maxDragPx) {
                            maxDragPx * (target / abs(target))
                        } else target
                        coroutineScope.launch { offsetX.snapTo(clamped) }
                        val crossed = abs(clamped) >= thresholdPx
                        if (crossed && !triggerArmed) {
                            triggerArmed = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
                        } else if (!crossed && triggerArmed) {
                            triggerArmed = false
                        }
                    },
                    onEnd = {
                        val passed = abs(offsetX.value) >= thresholdPx
                        val wasPlayNext = offsetX.value > 0f
                        coroutineScope.launch {
                            if (passed) {
                                // Slide further in the swipe direction for a satisfying
                                // "committed" feel, then spring back to rest.
                                offsetX.animateTo(
                                    maxDragPx * (offsetX.value / abs(offsetX.value)),
                                    spring(stiffness = Spring.StiffnessMedium)
                                )
                                offsetX.snapTo(0f)
                            } else {
                                offsetX.animateTo(
                                    0f,
                                    spring(
                                        stiffness = Spring.StiffnessMediumLow,
                                        dampingRatio = Spring.DampingRatioMediumBouncy
                                    )
                                )
                            }
                            triggerArmed = false
                        }
                        if (passed) {
                            if (wasPlayNext) onPlayNext() else onAddToQueue()
                        }
                    }
                )
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeActionHint(
    icon: ImageVector,
    label: String,
    tint: Color,
    alpha: Float,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            this.scaleX = scale
            this.scaleY = scale
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Thin wrapper over horizontal drag detection with start/end callbacks. */
private fun Modifier.pointerHorizontalDrag(
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit
): Modifier = composed {
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnEnd by rememberUpdatedState(onEnd)
    this.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { currentOnStart() },
            onDragEnd = { currentOnEnd() },
            onDragCancel = { currentOnEnd() }
        ) { change, dragAmount ->
            change.consume()
            currentOnDrag(dragAmount)
        }
    }
}
