package com.tunespark.music.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * KuGou (Kugou Music) lyrics lookup.
 *
 * This is the same source Better Lyrics exposes as "Legato". The public
 * endpoints are unofficial but long-lived and return plain LRC:
 *
 *  1. song search        -> mobilecdn.kugou.com/api/v3/search/song
 *  2. lyric candidates   -> krcs.kugou.com/search (hash based)
 *  3. lyric download     -> lyrics.kugou.com/download (base64 LRC)
 *
 * HTTP is used deliberately: the CDN's TLS certificate does not match these
 * hostnames, and the app already permits cleartext traffic for its other
 * providers.
 */
object KuGouLyricsProvider : LyricsProvider {
    override val id: String = LyricsProviderRegistry.KUGOU

    private const val SEARCH_URL = "http://mobilecdn.kugou.com/api/v3/search/song"
    private const val CANDIDATE_URL = "http://krcs.kugou.com/search"
    private const val DOWNLOAD_URL = "http://lyrics.kugou.com/download"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun fetch(request: LyricsRequest): LyricsProviderResult? = withContext(Dispatchers.IO) {
        if (request.title.isBlank()) return@withContext null

        val song = findBestSong(request) ?: return@withContext null
        val hash = song.optString("hash").takeIf { it.isNotBlank() } ?: return@withContext null
        val durationMs = song.optInt("duration", 0) * 1000

        val candidate = findBestCandidate(hash, durationMs) ?: return@withContext null
        val lyricId = candidate.optString("id").takeIf { it.isNotBlank() } ?: return@withContext null
        val accessKey = candidate.optString("accesskey").takeIf { it.isNotBlank() } ?: return@withContext null

        download(lyricId, accessKey)
    }

    private fun findBestSong(request: LyricsRequest): JSONObject? {
        val queries = buildList {
            add(listOf(request.title, request.artist).filter { it.isNotBlank() }.joinToString(" "))
            if (request.title.isNotBlank()) add(request.title)
        }.distinct()

        for (query in queries) {
            val url = "$SEARCH_URL?format=json&keyword=${encode(query)}&page=1&pagesize=10"
            val entries = runCatching {
                val body = get(url) ?: return@runCatching emptyList()
                JSONObject(body).optJSONObject("data")?.optJSONArray("info").toObjectList()
            }.getOrDefault(emptyList())

            val best = entries.maxByOrNull { entry ->
                val titleScore = matchSimilarity(entry.optString("songname"), request.title)
                val artistScore = matchSimilarity(entry.optString("singername"), request.artist)
                val durationScore = 1.0 - durationPenalty(request.durationSec, entry.optInt("duration", 0))
                titleScore * 0.55 + artistScore * 0.30 + durationScore * 0.15
            }

            if (best != null) {
                val titleScore = matchSimilarity(best.optString("songname"), request.title)
                val artistScore = matchSimilarity(best.optString("singername"), request.artist)
                if (titleScore >= 0.6 && (request.artist.isBlank() || artistScore >= 0.35)) {
                    return best
                }
            }
        }
        return null
    }

    private fun findBestCandidate(hash: String, durationMs: Int): JSONObject? {
        val url = "$CANDIDATE_URL?ver=1&man=yes&client=mobi&keyword=&duration=$durationMs&hash=${encode(hash)}"
        val candidates = runCatching {
            val body = get(url) ?: return@runCatching emptyList()
            JSONObject(body).optJSONArray("candidates").toObjectList()
        }.getOrDefault(emptyList())

        return candidates
            .filter { it.optString("id").isNotBlank() && it.optString("accesskey").isNotBlank() }
            .maxByOrNull { it.optInt("score", 0) }
    }

    private fun download(id: String, accessKey: String): LyricsProviderResult? {
        val url = "$DOWNLOAD_URL?ver=1&client=pc&id=${encode(id)}&accesskey=${encode(accessKey)}&fmt=lrc&charset=utf8"
        return runCatching {
            val body = get(url) ?: return null
            val json = JSONObject(body)
            if (json.optInt("status", 0) != 200) return null

            val encoded = json.optString("content")
            if (encoded.isBlank()) return null
            val raw = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trim()
            if (raw.isBlank()) return null

            LyricsProviderResult(
                providerId = id,
                rawLyrics = raw,
                synced = hasTimestamps(raw),
            )
        }.getOrNull()
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Tunespark/3.1")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
