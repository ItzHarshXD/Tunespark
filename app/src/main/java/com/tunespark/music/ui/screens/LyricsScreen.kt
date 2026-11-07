package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager
import com.tunespark.music.lyrics.LyricsProviderRegistry

/**
 * Lyrics settings: enable/disable each source and change the order in which
 * they are tried. All sources are enabled by default.
 */
@Composable
fun LyricsScreen(
    onNavigate: (AppScreen) -> Unit,
    onSettingsChanged: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var order by remember { mutableStateOf(SessionManager.getLyricsProviderOrder(context)) }
    var enabled by remember { mutableStateOf(SessionManager.getEnabledLyricsProviders(context)) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    fun feedback() {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun moveProvider(from: Int, to: Int) {
        if (to !in order.indices) return
        val updated = order.toMutableList()
        val item = updated.removeAt(from)
        updated.add(to, item)
        order = updated
        SessionManager.saveLyricsProviderOrder(context, updated)
        feedback()
        onSettingsChanged()
    }

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        val updated = enabled.toMutableSet()
        if (isEnabled) updated.add(id) else updated.remove(id)
        enabled = updated
        SessionManager.saveEnabledLyricsProviders(context, updated)
        feedback()
        onSettingsChanged()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(
            title = "Lyrics",
            onBack = { onNavigate(AppScreen.SETTINGS) }
        )

        Text(
            text = "Providers are tried from top to bottom. If a provider returns plain " +
                "lyrics, Tunespark keeps looking for a synced version before falling back.",
            color = Color.Gray,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (enabled.isEmpty()) {
            Text(
                text = "All providers are off. No lyrics will be shown.",
                color = Color(0xFFFF6B6B),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        order.forEachIndexed { index, id ->
            val info = LyricsProviderRegistry.info(id) ?: return@forEachIndexed
            val isEnabled = id in enabled

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { setProviderEnabled(id, !isEnabled) }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "${index + 1}. ${info.displayName}",
                        color = if (isEnabled) textColor else Color.Gray,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = info.description,
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = { moveProvider(index, index - 1) },
                        enabled = index > 0
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowUp,
                            contentDescription = "Move ${info.displayName} up",
                            tint = if (index > 0) textColor else Color.Gray.copy(alpha = 0.35f)
                        )
                    }
                    IconButton(
                        onClick = { moveProvider(index, index + 1) },
                        enabled = index < order.lastIndex
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Move ${info.displayName} down",
                            tint = if (index < order.lastIndex) textColor else Color.Gray.copy(alpha = 0.35f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                LyricsToggleSwitch(
                    checked = isEnabled,
                    onCheckedChange = { setProviderEnabled(id, it) },
                    backgroundColor = backgroundColor,
                    textColor = textColor
                )
            }

            if (index != order.lastIndex) {
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.25f))
            }
        }

        Spacer(modifier = Modifier.height(110.dp))
    }
}

@Composable
private fun LyricsToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    textColor: Color
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val trackWidth = 52.dp
    val trackHeight = 32.dp

    val thumbSize by animateDpAsState(
        targetValue = if (checked) 24.dp else 20.dp,
        label = "lyricsThumbSize"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 6.dp,
        label = "lyricsThumbOffset"
    )

    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(100))
            .background(if (checked) textColor else backgroundColor)
            .border(
                width = 1.5.dp,
                color = textColor,
                shape = RoundedCornerShape(100)
            )
            .clickable {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(backgroundColor)
                .then(
                    if (!checked) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = textColor,
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
        )
    }
}
