package com.tunespark.music

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object TtsService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    suspend fun generateGeminiTts(context: Context, apiKey: String, text: String): File = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-tts-preview:generateContent?key=$cleanApiKey"
        
        // Build request body according to the official Gemini API documentation
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", text)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Kore")
                        })
                    })
                })
            })
        }

        val body = requestJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", cleanApiKey)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val errorMsg = when (response.code) {
                    401, 403 -> "API key is invalid or expired (please verify your key)"
                    402 -> "Payment required or free plan limit reached (please verify your credits/subscription)"
                    429 -> "Rate limit exceeded (too many requests, try again later)"
                    else -> "Gemini API call failed: Code ${response.code}, Message: ${response.message}. Detail: $responseBody"
                }
                throw Exception(errorMsg)
            }
            
            val responseString = response.body?.string() ?: throw Exception("Empty response body from Gemini TTS")
            
            try {
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.getJSONArray("candidates")
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val inlineData = parts.getJSONObject(0).getJSONObject("inlineData")
                val base64Data = inlineData.getString("data")
                
                val rawPcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                
                // Convert raw PCM to standard playable WAV by adding a 44-byte RIFF/WAVE header
                val wavBytes = addWavHeader(rawPcmBytes, 24000)
                
                // Save to a temporary wav file
                val tempFile = File.createTempFile("gemini_tts_preview", ".wav", context.cacheDir)
                FileOutputStream(tempFile).use { fos ->
                    fos.write(wavBytes)
                }
                return@withContext tempFile
            } catch (e: Exception) {
                throw Exception("Failed to parse Gemini TTS response: ${e.message}. Response: $responseString")
            }
        }
    }

    private fun addWavHeader(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val totalSize = pcmBytes.size + 36
        val byteRate = sampleRate * 2 // 1 channel * 16 bits (2 bytes) per sample
        val header = ByteArray(44)
        
        // "RIFF"
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        // Size
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        
        // "WAVE"
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        // "fmt "
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        // Subchunk1 Size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        // Audio Format (1 for PCM)
        header[20] = 1
        header[21] = 0
        
        // Num Channels (1 for Mono)
        header[22] = 1
        header[23] = 0
        
        // Sample Rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        
        // Byte Rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        
        // Block Align (Channels * BitsPerSample / 8)
        header[32] = 2
        header[33] = 0
        
        // Bits Per Sample (16 bits)
        header[34] = 16
        header[35] = 0
        
        // "data"
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()
        
        val wavBytes = ByteArray(44 + pcmBytes.size)
        System.arraycopy(header, 0, wavBytes, 0, 44)
        System.arraycopy(pcmBytes, 0, wavBytes, 44, pcmBytes.size)
        return wavBytes
    }

    suspend fun generateElevenLabsTts(context: Context, apiKey: String, text: String, voiceId: String): File = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

        // Use the user-selected model ID (defaults to eleven_multilingual_v2)
        val modelId = SessionManager.getSelectedElevenLabsModelId(context)

        // Voice settings are tuned per-model. Eleven v3 benefits from slightly higher stability
        // for consistent emotional delivery; Flash/Multilingual use the balanced defaults.
        val (stability, similarityBoost, style) = when (modelId) {
            "eleven_v3" -> Triple(0.50, 0.80, 0.30)
            "eleven_flash_v2_5" -> Triple(0.35, 0.75, 0.40)
            else -> Triple(0.38, 0.78, 0.38) // eleven_multilingual_v2 and fallback
        }

        // Build the request body for ElevenLabs
        val requestJson = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", JSONObject().apply {
                put("stability", stability)
                put("similarity_boost", similarityBoost)
                put("style", style)
                put("use_speaker_boost", true)
            })
        }

        val body = requestJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", cleanApiKey)
            .addHeader("accept", "audio/mpeg")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val errorMsg = when (response.code) {
                    401, 403 -> "API key is invalid or expired (please verify your key)"
                    402 -> "Payment required/free limit reached (the selected voice might require a paid subscription, or your free credits are exhausted)"
                    429 -> "Rate limit exceeded (too many requests, try again later)"
                    else -> "ElevenLabs API call failed: Code ${response.code}, Message: ${response.message}. Detail: $responseBody"
                }
                throw Exception(errorMsg)
            }

            val responseBody = response.body ?: throw Exception("Empty response body from ElevenLabs")
            
            // Save to a temporary mp3 file
            val tempFile = File.createTempFile("elevenlabs_tts_preview", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { fos ->
                responseBody.byteStream().use { inputStream ->
                    inputStream.copyTo(fos)
                }
            }
            return@withContext tempFile
        }
    }

    private suspend fun findWorkingTextModel(apiKey: String): String = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanApiKey"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val responseString = response.body?.string() ?: ""
                try {
                    val jsonResponse = JSONObject(responseString)
                    val modelsArray = jsonResponse.getJSONArray("models")
                    
                    val candidates = mutableListOf<String>()
                    for (i in 0 until modelsArray.length()) {
                        val modelObj = modelsArray.getJSONObject(i)
                        val name = modelObj.getString("name") // e.g. "models/gemini-1.5-flash"
                        val methods = modelObj.getJSONArray("supportedGenerationMethods")
                        
                        var supportsGenerateContent = false
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") {
                                supportsGenerateContent = true
                                break
                            }
                        }
                        
                        if (supportsGenerateContent && !name.contains("tts") && !name.contains("translation")) {
                            candidates.add(name)
                        }
                    }
                    
                    val preferred = candidates.find { it.contains("gemini-3.1-flash-lite") }
                    if (preferred != null) {
                        android.util.Log.d("TtsService", "Dynamic model discovery selected: $preferred")
                        return@withContext preferred
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TtsService", "Failed to parse models list, falling back: ${e.message}")
                }
            } else {
                android.util.Log.e("TtsService", "Failed to query models list: Code ${response.code}")
            }
            
            return@withContext "models/gemini-3.1-flash-lite"
        }
    }

    suspend fun generateCommentaryScript(
        apiKey: String,
        currentSong: String?,
        upcomingSongs: List<String>,
        commentaryLength: Float,
        contextPrompt: String? = null,
        commentaryElements: Set<String> = emptySet(),
        isSessionOpener: Boolean = false,
        briefingArticle: BriefingArticle? = null,
        musicContextData: MusicContextData? = null,
        musicContextLyrics: String? = null,
        customInstructions: String? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val modelName = findWorkingTextModel(cleanApiKey)
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelName:generateContent?key=$cleanApiKey"

        val prompt = StringBuilder().apply {
            append("You are a friendly, cool, professional AI radio host for 'Tunespark Radio'.\n\n")

            if (!customInstructions.isNullOrBlank()) {
                append("CRITICAL USER CUSTOMIZATION PREFERENCES:\n")
                append("The user has specified custom instructions and style guidelines for your host persona. You MUST strictly prioritize and adhere to these preferences for personality, tone, humour, style, vocabulary, or constraints. Do NOT break character from these instructions:\n")
                append("\"\"\"\n")
                append(customInstructions.trim())
                append("\n\"\"\"\n\n")
            }

            if (musicContextData != null) {
                // ===== MUSIC CONTEXT PROMPT =====
                append("This is a dedicated 'Music Context' segment. You must focus 100% on presenting the background story, meaning, release details, and interesting historical details of the song specified below. Do NOT talk about previous or other upcoming songs, the user's listening history, current time, or weather.\n\n")
                append("Start the segment with a very short, single-sentence casual radio transition intro (e.g., 'Let's take a look at the story behind this track...', 'Ever wondered how this song came to be?...', or similar warm, casual DJ intro line).\n\n")
                append("Here is the gathered context for the song:\n")
                append("Song Title: ${musicContextData.title}\n")
                append("Artist: ${musicContextData.artist}\n")
                if (!musicContextData.musicBrainzAlbum.isNullOrBlank()) {
                    append("Album: ${musicContextData.musicBrainzAlbum}\n")
                }
                if (!musicContextData.musicBrainzReleaseDate.isNullOrBlank()) {
                    append("Release Date: ${musicContextData.musicBrainzReleaseDate}\n")
                }
                if (!musicContextData.musicBrainzInfo.isNullOrBlank()) {
                    append("MusicBrainz details: ${musicContextData.musicBrainzInfo}\n")
                }
                if (!musicContextData.wikidataDescription.isNullOrBlank()) {
                    append("Wikidata relationship info: ${musicContextData.wikidataDescription}\n")
                }
                if (!musicContextData.wikipediaProse.isNullOrBlank()) {
                    append("Wikipedia History & Story: ${musicContextData.wikipediaProse}\n")
                }
                if (!musicContextLyrics.isNullOrBlank()) {
                    append("Lyrics theme / Fallback:\n$musicContextLyrics\n")
                }
                append("\nINSTRUCTIONS:\n")
                append("- Speak in an engaging, narrative storytelling style. Make it casual and conversational, like an expert DJ sharing trivia between songs.\n")
                append("- Weave the facts together smoothly. Do NOT present bullet points, raw lists, labels, or dry reports. Just weave the story in naturally.\n")
                append("- Strictly avoid any markdown asterisks (*), hashtags, stage directions, or formatting. Output only clean, conversational spoken text.\n")
                append("- Keep it descriptive and flow nicely, fitting a narrative of around 50-80 words so the historical context and meaning can fit naturally.\n")
                if (!customInstructions.isNullOrBlank()) {
                    append("- IMPORTANT: Present this background story while fully adopting the personality, tone, and style requested in the custom instructions (e.g. sarcasm, humor, particular slang, etc.).\n")
                }
                append("\n")
            } else if (briefingArticle != null && !isSessionOpener) {
                // ===== AI BRIEFING PROMPT =====
                // This is a dedicated 'AI Briefing' segment. 
                // We MUST NOT mix up humour, roasts, jokes, music details or any other elements.
                append("This is a dedicated 'AI Briefing' segment. You must NOT mix up humour, roasts, jokes, or any other elements. You must NOT talk about the previous or upcoming songs, the user's listening history, or the music at all. Focus 100% on presenting a smooth, engaging radio news briefing based on the article provided below.\n\n")
                append("Start the segment with a very short, single-sentence radio intro/transition line to naturally introduce the briefing without rushing into the topic directly (e.g., 'Now for a quick update from our news desk...', 'Taking a quick break from the music for some updates...', or similar brief, casual intro).\n\n")
                append("Here is the news article to present to the listener:\n")
                append("Article Title: ${briefingArticle.title}\n")
                append("Source: ${briefingArticle.source}\n")
                append("Content:\n${briefingArticle.content}\n\n")
                append("INSTRUCTIONS:\n")
                append("- You MUST speak up and summarize this news article.\n")
                append("- Present an engaging spoken summary of the article in a premium radio news briefing style (just like how news briefing happens in between songs on FM radio).\n")
                append("- Speak casual, conversational, and natural. Keep it professional but easy to listen to.\n")
                append("- Do NOT read it like a dry, academic news report. Do NOT list bullet points. Do NOT use headers or labels (like 'Summary:' or 'Takeaway:'). Just weave the story in smoothly.\n")
                append("- Strictly avoid any markdown asterisks (*), hashtags, or formatting. Output only clean, conversational spoken text.\n")
                append("- Keep it concise and clear, but make sure to fully integrate the story so it stands as a highly effective news briefing.\n")
                if (!customInstructions.isNullOrBlank()) {
                    append("- IMPORTANT: Deliver this news briefing while fully adopting the personality, tone, and style requested in the custom instructions (e.g. sarcastic, humorous, dry, specific slang, etc.).\n")
                }
                append("\n")
            } else {
                if (isSessionOpener) {
                    // ===== SESSION OPENER PROMPT =====
                    // This is the FIRST commentary when the user starts a session.
                    // ONLY here do we greet, mention name, time, and weather.
                    append("This is the SESSION OPENER — the very first commentary when the user starts listening. This is the ONLY time you should:\n")
                    append("- Greet the user by name (if available in the context below)\n")
                    append("- Mention the time of day with a greeting (e.g. \"Good morning\", \"Good evening\" based on the context)\n")
                    append("- Mention the weather (if available in the context)\n")
                    append("- Welcome them to the session\n\n")
                    if (!customInstructions.isNullOrBlank()) {
                        append("IMPORTANT PERSONALITY & STYLE OVERRIDE FOR SESSION OPENER:\n")
                        append("While performing this session opening greeting, you MUST fully apply and reflect the user's custom instructions/preferences:\n")
                        append("\"\"\"\n")
                        append(customInstructions.trim())
                        append("\n\"\"\"\n")
                        append("For example, if the custom instructions ask you to be sarcastic, funny, dry, use specific slang, or have a specific accent/personality, you must open the session in that exact style rather than a generic warm greeting.\n\n")
                    } else {
                        append("Make the greeting feel natural and warm, like a radio host opening a show. Then transition into introducing the first song.\n\n")
                    }
                    append("When mentioning the weather, describe the conditions (e.g. \"it's a sunny afternoon\", \"bit cloudy out there\") but do NOT say the city name or location name.\n\n")
                } else {
                    // ===== BETWEEN-SONGS PROMPT =====
                    // This is a transition between songs. NO greeting, NO name, NO weather, NO time.
                    append("This is a BETWEEN-SONGS commentary — a short transition between tracks. You must NOT:\n")
                    append("- Greet the user or say \"welcome back\", \"welcome to\", or any greeting\n")
                    append("- Mention or address the user by name\n")
                    append("- Mention the time of day, \"good morning/evening\", or any time-based greeting\n")
                    append("- Mention the weather\n")
                    append("- Repeat any session-opening language or welcome message\n\n")
                    append("You are simply transitioning from the song that just played to the next song(s). Keep it focused purely on the music and the transition. Be concise and smooth.\n")
                    append("Do NOT refer to the upcoming songs as a \"playlist\", \"set\", \"collection\", or \"queue\" — they are simply the next songs playing. Never use the word \"playlist\".\n\n")
                }

                // Context is always provided — the AI has all the data but uses it differently
                if (contextPrompt != null && contextPrompt.isNotBlank()) {
                    if (isSessionOpener) {
                        append("Here is the current context about the user's day and session. Use the time, weather, and user's name for your greeting, and use the listening history to personalize the opener:\n")
                    } else {
                        append("Here is the current context about the user's day and session. Use this as BACKGROUND AWARENESS ONLY to inform your commentary — do NOT repeat greetings, mention the user's name, state the time, or describe the weather. You may use the listening history and session metadata to make the transition feel personalized:\n")
                    }
                    append(contextPrompt).append("\n\n")
                }

                // Song info
                if (currentSong != null) {
                    append("The user just finished listening to: $currentSong.\n")
                }
                if (upcomingSongs.isNotEmpty()) {
                    if (isSessionOpener) {
                        append("The user is about to start listening to: ${upcomingSongs.joinToString(", ")}.\n")
                    } else {
                        append("Coming up next, you are transitioning to: ${upcomingSongs.joinToString(", ")}.\n")
                    }
                }
                append("\n")

                // ===== HUMOUR ELEMENT =====
                // Present in BOTH session opener and between-songs. Roasting style.
                if (commentaryElements.contains("Humour")) {
                    append("HUMOUR ELEMENT (enabled): Add a funny, playful ROAST to your commentary. Roast the user's music taste, their listening habits, or the song/artist choices in a way that's genuinely funny and a little savage — like a best friend who lovingly mocks you. Think playful insults about their song choices, cheeky observations about their listening patterns from the context, or witty jabs at the artists. Dark humour is welcome. Keep it fun and don't be boring or overly safe. The roast should feel natural and woven into the commentary, not like a separate joke segment.\n\n")
                }
            }

            // ===== LENGTH INSTRUCTIONS =====
            val lengthInstructions = when {
                commentaryLength < 0.33f -> "Generate a very brief and concise radio host commentary script. Keep it to exactly 1 short sentence, maximum 15-20 words. Keep it extremely quick and snappy."
                commentaryLength < 0.66f -> "Generate a standard, engaging radio host commentary script. Keep it to 1 to 2 sentences, maximum 25-30 words. Make it flow naturally and keep it snappy and professional."
                else -> "Generate a detailed, deeply engaging, and longer radio host commentary script. Keep it to 3 to 4 sentences, around 50-70 words. Include interesting radio banter and provide a comprehensive and rich commentary."
            }
            append(lengthInstructions).append("\n\n")

            // If the briefing is enabled and an article is present, allow a
            // slightly longer script so the article summary can fit naturally.
            if (commentaryElements.contains("Briefing") && briefingArticle != null) {
                append("If you choose to include the briefing, you may extend the script slightly (by 1-2 sentences) to accommodate the article summary naturally.\n\n")
            }

            append("Output ONLY the spoken text. Do not include any actions, stage directions, sound effects, markdown, or quotation marks.")
            
            if (!customInstructions.isNullOrBlank()) {
                append("\n\nREMINDER: Strictly respect the user's custom instructions/style preferences (personality, tone, humor, style, etc.) specified above when writing this commentary script.")
            }
        }.toString()

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val body = requestJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                throw Exception("Failed to generate script: Code ${response.code}. Detail: $responseBody")
            }
            val responseString = response.body?.string() ?: throw Exception("Empty response body from Gemini script generator")
            try {
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.getJSONArray("candidates")
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val generatedText = parts.getJSONObject(0).getString("text").trim()
                return@withContext generatedText.replace("\"", "").replace("\n", " ")
            } catch (e: Exception) {
                throw Exception("Failed to parse Gemini script response: ${e.message}. Response: $responseString")
            }
        }
    }

    suspend fun generateCommentaryAudio(
        context: Context,
        currentSong: String?,
        upcomingSongs: List<String>,
        contextPrompt: String? = null,
        commentaryElements: Set<String> = emptySet(),
        isSessionOpener: Boolean = false,
        briefingArticle: BriefingArticle? = null,
        musicContextData: MusicContextData? = null,
        musicContextLyrics: String? = null
    ): Pair<File, String> {
        val provider = SessionManager.getActiveTtsProvider(context)
        val geminiKey = SessionManager.getGeminiApiKey(context)
        val elevenLabsKey = SessionManager.getElevenLabsApiKey(context)
        val voiceId = SessionManager.getElevenLabsVoiceId(context)
        val commentaryLength = SessionManager.getCommentaryLength(context)

        if (geminiKey.isBlank()) {
            throw Exception("Gemini API key is required to generate AI commentary script.")
        }

        var lastException: Exception? = null
        for (attempt in 1..3) {
            try {
                // 1. Generate the script using Gemini 3.1
                val script = generateCommentaryScript(
                    apiKey = geminiKey,
                    currentSong = currentSong,
                    upcomingSongs = upcomingSongs,
                    commentaryLength = commentaryLength,
                    contextPrompt = contextPrompt,
                    commentaryElements = commentaryElements,
                    isSessionOpener = isSessionOpener,
                    briefingArticle = briefingArticle,
                    musicContextData = musicContextData,
                    musicContextLyrics = musicContextLyrics,
                    customInstructions = SessionManager.getCustomInstructions(context)
                )
                android.util.Log.d("TtsService", "Generated script (Attempt $attempt): $script")

                // 2. Synthesize using the active TTS provider
                val audioFile = if (provider == "ElevenLabs") {
                    if (elevenLabsKey.isBlank()) {
                        throw Exception("ElevenLabs API key is required for ElevenLabs TTS.")
                    }
                    generateElevenLabsTts(context, elevenLabsKey, script, voiceId)
                } else {
                    generateGeminiTts(context, geminiKey, script)
                }
                return Pair(audioFile, script)
            } catch (e: Exception) {
                lastException = e
                android.util.Log.e("TtsService", "Attempt $attempt failed: ${e.message}")
                if (attempt < 3) {
                    kotlinx.coroutines.delay(2000L * attempt) // backoff: 2s, 4s
                }
            }
        }
        throw lastException ?: Exception("Unknown error generating commentary audio after 3 attempts.")
    }
}