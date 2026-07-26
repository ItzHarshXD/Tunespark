package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import com.tunespark.music.VisualizerData
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.tunespark.music.AppScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitFirstDown
import android.view.SoundEffectConstants
import android.view.HapticFeedbackConstants
import kotlin.math.pow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.animation.core.animateDpAsState

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
fun DropIndicatorLine(
    isHighlighted: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(
                color = if (isHighlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(3.dp)
            )
    )
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
    onStopRadio: () -> Unit,
    lyricsLines: List<LyricLine>,
    isLyricsLoading: Boolean,
    fullPlayerProgress: Animatable<Float, *>? = null,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigate(AppScreen.HOME)
    }

    val context = LocalContext.current
    val keepScreenOnSetting = remember(context) { com.tunespark.music.SessionManager.getKeepScreenOn(context) }
    val showVisualizerSetting = remember(context) { com.tunespark.music.SessionManager.getShowVisualizer(context) }

    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var currentTouchY by remember { mutableStateOf(0f) }
    var hoverIndex by remember { mutableStateOf(-1) }
    val localDensity = LocalDensity.current
    val itemHeightPx = with(localDensity) { 76.dp.toPx() }
    val queueListState = rememberLazyListState()
    val listState = rememberLazyListState()

    var localQueue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(playQueue, draggedId) {
        if (draggedId == null) {
            localQueue = playQueue
        }
    }

    val vibrator = remember(context) { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    val triggerVibration: (Long) -> Unit = { durationMs ->
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val updateHoverIndex = {
        val layoutInfo = queueListState.layoutInfo
                val draggedItemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedId }
                if (draggedItemInfo != null) {
                    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                    val minOffset = -draggedItemInfo.offset.toFloat()
                    val maxOffset = (viewportHeight - draggedItemInfo.size - draggedItemInfo.offset).toFloat()
                    dragOffset = dragOffset.coerceIn(minOffset, maxOffset)
                }

        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        if (viewportHeight > 0) {
            val clampedTouchY = currentTouchY.coerceIn(0f, viewportHeight.toFloat())

            var bestTarget = -1
            var minDiff = Float.MAX_VALUE

            for (itemInfo in layoutInfo.visibleItemsInfo) {
                val idx = itemInfo.index

                // Distance to top of this item
                val distTop = kotlin.math.abs(itemInfo.offset - clampedTouchY)
                if (distTop < minDiff) {
                    minDiff = distTop
                    bestTarget = idx
                }

                // Distance to bottom of this item
                val distBottom = kotlin.math.abs((itemInfo.offset + itemInfo.size) - clampedTouchY)
                if (distBottom < minDiff) {
                    minDiff = distBottom
                    bestTarget = idx + 1
                }
            }

            if (bestTarget != -1) {
                val newHover = bestTarget.coerceIn(0, localQueue.size)
                if (newHover != hoverIndex) {
                    hoverIndex = newHover
                    triggerVibration(25) // sharp physical click vibration on hover line shift
                }
            }
        }
    }

    LaunchedEffect(draggedId) {
        if (draggedId != null) {
            while (true) {
                val layoutInfo = queueListState.layoutInfo
                val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
                if (viewportHeight > 0) {
                    val scrollThreshold = itemHeightPx * 0.75f
                    var scrollAmount = 0f

                    if (currentTouchY < scrollThreshold) {
                        val ratio = ((scrollThreshold - currentTouchY) / scrollThreshold).coerceIn(0f, 2f)
                        scrollAmount = -12f * ratio
                    } else if (currentTouchY > viewportHeight - scrollThreshold) {
                        val ratio = ((currentTouchY - (viewportHeight - scrollThreshold)) / scrollThreshold).coerceIn(0f, 2f)
                        scrollAmount = 12f * ratio
                    }

                    if (scrollAmount != 0f) {
                        val canScrollUp = queueListState.canScrollBackward
                        val canScrollDown = queueListState.canScrollForward
                        if ((scrollAmount < 0f && canScrollUp) || (scrollAmount > 0f && canScrollDown)) {
                            queueListState.scrollBy(scrollAmount)
                            dragOffset += scrollAmount
                            updateHoverIndex()
                        }
                    }
                }
                delay(16)
            }
        }
    }

    DisposableEffect(keepScreenOnSetting) {
        var window: android.view.Window? = null
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) {
                window = ctx.window
                break
            }
            ctx = ctx.baseContext
        }

        if (window != null && keepScreenOnSetting) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            if (window != null && keepScreenOnSetting) {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var activeTab by remember { mutableStateOf("lyrics") }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val playPauseExtraWidth = remember { Animatable(0f) }
    val skipExtraWidth = remember { Animatable(0f) }
    var isStopExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(isStopExpanded) {
        if (isStopExpanded) {
            delay(4000)
            isStopExpanded = false
        }
    }

    LaunchedEffect(exoPlayer, currentTrackIndex) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            val d = exoPlayer.duration
            duration = if (d == C.TIME_UNSET || d < 0) 0L else d
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

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val dragModifier = if (fullPlayerProgress != null) {
        Modifier.pointerInput(activeTab, listState, queueListState) {
            val thresholdPx = 15f * density
            val screenHeightPx = size.height.toFloat()
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown()
                    val startY = down.position.y

                    val isListAtTop = if (activeTab == "lyrics") {
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    } else {
                        queueListState.firstVisibleItemIndex == 0 && queueListState.firstVisibleItemScrollOffset == 0
                    }

                    val listTopBoundaryPx = 280f * density
                    val touchStartedInListArea = startY >= listTopBoundaryPx
                    val touchStartedOnTopButtons = startY <= 80f * density

                    val shouldAllowDrag = when {
                        touchStartedOnTopButtons -> false
                        touchStartedInListArea -> isListAtTop
                        else -> true
                    }

                    if (shouldAllowDrag) {
                        var accumulatedDy = 0f
                        val startTime = System.currentTimeMillis()
                        var gestureDirection: String? = null

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.pressed) {
                                val deltaY = change.position.y - change.previousPosition.y

                                if (gestureDirection == null) {
                                    accumulatedDy += deltaY
                                    if (kotlin.math.abs(accumulatedDy) >= thresholdPx) {
                                        if (accumulatedDy > 0) {
                                            gestureDirection = "vertical"
                                            change.consume()
                                        } else {
                                            break
                                        }
                                    }
                                } else {
                                    change.consume()
                                    val progressDelta = -deltaY / screenHeightPx
                                    val newProgress = (fullPlayerProgress.value + progressDelta).coerceIn(0f, 1f)
                                    scope.launch {
                                        fullPlayerProgress.snapTo(newProgress)
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (gestureDirection == "vertical") {
                            val swipeDuration = System.currentTimeMillis() - startTime
                            val isFlingDown = swipeDuration < 250 && accumulatedDy > 30f * density
                            val dragPassedThreshold = fullPlayerProgress.value < 0.85f

                            if (dragPassedThreshold || isFlingDown) {
                                scope.launch {
                                    fullPlayerProgress.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                    onNavigate(AppScreen.HOME)
                                }
                            } else {
                                scope.launch {
                                    fullPlayerProgress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(dragModifier)
            .background(backgroundColor)
            .padding(horizontal = 24.dp, vertical = 0.dp)
    ) {
        // Transparent overlay to catch clicks anywhere outside the top bar when the Stop button is expanded
        if (isStopExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 68.dp) // Starts below top bar (12.dp top padding + 56.dp height)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isStopExpanded = false
                    }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // 1. Top Bar
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 0.dp)
            ) {
                val parentWidth = maxWidth

                // Back Button (Left Aligned)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(primaryColor)
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onNavigate(AppScreen.HOME) // Always takes user to HOME
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = onPrimaryColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Play/Pause & Skip buttons (Center Aligned, Hides when stop button expands)
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isStopExpanded,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .height(56.dp)
                            .width(240.dp)
                    ) {
                        // Left half (Play/Pause)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(119.dp)
                                .align(Alignment.CenterStart)
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .width(80.dp + playPauseExtraWidth.value.coerceAtLeast(0f).dp)
                                    .align(Alignment.CenterEnd)
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
                                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        scope.launch {
                                            playPauseExtraWidth.snapTo(0f)
                                            playPauseExtraWidth.animateTo(
                                                targetValue = 10f, // Premium subtle peak elongation
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                            playPauseExtraWidth.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
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
                        }

                        // Right half (Skip)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(119.dp)
                                .align(Alignment.CenterEnd)
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .width(80.dp + skipExtraWidth.value.coerceAtLeast(0f).dp)
                                    .align(Alignment.CenterStart)
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
                                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        scope.launch {
                                            skipExtraWidth.snapTo(0f)
                                            skipExtraWidth.animateTo(
                                                targetValue = 10f, // Premium subtle peak elongation
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                            skipExtraWidth.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
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
                    }
                }

                // Stop Button (Right Aligned, Expands on first click)
                val stopButtonWidth by animateDpAsState(
                    targetValue = if (isStopExpanded) parentWidth - 68.dp else 56.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "StopButtonWidth"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .height(56.dp)
                        .width(stopButtonWidth)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFFFF0000))
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            if (!isStopExpanded) {
                                isStopExpanded = true
                            } else {
                                onStopRadio()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isStopExpanded,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Stop",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            val shouldShowVisualizer = showVisualizerSetting && activeTab != "queue"

            AnimatedVisibility(
                visible = shouldShowVisualizer,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(10.dp))
                    RadioEqualizerWaveform(isPlaying = isPlaying)
                }
            }

            val spacerHeight by animateDpAsState(
                targetValue = if (shouldShowVisualizer) 10.dp else 20.dp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "VisualizerSpacerHeight"
            )
            Spacer(modifier = Modifier.height(spacerHeight))

            // 3. Current Song Details Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 0.dp),
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
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            activeTab = "lyrics"
                        }
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
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            activeTab = "queue"
                        }
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
                    var isAutoScrolling by remember { mutableStateOf(false) }
                    var userHasScrolledAway by remember { mutableStateOf(false) }

                    val activeInSight by remember(activeLyricIndex) {
                        derivedStateOf {
                            if (activeLyricIndex < 0 || lyricsLines.isEmpty()) {
                                true
                            } else {
                                listState.layoutInfo.visibleItemsInfo.any { it.index == activeLyricIndex }
                            }
                        }
                    }

                    LaunchedEffect(activeInSight, listState.isScrollInProgress) {
                        if (activeInSight) {
                            userHasScrolledAway = false
                        } else if (listState.isScrollInProgress && !isAutoScrolling) {
                            userHasScrolledAway = true
                        }
                    }

                    val isPlaybackDone = remember(exoPlayer.playbackState, isPlaying, currentPosition, duration) {
                        exoPlayer.playbackState == Player.STATE_ENDED || (!isPlaying && currentPosition >= duration - 1000 && duration > 0)
                    }

                    LaunchedEffect(isPlaybackDone) {
                        if (isPlaybackDone) {
                            listState.animateScrollToItem(0)
                        }
                    }

                    LaunchedEffect(activeLyricIndex) {
                        if (!userHasScrolledAway && activeLyricIndex >= 0 && activeLyricIndex < lyricsLines.size) {
                            val targetOffset = -10
                            val initialVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLyricIndex }
                            if (initialVisibleItem == null) {
                                // If the item is not visible at all (e.g. initial load or far seek click),
                                // snap to it instantly so there's no jarring jump, then it's in view for tracking
                                listState.scrollToItem(activeLyricIndex, targetOffset)
                            } else {
                                // If the item is already visible, perform a buttery-smooth chase scroll.
                                // It runs inside a scroll session and continuously calculates the item's live offset
                                // during expansion/shrinks, guaranteeing 0% overshoot or bounciness glitches.
                                isAutoScrolling = true
                                try {
                                    listState.scroll {
                                        var lastValue = 0f
                                        animate(
                                            initialValue = 0f,
                                            targetValue = 1f,
                                            animationSpec = tween(
                                                durationMillis = 600,
                                                easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)
                                            )
                                        ) { currentValue, _ ->
                                            val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLyricIndex }
                                            if (visibleItem != null) {
                                                val currentOffset = visibleItem.offset
                                                val remainingDelta = currentOffset - targetOffset

                                                val denominator = 1f - lastValue
                                                val stepFraction = if (denominator > 0.001f) {
                                                    (currentValue - lastValue) / denominator
                                                } else {
                                                    1f
                                                }
                                                val scrollAmount = remainingDelta * stepFraction

                                                scrollBy(scrollAmount)
                                                lastValue = currentValue
                                            }
                                        }
                                    }
                                } finally {
                                    isAutoScrolling = false
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 24.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                        itemsIndexed(lyricsLines) { index, line ->
                            val isPlaceholder = lyricsLines.size == 1 &&
                                    (line.text.startsWith("No lyrics") ||
                                            line.text.startsWith("Could not") ||
                                            line.text.startsWith("No track"))

                            val isActive = index == activeLyricIndex

                            val targetAlpha = when {
                                isPlaceholder -> 0.5f
                                isActive -> 1f
                                activeLyricIndex == -1 -> {
                                    when {
                                        index < 2 -> 1f
                                        index in 2..4 -> 0.9f
                                        index in 5..7 -> 0.75f
                                        index in 8..10 -> 0.6f
                                        else -> 0.45f
                                    }
                                }
                                else -> {
                                    val distance = kotlin.math.abs(index - activeLyricIndex)
                                    when {
                                        distance == 1 -> 0.85f
                                        distance == 2 -> 0.7f
                                        distance == 3 -> 0.55f
                                        else -> 0.4f
                                    }
                                }
                            }

                            val animatedAlpha by animateFloatAsState(
                                targetValue = targetAlpha,
                                animationSpec = tween(
                                    durationMillis = 350,
                                    easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)
                                ),
                                label = "LyricAlpha"
                            )

                            val activeFraction by animateFloatAsState(
                                targetValue = if (isActive || (activeLyricIndex == -1 && index < 2 && !isPlaceholder)) 1f else 0f,
                                animationSpec = tween(
                                    durationMillis = 350,
                                    easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)
                                ),
                                label = "LyricScale"
                            )

                            val fontSize = (21f + 5f * activeFraction).sp
                            val lineHeight = (30f + 6f * activeFraction).sp
                            val fontWeight = FontWeight.Bold

                            val widthFraction by animateFloatAsState(
                                targetValue = when {
                                    isPlaceholder -> 1f
                                    isActive -> 1f
                                    activeLyricIndex == -1 && index < 2 -> 1f
                                    else -> 0.78f
                                },
                                animationSpec = tween(
                                    durationMillis = 350,
                                    easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)
                                ),
                                label = "LyricWidth"
                            )

                            val verticalPadding by animateDpAsState(
                                targetValue = when {
                                    isPlaceholder -> 0.dp
                                    isActive -> 10.dp
                                    activeLyricIndex == -1 && index < 2 -> 10.dp
                                    else -> 0.dp
                                },
                                animationSpec = tween(
                                    durationMillis = 350,
                                    easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)
                                ),
                                label = "LyricPadding"
                            )

                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = line.text,
                                    color = textColor.copy(alpha = animatedAlpha),
                                    fontSize = fontSize,
                                    fontWeight = fontWeight,
                                    lineHeight = lineHeight,
                                    style = LocalTextStyle.current.copy(
                                        lineHeightStyle = LineHeightStyle(
                                            alignment = LineHeightStyle.Alignment.Center,
                                            trim = LineHeightStyle.Trim.None
                                        )
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth(widthFraction)
                                        .padding(vertical = verticalPadding)
                                        .then(
                                            if (!isPlaceholder && line.timestampMs >= 0) {
                                                Modifier.clickable {
                                                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                    exoPlayer.seekTo(line.timestampMs)
                                                    currentPosition = line.timestampMs
                                                }
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    textAlign = if (isPlaceholder) TextAlign.Center else TextAlign.Start
                                )
                            }
                        }

                        if (lyricsLines.size > 1) {
                            item {
                                Spacer(modifier = Modifier.fillParentMaxHeight(0.85f))
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = userHasScrolledAway && activeLyricIndex >= 0,
                        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    ) {
                        Button(
                            onClick = {
                                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                userHasScrolledAway = false
                                scope.launch {
                                    if (activeLyricIndex >= 0 && activeLyricIndex < lyricsLines.size) {
                                        val targetOffset = -10
                                        isAutoScrolling = true
                                        try {
                                            val initialVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLyricIndex }
                                            if (initialVisibleItem == null) {
                                                listState.scrollToItem(activeLyricIndex, targetOffset)
                                            } else {
                                                listState.animateScrollToItem(activeLyricIndex, targetOffset)
                                            }
                                        } finally {
                                            isAutoScrolling = false
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = onPrimaryColor
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                        ) {
                            Text(
                                text = "Sync",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
                LazyColumn(
                    state = queueListState,
                    userScrollEnabled = draggedId == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .pointerInput(localQueue) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startOffset ->
                                    val layoutInfo = queueListState.layoutInfo
                                    val clickedItem = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                        startOffset.y >= item.offset && startOffset.y <= (item.offset + item.size)
                                    }
                                    if (clickedItem != null) {
                                        draggedId = clickedItem.key as? String
                                        currentTouchY = startOffset.y
                                        dragOffset = 0f
                                        val originalIdx = localQueue.indexOfFirst { it.mediaId == draggedId }
                                        if (originalIdx != -1) {
                                            hoverIndex = originalIdx
                                            triggerVibration(120) // hardware-level long-press physical grab vibration
                                        }
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    currentTouchY = change.position.y
                                    updateHoverIndex()
                                },
                                onDragEnd = {
                                    val originalIdx = playQueue.indexOfFirst { it.mediaId == draggedId }
                                    if (originalIdx != -1 && hoverIndex != -1) {
                                        val targetIdx = if (hoverIndex < originalIdx) {
                                            hoverIndex
                                        } else if (hoverIndex > originalIdx) {
                                            hoverIndex - 1
                                        } else {
                                            originalIdx
                                        }
                                        if (targetIdx != originalIdx) {
                                            exoPlayer.moveMediaItem(originalIdx, targetIdx)
                                        }
                                    }
                                    draggedId = null
                                    hoverIndex = -1
                                    dragOffset = 0f
                                    currentTouchY = 0f
                                },
                                onDragCancel = {
                                    val originalIdx = playQueue.indexOfFirst { it.mediaId == draggedId }
                                    if (originalIdx != -1 && hoverIndex != -1) {
                                        val targetIdx = if (hoverIndex < originalIdx) {
                                            hoverIndex
                                        } else if (hoverIndex > originalIdx) {
                                            hoverIndex - 1
                                        } else {
                                            originalIdx
                                        }
                                        if (targetIdx != originalIdx) {
                                            exoPlayer.moveMediaItem(originalIdx, targetIdx)
                                        }
                                    }
                                    draggedId = null
                                    hoverIndex = -1
                                    dragOffset = 0f
                                    currentTouchY = 0f
                                }
                            )
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(localQueue, key = { index, item -> item.mediaId }) { index, item ->
                        val isCurrent = item.mediaId == (playQueue.getOrNull(currentTrackIndex)?.mediaId ?: "")
                        val isCommentary = item.mediaId.startsWith("commentary_")

                        val title = item.mediaMetadata.title?.toString() ?: "Unknown Song"
                        val artist = item.mediaMetadata.artist?.toString() ?: "Unknown Artist"
                        val artworkUri = item.mediaMetadata.artworkUri

                        val isDragged = item.mediaId == draggedId
                        val translationY = if (isDragged) dragOffset else 0f
                        val zIndexValue = if (isDragged) 10f else 1f

                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Highlightable line before the item
                            DropIndicatorLine(isHighlighted = draggedId != null && hoverIndex == index)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(zIndexValue)
                                    .graphicsLayer {
                                        this.translationY = translationY
                                        this.scaleX = if (isDragged) 1.05f else 1f
                                        this.scaleY = if (isDragged) 1.05f else 1f
                                        this.shadowElevation = if (isDragged) with(localDensity) { 8.dp.toPx() } else 0f
                                    }
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isCurrent) secondaryColor else if (isDragged) secondaryColor.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable(enabled = draggedId == null) {
                                        exoPlayer.seekToDefaultPosition(index)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {

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

                            if (index == localQueue.size - 1) {
                                DropIndicatorLine(isHighlighted = draggedId != null && hoverIndex == localQueue.size)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RadioEqualizerWaveform(isPlaying: Boolean) {
    val barCount = 21
    val maxDots = 7
    val bandLevels by VisualizerData.bandLevels.collectAsState()

    var peaks by remember { mutableStateOf(FloatArray(barCount) { 0f }) }
    var fallVelocity by remember { mutableStateOf(FloatArray(barCount) { 0f }) }
    var sensitivity by remember { mutableStateOf(1f) }

    // Tuned values
    val gravity = 0.020f
    val smoothingStrength = 1.60f

    val centerBias = remember {
        FloatArray(barCount) { i ->
            val center = (barCount - 1) / 2f
            val distanceRatio = kotlin.math.abs(i - center) / center
            val bias = kotlin.math.cos(distanceRatio * (Math.PI / 2)).toFloat()

            // Slight, natural center emphasis
            0.94f + 0.06f * bias
        }
    }

    LaunchedEffect(bandLevels, isPlaying) {
        if (isPlaying) {
            val frameMax = bandLevels.maxOrNull() ?: 0f

            if (frameMax * sensitivity > 0.96f) {
                sensitivity = (sensitivity - 0.010f).coerceAtLeast(0.45f)
            } else if (frameMax * sensitivity < 0.38f) {
                sensitivity = (sensitivity + 0.006f).coerceAtMost(2.8f)
            }

            val rawTargets = FloatArray(barCount)

            for (i in 0 until barCount) {
                val raw = bandLevels.getOrElse(i) { 0f }.coerceIn(0f, 1f)

                val expanded = Math.pow(raw.toDouble(), 0.58).toFloat()

                val withSensitivity =
                    (expanded * sensitivity).coerceIn(0f, 1f)

                rawTargets[i] = withSensitivity * maxDots
            }

            val smoothed = applyMonstercatSmoothing(
                rawTargets,
                strength = smoothingStrength
            )

            val shaped = FloatArray(barCount) { i ->
                (smoothed[i] * centerBias[i])
                    .coerceIn(0f, maxDots.toFloat())
            }

            val newPeaks = FloatArray(barCount)
            val newVelocity = fallVelocity.copyOf()

            for (i in shaped.indices) {
                val yValue = shaped[i]

                if (yValue >= peaks[i]) {
                    newPeaks[i] = yValue
                    newVelocity[i] = 0f
                } else {
                    newVelocity[i] += gravity
                    newPeaks[i] =
                        (peaks[i] - newVelocity[i]).coerceAtLeast(0f)
                }
            }

            peaks = newPeaks
            fallVelocity = newVelocity
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .height(86.dp)
            .padding(vertical = 12.dp)
    ) {
        peaks.forEach { mag ->
            val height = kotlin.math.round(mag).toInt().coerceIn(1, maxDots)

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

fun applyMonstercatSmoothing(
    values: FloatArray,
    strength: Float = 1.60f
): FloatArray {
    val result = values.copyOf()

    for (i in result.indices) {
        for (j in result.indices) {
            if (i != j) {
                val distance = kotlin.math.abs(i - j)

                val allowed =
                    result[i] /
                            Math.pow(
                                strength.toDouble(),
                                distance.toDouble()
                            ).toFloat()

                if (result[j] < allowed) {
                    result[j] = allowed
                }
            }
        }
    }

    return result
}

// These are the best knobs for tuning it further:

// gravity
// Higher = faster, livelier drop.
// Lower = smoother, floatier decay.

// smoothingStrength
// Lower = wider connected wave.
// Higher = more separated bars.

// maxDots
// 6 = tighter, cleaner.
// 7 = best balance.
// 8+ = more expressive but less compact.

// centerBias
// Stronger bias = more centered crest.
// Lower bias = more natural spectrum shape.

// Autosens thresholds
// Controls how aggressively quiet or loud tracks get corrected.

// FFT band mapping
// You can remap bins to emphasize bass, mids, or vocals differently.

// First-bin dampening
// Useful if the left-most columns still dominate too much.

// Row spacing / dot size / column spacing
// Purely visual but changes the perceived feel a lot.