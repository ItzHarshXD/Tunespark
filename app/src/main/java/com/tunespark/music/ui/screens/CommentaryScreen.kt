package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    val view = LocalView.current
    val scrollState = rememberScrollState()
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var isCommentaryEnabled by remember { mutableStateOf(SessionManager.isCommentaryEnabled(context)) }

    var selectedCommentary by remember { mutableStateOf(SessionManager.getSelectedCommentary(context)) }
    val commentaryOptions = listOf(
        "Session opener",
        "Humour",
        "Briefing",
        "Music Context"
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
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(title = "Commentary", onBack = { onNavigate(AppScreen.SETTINGS) })

        // Commentary Enable Toggle at the top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .clickable {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val geminiKey = SessionManager.getGeminiApiKey(context)
                    if (geminiKey.isBlank()) {
                        Toast.makeText(
                            context,
                            "Please enter your Gemini API Key in AI Settings first!",
                            Toast.LENGTH_LONG
                        ).show()
                        isCommentaryEnabled = false
                    } else {
                        val newValue = !isCommentaryEnabled
                        isCommentaryEnabled = newValue
                        SessionManager.saveCommentaryEnabled(context, newValue)
                    }
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
                    text = "Enable AI Commentary",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Inject AI-generated radio host commentary tracks between songs.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            SimpleToggleSwitch(
                checked = isCommentaryEnabled,
                onCheckedChange = {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val geminiKey = SessionManager.getGeminiApiKey(context)
                    if (geminiKey.isBlank()) {
                        Toast.makeText(
                            context,
                            "Please enter your Gemini API Key in AI Settings first!",
                            Toast.LENGTH_LONG
                        ).show()
                        isCommentaryEnabled = false
                    } else {
                        isCommentaryEnabled = it
                        SessionManager.saveCommentaryEnabled(context, it)
                    }
                },
                backgroundColor = backgroundColor,
                textColor = textColor
            )
        }

        // Hide downstream settings if commentary is turned off
        if (isCommentaryEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Commentary Elements",
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            commentaryOptions.forEach { option ->
                val isChecked = selectedCommentary.contains(option)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .clickable {
                            val newSet = if (isChecked) {
                                selectedCommentary - option
                            } else {
                                selectedCommentary + option
                            }
                            selectedCommentary = newSet
                            SessionManager.saveSelectedCommentary(context, newSet)
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val checkboxShape = RoundedCornerShape(6.dp)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(if (isChecked) primaryColor else Color.Transparent, checkboxShape)
                            .border(2.dp, if (isChecked) primaryColor else Color.Gray, checkboxShape),
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
            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
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
            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
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

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = textColor.copy(alpha = 0.1f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(24.dp))

            // Custom Instructions
            Text(
                text = "Custom Instructions",
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Customize your AI Radio's personality, tone, humor, style, or specific guidelines (things you want/don't want). These preferences apply globally across all commentary elements.",
                color = Color.Gray,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            var customInstructionsText by remember { mutableStateOf(SessionManager.getCustomInstructions(context)) }

            OutlinedTextField(
                value = customInstructionsText,
                onValueChange = {
                    customInstructionsText = it
                    SessionManager.saveCustomInstructions(context, it)
                },
                placeholder = {
                    Text(
                        text = "e.g., 'Speak in hinglish language.'",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 200.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedBorderColor = primaryColor,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = primaryColor,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
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
