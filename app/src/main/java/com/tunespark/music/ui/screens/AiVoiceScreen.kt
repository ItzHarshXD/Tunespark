package com.tunespark.music.ui.screens

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen
import com.tunespark.music.SessionManager
import com.tunespark.music.TtsService
import kotlinx.coroutines.launch

@Composable
fun AiVoiceScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var activeAiTab by remember { mutableStateOf("Gemini") }
    var geminiApiKey by remember { mutableStateOf(SessionManager.getGeminiApiKey(context)) }
    var elevenLabsApiKey by remember { mutableStateOf(SessionManager.getElevenLabsApiKey(context)) }
    var elevenLabsVoiceId by remember { mutableStateOf(SessionManager.getElevenLabsVoiceId(context)) }
    var commentaryFrequency by remember { mutableStateOf(SessionManager.getCommentaryFrequency(context)) }
    var isGenerating by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Clean up MediaPlayer on dispose
    DisposableEffect(Unit) {
        onDispose {
            activePlayer?.release()
        }
    }

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
        SettingsHeader(title = "AI and Voice", onBack = { onNavigate(AppScreen.SETTINGS) })

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gemini Tab Button
            Button(
                onClick = { activeAiTab = "Gemini" },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (activeAiTab != "Gemini") Modifier.border(1.dp, textColor, RoundedCornerShape(24.dp))
                        else Modifier
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeAiTab == "Gemini") Color(0xFFFF0000) else Color.Transparent,
                    contentColor = if (activeAiTab == "Gemini") Color.White else textColor
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Gemini", fontWeight = FontWeight.Bold)
            }

            // ElevenLabs Tab Button
            Button(
                onClick = { activeAiTab = "ElevenLabs" },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (activeAiTab != "ElevenLabs") Modifier.border(1.dp, textColor, RoundedCornerShape(24.dp))
                        else Modifier
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeAiTab == "ElevenLabs") Color(0xFFFF0000) else Color.Transparent,
                    contentColor = if (activeAiTab == "ElevenLabs") Color.White else textColor
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("ElevenLabs", fontWeight = FontWeight.Bold)
            }
        }

        if (activeAiTab == "Gemini") {
            // Gemini Content
            Text("How to get your free key:", color = textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))

            val geminiSteps = listOf(
                "1. Go to aistudio.google.com/api-keys",
                "2. Tap “Get API key”",
                "3. Tap “Create API key”",
                "4. Copy it and paste it here"
            )
            geminiSteps.forEach { step ->
                if (step.contains("aistudio.google.com/api-keys")) {
                    val urlStart = step.indexOf("aistudio.google.com/api-keys")
                    Text(
                        text = buildAnnotatedString {
                            append(step.substring(0, urlStart))
                            withStyle(style = SpanStyle(color = Color(0xFFFF0000))) {
                                append("aistudio.google.com/api-keys")
                            }
                        },
                        color = textColor,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Text(text = step, color = textColor, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Text Field / Paste Box
            OutlinedTextField(
                value = geminiApiKey,
                onValueChange = {
                    geminiApiKey = it
                    SessionManager.saveGeminiApiKey(context, it)
                },
                placeholder = { Text("Paste your Gemini API key here", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedBorderColor = textColor,
                    focusedBorderColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedTextColor = textColor
                ),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { text ->
                            geminiApiKey = text
                            SessionManager.saveGeminiApiKey(context, text)
                            Toast.makeText(context, "API Key Pasted", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("📋", fontSize = 20.sp)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // A little check box on the Gemini tab for TTS service
            var isGeminiTts by remember { mutableStateOf(SessionManager.getActiveTtsProvider(context) == "Gemini") }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isGeminiTts = !isGeminiTts
                        SessionManager.saveActiveTtsProvider(context, if (isGeminiTts) "Gemini" else "ElevenLabs")
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isGeminiTts,
                    onCheckedChange = { checked ->
                        isGeminiTts = checked
                        SessionManager.saveActiveTtsProvider(context, if (checked) "Gemini" else "ElevenLabs")
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFFFF0000),
                        uncheckedColor = textColor,
                        checkmarkColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Use Gemini for TTS (otherwise ElevenLabs will be used)",
                    color = textColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // ElevenLabs Content
            Text("How to get your free key:", color = textColor, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))

            val elevenSteps = listOf(
                "1. Go to elevenlabs.io/app/developers/api-keys",
                "2. Sign in or create an account",
                "3. Create or copy your API key",
                "4. Paste it here"
            )
            elevenSteps.forEach { step ->
                if (step.contains("elevenlabs.io/app/developers/api-keys")) {
                    val urlStart = step.indexOf("elevenlabs.io/app/developers/api-keys")
                    Text(
                        text = buildAnnotatedString {
                            append(step.substring(0, urlStart))
                            withStyle(style = SpanStyle(color = Color(0xFFFF0000))) {
                                append("elevenlabs.io/app/developers/api-keys")
                            }
                        },
                        color = textColor,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else {
                    Text(text = step, color = textColor, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Text Field / Paste Box
            OutlinedTextField(
                value = elevenLabsApiKey,
                onValueChange = {
                    elevenLabsApiKey = it
                    SessionManager.saveElevenLabsApiKey(context, it)
                },
                placeholder = { Text("Paste your ElevenLabs API key here", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedBorderColor = textColor,
                    focusedBorderColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedTextColor = textColor
                ),
                trailingIcon = {
                    IconButton(onClick = {
                        clipboardManager.getText()?.text?.let { text ->
                            val trimmed = text.trim()
                            elevenLabsApiKey = trimmed
                            SessionManager.saveElevenLabsApiKey(context, trimmed)
                            Toast.makeText(context, "API Key Pasted", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("📋", fontSize = 20.sp)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Voice ID Input Box
            OutlinedTextField(
                value = elevenLabsVoiceId,
                onValueChange = {
                    elevenLabsVoiceId = it
                    SessionManager.saveElevenLabsVoiceId(context, it)
                },
                placeholder = { Text("Enter Voice ID (e.g. EXAVITQu4vr4xnSDxMaL)", color = Color.Gray) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedBorderColor = textColor,
                    focusedBorderColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedTextColor = textColor
                ),
                label = { Text("Voice ID", color = textColor) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Preview voice button
        Button(
            onClick = {
                val currentKey = if (activeAiTab == "Gemini") geminiApiKey else elevenLabsApiKey
                if (currentKey.isBlank()) {
                    Toast.makeText(context, "Please enter an API Key first", Toast.LENGTH_LONG).show()
                    return@Button
                }
                isGenerating = true
                scope.launch {
                    try {
                        val previewText = "Hello! This is a preview of the TuneSpark AI voice."
                        val file = if (activeAiTab == "Gemini") {
                            TtsService.generateGeminiTts(context, currentKey, previewText)
                        } else {
                            TtsService.generateElevenLabsTts(context, currentKey, previewText, elevenLabsVoiceId)
                        }

                        activePlayer?.release()
                        val mp = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            prepare()
                            start()
                        }
                        activePlayer = mp
                        Toast.makeText(context, "Playing voice preview", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        isGenerating = false
                    }
                }
            },
            enabled = !isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryColor,
                contentColor = onPrimaryColor,
                disabledContainerColor = Color.Gray,
                disabledContentColor = Color.White
            ),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text(
                text = if (isGenerating) "Generating Preview..." else "Preview voice",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (isGenerating) Color.White else onPrimaryColor
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Commentary Frequency Slider
        Text("Commentary Frequency", color = textColor, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        Slider(
            value = commentaryFrequency,
            onValueChange = {
                commentaryFrequency = it
                SessionManager.saveCommentaryFrequency(context, it)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF0000),
                activeTrackColor = Color(0xFFFF0000),
                inactiveTrackColor = Color.Gray
            )
        )
    }
}
