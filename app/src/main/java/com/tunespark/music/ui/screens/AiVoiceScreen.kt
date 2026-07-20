package com.tunespark.music.ui.screens

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var activeAiTab by remember { mutableStateOf("Gemini") }
    var geminiApiKey by remember { mutableStateOf(SessionManager.getGeminiApiKey(context)) }
    var elevenLabsApiKey by remember { mutableStateOf(SessionManager.getElevenLabsApiKey(context)) }
    var elevenLabsVoiceId by remember { mutableStateOf(SessionManager.getElevenLabsVoiceId(context)) }
    var isGenerating by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activePlayer?.release()
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val linkColor = Color(0xFFFF0000)

    fun openUrl(url: String) {
        try {
            val formattedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                "https://$url"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(
            title = "AI and Voice",
            onBack = { onNavigate(AppScreen.SETTINGS) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activeAiTab = "Gemini"
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (activeAiTab != "Gemini") {
                            Modifier.border(1.dp, textColor, RoundedCornerShape(24.dp))
                        } else {
                            Modifier
                        }
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeAiTab == "Gemini") Color(0xFFFF0000) else Color.Transparent,
                    contentColor = if (activeAiTab == "Gemini") Color.White else textColor
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Gemini", fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    activeAiTab = "ElevenLabs"
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (activeAiTab != "ElevenLabs") {
                            Modifier.border(1.dp, textColor, RoundedCornerShape(24.dp))
                        } else {
                            Modifier
                        }
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
            Text(
                text = "How to get your free key:",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LinkStepRow(
                prefix = "1. Go to ",
                urlText = "aistudio.google.com/api-keys",
                urlToOpen = "aistudio.google.com/api-keys",
                textColor = textColor,
                linkColor = linkColor,
                onOpenLink = ::openUrl
            )

            Text(
                text = "2. Tap “Get API key”",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "3. Tap “Create API key”",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "4. Copy it and paste it here",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = geminiApiKey,
                onValueChange = {
                    geminiApiKey = it
                    SessionManager.saveGeminiApiKey(context, it)
                },
                placeholder = {
                    Text("Paste your Gemini API key here", color = Color.Gray)
                },
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
                    IconButton(
                        onClick = {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            clipboardManager.getText()?.text?.let { text ->
                                val trimmed = text.trim()
                                geminiApiKey = trimmed
                                SessionManager.saveGeminiApiKey(context, trimmed)
                                Toast.makeText(context, "API Key pasted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste Gemini API key",
                            tint = textColor
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            var isGeminiTts by remember {
                mutableStateOf(SessionManager.getActiveTtsProvider(context) == "Gemini")
            }
            val isGeminiTtsActual = isGeminiTts || elevenLabsApiKey.isBlank()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (elevenLabsApiKey.isBlank()) {
                            Toast.makeText(context, "Input ElevenLabs API key first to use it for TTS!", Toast.LENGTH_SHORT).show()
                        } else {
                            isGeminiTts = !isGeminiTts
                            SessionManager.saveActiveTtsProvider(
                                context,
                                if (isGeminiTts) "Gemini" else "ElevenLabs"
                            )
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isGeminiTtsActual,
                    onCheckedChange = { checked ->
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (elevenLabsApiKey.isBlank()) {
                            Toast.makeText(context, "Input ElevenLabs API key first to use it for TTS!", Toast.LENGTH_SHORT).show()
                        } else {
                            isGeminiTts = checked
                            SessionManager.saveActiveTtsProvider(
                                context,
                                if (checked) "Gemini" else "ElevenLabs"
                            )
                        }
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
            Text(
                text = "How to get your free key:",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LinkStepRow(
                prefix = "1. Go to ",
                urlText = "elevenlabs.io/app/developers/api-keys",
                urlToOpen = "elevenlabs.io/app/developers/api-keys",
                textColor = textColor,
                linkColor = linkColor,
                onOpenLink = ::openUrl
            )

            Text(
                text = "2. Sign in or create an account",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "3. Create or copy your API key",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "4. Paste it here",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = elevenLabsApiKey,
                onValueChange = {
                    elevenLabsApiKey = it
                    SessionManager.saveElevenLabsApiKey(context, it)
                },
                placeholder = {
                    Text("Paste your ElevenLabs API key here", color = Color.Gray)
                },
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
                    IconButton(
                        onClick = {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            clipboardManager.getText()?.text?.let { text ->
                                val trimmed = text.trim()
                                elevenLabsApiKey = trimmed
                                SessionManager.saveElevenLabsApiKey(context, trimmed)
                                Toast.makeText(context, "API Key pasted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste ElevenLabs API key",
                            tint = textColor
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = elevenLabsVoiceId,
                onValueChange = {
                    elevenLabsVoiceId = it
                    SessionManager.saveElevenLabsVoiceId(context, it)
                },
                placeholder = {
                    Text(
                        "Enter Voice ID (e.g. EXAVITQu4vr4xnSDxMaL)",
                        color = Color.Gray
                    )
                },
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
                label = {
                    Text("Voice ID", color = textColor)
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                            TtsService.generateElevenLabsTts(
                                context,
                                currentKey,
                                previewText,
                                elevenLabsVoiceId
                            )
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
        
        Spacer(modifier = Modifier.height(110.dp))
    }
}

@Composable
private fun LinkStepRow(
    prefix: String,
    urlText: String,
    urlToOpen: String,
    textColor: Color,
    linkColor: Color,
    onOpenLink: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onOpenLink(urlToOpen)
                },
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = prefix,
                color = textColor,
                fontSize = 16.sp
            )

            Text(
                text = urlText,
                color = linkColor,
                fontSize = 16.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onOpenLink(urlToOpen)
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open link",
                tint = linkColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
