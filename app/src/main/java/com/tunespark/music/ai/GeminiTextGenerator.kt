package com.tunespark.music.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reusable Gemini text generation pipeline.
 *
 * Mirrors the exact same API key, model discovery, and generation approach
 * already used by [com.tunespark.music.TtsService] for AI commentary, so
 * future features (AI commentary, "Why this matters", etc.) can share one
 * consistent pipeline.
 */
object GeminiTextGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * Sends a text prompt to the working Gemini text model and returns the
     * generated text response.
     *
     * @param apiKey The user's Gemini API key (from SessionManager).
     * @param prompt The prompt to send.
     * @return The generated text, trimmed.
     */
    suspend fun generateText(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.trim()
        val modelName = findWorkingTextModel(cleanApiKey)
        val url = "https://generativelanguage.googleapis.com/v1beta/$modelName:generateContent?key=$cleanApiKey"

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
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 1024)
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

            val responseString = response.body?.string() ?: throw Exception("Empty response body from Gemini")
            try {
                val jsonResponse = JSONObject(responseString)
                val candidates = jsonResponse.getJSONArray("candidates")
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                return@withContext parts.getJSONObject(0).getString("text").trim()
            } catch (e: Exception) {
                throw Exception("Failed to parse Gemini response: ${e.message}. Response: $responseString")
            }
        }
    }

    /**
     * Discovers a working Gemini text model for the given API key.
     * Prefers `gemini-3.1-flash-lite` (same preference as the existing
     * AI commentary pipeline), falling back to a hardcoded default.
     */
    suspend fun findWorkingTextModel(apiKey: String): String = withContext(Dispatchers.IO) {
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
                        android.util.Log.d("GeminiTextGenerator", "Dynamic model discovery selected: $preferred")
                        return@withContext preferred
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GeminiTextGenerator", "Failed to parse models list, falling back: ${e.message}")
                }
            } else {
                android.util.Log.e("GeminiTextGenerator", "Failed to query models list: Code ${response.code}")
            }

            return@withContext "models/gemini-3.1-flash-lite"
        }
    }
}