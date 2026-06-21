package com.tunespark.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen

@Composable
fun CommentaryScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCommentary by remember { mutableStateOf(setOf("Weather updates", "Session opener", "Song intro", "Artist backstory", "Mood transition", "Skip reaction")) }
    val commentaryOptions = listOf(
        "Weather updates",
        "Session opener",
        "Song intro",
        "Artist backstory",
        "Mood transition",
        "Skip reaction"
    )

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(title = "Commentary", onBack = { onNavigate(AppScreen.SETTINGS) })

        commentaryOptions.forEach { option ->
            val isChecked = selectedCommentary.contains(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedCommentary = if (isChecked) {
                            selectedCommentary - option
                        } else {
                            selectedCommentary + option
                        }
                    }
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(if (isChecked) primaryColor else Color.Transparent, CircleShape)
                        .border(2.dp, if (isChecked) primaryColor else Color.Gray, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChecked) {
                        Text(
                            text = "✓",
                            color = onPrimaryColor,
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = option,
                    color = textColor,
                    fontSize = 22.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
                )
            }
        }
    }
}
