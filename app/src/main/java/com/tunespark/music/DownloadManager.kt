package com.tunespark.music

import android.content.Context
import android.net.Uri
import com.metrolist.innertube.models.SongItem
import com.tunespark.music.provider.AudioProvider
import com.tunespark.music.provider.AudioProviderIds
import com.tunespark.music.provider.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * On-device song downloads.
 *
 * A download is a real audio file stored inside the app's private files
 * directory plus a small JSON record carrying the song metadata and the
 * verified provider/codec/bitrate that was fetched. Once a song is downloaded
 * it becomes playable with no network at all: both the eager playback path in
 * `MainActivity` and the lazy queue resolution in `PlaybackService` prefer the
 * local file over a remote stream.
 *
 * Downloads are intentionally independent from the YouTube Music account. They
 * live on this device, survive sign-in/sign-out, and are exposed in the Library
 * through the special, non-editable [DOWNLOADS_PLAYLIST_ID] playlist.
 */
object DownloadManager {

    const val DOWNLOADS_PLAYLIST_ID = "downloads"
    private const val PREFS_NAME = "tunespark_downloads"
    private const val KEY_DOWNLOADS = "downloads_json"
    private const val DOWNLOADS_DIR = "downloads"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    data class DownloadedSong(
        val song: SongItem,
        val fileName: String,
        val downloadedAt: Long,
        val provider: AudioProvider,
        val codec: String?,
        val bitrateKbps: Int?
    ) {
        fun file(context: Context): File = File(downloadsDir(context), fileName)
    }

