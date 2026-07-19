package com.tunespark.music

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Artist
import org.json.JSONArray
import org.json.JSONObject

object SessionManager {
    private const val PREF_NAME = "tunespark_session_prefs"
    private const val KEY_COOKIE = "youtube_cookie"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_HANDLE = "user_handle"
    private const val KEY_USER_THUMBNAIL = "user_thumbnail"
    private const val KEY_GEMINI_KEY = "gemini_api_key"
    private const val KEY_ELEVENLABS_KEY = "elevenlabs_api_key"
    private const val KEY_ELEVENLABS_VOICE_ID = "elevenlabs_voice_id"
    private const val KEY_COMMENTARY_FREQUENCY = "commentary_frequency"
    private const val KEY_COMMENTARY_LENGTH = "commentary_length"
    private const val KEY_ACTIVE_TTS_PROVIDER = "active_tts_provider"
    private const val KEY_THEME = "app_theme"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_SHOW_VISUALIZER = "show_visualizer"
    private const val KEY_COMMENTARY_ENABLED = "commentary_enabled"
    private const val KEY_LOCAL_HISTORY = "local_listening_history"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes the InnerTube YouTube session with the saved cookie on app startup.
     */
    fun initialize(context: Context) {
        val cookie = getPrefs(context).getString(KEY_COOKIE, null)
        YouTube.cookie = cookie
    }

    /**
     * Checks if a user is currently signed in.
     */
    fun isUserSignedIn(context: Context): Boolean {
        return getPrefs(context).getString(KEY_COOKIE, null) != null
    }

    /**
     * Retrieves the saved cookie string.
     */
    fun getSavedCookie(context: Context): String? {
        return getPrefs(context).getString(KEY_COOKIE, null)
    }

    /**
     * Saves the cookie string and updates the active YouTube session.
     */
    fun saveCookie(context: Context, cookie: String) {
        getPrefs(context).edit()
            .putString(KEY_COOKIE, cookie)
            .apply()
        YouTube.cookie = cookie
    }

    /**
     * Saves user profile details cached locally.
     */
    fun saveAccountInfo(context: Context, accountInfo: AccountInfo) {
        getPrefs(context).edit()
            .putString(KEY_USER_NAME, accountInfo.name)
            .putString(KEY_USER_EMAIL, accountInfo.email)
            .putString(KEY_USER_HANDLE, accountInfo.channelHandle)
            .putString(KEY_USER_THUMBNAIL, accountInfo.thumbnailUrl)
            .apply()
    }

    /**
     * Retrieves cached user profile details.
     */
    fun getCachedAccountInfo(context: Context): AccountInfo? {
        val name = getPrefs(context).getString(KEY_USER_NAME, null) ?: return null
        val email = getPrefs(context).getString(KEY_USER_EMAIL, null)
        val handle = getPrefs(context).getString(KEY_USER_HANDLE, null)
        val thumbnail = getPrefs(context).getString(KEY_USER_THUMBNAIL, null)
        return AccountInfo(
            name = name,
            email = email,
            channelHandle = handle,
            thumbnailUrl = thumbnail
        )
    }

