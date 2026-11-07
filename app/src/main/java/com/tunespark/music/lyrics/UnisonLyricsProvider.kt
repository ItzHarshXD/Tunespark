package com.tunespark.music.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Unison (betterlyrics.org) read API.
 *
 * Lookup order:
 *  1. exact video id:  GET /lyrics?v=<videoId>
 *  2. text search:     GET /lyrics/search?q=<title artist>
 *  3. fetch the best search hit by video id.
 *
 * Reads need no auth; only writes/votes are signed. Responses are LRC, plain
 * text, or TTML (word-level); TTML is flattened to line-synced LRC here because
 * the in-app renderer consumes LRC timestamps.
 */
object UnisonLyricsProvider : LyricsProvider {
    override val id: String = LyricsProviderRegistry.UNISON

    private const val BASE_URL = "https://unison.betterlyrics.org"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun fetch(request: LyricsRequest): LyricsProviderResult? = withContext(Dispatchers.IO) {
        request.videoId
            ?.takeIf { it.isNotBlank() }
            ?.let { fetchByVideoId(it) }
            ?.let { return@withContext it }

        val query = listOf(request.title, request.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (query.isBlank()) return@withContext null

        val best = search(query)
            .mapNotNull { candidate ->
                val videoId = candidate.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val song = candidate.optString("song")
                val artist = candidate.optString("artist")
                val duration = candidate.optInt("duration", 0)
                val score = matchSimilarity(song, request.title) * 0.6 +
                    matchSimilarity(artist, request.artist) * 0.4 -
                    durationPenalty(request.durationSec, duration)
                videoId to score
            }
            .maxByOrNull { it.second }

        // A weak search hit is more likely a different song than a real match.
        if (best == null || best.second < 0.45) return@withContext null

        fetchByVideoId(best.first)
    }

    private fun fetchByVideoId(videoId: String): LyricsProviderResult? {
        val url = "$BASE_URL/lyrics?v=${java.net.URLEncoder.encode(videoId, "UTF-8")}"
        return runCatching {
            val body = get(url) ?: return null
            val json = JSONObject(body)
            if (!json.optBoolean("success")) return null
            val data = json.optJSONObject("data") ?: return null
            val lyrics = data.optString("lyrics")
            if (lyrics.isBlank()) return null

            when (data.optString("format").lowercase()) {
                "lrc" -> LyricsProviderResult(id, lyrics, hasTimestamps(lyrics))
                "plain" -> LyricsProviderResult(id, lyrics, synced = false)
                "ttml" -> {
                    val lrc = ttmlToLrc(lyrics)
                    if (lrc.isBlank()) null else LyricsProviderResult(id, lrc, synced = true)
                }
                else -> LyricsProviderResult(id, lyrics, hasTimestamps(lyrics))
            }
        }.getOrNull()
    }

    private fun search(query: String): List<JSONObject> {
        val url = "$BASE_URL/lyrics/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return runCatching {
            val body = get(url) ?: return emptyList()
            val json = JSONObject(body)
            if (!json.optBoolean("success")) return emptyList()
            json.optJSONArray("data").toObjectList()
        }.getOrDefault(emptyList())
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Tunespark/3.1 (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /**
     * Flattens TTML `<p>` blocks to LRC lines. Word-level `<span>` timings are
     * intentionally ignored; the renderer highlights whole lines.
     */
    internal fun ttmlToLrc(ttml: String): String {
        val paragraphRegex = Regex("""(?s)<p\b([^>]*)>(.*?)</p>""")
        val beginRegex = Regex("""begin\s*=\s*"([^"]+)"""")
        val output = StringBuilder()

        paragraphRegex.findAll(ttml).forEach { match ->
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            val begin = beginRegex.find(attrs)?.groupValues?.get(1) ?: return@forEach
            val timestamp = ttmlTimestampToLrc(begin) ?: return@forEach

            val text = inner
                .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""<[^>]+>"""), "")
                .let(::decodeXmlEntities)
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (text.isNotEmpty()) {
                output.append(timestamp).append(' ').append(text).append('\n')
            }
        }

        return output.toString().trim()
    }

    /** Converts `m:ss.mmm` / `mm:ss.mmm` to `[mm:ss.mmm]`. */
    private fun ttmlTimestampToLrc(value: String): String? {
        val match = Regex("""^(\d+):(\d{2})(?:\.(\d{1,3}))?$""").find(value.trim()) ?: return null
        val minutes = match.groupValues[1].toIntOrNull() ?: return null
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        val fraction = match.groupValues[3].padEnd(3, '0').take(3).ifBlank { "000" }
        return String.format(java.util.Locale.US, "[%02d:%02d.%s]", minutes, seconds, fraction)
    }
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
