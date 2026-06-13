package com.tunespark.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen

@Composable
fun SettingsScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            IconButton(
                onClick = { onNavigate(AppScreen.HOME) },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color(0xFFFF0000), shape = CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
        }

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
                color = Color.White,
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
