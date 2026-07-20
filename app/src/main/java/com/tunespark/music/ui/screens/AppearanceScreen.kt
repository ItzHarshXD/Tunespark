package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

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
            title = "Appearance",
            onBack = { onNavigate(AppScreen.SETTINGS) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Select theme",
            color = textColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            ThemeOption(
                modifier = Modifier.weight(1f),
                themeName = "Light",
                isSelected = currentTheme == "Light",
                onClick = { 
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onThemeChange("Light") 
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.White, shape = CircleShape)
                        .border(1.5.dp, Color.Black, CircleShape)
                )
            }

            ThemeOption(
                modifier = Modifier.weight(1f),
                themeName = "Dark",
                isSelected = currentTheme == "Dark",
                onClick = { 
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onThemeChange("Dark") 
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color.Black, shape = CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                )
            }

            ThemeOption(
                modifier = Modifier.weight(1f),
                themeName = "System",
                isSelected = currentTheme == "System",
                onClick = { 
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onThemeChange("System") 
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(1.5.dp, textColor, CircleShape)
                        .rotate(-45f)
                ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color.White,
                    size = androidx.compose.ui.geometry.Size(size.width, size.height / 2f)
                )
            }
        }
    }
    
    Spacer(modifier = Modifier.height(110.dp))
}
    }
}

@Composable
private fun ThemeOption(
    modifier: Modifier = Modifier,
    themeName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            preview()
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .height(32.dp),
            contentAlignment = Alignment.Center
        ) {
            ThemeLabel(
                text = themeName,
                isSelected = isSelected
            )
        }
    }
}

@Composable
fun ThemeLabel(text: String, isSelected: Boolean) {
    val textColor = MaterialTheme.colorScheme.onBackground

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 72.dp)
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, textColor, RoundedCornerShape(60.dp))
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp
        )
    }
}
