package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.tunespark.music.rss.RssRepository

@Composable
fun DiscoverFeedScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val view = LocalView.current

    val playSoundAndHaptic = {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // Load categories persistently from SessionManager
    val interests = remember {
        mutableStateListOf<Pair<String, Boolean>>().apply {
            addAll(SessionManager.getDiscoverCategories(context))
        }
    }

    // Master toggle state - ON by default, but respects the user's saved preference
    var isDiscoverEnabled by remember { mutableStateOf(SessionManager.isDiscoverFeedEnabled(context)) }

    // Auto-sync helper: if all interests are off, master toggle turns off automatically.
    // If at least one interest is on, master toggle turns on automatically.
    // This is ONLY called when the user actually toggles an interest, NOT on initial load,
    // so the user's explicit master toggle choice is preserved across app restarts.
    fun syncMasterToggleWithInterests() {
        val anyInterestSelected = interests.any { it.second }
        if (anyInterestSelected != isDiscoverEnabled) {
            isDiscoverEnabled = anyInterestSelected
            SessionManager.saveDiscoverFeedEnabled(context, anyInterestSelected)
        }
    }

    BackHandler {
        onNavigate(AppScreen.SETTINGS)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Header using the shared SettingsHeader
        SettingsHeader(
            title = "Discover feed",
            onBack = {
                onNavigate(AppScreen.SETTINGS)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Manage your news preferences. Your selections automatically personalize your Discover RSS headline feeds.",
            fontSize = 15.sp,
            color = textColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Master toggle row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(textColor.copy(alpha = 0.05f))
                .clickable {
                    playSoundAndHaptic()
                    val newValue = !isDiscoverEnabled
                    isDiscoverEnabled = newValue
                    SessionManager.saveDiscoverFeedEnabled(context, newValue)
                    RssRepository.clearCache(context)
                }
                .padding(vertical = 16.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Discover feed",
                    fontSize = 18.sp,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isDiscoverEnabled) "Enabled" else "Disabled",
                    fontSize = 13.sp,
                    color = textColor.copy(alpha = 0.55f)
                )
            }

            SimpleToggleSwitch(
                checked = isDiscoverEnabled,
                onCheckedChange = { newValue ->
                    playSoundAndHaptic()
                    isDiscoverEnabled = newValue
                    SessionManager.saveDiscoverFeedEnabled(context, newValue)
                    RssRepository.clearCache(context)
                },
                backgroundColor = backgroundColor,
                textColor = textColor
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(textColor.copy(alpha = 0.1f))
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Interest switches list - greyed out when master toggle is off
        interests.forEachIndexed { index, (interest, isSelected) ->
            val isEnabled = isDiscoverEnabled
            val itemAlpha = if (isEnabled) 1f else 0.35f

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = isEnabled) {
                        playSoundAndHaptic()
                        val newValue = !isSelected
                        interests[index] = interest to newValue
                        SessionManager.saveDiscoverCategory(context, interest, newValue)
                        RssRepository.clearCache(context)
                        syncMasterToggleWithInterests()
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = interest,
                    fontSize = 18.sp,
                    color = textColor.copy(alpha = itemAlpha),
                    fontWeight = FontWeight.Medium
                )

                SimpleToggleSwitch(
                    checked = isSelected,
                    onCheckedChange = { newValue ->
                        if (isEnabled) {
                            playSoundAndHaptic()
                            interests[index] = interest to newValue
                            SessionManager.saveDiscoverCategory(context, interest, newValue)
                            RssRepository.clearCache(context)
                            syncMasterToggleWithInterests()
                        }
                    },
                    backgroundColor = backgroundColor,
                    textColor = textColor,
                    enabled = isEnabled
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(110.dp))
    }
}

@Composable
private fun SimpleToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    textColor: Color,
    enabled: Boolean = true
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
        label = "thumbSize"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 6.dp,
        label = "thumbOffset"
    )

    val alpha = if (enabled) 1f else 0.35f

    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(100))
            .background(if (checked) textColor.copy(alpha = alpha) else backgroundColor.copy(alpha = alpha))
            .border(
                width = 1.5.dp,
                color = textColor.copy(alpha = alpha),
                shape = RoundedCornerShape(100)
            )
            .clickable(enabled = enabled) {
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
                .background(backgroundColor.copy(alpha = alpha))
                .then(
                    if (!checked) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = textColor.copy(alpha = alpha),
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
        )
    }
}