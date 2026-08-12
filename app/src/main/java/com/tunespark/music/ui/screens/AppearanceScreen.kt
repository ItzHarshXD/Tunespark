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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.tunespark.music.SessionManager

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

    val scrollState = rememberScrollState()

    var showTimeWeather by remember {
        mutableStateOf(SessionManager.getShowTimeWeather(context))
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(scrollState)
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
}

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    val newValue = !showTimeWeather
                    showTimeWeather = newValue
                    SessionManager.saveShowTimeWeather(context, newValue)
                }
                .padding(
                    horizontal = 10.dp,
                    vertical = 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Show Time and Weather on Home Screen",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Toggle the visibility of the clock and weather widget on the main home screen.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            SimpleToggleSwitch(
                checked = showTimeWeather,
                onCheckedChange = {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                    showTimeWeather = it
                    SessionManager.saveShowTimeWeather(context, it)
                },
                backgroundColor = backgroundColor,
                textColor = textColor
            )
        }
    
        Spacer(modifier = Modifier.height(110.dp))
    }
}

@Composable
private fun SimpleToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    textColor: Color
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

    Box(
        modifier = Modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(100))
            .background(if (checked) textColor else backgroundColor)
            .border(
                width = 1.5.dp,
                color = textColor,
                shape = RoundedCornerShape(100)
            )
            .clickable { 
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
                .background(backgroundColor)
                .then(
                    if (!checked) {
                        Modifier.border(
                            width = 1.5.dp,
                            color = textColor,
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
        )
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
