package com.tunespark.music.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen

@Composable
fun AppearanceScreen(
    currentTheme: String,
    onThemeChange: (String) -> Unit,
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
        SettingsHeader(title = "Appearance", onBack = { onNavigate(AppScreen.SETTINGS) })

        Text(
            text = "Select theme",
            color = textColor,
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Light Theme
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onThemeChange("Light") }
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White, shape = CircleShape)
                        .border(1.5.dp, textColor.copy(alpha = 0.2f), CircleShape)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ThemeLabel(text = "Light", isSelected = currentTheme == "Light")
            }

            // Dark Theme
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onThemeChange("Dark") }
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.Black, shape = CircleShape)
                        .border(1.5.dp, textColor.copy(alpha = 0.2f), CircleShape)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ThemeLabel(text = "Dark", isSelected = currentTheme == "Dark")
            }

            // System Theme
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onThemeChange("System") }
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(1.5.dp, textColor, CircleShape)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            color = Color.White,
                            size = androidx.compose.ui.geometry.Size(size.width, size.height / 2f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                ThemeLabel(text = "System", isSelected = currentTheme == "System")
            }
        }
    }
}

@Composable
fun ThemeLabel(text: String, isSelected: Boolean) {
    val textColor = MaterialTheme.colorScheme.onBackground
    if (isSelected) {
        Box(
            modifier = Modifier
                .border(1.5.dp, textColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(text = text, color = textColor, fontSize = 14.sp)
        }
    } else {
        Text(text = text, color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
    }
}
