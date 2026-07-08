package com.tunespark.music

import android.content.Context
import android.content.SharedPreferences
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo

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
        getPrefs(context).edit().clear().apply()
        YouTube.cookie = null
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
