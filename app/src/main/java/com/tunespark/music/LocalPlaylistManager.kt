package com.tunespark.music

import android.content.Context
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages on-device (local) playlists.
 *
 * Local playlists are a first-class citizen of the Library: they work fully in the
 * signed-out state (create / rename / delete / add songs / remove songs) and they
 * intentionally NEVER sync with YouTube Music. They are persisted as JSON inside
 * SharedPreferences — the same proven pattern [SessionManager] uses for the local
 * listening history — so they survive app restarts, sign-in and sign-out.
 *
 * A local playlist id is always prefixed with [LOCAL_ID_PREFIX] so every other part
 * of the app (grid, quick-action sheet, remove-song routing) can tell a local list
 * apart from a real YouTube Music playlist at a glance.
 */
object LocalPlaylistManager {

    private const val PREFS_NAME = "tunespark_local_playlists"
    private const val KEY_PLAYLISTS = "local_playlists_json"

    /** Every local playlist id starts with this marker. */
    const val LOCAL_ID_PREFIX = "local_"

    /** Immutable snapshot of one local playlist. */
    data class LocalPlaylist(
        val id: String,
        val name: String,
        val createdAt: Long,
        val songs: List<SongItem>
    ) {
        val songCountText: String
            get() = when (songs.size) {
                0 -> "No songs"
                1 -> "1 song"
                else -> "${songs.size} songs"
            }

        /** Best available artwork: the first song's thumbnail. */
        val thumbnail: String?
            get() = songs.firstOrNull { it.thumbnail.isNotBlank() }?.thumbnail
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when [playlistId] belongs to a locally-stored playlist. */
    fun isLocalPlaylistId(playlistId: String?): Boolean =
        playlistId != null && playlistId.startsWith(LOCAL_ID_PREFIX)

    /** Returns every local playlist, newest first. */
    fun getPlaylists(context: Context): List<LocalPlaylist> {
        val raw = prefs(context).getString(KEY_PLAYLISTS, "[]") ?: "[]"
        val result = mutableListOf<LocalPlaylist>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isBlank()) continue
                result.add(
                    LocalPlaylist(
                        id = id,
                        name = obj.optString("name").ifBlank { "Playlist" },
                        createdAt = obj.optLong("createdAt", 0L),
                        songs = parseSongs(obj.optJSONArray("songs"))
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result.sortedByDescending { it.createdAt }
    }

    /** Reads a single local playlist by id, or null when it no longer exists. */
    fun getPlaylist(context: Context, playlistId: String): LocalPlaylist? =
        getPlaylists(context).firstOrNull { it.id == playlistId }

    /** Creates a new (empty) local playlist and returns it. */
    fun createPlaylist(context: Context, name: String): LocalPlaylist {
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = LOCAL_ID_PREFIX + now,
            name = name.trim().ifBlank { "New playlist" },
            createdAt = now,
            songs = emptyList()
        )
        persist(context, getPlaylists(context) + playlist)
        return playlist
    }

    /** Renames a local playlist. Returns true when it was found and updated. */
    fun renamePlaylist(context: Context, playlistId: String, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return false
        val current = getPlaylists(context)
        if (current.none { it.id == playlistId }) return false
        persist(context, current.map { if (it.id == playlistId) it.copy(name = cleanName) else it })
        return true
    }

    /** Deletes a local playlist. Returns true when it existed. */
    fun deletePlaylist(context: Context, playlistId: String): Boolean {
        val current = getPlaylists(context)
        if (current.none { it.id == playlistId }) return false
        persist(context, current.filterNot { it.id == playlistId })
        return true
    }

    /**
     * Adds a song to a local playlist. Duplicate video ids are ignored (so tapping
     * "Add to playlist" twice doesn't create duplicate rows).
     */
    fun addSong(context: Context, playlistId: String, song: SongItem): Boolean {
        if (song.id.isBlank()) return false
        val current = getPlaylists(context)
        val target = current.firstOrNull { it.id == playlistId } ?: return false
        if (target.songs.any { it.id == song.id }) return false
        persist(context, current.map { if (it.id == playlistId) it.copy(songs = it.songs + song) else it })
        return true
    }

    /** Removes every occurrence of [songId] from a local playlist. */
    fun removeSong(context: Context, playlistId: String, songId: String): Boolean {
        val current = getPlaylists(context)
        val target = current.firstOrNull { it.id == playlistId } ?: return false
        if (target.songs.none { it.id == songId }) return false
        persist(
            context,
            current.map {
                if (it.id == playlistId) it.copy(songs = it.songs.filterNot { s -> s.id == songId }) else it
            }
        )
        return true
    }

    /** Replaces the whole song list of a local playlist (used for future reordering). */
    fun setSongs(context: Context, playlistId: String, songs: List<SongItem>): Boolean {
        val current = getPlaylists(context)
        if (current.none { it.id == playlistId }) return false
        persist(context, current.map { if (it.id == playlistId) it.copy(songs = songs) else it })
        return true
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private fun persist(context: Context, playlists: List<LocalPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            val obj = JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("createdAt", playlist.createdAt)
                val songsArray = JSONArray()
                playlist.songs.forEach { song ->
                    songsArray.put(songToJson(song))
                }
                put("songs", songsArray)
            }
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    private fun songToJson(song: SongItem): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("thumbnail", song.thumbnail)
        song.duration?.let { put("duration", it) }
        song.album?.name?.let { put("album", it) }
        val artistsArray = JSONArray()
        song.artists.forEach { artist ->
            artistsArray.put(
                JSONObject().apply {
                    put("name", artist.name)
                    put("id", artist.id ?: "")
                }
            )
        }
        put("artists", artistsArray)
    }

    private fun parseSongs(array: JSONArray?): List<SongItem> {
        if (array == null) return emptyList()
        val songs = mutableListOf<SongItem>()
        for (i in 0 until array.length()) {
            try {
                val item = array.getJSONObject(i)
                val id = item.optString("id")
                if (id.isBlank()) continue
                songs.add(songFromJson(id, item))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return songs
    }

    private fun songFromJson(id: String, item: JSONObject): SongItem {
        val artists = mutableListOf<Artist>()
        item.optJSONArray("artists")?.let { artistsArray ->
            for (j in 0 until artistsArray.length()) {
                val artistObj = artistsArray.getJSONObject(j)
                artists.add(
                    Artist(
                        name = artistObj.optString("name"),
                        id = artistObj.optString("id").ifBlank { null }
                    )
                )
            }
        }
        val albumName = item.optString("album").ifBlank { null }
        return SongItem(
            id = id,
            title = item.optString("title"),
            artists = artists,
            album = albumName?.let { com.metrolist.innertube.models.Album(name = it, id = "") },
            duration = if (item.has("duration")) item.optInt("duration") else null,
            thumbnail = item.optString("thumbnail")
        )
    }
}