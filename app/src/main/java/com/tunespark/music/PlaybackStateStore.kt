package com.tunespark.music

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persists the last playback snapshot (the currently playing song and its
 * position) so the app can resume it — paused, at the saved timestamp — after
 * the process is closed or killed, exactly like mainstream music players.
 *
 * Storage follows the same SharedPreferences + JSON pattern used by
 * [SessionManager] (listening history) and [LocalPlaylistManager] (playlists):
 * a single JSON string snapshot in a dedicated prefs file.
 *
 * Written by [PlaybackService] on every song transition, periodically while a
 * song is playing (covers unexpected system kills), and on service teardown
 * (covers swipe-away app close — the player is paused there, so the saved
 * position is final). Cleared whenever the user stops playback (Stop Radio) so
 * a deliberately stopped session never resurrects.
 */
object PlaybackStateStore {

    /** A persisted snapshot of the last playback state. */
    data class SavedPlayback(
        val videoId: String,
        val title: String,
        val artist: String,
        val thumbnail: String,
        val positionMs: Long,
        val savedAt: Long
    )

    private const val PREF_NAME = "tunespark_playback_state"
    private const val KEY_SNAPSHOT = "last_playback_snapshot"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves a playback snapshot. Calls with a blank [videoId] are ignored so a
     * half-built item can never overwrite a good snapshot.
     */
    fun save(
        context: Context,
        videoId: String,
        title: String,
        artist: String,
        thumbnail: String,
        positionMs: Long
    ) {
        if (videoId.isBlank()) return
        val json = JSONObject()
            .put("videoId", videoId)
            .put("title", title)
            .put("artist", artist)
            .put("thumbnail", thumbnail)
            .put("positionMs", positionMs.coerceAtLeast(0L))
            .put("savedAt", System.currentTimeMillis())
        getPrefs(context).edit().putString(KEY_SNAPSHOT, json.toString()).apply()
    }

    /** Returns the last saved snapshot, or null when none exists / it is corrupt. */
    fun load(context: Context): SavedPlayback? {
        return try {
            val raw = getPrefs(context).getString(KEY_SNAPSHOT, null) ?: return null
            val json = JSONObject(raw)
            val videoId = json.optString("videoId", "")
            if (videoId.isBlank()) return null
            SavedPlayback(
                videoId = videoId,
                title = json.optString("title", ""),
                artist = json.optString("artist", ""),
                thumbnail = json.optString("thumbnail", ""),
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                savedAt = json.optLong("savedAt", 0L)
            )
        } catch (e: Exception) {
            android.util.Log.e("PlaybackStateStore", "Failed to load playback snapshot", e)
            null
        }
    }

    /** Removes the snapshot (used when the user deliberately stops playback). */
    fun clear(context: Context) {
        getPrefs(context).edit().remove(KEY_SNAPSHOT).apply()
    }
}