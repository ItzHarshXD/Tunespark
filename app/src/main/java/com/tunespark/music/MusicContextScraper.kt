package com.tunespark.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MusicContextData(
    val title: String,
    val artist: String,
    val musicBrainzAlbum: String? = null,
    val musicBrainzReleaseDate: String? = null,
    val musicBrainzInfo: String? = null,
    val wikidataDescription: String? = null,
    val wikipediaProse: String? = null
)

object MusicContextScraper {
    private const val TAG = "MusicContextScraper"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT = "TuneSpark/1.24.2 ( mailto:harsh@tunespark.com )"

    suspend fun fetchMusicContext(title: String, artist: String): MusicContextData = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(artist)

        android.util.Log.d(TAG, "Fetching music context for: '$cleanTitle' by '$cleanArtist'")

        val musicBrainzDeferred = async { fetchMusicBrainz(cleanTitle, cleanArtist) }
        val wikidataDeferred = async { fetchWikidata(cleanTitle, cleanArtist) }
        val wikipediaDeferred = async { fetchWikipedia(cleanTitle, cleanArtist) }

        val mbResult = try { musicBrainzDeferred.await() } catch (e: Exception) {
            android.util.Log.e(TAG, "MusicBrainz failed: ${e.message}")
            null
        }
        val wdResult = try { wikidataDeferred.await() } catch (e: Exception) {
            android.util.Log.e(TAG, "Wikidata failed: ${e.message}")
            null
        }
        val wpResult = try { wikipediaDeferred.await() } catch (e: Exception) {
            android.util.Log.e(TAG, "Wikipedia failed: ${e.message}")
            null
        }

        MusicContextData(
            title = title,
            artist = artist,
            musicBrainzAlbum = mbResult?.first,
            musicBrainzReleaseDate = mbResult?.second,
            musicBrainzInfo = mbResult?.third,
            wikidataDescription = wdResult,
            wikipediaProse = wpResult
        )
    }

    private fun cleanSearchTerm(term: String): String {
        val cleanupPatterns = listOf(
            Regex("""\s*\(.*?((?i)official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)"""),
            Regex("""\s*\[.*?((?i)official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]"""),
            Regex("""\s*【.*?】"""),
            Regex("""\s*\|.*$"""),
            Regex("""\s*-\s*((?i)official|video|audio|lyrics|lyric|visualizer).*$"""),
            Regex("""\s*\((?i)feat\..*?\)"""),
            Regex("""\s*\((?i)ft\..*?\)"""),
            Regex("""\s*(?i)feat\..*$"""),
            Regex("""\s*(?i)ft\..*$""")
        )
        var cleaned = term.trim()
        for (pattern in cleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun fetchMusicBrainz(title: String, artist: String): Triple<String?, String?, String?>? {
        try {
            val query = "recording:\"$title\" AND artist:\"$artist\""
            val url = "https://musicbrainz.org/ws/2/recording/?query=${Uri.encode(query)}&fmt=json"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val json = JSONObject(bodyString)
                val recordings = json.optJSONArray("recordings") ?: return null
                if (recordings.length() == 0) return null

                val rec = recordings.getJSONObject(0)
                val releases = rec.optJSONArray("releases")
                var albumTitle: String? = null
                var releaseDate: String? = null
                var extraInfo: String? = null

                if (releases != null && releases.length() > 0) {
                    val rel = releases.getJSONObject(0)
                    albumTitle = rel.optString("title").takeIf { it.isNotBlank() }
                    releaseDate = rel.optString("date").takeIf { it.isNotBlank() }
                    val labelInfoArray = rel.optJSONArray("label-info")
                    if (labelInfoArray != null && labelInfoArray.length() > 0) {
                        val labelObj = labelInfoArray.getJSONObject(0).optJSONObject("label")
                        val labelName = labelObj?.optString("name")
                        if (!labelName.isNullOrBlank()) {
                            extraInfo = "Released under label: $labelName"
                        }
                    }
                }

                return Triple(albumTitle, releaseDate, extraInfo)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error fetching from MusicBrainz: ${e.message}")
        }
        return null
    }

    private fun fetchWikidata(title: String, artist: String): String? {
        try {
            val query = "$title $artist"
            val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${Uri.encode(query)}&language=en&format=json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val json = JSONObject(bodyString)
                val searchArray = json.optJSONArray("search") ?: return null
                if (searchArray.length() == 0) return null

                val item = searchArray.getJSONObject(0)
                val description = item.optString("description").takeIf { it.isNotBlank() }
                return description
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error fetching from Wikidata: ${e.message}")
        }
        return null
    }

    private fun fetchWikipedia(title: String, artist: String): String? {
        try {
            // Search Wikipedia
            val searchQuery = "$title song $artist"
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${Uri.encode(searchQuery)}&format=json"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            var pageTitle: String? = null
            client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val json = JSONObject(bodyString)
                val queryObj = json.optJSONObject("query") ?: return null
                val searchArray = queryObj.optJSONArray("search") ?: return null
                if (searchArray.length() == 0) return null

                val firstMatch = searchArray.getJSONObject(0)
                pageTitle = firstMatch.optString("title").takeIf { it.isNotBlank() }
            }

            if (pageTitle == null) return null

            // Fetch Page Intro Extract
            val extractUrl = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro&explaintext&titles=${Uri.encode(pageTitle)}&format=json"
            val extractRequest = Request.Builder()
                .url(extractUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(extractRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string() ?: return null
                val json = JSONObject(bodyString)
                val queryObj = json.optJSONObject("query") ?: return null
                val pagesObj = queryObj.optJSONObject("pages") ?: return null
                val keys = pagesObj.keys()
                if (keys.hasNext()) {
                    val key = keys.next()
                    val pageObj = pagesObj.getJSONObject(key)
                    val extract = pageObj.optString("extract").takeIf { it.isNotBlank() }
                    return extract
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error fetching from Wikipedia: ${e.message}")
        }
        return null
    }
}