    fun isDownloadsPlaylist(playlistId: String?): Boolean =
        playlistId == DOWNLOADS_PLAYLIST_ID

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun downloadsDir(context: Context): File {
        val dir = File(context.filesDir, DOWNLOADS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Bare id used for matching regardless of the `#q<n>` queue qualifier. */
    private fun bareId(songId: String): String = AudioProviderIds.bareMediaId(songId)

    /** Every downloaded song, newest first (records whose file vanished are ignored). */
    fun getDownloadedSongs(context: Context): List<DownloadedSong> {
        val raw = prefs(context).getString(KEY_DOWNLOADS, "[]") ?: "[]"
        val result = mutableListOf<DownloadedSong>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val song = SongJson.fromJson(obj.optJSONObject("song") ?: continue) ?: continue
                val fileName = obj.optString("fileName")
                if (song.id.isBlank() || fileName.isBlank()) continue
                val provider = try {
                    AudioProvider.valueOf(obj.optString("provider", AudioProvider.YOUTUBE_MUSIC.name))
                } catch (e: Exception) {
                    AudioProvider.YOUTUBE_MUSIC
                }
                val entry = DownloadedSong(
                    song = song,
                    fileName = fileName,
                    downloadedAt = obj.optLong("downloadedAt", 0L),
                    provider = provider,
                    codec = obj.optString("codec").ifBlank { null },
                    bitrateKbps = obj.optInt("bitrateKbps", -1).takeIf { it > 0 }
                )
                if (entry.file(context).exists()) result.add(entry)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.sortedByDescending { it.downloadedAt }
    }

    /** Ids (bare) of every downloaded song — used to grey out offline rows. */
    fun downloadedSongIds(context: Context): Set<String> =
        getDownloadedSongs(context).mapTo(mutableSetOf()) { bareId(it.song.id) }

    fun getDownloadedSong(context: Context, songId: String): DownloadedSong? {
        val target = bareId(songId)
        return getDownloadedSongs(context).firstOrNull { bareId(it.song.id) == target }
    }

    fun isDownloaded(context: Context, songId: String): Boolean =
        getDownloadedSong(context, songId) != null

    fun songCountText(context: Context): String {
        val count = getDownloadedSongs(context).size
        return when (count) {
            0 -> "No songs"
            1 -> "1 song"
            else -> "$count songs"
        }
    }

    fun firstThumbnail(context: Context): String? =
        getDownloadedSongs(context).firstOrNull { it.song.thumbnail.isNotBlank() }?.song?.thumbnail

    /**
     * A playable local stream for [songId], or null when it is not downloaded.
     * The stored provider/codec/bitrate describe where the file originally came
     * from, so the Now Playing stats line stays truthful.
     */
    fun downloadedStream(context: Context, songId: String): ResolvedStream? {
        val entry = getDownloadedSong(context, songId) ?: return null
        val file = entry.file(context)
        if (!file.exists() || file.length() <= 0L) return null
        return ResolvedStream(
            url = Uri.fromFile(file).toString(),
            provider = entry.provider,
            codec = entry.codec,
            bitrateKbps = entry.bitrateKbps
        )
    }

    /**
     * Downloads one song. Returns true when the song is available locally
     * afterwards (including the already-downloaded case).
     */
    suspend fun downloadSong(context: Context, song: SongItem): Boolean = withContext(Dispatchers.IO) {
        if (song.id.isBlank()) return@withContext false
        val key = bareId(song.id)
        if (isDownloaded(context, key)) return@withContext true
        if (!inFlight.add(key)) return@withContext false

        var tempFile: File? = null
        try {
            val stream = StreamUrlResolver.resolveStream(
                songId = song.id,
                title = song.title,
                artist = song.artists.joinToString(", ") { it.name },
                album = song.album?.name,
                durationSeconds = song.duration,
                jioSaavnEnabled = SessionManager.isJioSaavnEnabled(context)
            ) ?: return@withContext false

            val extension = extensionFor(stream)
            val safeName = sanitizeFileName(key)
            val fileName = "$safeName.$extension"
            val dir = downloadsDir(context)
            val finalFile = File(dir, fileName)
            val partial = File(dir, "$fileName.part")
            tempFile = partial

            val request = Request.Builder()
                .url(stream.url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                partial.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }

            if (!partial.exists() || partial.length() <= 0L) return@withContext false
            if (finalFile.exists()) finalFile.delete()
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
            if (!finalFile.exists() || finalFile.length() <= 0L) return@withContext false

            val record = JSONObject().apply {
                put("song", SongJson.toJson(song))
                put("fileName", fileName)
                put("downloadedAt", System.currentTimeMillis())
                put("provider", stream.provider.name)
                stream.codec?.takeIf { it.isNotBlank() }?.let { put("codec", it) }
                stream.bitrateKbps?.let { put("bitrateKbps", it) }
            }
            val current = readRecords(context)
            val updated = JSONArray()
            for (i in 0 until current.length()) {
                val obj = current.optJSONObject(i) ?: continue
                val existingId = obj.optJSONObject("song")?.optString("id").orEmpty()
                if (bareId(existingId) != key) updated.put(obj)
            }
            updated.put(record)
            prefs(context).edit().putString(KEY_DOWNLOADS, updated.toString()).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            tempFile?.delete()
            false
        } finally {
            inFlight.remove(key)
        }
    }

    /**
     * Downloads a whole list sequentially. [onProgress] is called with
     * (completed, total) after every song. Returns the number of songs that are
     * now available locally.
     */
    suspend fun downloadSongs(
        context: Context,
        songs: List<SongItem>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val unique = songs.distinctBy { bareId(it.id) }
        var completed = 0
        var success = 0
        for (song in unique) {
            val ok = downloadSong(context, song)
            completed++
            if (ok) success++
            withContext(Dispatchers.Main) { onProgress?.invoke(completed, unique.size) }
        }
        success
    }

    /** Deletes a downloaded song (file + record). */
    suspend fun deleteSong(context: Context, songId: String): Boolean = withContext(Dispatchers.IO) {
        val key = bareId(songId)
        val records = readRecords(context)
        var removed = false
        for (i in 0 until records.length()) {
            val obj = records.optJSONObject(i) ?: continue
            val song = obj.optJSONObject("song")?.optString("id").orEmpty()
            if (bareId(song) != key) continue
            val fileName = obj.optString("fileName")
            if (fileName.isNotBlank()) File(downloadsDir(context), fileName).delete()
            removed = true
        }
        if (removed) {
            val kept = JSONArray()
            for (i in 0 until records.length()) {
                val obj = records.optJSONObject(i) ?: continue
                val id = obj.optJSONObject("song")?.optString("id").orEmpty()
                if (bareId(id) != key) kept.put(obj)
            }
            prefs(context).edit().putString(KEY_DOWNLOADS, kept.toString()).apply()
        }
        removed
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readRecords(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_DOWNLOADS, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun extensionFor(stream: ResolvedStream): String {
        return when (stream.codec?.lowercase()) {
            "opus", "vorbis" -> "webm"
            "mp3" -> "mp3"
            "aac", "mp4a" -> "m4a"
            else -> {
                val url = stream.url.lowercase()
                when {
                    url.contains("mime=audio%2fwebm") || url.contains("mime=audio/webm") -> "webm"
                    url.contains("mime=audio%2fmp4") || url.contains("mime=audio/mp4") -> "m4a"
                    url.contains(".mp3") -> "mp3"
                    else -> "m4a"
                }
            }
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "song" }
}