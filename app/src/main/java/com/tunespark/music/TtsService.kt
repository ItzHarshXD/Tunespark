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
        
        // Build the request body for ElevenLabs
        val requestJson = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.38)
                put("similarity_boost", 0.78)
                put("style", 0.38)
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
                    
                    val preferred = candidates.find { it.contains("gemini-1.5-flash-latest") }
                        ?: candidates.find { it.contains("gemini-1.5-flash") }
                        ?: candidates.find { it.contains("gemini-2.5-flash") }
                        ?: candidates.find { it.contains("gemini-2.0-flash") }
                        ?: candidates.find { it.contains("flash") }
                        ?: candidates.firstOrNull()
                        
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
            
            return@withContext "models/gemini-1.5-flash"
        }
    }

    suspend fun generateCommentaryScript(
        apiKey: String,
        currentSong: String?,
        upcomingSongs: List<String>
    ): String = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val modelName = findWorkingTextModel(cleanApiKey)
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelName:generateContent?key=$cleanApiKey"

        val prompt = StringBuilder().apply {
            append("You are a friendly, cool, professional AI radio host for 'TuneSpark AI DJ'.\n")
            if (currentSong != null) {
                append("The user just finished listening to: $currentSong.\n")
            }
            if (upcomingSongs.isNotEmpty()) {
                append("Coming up next, you are transitioning to these songs: ${upcomingSongs.joinToString(", ")}.\n")
            }
            append("Generate a brief, engaging radio host transition or commentary script (1 to 2 sentences, maximum 25-30 words). ")
            append("Make it flow naturally, mention the transition, and keep it extremely snappy and professional. ")
            append("Output ONLY the spoken text. Do not include any actions, stage directions, sound effects, introductory greetings, markdown, or quotation marks.")
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
        upcomingSongs: List<String>
    ): File {
        val provider = SessionManager.getActiveTtsProvider(context)
        val geminiKey = SessionManager.getGeminiApiKey(context)
        val elevenLabsKey = SessionManager.getElevenLabsApiKey(context)
        val voiceId = SessionManager.getElevenLabsVoiceId(context)

        if (geminiKey.isBlank()) {
            throw Exception("Gemini API key is required to generate AI commentary script.")
        }

        // 1. Generate the script using Gemini 3.1
        val script = generateCommentaryScript(geminiKey, currentSong, upcomingSongs)
        android.util.Log.d("TtsService", "Generated script: $script")

        // 2. Synthesize using the active TTS provider
        return if (provider == "ElevenLabs") {
            if (elevenLabsKey.isBlank()) {
                throw Exception("ElevenLabs API key is required for ElevenLabs TTS.")
            }
            generateElevenLabsTts(context, elevenLabsKey, script, voiceId)
        } else {
            generateGeminiTts(context, geminiKey, script)
        }
    }
}
