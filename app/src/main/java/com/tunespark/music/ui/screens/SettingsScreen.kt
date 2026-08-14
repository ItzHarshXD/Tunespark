package com.tunespark.music.ui.screens
import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(
            title = "Settings",
            onBack = { onNavigate(AppScreen.HOME) }
        )

        val settingsItems = listOf(
            "Appearance" to Icons.Outlined.Palette,
            "Account" to Icons.Outlined.AccountCircle,
            "Discover feed" to Icons.AutoMirrored.Outlined.List,
            "AI and Voice" to Icons.Outlined.Mic,
            "Commentary" to Icons.Outlined.ChatBubbleOutline,
            "Player and Audio" to Icons.Outlined.MusicNote,
            "Location" to Icons.Outlined.LocationOn,
            "Updates" to Icons.Outlined.Info
        )

        settingsItems.forEach { (item, icon) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clickable {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        val destination = when (item) {
                            "Appearance" -> AppScreen.APPEARANCE
                            "Account" -> AppScreen.ACCOUNT
                            "Discover feed" -> AppScreen.DISCOVER_FEED
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
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = item,
                    tint = textColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = item,
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
        
        Spacer(modifier = Modifier.height(110.dp))
    }
}
