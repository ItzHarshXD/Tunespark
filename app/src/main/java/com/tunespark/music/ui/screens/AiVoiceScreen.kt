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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
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
                    .height(60.dp)
                    .then(
                        if (activeAiTab != "Gemini") {
                            Modifier.border(1.dp, textColor, RoundedCornerShape(30.dp))
                        } else {
                            Modifier
                        }
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeAiTab == "Gemini") Color(0xFFFF0000) else Color.Transparent,
                    contentColor = if (activeAiTab == "Gemini") Color.White else textColor
                ),
                shape = RoundedCornerShape(30.dp)
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
                    .height(60.dp)
                    .then(
                        if (activeAiTab != "ElevenLabs") {
                            Modifier.border(1.dp, textColor, RoundedCornerShape(30.dp))
                        } else {
                            Modifier
                        }
                    ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeAiTab == "ElevenLabs") Color(0xFFFF0000) else Color.Transparent,
                    contentColor = if (activeAiTab == "ElevenLabs") Color.White else textColor
                ),
                shape = RoundedCornerShape(30.dp)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                singleLine = true,
                shape = RoundedCornerShape(30.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedBorderColor = textColor,
                    focusedBorderColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedTextColor = textColor
                ),
                label = {
                    Text("Gemini API Key", color = textColor)
                },
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

            // Gemini Text Generation Model Dropdown
            var textDropdownExpanded by remember { mutableStateOf(false) }
            var selectedTextModel by remember { mutableStateOf(SessionManager.getSelectedGeminiTextModel(context)) }

            val geminiTextModels = remember {
                listOf(
                    "Gemini 3.1 Flash Lite",
                    "Gemini 3.5 Flash",
                    "Gemini 3.5 Flash Lite",
                    "Gemini 2 Flash",
                    "Gemini 2 Flash Lite",
                    "Gemini 2.5 Flash",
                    "Gemini 2.5 Flash Lite",
                    "Gemini 2.5 Pro",
                    "Gemini 3 Flash",
                    "Gemini 3.1 Pro",
                    "Gemini 3.6 Flash",
                    "Gemma 4 26B",
                    "Gemma 4 31B"
                )
            }

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = selectedTextModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Gemini Text Generation Model", color = textColor) },
                    trailingIcon = {
                        IconButton(onClick = { textDropdownExpanded = !textDropdownExpanded }) {
                            Icon(
                                imageVector = if (textDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Toggle Dropdown",
                                tint = textColor
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = textColor,
                        focusedBorderColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            textDropdownExpanded = true
                        }
                )
                DropdownMenu(
                    expanded = textDropdownExpanded,
                    onDismissRequest = { textDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    geminiTextModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                selectedTextModel = model
                                SessionManager.saveSelectedGeminiTextModel(context, model)
                                textDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Test API Button & Status Indicator
            var apiTestStatus by remember { mutableStateOf<String?>(null) }
            var isTestingApi by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (geminiApiKey.isBlank()) {
                            Toast.makeText(context, "Please enter a Gemini API Key first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isTestingApi = true
                        apiTestStatus = "Testing..."
                        scope.launch {
                            val (success, message) = testGeminiApiKey(geminiApiKey)
                            isTestingApi = false
                            apiTestStatus = message
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !isTestingApi,
                    modifier = Modifier.height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF0000),
                        contentColor = Color.White,
                        disabledContainerColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(
                        text = if (isTestingApi) "Testing..." else "Test API",
                        fontWeight = FontWeight.Bold
                    )
                }

                apiTestStatus?.let { status ->
                    val statusColor = if (status.startsWith("API Key is working")) {
                        Color(0xFF4CAF50) // Green
                    } else if (status.startsWith("Testing")) {
                        Color.Gray
                    } else {
                        Color(0xFFE53935) // Red
                    }
                    Text(
                        text = status,
                        color = statusColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Gemini TTS Model Dropdown
            var ttsDropdownExpanded by remember { mutableStateOf(false) }
            var selectedTtsModel by remember { mutableStateOf(SessionManager.getSelectedGeminiTtsModel(context)) }

            val geminiTtsModels = remember {
                listOf(
                    "Gemini 3.1 Flash TTS",
                    "Gemini 2.5 Flash TTS",
                    "Gemini 2.5 Pro TTS"
                )
            }

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = selectedTtsModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Gemini TTS Model", color = textColor) },
                    trailingIcon = {
                        IconButton(onClick = { ttsDropdownExpanded = !ttsDropdownExpanded }) {
                            Icon(
                                imageVector = if (ttsDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Toggle Dropdown",
                                tint = textColor
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = textColor,
                        focusedBorderColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            ttsDropdownExpanded = true
                        }
                )
                DropdownMenu(
                    expanded = ttsDropdownExpanded,
                    onDismissRequest = { ttsDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    geminiTtsModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                selectedTtsModel = model
                                SessionManager.saveSelectedGeminiTtsModel(context, model)
                                ttsDropdownExpanded = false
                            }
                        )
                    }
                }
            }

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
                text = "3. Enable Text to Speech setting",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "4. Create or copy your API key",
                color = textColor,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "5. Paste it here",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                singleLine = true,
                shape = RoundedCornerShape(30.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedBorderColor = textColor,
                    focusedBorderColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedTextColor = textColor
                ),
                label = {
                    Text("ElevenLabs API Key", color = textColor)
                },
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

            // ===== ElevenLabs Model Selection (exactly 3 supported models) =====
            var elevenLabsModelDropdownExpanded by remember { mutableStateOf(false) }
            var selectedElevenLabsModelId by remember { mutableStateOf(SessionManager.getSelectedElevenLabsModelId(context)) }

            // The 3 supported models: (displayName, modelId, description)
            val supportedElevenLabsModels = remember {
                listOf(
                    Triple(
                        "Eleven v3",
                        "eleven_v3",
                        "The most expressive and emotionally nuanced synthesis model. Built for dramatic delivery and natural multi-speaker dialogue. Supports over 70 languages with a 5,000-character single-request limit."
                    ),
                    Triple(
                        "Eleven Multilingual v2",
                        "eleven_multilingual_v2",
                        "The default fallback model. It delivers highly consistent, lifelike quality and excels at long-form prose or audiobook generation. Supports 29 languages with a 10,000-character limit."
                    ),
                    Triple(
                        "Eleven Flash v2.5",
                        "eleven_flash_v2_5",
                        "The fastest and most cost-effective option. Engineered for real-time conversational agents with ultra-low latency (~75ms). Supports 32 languages and accommodates up to 40,000 characters per call."
                    )
                )
            }

            val selectedModelOption = supportedElevenLabsModels.find { it.second == selectedElevenLabsModelId }
                ?: supportedElevenLabsModels[1]

            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = selectedModelOption.first,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("ElevenLabs TTS Model", color = textColor) },
                    trailingIcon = {
                        IconButton(onClick = { elevenLabsModelDropdownExpanded = !elevenLabsModelDropdownExpanded }) {
                            Icon(
                                imageVector = if (elevenLabsModelDropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = "Toggle Dropdown",
                                tint = textColor
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = textColor,
                        focusedBorderColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedTextColor = textColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable {
                            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            elevenLabsModelDropdownExpanded = true
                        }
                )
                DropdownMenu(
                    expanded = elevenLabsModelDropdownExpanded,
                    onDismissRequest = { elevenLabsModelDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    supportedElevenLabsModels.forEach { (displayName, modelId, description) ->
                        DropdownMenuItem(
                            text = { Text(displayName) },
                            onClick = {
                                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                selectedElevenLabsModelId = modelId
                                SessionManager.saveSelectedElevenLabsModel(context, displayName)
                                SessionManager.saveSelectedElevenLabsModelId(context, modelId)
                                elevenLabsModelDropdownExpanded = false
                            }
                        )
                    }
                }
            }


            Spacer(modifier = Modifier.height(8.dp))

            // ===== Voice ID (simple editable text field) =====
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                singleLine = true,
                shape = RoundedCornerShape(30.dp),
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
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryColor,
                contentColor = onPrimaryColor,
                disabledContainerColor = Color.Gray,
                disabledContentColor = Color.White
            ),
            shape = RoundedCornerShape(30.dp)
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

private suspend fun testGeminiApiKey(apiKey: String): Pair<Boolean, String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}"
        val request = okhttp3.Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Pair(true, "API Key is working successfully!")
            } else {
                val body = response.body?.string() ?: ""
                var errorDetail = response.message
                try {
                    val json = org.json.JSONObject(body)
                    if (json.has("error")) {
                        errorDetail = json.getJSONObject("error").getString("message")
                    }
                } catch (e: Exception) {}
                Pair(false, "Verification failed: $errorDetail")
            }
        }
    } catch (e: java.io.IOException) {
        Pair(false, "Connection error: ${e.message}")
    } catch (e: Exception) {
        Pair(false, "Error: ${e.message}")
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
