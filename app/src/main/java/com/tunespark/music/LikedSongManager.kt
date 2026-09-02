package com.tunespark.music

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages liked songs state and synchronizes with YouTube Music InnerTube backend.
 */
object LikedSongManager {
    private const val PREFS_NAME = "tunespark_liked_songs"
    private const val KEY_LIKED_SONG_IDS = "cached_liked_song_ids"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Reactive map of songId -> Boolean (liked status)
    private val _likedMap = mutableStateMapOf<String, Boolean>()
    val likedMap: SnapshotStateMap<String, Boolean> = _likedMap

    private var isInitialized = false

    /**
     * Initializes cached liked songs from SharedPreferences and triggers a background sync if logged in.
     */
    fun init(context: Context) {
        if (!isInitialized) {
            isInitialized = true
            loadFromPrefs(context)
        }
        if (SessionManager.isUserSignedIn(context)) {
            refreshLikedSongs(context)
        }
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_LIKED_SONG_IDS, emptySet()) ?: emptySet()
        set.forEach { id ->
            _likedMap[id] = true
        }
    }

    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val likedIds = _likedMap.filterValues { it }.keys.toSet()
        prefs.edit().putStringSet(KEY_LIKED_SONG_IDS, likedIds).apply()
    }

    /**
     * Checks whether a song is liked.
     */
    fun isLiked(songId: String): Boolean {
        if (songId.isBlank() || songId.startsWith("commentary_")) return false
        return _likedMap[songId] == true
    }

    /**
     * Records multiple liked song IDs into the map (e.g. when loading liked playlist).
     */
    fun setLikedSongs(context: Context, songIds: Collection<String>) {
        songIds.forEach { id ->
            if (id.isNotBlank() && !id.startsWith("commentary_")) {
                _likedMap[id] = true
            }
        }
        saveToPrefs(context)
    }

    /**
     * Fetches the user's liked music playlist ("LM") from YouTube Music and updates the local state.
     */
    fun refreshLikedSongs(context: Context) {
        if (!SessionManager.isUserSignedIn(context)) return
        scope.launch {
            try {
                val result = YouTube.playlist("LM")
                if (result.isSuccess) {
                    val songs = result.getOrNull()?.songs.orEmpty()
                    withContext(Dispatchers.Main) {
                        _likedMap.clear()
                        songs.forEach { song ->
                            if (song.id.isNotBlank()) {
                                _likedMap[song.id] = true
                            }
                        }
                    }
                    saveToPrefs(context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Toggles the like status of a song, updating the UI optimistically and calling InnerTube.
     */
    suspend fun toggleLike(songId: String, context: Context): Boolean {
        if (songId.isBlank() || songId.startsWith("commentary_")) return false
        if (!SessionManager.isUserSignedIn(context)) return false

        val currentlyLiked = isLiked(songId)
        val newLikedState = !currentlyLiked

        // Optimistic UI update
        withContext(Dispatchers.Main) {
            _likedMap[songId] = newLikedState
        }
        saveToPrefs(context)

        return try {
            val result = YouTube.likeVideo(songId, newLikedState)
            if (result.isSuccess) {
                true
            } else {
                // Revert on failure
                withContext(Dispatchers.Main) {
                    _likedMap[songId] = currentlyLiked
                }
                saveToPrefs(context)
                false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _likedMap[songId] = currentlyLiked
            }
            saveToPrefs(context)
            false
        }
    }

    /**
     * Clears all liked songs state (e.g. on sign out).
     */
    fun clear(context: Context) {
        _likedMap.clear()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
