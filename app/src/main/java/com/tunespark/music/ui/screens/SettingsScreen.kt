package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen

@Composable
fun SettingsScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(
            title = "Settings",
            onBack = { onNavigate(AppScreen.HOME) }
        )

        val settingsItems = listOf(
            "Appearance",
            "Account",
            "AI and Voice",
            "Commentary",
            "Player and Audio",
            "Location",
            "Updates"
        )

        settingsItems.forEach { item ->
            Text(
                text = item,
                color = textColor,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val destination = when (item) {
                            "Appearance" -> AppScreen.APPEARANCE
                            "Account" -> AppScreen.ACCOUNT
                            "AI and Voice" -> AppScreen.AI_VOICE
                            "Commentary" -> AppScreen.COMMENTARY
                            "Player and Audio" -> AppScreen.PLAYER_AUDIO
                            "Location" -> AppScreen.LOCATION
                            "Updates" -> AppScreen.UPDATES
                            else -> null
                        }
                        if (destination != null) {
                            onNavigate(destination)
                        }
                    }
                    .padding(vertical = 16.dp)
            )
        }
    }
}
