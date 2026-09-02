package com.tunespark.music

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Artist
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    private const val KEY_SHOW_TIME_WEATHER = "show_time_weather"
    private const val KEY_COMMENTARY_ENABLED = "commentary_enabled"
    private const val KEY_LOCAL_HISTORY = "local_listening_history"
    private const val KEY_SELECTED_GEMINI_TEXT_MODEL = "selected_gemini_text_model"
    private const val KEY_SELECTED_GEMINI_TTS_MODEL = "selected_gemini_tts_model"
    private const val KEY_SELECTED_ELEVENLABS_MODEL = "selected_elevenlabs_model"
    private const val KEY_SELECTED_ELEVENLABS_MODEL_ID = "selected_elevenlabs_model_id"
    private const val KEY_SELECTED_ELEVENLABS_LANGUAGES = "selected_elevenlabs_languages"
    private const val KEY_DISCOVER_FEED_ENABLED = "discover_feed_enabled"
    private const val KEY_USED_BRIEFING_ARTICLE_URLS = "used_briefing_article_urls"
    private const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
    private const val KEY_RADIO_LAYOUT_STATE = "radio_layout_state"

    @Volatile
    private var cachedHistory: List<Pair<SongItem, Long>>? = null
    private val historyLock = Any()
    private val historyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes the InnerTube YouTube session with the saved cookie on app startup.
     */
    fun initialize(context: Context) {
        val cookie = getPrefs(context).getString(KEY_COOKIE, null)
        YouTube.cookie = cookie
        LikedSongManager.init(context)

        // Migrate/override old default values for first-time or existing installations
        val prefs = getPrefs(context)
        if (!prefs.contains("migrated_to_new_defaults_v2")) {
            val editor = prefs.edit()
            
            // Keep screen on should be off (false) by default
            editor.putBoolean(KEY_KEEP_SCREEN_ON, false)
            
            // ElevenLabs default model should be Eleven v3 (eleven_v3)
            editor.putString(KEY_SELECTED_ELEVENLABS_MODEL_ID, "eleven_v3")
            editor.putString(KEY_SELECTED_ELEVENLABS_MODEL, "Eleven v3")
            
            editor.putBoolean("migrated_to_new_defaults_v2", true)
            editor.apply()
        }
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
        LikedSongManager.refreshLikedSongs(context)
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
        LikedSongManager.clear(context)
    }

    /**
     * Adds a song to local listening history.
     */
    fun addSongToHistory(context: Context, song: SongItem) {
        synchronized(historyLock) {
            // Force load existing history into cache if not already loaded
            val currentList = getLocalHistoryWithTimestamps(context)
            
            // If the song is identical to the most recent one (at index 0), do not record it consecutively
            if (currentList.isNotEmpty() && currentList[0].first.id == song.id) {
                return // skip adding consecutively
            }
            
            val timestamp = System.currentTimeMillis()
            val newList = mutableListOf<Pair<SongItem, Long>>()
            newList.add(Pair(song, timestamp))
            
            // Limit to 1000 elements
            val remainingLimit = 1000 - 1
            val itemsToAdd = Math.min(currentList.size, remainingLimit)
            for (i in 0 until itemsToAdd) {
                newList.add(currentList[i])
            }
            
            // Update the cache immediately so it's instantly visible to the app
            cachedHistory = newList
            
            // Asynchronously offload serialization & shared preferences write
            historyScope.launch(Dispatchers.IO) {
                synchronized(historyLock) {
                    val listToSerialize = cachedHistory ?: newList
                    val finalArray = JSONArray()
                    for (pair in listToSerialize) {
                        val songItem = pair.first
                        val ts = pair.second
                        val songJson = JSONObject().apply {
                            put("id", songItem.id)
                            put("title", songItem.title)
                            put("thumbnail", songItem.thumbnail)
                            put("timestamp", ts)
                            val artistsArray = JSONArray()
                            songItem.artists.forEach { artist ->
                                artistsArray.put(JSONObject().apply {
                                    put("name", artist.name)
                                    put("id", artist.id ?: "")
                                })
                            }
                            put("artists", artistsArray)
                        }
                        finalArray.put(songJson)
                    }
                    getPrefs(context).edit()
                        .putString(KEY_LOCAL_HISTORY, finalArray.toString())
                        .apply()
                }
            }
        }
    }

    /**
     * Retrieves the local listening history.
     */
    fun getLocalHistory(context: Context): List<SongItem> {
        return getLocalHistoryWithTimestamps(context).map { it.first }
    }

    fun getLocalHistoryWithTimestamps(context: Context): List<Pair<SongItem, Long>> {
        cachedHistory?.let { return it }
        synchronized(historyLock) {
            cachedHistory?.let { return it }
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
            cachedHistory = songsList
            return songsList
        }
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
        return getPrefs(context).getBoolean(KEY_KEEP_SCREEN_ON, false)
    }

    fun saveKeepScreenOn(context: Context, keepOn: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply()
    }

    fun getShowTimeWeather(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_TIME_WEATHER, true)
    }

    fun saveShowTimeWeather(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_TIME_WEATHER, show).apply()
    }

    fun getSelectedGeminiTextModel(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_GEMINI_TEXT_MODEL, "Gemini 3.5 Flash Lite") ?: "Gemini 3.5 Flash Lite"
    }

    fun saveSelectedGeminiTextModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_GEMINI_TEXT_MODEL, model).apply()
    }

    fun getSelectedGeminiTtsModel(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_GEMINI_TTS_MODEL, "Gemini 3.1 Flash TTS") ?: "Gemini 3.1 Flash TTS"
    }

    fun saveSelectedGeminiTtsModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_GEMINI_TTS_MODEL, model).apply()
    }

    fun getSelectedElevenLabsModel(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_ELEVENLABS_MODEL, "Eleven v3") ?: "Eleven v3"
    }

    fun saveSelectedElevenLabsModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_ELEVENLABS_MODEL, model).apply()
    }

    /**
     * Returns the selected ElevenLabs model ID (e.g. "eleven_v3", "eleven_multilingual_v2", "eleven_flash_v2_5").
     * Defaults to "eleven_multilingual_v2".
     */
    fun getSelectedElevenLabsModelId(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_ELEVENLABS_MODEL_ID, "eleven_v3") ?: "eleven_v3"
    }

    fun saveSelectedElevenLabsModelId(context: Context, modelId: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_ELEVENLABS_MODEL_ID, modelId).apply()
    }

    /**
     * Returns the set of selected ElevenLabs language IDs (e.g. "en", "hi", "es").
     * Defaults to an empty set (let the API auto-detect).
     */
    fun getSelectedElevenLabsLanguages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_SELECTED_ELEVENLABS_LANGUAGES, emptySet()) ?: emptySet()
    }

    fun saveSelectedElevenLabsLanguages(context: Context, languages: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_SELECTED_ELEVENLABS_LANGUAGES, languages).apply()
    }

    fun getDiscoverCategories(context: Context): List<Pair<String, Boolean>> {
        val categories = listOf(
            "🤖 AI", "💻 Tech", "🚀 Space", "🔬 Science", "🚗 Cars & EVs", "🎮 Gaming", "🎬 Movies & TV", "💼 Business & Startups", "💰 Finance", "🧠 Mind & Productivity", "🌍 World", "🎵 Music", "⚽ Sports", "👗 Fashion", "🍳 Food", "✈️ Travel"
        )
        val prefs = getPrefs(context)
        return categories.map { category ->
            category to prefs.getBoolean("discover_category_$category", true)
        }
    }

    fun saveDiscoverCategory(context: Context, category: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean("discover_category_$category", enabled).apply()
    }

    fun getSelectedCommentary(context: Context): Set<String> {
        return getPrefs(context).getStringSet("selected_commentary_elements", setOf("Session opener", "Humour", "Briefing", "Music Context")) ?: setOf("Session opener", "Humour", "Briefing", "Music Context")
    }

    fun saveSelectedCommentary(context: Context, elements: Set<String>) {
        getPrefs(context).edit().putStringSet("selected_commentary_elements", elements).apply()
    }

    /**
     * Returns whether the "Session opener" commentary element is enabled.
     * The session opener is the only commentary that plays at the start of a
     * music session. It should only be spoken when this element is checked.
     */
    fun isSessionOpenerEnabled(context: Context): Boolean {
        return "Session opener" in getSelectedCommentary(context)
    }

    /**
     * Returns whether any between-songs commentary elements (Humour, Briefing,
     * Music Context) are enabled. If none are selected, no between-songs
     * commentary should be generated at all.
     */
    fun hasBetweenSongsCommentaryElements(context: Context): Boolean {
        return getSelectedCommentary(context).any { it != "Session opener" }
    }

    /**
     * Returns whether the Discover feed is enabled (master toggle).
     * Defaults to true (ON).
     */
    fun isDiscoverFeedEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DISCOVER_FEED_ENABLED, true)
    }

    /**
     * Saves the Discover feed master toggle state.
     */
    fun saveDiscoverFeedEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DISCOVER_FEED_ENABLED, enabled).apply()
    }

    /**
     * Returns the set of article URLs that have already been used in a
     * Briefing commentary, so the same article is never repeated.
     */
    fun getUsedBriefingArticleUrls(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_USED_BRIEFING_ARTICLE_URLS, emptySet()) ?: emptySet()
    }

    /**
     * Marks an article URL as used in a Briefing commentary.
     */
    fun addUsedBriefingArticleUrl(context: Context, url: String) {
        val current = getUsedBriefingArticleUrls(context).toMutableSet()
        current.add(url)
        getPrefs(context).edit().putStringSet(KEY_USED_BRIEFING_ARTICLE_URLS, current).apply()
    }

    /**
     * Clears all used briefing article URLs. Called when every article in the
     * feed has been used, so the cycle can start over.
     */
    fun clearUsedBriefingArticleUrls(context: Context) {
        getPrefs(context).edit().remove(KEY_USED_BRIEFING_ARTICLE_URLS).apply()
    }

    /**
     * Retrieves the user's custom commentary instructions.
     */
    fun getCustomInstructions(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_INSTRUCTIONS, "") ?: ""
    }

    /**
     * Saves the user's custom commentary instructions.
     */
    fun saveCustomInstructions(context: Context, instructions: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_INSTRUCTIONS, instructions).apply()
    }

    /**
     * Retrieves the saved layout state of the Radio screen ("none", "lyrics", "queue").
     * Defaults to "none" (minimized vinyl disc state) for first-time users.
     */
    fun getRadioLayoutState(context: Context): String {
        return getPrefs(context).getString(KEY_RADIO_LAYOUT_STATE, "none") ?: "none"
    }

    /**
     * Saves the active layout state of the Radio screen.
     */
    fun saveRadioLayoutState(context: Context, state: String) {
        getPrefs(context).edit().putString(KEY_RADIO_LAYOUT_STATE, state).apply()
    }
}
