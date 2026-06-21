package com.tunespark.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
fun UpdatesScreen(
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
        SettingsHeader(title = "Updates", onBack = { onNavigate(AppScreen.SETTINGS) })

        Text(
            text = "App version",
            color = textColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Normal
        )

        Text(
            text = "v1.24.2",
            color = textColor,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Check for updates",
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { /* Check logic */ }
                        .padding(8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "App is up to date",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
    }
}
