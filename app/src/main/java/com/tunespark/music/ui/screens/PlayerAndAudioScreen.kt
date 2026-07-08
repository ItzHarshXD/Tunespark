package com.tunespark.music.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager

@Composable
fun PlayerAndAudioScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var keepScreenOn by remember {
        mutableStateOf(SessionManager.getKeepScreenOn(context))
    }

    var showVisualizer by remember {
        mutableStateOf(SessionManager.getShowVisualizer(context))
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(
            title = "Player and Audio",
            onBack = { onNavigate(AppScreen.SETTINGS) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    val newValue = !keepScreenOn
                    keepScreenOn = newValue
                    SessionManager.saveKeepScreenOn(context, newValue)
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Keep screen ON when expanded",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "The screen will remain awake when the Radio player screen is opened.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            SimpleToggleSwitch(
                checked = keepScreenOn,
                onCheckedChange = {
                    keepScreenOn = it
                    SessionManager.saveKeepScreenOn(context, it)
                },
                backgroundColor = backgroundColor,
                textColor = textColor
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    val newValue = !showVisualizer
                    showVisualizer = newValue
                    SessionManager.saveShowVisualizer(context, newValue)
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Display visualizer",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Show the real-time beat visualizer on the Radio screen.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            SimpleToggleSwitch(
                checked = showVisualizer,
                onCheckedChange = {
                    showVisualizer = it
                    SessionManager.saveShowVisualizer(context, it)
                },
                backgroundColor = backgroundColor,
                textColor = textColor
            )
        }
    }
}

@Composable
private fun SimpleToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backgroundColor: Color,
    textColor: Color
) {
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
            .clickable { onCheckedChange(!checked) },
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
