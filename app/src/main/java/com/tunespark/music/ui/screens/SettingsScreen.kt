package com.tunespark.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            "Notifications",
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
                        val destination = when (item) {
                            "Appearance" -> AppScreen.APPEARANCE
                            "Account" -> AppScreen.ACCOUNT
                            "AI and Voice" -> AppScreen.AI_VOICE
                            "Commentary" -> AppScreen.COMMENTARY
                            "Notifications" -> AppScreen.NOTIFICATIONS
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