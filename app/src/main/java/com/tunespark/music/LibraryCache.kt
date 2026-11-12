package com.tunespark.music

import android.content.Context
import com.metrolist.innertube.models.SongItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent "footprint" of the user's YouTube Music library.
 *
 * The Library screen is normally built from live InnerTube browse calls. When
 * the device is offline those calls fail, so this cache keeps the last known
 * playlists, artists and playlist tracklists on disk. The Library can then
 * still render its grid, open a playlist and show its songs — with the
 * non-downloaded ones greyed out — while downloaded songs keep playing from
 * their local files.
 *
 * Only account-backed data is stored here. On-device playlists already persist
 * themselves in [LocalPlaylistManager] and downloads are always read live from
 * [DownloadManager].
 */
object LibraryCache {

    private const val PREFS_NAME = "tunespark_library_cache"
    private const val KEY_GRID = "library_grid"
    private const val KEY_PLAYLISTS = "library_playlists"
    private const val UPDATED_AT = "updated_at"

    data class CachedLibraryItem(
        val id: String,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val isLiked: Boolean,
        val authorName: String?,
        val authorAvatarUrl: String?,
        val isArtist: Boolean,
        val isLocal: Boolean
    )

    data class CachedPlaylist(
        val songs: List<SongItem>,
        val name: String?,
        val thumbnail: String?,
        val songCountText: String?,
        val authorName: String?,
        val authorAvatarUrl: String?,
        val editable: Boolean?,
        val savedAt: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── library grid ────────────────────────────────────────────────────────

    fun saveGrid(context: Context, items: List<CachedLibraryItem>) {
        try {
            // Local playlists are re-read live from LocalPlaylistManager, so they
            // are deliberately not duplicated in the cache.
            val accountItems = items.filterNot { it.isLocal }
            val array = JSONArray()
            accountItems.forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("subtitle", item.subtitle)
                        put("thumbnailUrl", item.thumbnailUrl ?: "")
                        put("isLiked", item.isLiked)
                        put("authorName", item.authorName ?: "")
                        put("authorAvatarUrl", item.authorAvatarUrl ?: "")
                        put("isArtist", item.isArtist)
                    }
                )
            }
            prefs(context).edit()
                .putString(KEY_GRID, array.toString())
                .putLong(UPDATED_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadGrid(context: Context): List<CachedLibraryItem> {
        val raw = prefs(context).getString(KEY_GRID, "[]") ?: "[]"
        val result = mutableListOf<CachedLibraryItem>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isBlank()) continue
                result.add(
                    CachedLibraryItem(
                        id = id,
                        title = obj.optString("title"),
                        subtitle = obj.optString("subtitle"),
                        thumbnailUrl = obj.optString("thumbnailUrl").ifBlank { null },
                        isLiked = obj.optBoolean("isLiked", false),
                        authorName = obj.optString("authorName").ifBlank { null },
                        authorAvatarUrl = obj.optString("authorAvatarUrl").ifBlank { null },
                        isArtist = obj.optBoolean("isArtist", false),
                        isLocal = false
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    // ── playlist tracklists ─────────────────────────────────────────────────

    fun savePlaylist(
        context: Context,
        playlistId: String,
        songs: List<SongItem>,
        name: String? = null,
        thumbnail: String? = null,
        songCountText: String? = null,
        authorName: String? = null,
        authorAvatarUrl: String? = null,
        editable: Boolean? = null
    ) {
        if (playlistId.isBlank()) return
        try {
            val all = JSONObject(prefs(context).getString(KEY_PLAYLISTS, "{}") ?: "{}")
            all.put(
                playlistId,
                JSONObject().apply {
                    put("songs", SongJson.toJsonArray(songs))
                    put("name", name ?: "")
                    put("thumbnail", thumbnail ?: "")
                    put("songCountText", songCountText ?: "")
                    put("authorName", authorName ?: "")
                    put("authorAvatarUrl", authorAvatarUrl ?: "")
                    editable?.let { put("editable", it) }
                    put("savedAt", System.currentTimeMillis())
                }
            )
            prefs(context).edit().putString(KEY_PLAYLISTS, all.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadPlaylist(context: Context, playlistId: String): CachedPlaylist? {
        if (playlistId.isBlank()) return null
        return try {
            val all = JSONObject(prefs(context).getString(KEY_PLAYLISTS, "{}") ?: "{}")
            val obj = all.optJSONObject(playlistId) ?: return null
            val songs = SongJson.fromJsonArray(obj.optJSONArray("songs"))
            if (songs.isEmpty()) return null
            CachedPlaylist(
                songs = songs,
                name = obj.optString("name").ifBlank { null },
                thumbnail = obj.optString("thumbnail").ifBlank { null },
                songCountText = obj.optString("songCountText").ifBlank { null },
                authorName = obj.optString("authorName").ifBlank { null },
                authorAvatarUrl = obj.optString("authorAvatarUrl").ifBlank { null },
                editable = if (obj.has("editable")) obj.optBoolean("editable") else null,
                savedAt = obj.optLong("savedAt", 0L)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Removes a cached playlist (e.g. after the user deletes it). */
    fun removePlaylist(context: Context, playlistId: String) {
        try {
            val all = JSONObject(prefs(context).getString(KEY_PLAYLISTS, "{}") ?: "{}")
            if (!all.has(playlistId)) return
            all.remove(playlistId)
            prefs(context).edit().putString(KEY_PLAYLISTS, all.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}