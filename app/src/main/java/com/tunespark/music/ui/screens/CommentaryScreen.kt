package com.tunespark.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager

@Composable
fun CommentaryScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var selectedCommentary by remember { mutableStateOf(setOf("Weather updates", "Session opener", "Song intro", "Artist backstory", "Mood transition", "Skip reaction")) }
    val commentaryOptions = listOf(
        "Weather updates",
        "Session opener",
        "Song intro",
        "Artist backstory",
        "Mood transition",
        "Skip reaction"
    )

    var commentaryFrequency by remember { mutableStateOf(SessionManager.getCommentaryFrequency(context)) }
    var commentaryLength by remember { mutableStateOf(SessionManager.getCommentaryLength(context)) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    val blockSize = Math.round(commentaryFrequency * 7).toInt() + 1
    val frequencyDetail = if (blockSize == 1) "Every song" else "Every $blockSize songs"

    val lengthDetail = when {
        commentaryLength < 0.25f -> "Short (15-20 words, quick transitions)"
        commentaryLength < 0.5f -> "Medium (25-35 words, balanced radio style)"
        commentaryLength < 0.75f -> "Long & Detailed (50-70 words, deep host commentary)"
        else -> "Mega Host & Deep Story (~200 words, detailed podcast-style showcase)"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp)
            .verticalScroll(scrollState),
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
                    .padding(vertical = 12.dp),
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
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = option,
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Commentary Frequency Slider
        Text(
            text = "Commentary Frequency",
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Inject commentary: $frequencyDetail",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Slider(
            value = commentaryFrequency,
            onValueChange = {
                commentaryFrequency = it
                SessionManager.saveCommentaryFrequency(context, it)
            },
            steps = 6,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = textColor,
                activeTrackColor = textColor,
                inactiveTrackColor = Color.Gray
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Commentary Length Slider
        Text(
            text = "Commentary Length",
            color = textColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            text = "Duration style: $lengthDetail",
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Slider(
            value = commentaryLength,
            onValueChange = {
                commentaryLength = it
                SessionManager.saveCommentaryLength(context, it)
            },
            steps = 2,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = textColor,
                activeTrackColor = textColor,
                inactiveTrackColor = Color.Gray
            )
        )
    }
}
