package com.tunespark.music

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small JSON codec for [SongItem] used by the on-device stores (local
 * playlists, downloads and the offline library cache).
 *
 * Keeping the serialization in one place means the downloaded-library cache
 * and the download records always parse songs exactly like the local playlist
 * store does, so a downloaded song survives a restart with all the metadata
 * playback needs.
 */
object SongJson {

    fun toJson(song: SongItem): JSONObject = JSONObject().apply {
        put("id", song.id)
        put("title", song.title)
        put("thumbnail", song.thumbnail)
        song.duration?.let { put("duration", it) }
        song.album?.name?.let { put("album", it) }
        song.album?.id?.let { put("albumId", it) }
        put("explicit", song.explicit)
        put("musicVideoType", song.musicVideoType ?: "")
        song.setVideoId?.let { put("setVideoId", it) }
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

    fun fromJson(item: JSONObject): SongItem? {
        val id = item.optString("id")
        if (id.isBlank()) return null
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
            album = albumName?.let { Album(name = it, id = item.optString("albumId")) },
            duration = if (item.has("duration")) item.optInt("duration") else null,
            thumbnail = item.optString("thumbnail"),
            explicit = item.optBoolean("explicit", false),
            musicVideoType = item.optString("musicVideoType").ifBlank { null },
            setVideoId = item.optString("setVideoId").ifBlank { null }
        )
    }

    fun toJsonArray(songs: List<SongItem>): JSONArray = JSONArray().apply {
        songs.forEach { put(toJson(it)) }
    }

    fun fromJsonArray(array: JSONArray?): List<SongItem> {
        if (array == null) return emptyList()
        val songs = mutableListOf<SongItem>()
        for (i in 0 until array.length()) {
            try {
                fromJson(array.getJSONObject(i))?.let { songs.add(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return songs
    }
}