    /**
     * Clears all session data (logout).
     */
    fun clearSession(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_COOKIE)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_HANDLE)
            .remove(KEY_USER_THUMBNAIL)
            .apply()
        YouTube.cookie = null
    }

    /**
     * Adds a song to local listening history.
     */
    fun addSongToHistory(context: Context, song: SongItem) {
        val prefs = getPrefs(context)
        val historyStr = prefs.getString(KEY_LOCAL_HISTORY, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(historyStr)
            
            // If the song is identical to the most recent one (at index 0), do not record it consecutively
            if (jsonArray.length() > 0) {
                val lastSong = jsonArray.getJSONObject(0)
                if (lastSong.getString("id") == song.id) {
                    return // skip adding consecutively
                }
            }
            
            val newArray = JSONArray()
            
            // Add the new song at the top/index 0
            val newSongJson = JSONObject().apply {
                put("id", song.id)
                put("title", song.title)
                put("thumbnail", song.thumbnail)
                put("timestamp", System.currentTimeMillis())
                val artistsArray = JSONArray()
                song.artists.forEach { artist ->
                    artistsArray.put(JSONObject().apply {
                        put("name", artist.name)
                        put("id", artist.id ?: "")
                    })
                }
                put("artists", artistsArray)
            }
            newArray.put(newSongJson)
            
            // Append all previous items (do not remove existing duplicates!)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                newArray.put(item)
            }
            
            // Limit local history size to 1000 songs to prevent SharedPreferences from growing too large
            val finalArray = JSONArray()
            val limit = Math.min(newArray.length(), 1000)
            for (i in 0 until limit) {
                finalArray.put(newArray.get(i))
            }
            
            prefs.edit().putString(KEY_LOCAL_HISTORY, finalArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Retrieves the local listening history.
     */
    fun getLocalHistory(context: Context): List<SongItem> {
        val prefs = getPrefs(context)
        val historyStr = prefs.getString(KEY_LOCAL_HISTORY, "[]") ?: "[]"
        val songsList = mutableListOf<SongItem>()
        try {
            val jsonArray = JSONArray(historyStr)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val id = item.getString("id")
                val title = item.getString("title")
                val thumbnail = item.getString("thumbnail")
                
                val artistsList = mutableListOf<Artist>()
                val artistsArray = item.getJSONArray("artists")
                for (j in 0 until artistsArray.length()) {
                    val artistObj = artistsArray.getJSONObject(j)
                    val artistName = artistObj.getString("name")
                    val artistId = artistObj.optString("id").ifBlank { null }
                    artistsList.add(Artist(name = artistName, id = artistId))
                }
                
                songsList.add(
                    SongItem(
                        id = id,
                        title = title,
                        artists = artistsList,
                        thumbnail = thumbnail
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songsList
    }

    fun getLocalHistoryWithTimestamps(context: Context): List<Pair<SongItem, Long>> {
        val prefs = getPrefs(context)
        val historyStr = prefs.getString(KEY_LOCAL_HISTORY, "[]") ?: "[]"
        val songsList = mutableListOf<Pair<SongItem, Long>>()
        try {
            val jsonArray = JSONArray(historyStr)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val id = item.getString("id")
                val title = item.getString("title")
                val thumbnail = item.getString("thumbnail")
                val timestamp = item.optLong("timestamp", System.currentTimeMillis() - i * 60000L)
                
                val artistsList = mutableListOf<Artist>()
                val artistsArray = item.getJSONArray("artists")
                for (j in 0 until artistsArray.length()) {
                    val artistObj = artistsArray.getJSONObject(j)
                    val artistName = artistObj.getString("name")
                    val artistId = artistObj.optString("id").ifBlank { null }
                    artistsList.add(Artist(name = artistName, id = artistId))
                }
                
                songsList.add(
                    Pair(
                        SongItem(
                            id = id,
                            title = title,
                            artists = artistsList,
                            thumbnail = thumbnail
                        ),
                        timestamp
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songsList
    }

    fun getGeminiApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_GEMINI_KEY, "") ?: ""
    }

    fun saveGeminiApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_GEMINI_KEY, key).apply()
    }

    fun getElevenLabsApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_ELEVENLABS_KEY, "") ?: ""
    }

    fun saveElevenLabsApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_ELEVENLABS_KEY, key).apply()
    }

    fun getElevenLabsVoiceId(context: Context): String {
        return getPrefs(context).getString(KEY_ELEVENLABS_VOICE_ID, "EXAVITQu4vr4xnSDxMaL") ?: "EXAVITQu4vr4xnSDxMaL"
    }

    fun saveElevenLabsVoiceId(context: Context, voiceId: String) {
        getPrefs(context).edit().putString(KEY_ELEVENLABS_VOICE_ID, voiceId).apply()
    }

    fun isCommentaryEnabled(context: Context): Boolean {
        if (getGeminiApiKey(context).isBlank()) return false
        return getPrefs(context).getBoolean(KEY_COMMENTARY_ENABLED, true)
    }

    fun saveCommentaryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMMENTARY_ENABLED, enabled).apply()
    }

    fun getCommentaryFrequency(context: Context): Float {
        return getPrefs(context).getFloat(KEY_COMMENTARY_FREQUENCY, 0.5f)
    }

    fun saveCommentaryFrequency(context: Context, frequency: Float) {
        getPrefs(context).edit().putFloat(KEY_COMMENTARY_FREQUENCY, frequency).apply()
    }

    fun getCommentaryLength(context: Context): Float {
        return getPrefs(context).getFloat(KEY_COMMENTARY_LENGTH, 0.5f)
    }

    fun saveCommentaryLength(context: Context, length: Float) {
        getPrefs(context).edit().putFloat(KEY_COMMENTARY_LENGTH, length).apply()
    }

    fun getActiveTtsProvider(context: Context): String {
        if (getElevenLabsApiKey(context).isBlank()) return "Gemini"
        return getPrefs(context).getString(KEY_ACTIVE_TTS_PROVIDER, "Gemini") ?: "Gemini"
    }

    fun saveActiveTtsProvider(context: Context, provider: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_TTS_PROVIDER, provider).apply()
    }

    fun getCommentaryBlockSize(context: Context): Int {
        val freq = getCommentaryFrequency(context)
        return Math.round(freq * 7).toInt() + 1
    }

    fun getTheme(context: Context): String {
        return getPrefs(context).getString(KEY_THEME, "System") ?: "System"
    }

    fun saveTheme(context: Context, theme: String) {
        getPrefs(context).edit().putString(KEY_THEME, theme).apply()
    }

    fun getKeepScreenOn(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_KEEP_SCREEN_ON, true)
    }

    fun saveKeepScreenOn(context: Context, keepOn: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply()
    }

    fun getShowVisualizer(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_VISUALIZER, true)
    }

    fun saveShowVisualizer(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_VISUALIZER, show).apply()
    }
}
