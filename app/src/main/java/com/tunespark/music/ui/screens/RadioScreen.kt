package com.tunespark.music.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.AutoAwesome
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

/**
 * Briefing article metadata extracted from a commentary MediaItem.
 * Shown as an "Open Article" card while the briefing commentary plays.
 */
data class BriefingArticleUi(
    val url: String,
    val title: String,
    val source: String,
    val thumbnail: String
)

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
    val isCommentaryEnabled = remember(context) { com.tunespark.music.SessionManager.isCommentaryEnabled(context) }
    val isMusicContextChecked = remember(context) { "Music Context" in com.tunespark.music.SessionManager.getSelectedCommentary(context) }

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
    var activeTab by remember(context) { mutableStateOf(com.tunespark.music.SessionManager.getRadioLayoutState(context)) }

    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            val degreesPerMs = 360f / 16000f // smooth rotation, 1 rotation per 16 seconds (slower and more relaxing)
            var lastTime = withFrameMillis { it }
            while (true) {
                val frameTime = withFrameMillis { it }
                val elapsed = frameTime - lastTime
                lastTime = frameTime
                val delta = elapsed * degreesPerMs
                val nextRotation = (rotation.value + delta) % 360f
                rotation.snapTo(nextRotation)
            }
        }
    }

    val swipeThresholdPx = remember(localDensity) { with(localDensity) { 80.dp.toPx() } }
    val swipeLimitPx = remember(localDensity) { with(localDensity) { 360.dp.toPx() } }
    val swipeOffsetX = remember { Animatable(0f) }
    var isSwiping by remember { mutableStateOf(false) }

    LaunchedEffect(currentTrackIndex) {
        if (!isSwiping) {
            swipeOffsetX.snapTo(swipeLimitPx / 2f)
            swipeOffsetX.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    }

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    val playPauseExtraWidth = remember { Animatable(0f) }
    val skipExtraWidth = remember { Animatable(0f) }
    val artworkScale = remember { Animatable(1f) }
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

    // Detect if the current media item is a briefing commentary. The briefing
    // article metadata is stored in the MediaItem's MediaMetadata fields:
    //   subtitle    -> article URL
    //   albumTitle  -> article headline
    //   albumArtist -> article source
    //   artworkUri  -> article thumbnail
    val briefingArticle = remember(exoPlayer, currentTrackIndex) {
        val item = exoPlayer.currentMediaItem ?: return@remember null
        if (!item.mediaId.startsWith("commentary_")) return@remember null
        val url = item.mediaMetadata.subtitle?.toString()?.takeIf { it.isNotBlank() } ?: return@remember null
        val title = item.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: return@remember null
        val source = item.mediaMetadata.albumArtist?.toString()?.takeIf { it.isNotBlank() } ?: ""
        val thumbnail = item.mediaMetadata.artworkUri?.toString() ?: ""
        BriefingArticleUi(
            url = url,
            title = title,
            source = source,
            thumbnail = thumbnail
        )
    }

    val isDarkTheme = isSystemInDarkTheme()
    val weatherBgColor = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
    val weatherTextColor = if (isDarkTheme) Color.White else Color.Black
    val weatherBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.06f)

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
                        .background(weatherBgColor)
                        .border(1.dp, weatherBorderColor, CircleShape)
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
                        tint = weatherTextColor,
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
                            val playPauseShape = RoundedCornerShape(
                                topStart = 28.dp,
                                bottomStart = 28.dp,
                                topEnd = 8.dp,
                                bottomEnd = 8.dp
                            )
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .width(80.dp + playPauseExtraWidth.value.coerceAtLeast(0f).dp)
                                    .align(Alignment.CenterEnd)
                                    .clip(playPauseShape)
                                    .background(weatherBgColor)
                                    .border(1.dp, weatherBorderColor, playPauseShape)
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
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = weatherTextColor,
                                    modifier = Modifier.size(26.dp) // Adjusted visual balance
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
                            val skipShape = RoundedCornerShape(
                                topStart = 8.dp,
                                bottomStart = 8.dp,
                                topEnd = 28.dp,
                                bottomEnd = 28.dp
                            )
                            Box(
                                modifier = Modifier
                                    .height(56.dp)
                                    .width(80.dp + skipExtraWidth.value.coerceAtLeast(0f).dp)
                                    .align(Alignment.CenterStart)
                                    .clip(skipShape)
                                    .background(weatherBgColor)
                                    .border(1.dp, weatherBorderColor, skipShape)
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
                                    imageVector = Icons.Rounded.SkipNext,
                                    contentDescription = "Skip Next",
                                    tint = weatherTextColor,
                                    modifier = Modifier.size(26.dp) // Matched size for perfect balance
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

            if (activeTab == "none") {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (briefingArticle != null) {
                        BriefingArticleCard(
                            article = briefingArticle,
                            textColor = textColor,
                            primaryColor = primaryColor,
                            onPrimaryColor = onPrimaryColor,
                            secondaryColor = secondaryColor,
                            onOpenArticle = {
                                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(briefingArticle.url))
                                context.startActivity(intent)
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Stationary (Unrotated) wrapper Box to catch clean screen gestures and clicks
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .pointerInput(hasNextTrack, hasPreviousTrack, swipeThresholdPx, swipeLimitPx) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        isSwiping = true
                                    },
                                    onDragEnd = {
                                        scope.launch {
                                            if (swipeOffsetX.value < -swipeThresholdPx && hasNextTrack) {
                                                // Swipe Left -> Next song
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                swipeOffsetX.animateTo(
                                                    targetValue = -swipeLimitPx,
                                                    animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
                                                )
                                                exoPlayer.seekToNext()
                                                swipeOffsetX.snapTo(swipeLimitPx)
                                                swipeOffsetX.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow
                                                    )
                                                )
                                            } else if (swipeOffsetX.value > swipeThresholdPx && hasPreviousTrack) {
                                                // Swipe Right -> Previous song
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                swipeOffsetX.animateTo(
                                                    targetValue = swipeLimitPx,
                                                    animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing)
                                                )
                                                exoPlayer.seekToPrevious()
                                                swipeOffsetX.snapTo(-swipeLimitPx)
                                                swipeOffsetX.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = Spring.StiffnessMediumLow
                                                    )
                                                )
                                            } else {
                                                // Bounce back
                                                swipeOffsetX.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                )
                                            }
                                            isSwiping = false
                                        }
                                    },
                                    onDragCancel = {
                                        scope.launch {
                                            swipeOffsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                            isSwiping = false
                                        }
                                    },
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        scope.launch {
                                            swipeOffsetX.snapTo(swipeOffsetX.value + dragAmount)
                                        }
                                    }
                                )
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                scope.launch {
                                    artworkScale.animateTo(
                                        targetValue = 0.88f,
                                        animationSpec = tween(durationMillis = 80, easing = LinearEasing)
                                    )
                                    artworkScale.animateTo(
                                        targetValue = 1f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Sliding and Rotating Vinyl Disc body
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = swipeOffsetX.value
                                    rotationZ = rotation.value
                                }
                                .clip(CircleShape)
                                .background(Color(0xFF181818)), // Vinyl body
                            contentAlignment = Alignment.Center
                        ) {
                            // Concentric grooves
                            Box(
                                modifier = Modifier
                                    .size(210.dp)
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            )
                            Box(
                                modifier = Modifier
                                    .size(170.dp)
                                    .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            )

                            // Song Artwork
                            val overlayAlpha by animateFloatAsState(
                                targetValue = if (artworkScale.value < 0.95f) 0.3f else 0f,
                                animationSpec = tween(durationMillis = 100),
                                label = "ArtworkTapOverlay"
                            )
                            Box(
                                modifier = Modifier
                                    .size(110.dp)
                                    .graphicsLayer {
                                        scaleX = artworkScale.value
                                        scaleY = artworkScale.value
                                    }
                                    .clip(CircleShape)
                                    .background(secondaryColor)
                            ) {
                                if (!currentSongArtwork.isNullOrEmpty()) {
                                    AsyncImage(
                                        model = currentSongArtwork,
                                        contentDescription = "Vinyl Artwork",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🎵", fontSize = 32.sp)
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = overlayAlpha))
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Center-aligned title and artist
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = currentSongTitle,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            textAlign = TextAlign.Center,
                            lineHeight = 28.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (currentSongArtist.isNotEmpty()) currentSongArtist else "TuneSpark",
                            fontSize = 17.sp,
                            color = textColor.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        
                        if (isCommentaryEnabled && isMusicContextChecked) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(secondaryColor)
                                    .clickable {
                                        val viewLocal = view
                                        val audioManagerLocal = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                                        audioManagerLocal?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 1f)
                                        viewLocal.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                                        val plainTextLyrics = lyricsLines.joinToString("\n") { it.text }
                                        com.tunespark.music.PlaybackService.instance?.generateAndQueueMusicContextForCurrentSong(plainTextLyrics)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Generate Commentary",
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                // Music Progress Slider and Times
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

                // Tab Selector
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
                                val newTab = if (activeTab == "lyrics") "none" else "lyrics"
                                activeTab = newTab
                                com.tunespark.music.SessionManager.saveRadioLayoutState(context, newTab)
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
                                val newTab = if (activeTab == "queue") "none" else "queue"
                                activeTab = newTab
                                com.tunespark.music.SessionManager.saveRadioLayoutState(context, newTab)
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
            } else {
                Spacer(modifier = Modifier.height(20.dp))

                // 3. Current Song Details Row — replaced by the article card when a
                // briefing commentary is playing. The slider, tabs, and content
                // area below remain visible as usual.
                if (briefingArticle != null) {
                    BriefingArticleCard(
                        article = briefingArticle,
                        textColor = textColor,
                        primaryColor = primaryColor,
                        onPrimaryColor = onPrimaryColor,
                        secondaryColor = secondaryColor,
                        onOpenArticle = {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(briefingArticle.url))
                            context.startActivity(intent)
                        }
                    )
                } else {
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

                        if (isCommentaryEnabled && isMusicContextChecked) {
                            Spacer(modifier = Modifier.width(12.dp))
                            IconButton(
                                onClick = {
                                    val viewLocal = view
                                    val audioManagerLocal = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                                    audioManagerLocal?.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 1f)
                                    viewLocal.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                                    val plainTextLyrics = lyricsLines.joinToString("\n") { it.text }
                                    com.tunespark.music.PlaybackService.instance?.generateAndQueueMusicContextForCurrentSong(plainTextLyrics)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(secondaryColor)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Generate Commentary",
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
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
                                val newTab = if (activeTab == "lyrics") "none" else "lyrics"
                                activeTab = newTab
                                com.tunespark.music.SessionManager.saveRadioLayoutState(context, newTab)
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
                                val newTab = if (activeTab == "queue") "none" else "queue"
                                activeTab = newTab
                                com.tunespark.music.SessionManager.saveRadioLayoutState(context, newTab)
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
            } // closes the else block of activeTab == "none"
        }
    }
}

@Composable
private fun BriefingArticleCard(
    article: BriefingArticleUi,
    textColor: Color,
    primaryColor: Color,
    onPrimaryColor: Color,
    secondaryColor: Color,
    onOpenArticle: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Compact horizontal card that takes the place of the song details row
    // (64dp thumbnail + title/artist subtitle + "Open Article" affordance).
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(secondaryColor)
            .clickable(onClick = onOpenArticle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Article thumbnail
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Gray.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            if (article.thumbnail.isNotEmpty()) {
                AsyncImage(
                    model = article.thumbnail,
                    contentDescription = article.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("📰", fontSize = 24.sp)
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Headline + Source
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = article.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = article.source.ifEmpty { "News Briefing" },
                fontSize = 13.sp,
                color = textColor.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // "Open Article" tap target
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(primaryColor)
                .clickable(onClick = onOpenArticle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Open Article",
                tint = onPrimaryColor,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        rotationZ = 180f // point right
                    }
            )
        }
    }
}

