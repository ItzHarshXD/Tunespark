package com.tunespark.music

import android.content.Context
import android.content.SharedPreferences
import com.tunespark.music.rss.Article
import com.tunespark.music.rss.RssRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Centralized AI Commentary Context Manager.
 *
 * This is the single reusable service that gathers and maintains all relevant
 * information for the current day, acting as the foundation for every AI
 * commentary feature (Session Opener, Humour, Briefing, Music Context).
 *
 * Instead of each feature collecting its own data independently, they all
 * request the same [CommentaryContext] object from this manager.
 *
 * Key design principles:
 *  - **Daily scoping**: The context is scoped to the current calendar day
 *    (00:00–23:59 in the user's local timezone). It automatically resets when
 *    a new day begins.
 *  - **Continuous updates**: The context is refreshed as the user listens,
 *    so it always reflects the latest state of the session.
 *  - **Modular & scalable**: New context sources can be added as fields on
 *    [CommentaryContext] without changing the commentary generation pipeline.
 */
object CommentaryContextManager {

    private const val PREFS_NAME = "tunespark_commentary_context"
    private const val KEY_CONTEXT_DATE = "context_date"
    private const val KEY_SESSION_START = "session_start_time"
    private const val KEY_SESSION_SONGS = "session_songs_played"
    private const val KEY_PREVIOUS_COMMENTARIES = "previous_commentaries"
    private const val MAX_PREVIOUS_COMMENTARIES = 10

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var cachedContext: CommentaryContext? = null

    @Volatile
    private var lastWeatherFetchTime: Long = 0L

    @Volatile
    private var cachedWeather: WeatherInfo? = null

    private val weatherCacheDurationMs = 10 * 60 * 1000L // 10 minutes

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the current day's context, resetting it automatically if a new
     * day has begun. This is the primary entry point for all commentary
     * features.
     *
     * The context is built synchronously from cached/local data where possible
     * (listening history, user name, session metadata) and asynchronously
     * refreshes network-backed sources (weather, RSS articles) in the
     * background so the returned object is always immediately usable.
     */
    fun getCurrentContext(context: Context): CommentaryContext {
        val prefs = getPrefs(context)
        val todayKey = CommentaryContext.currentDateKey()

        // Detect day rollover and reset session metadata if needed
        val storedDate = prefs.getString(KEY_CONTEXT_DATE, null)
        if (storedDate != todayKey) {
            prefs.edit()
                .putString(KEY_CONTEXT_DATE, todayKey)
                .putLong(KEY_SESSION_START, System.currentTimeMillis())
                .putInt(KEY_SESSION_SONGS, 0)
                .apply()
            cachedContext = null
        }

        // Build the context from local/cached data (fast, synchronous)
        val dailyContext = buildContext(context, prefs)

        // Cache it so repeated calls within the same day are cheap
        cachedContext = dailyContext

        // Asynchronously refresh network-backed sources (weather, RSS)
        refreshNetworkContext(context)

        return dailyContext
    }

    /**
     * Records a generated commentary script so the AI has awareness of what
     * was already covered during the day. This prevents the briefing (and
     * other elements) from repeating the same topics or articles.
     *
     * Called by the playback pipeline after a commentary is generated.
     */
    fun recordCommentary(context: Context, script: String) {
        val prefs = getPrefs(context)
        val todayKey = CommentaryContext.currentDateKey()
        val storedDate = prefs.getString(KEY_CONTEXT_DATE, null)
        if (storedDate != todayKey) return // Only track within the current day

        val current = prefs.getString(KEY_PREVIOUS_COMMENTARIES, null)
        val list = mutableListOf<String>()
        if (current != null) {
            try {
                val jsonArray = org.json.JSONArray(current)
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
            } catch (e: Exception) {
                // Ignore corrupt data
            }
        }
        list.add(0, script)
        // Keep only the most recent N commentaries
        while (list.size > MAX_PREVIOUS_COMMENTARIES) {
            list.removeAt(list.size - 1)
        }
        val jsonArray = org.json.JSONArray()
        list.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_PREVIOUS_COMMENTARIES, jsonArray.toString()).apply()
        cachedContext = null // Invalidate so next call picks up the new commentary
    }

    /**
     * Records that a song has been played in the current session. Called by
     * the playback pipeline whenever a real song (not a commentary) starts.
     */
    fun recordSongPlayed(context: Context) {
        val prefs = getPrefs(context)
        val todayKey = CommentaryContext.currentDateKey()
        val storedDate = prefs.getString(KEY_CONTEXT_DATE, null)
        if (storedDate != todayKey) {
            // New day: reset session counters
            prefs.edit()
                .putString(KEY_CONTEXT_DATE, todayKey)
                .putLong(KEY_SESSION_START, System.currentTimeMillis())
                .putInt(KEY_SESSION_SONGS, 1)
                .apply()
        } else {
            val count = prefs.getInt(KEY_SESSION_SONGS, 0) + 1
            prefs.edit().putInt(KEY_SESSION_SONGS, count).apply()
        }
        cachedContext = null // Invalidate cache so next getCurrentContext rebuilds
    }

    /**
     * Builds the [CommentaryContext] from local/cached data sources.
     * This is fast and synchronous so it can be called on the main thread.
     */
    private fun buildContext(context: Context, prefs: SharedPreferences): CommentaryContext {
        val todayKey = CommentaryContext.currentDateKey()

        // 1. Today's listening history (most recent first)
        val allHistory = SessionManager.getLocalHistoryWithTimestamps(context)
        val todayStart = startOfTodayMillis()
        val todaySongs = allHistory
            .filter { it.second >= todayStart }
            .take(20) // Cap at 20 songs for context size

        // 2. User's name (if signed in)
        val userName = SessionManager.getCachedAccountInfo(context)?.name

        // 3. Weather (cached, refreshed asynchronously)
        val weather = getCachedWeather(context)

        // 4. Discover feed articles (cached, refreshed asynchronously)
        val discoverArticles = getCachedArticles(context)

        // 5. Session metadata
        val sessionStart = prefs.getLong(KEY_SESSION_START, System.currentTimeMillis())
        val songsThisSession = prefs.getInt(KEY_SESSION_SONGS, 0)

        // 6. Previous commentaries from today (so the AI doesn't repeat topics)
        val previousCommentaries = getPreviousCommentaries(prefs)

        return CommentaryContext(
            dateKey = todayKey,
            currentTime = CommentaryContext.currentTimeString(),
            timeOfDay = CommentaryContext.currentTimeOfDay(),
            userName = userName,
            todaySongs = todaySongs,
            weather = weather,
            discoverArticles = discoverArticles,
            sessionStartTime = sessionStart,
            songsPlayedThisSession = songsThisSession,
            currentSong = null,
            upcomingSongs = emptyList(),
            previousCommentaries = previousCommentaries
        )
    }

    /**
     * Asynchronously refreshes network-backed context sources (weather and
     * RSS articles) so the context stays fresh without blocking the caller.
     */
    private fun refreshNetworkContext(appContext: Context) {
        scope.launch {
            // Refresh weather if stale
            val now = System.currentTimeMillis()
            if (now - lastWeatherFetchTime > weatherCacheDurationMs) {
                val freshWeather = fetchWeather(appContext)
                if (freshWeather != null) {
                    cachedWeather = freshWeather
                    lastWeatherFetchTime = now
                    cachedContext = null // Invalidate so next call picks up fresh weather
                }
            }

            // Refresh RSS articles (RssRepository handles its own 25-min cache)
            val freshArticles = withContext(Dispatchers.IO) {
                try {
                    RssRepository.getArticles(appContext)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (freshArticles.isNotEmpty()) {
                cachedArticles = freshArticles
                cachedContext = null
            }
        }
    }

    // --- Weather ---

    @Volatile
    private var cachedArticles: List<Article>? = null

    private fun getCachedWeather(context: Context): WeatherInfo? {
        // If we have a fresh cached weather, return it
        if (cachedWeather != null && System.currentTimeMillis() - lastWeatherFetchTime <= weatherCacheDurationMs) {
            return cachedWeather
        }
        // Otherwise try a synchronous fetch (fast, single HTTP call)
        val weather = fetchWeather(context)
        if (weather != null) {
            cachedWeather = weather
            lastWeatherFetchTime = System.currentTimeMillis()
        }
        return weather
    }

    private fun fetchWeather(context: Context): WeatherInfo? {
        return try {
            val prefs = context.getSharedPreferences("tunespark_location_prefs", Context.MODE_PRIVATE)
            val locationEnabled = prefs.getBoolean("location_enabled", false)
            if (!locationEnabled) return null
            val locationDisplay = prefs.getString(
                "location_display",
                "San Francisco, CA (37.7749, -122.4194)"
            ) ?: "San Francisco, CA (37.7749, -122.4194)"
            WeatherService.fetchWeather(locationDisplay)
        } catch (e: Exception) {
            null
        }
    }

    // --- RSS Articles ---

    private fun getCachedArticles(context: Context): List<Article> {
        cachedArticles?.let { return it }
        // Read directly from RssRepository's SharedPreferences cache synchronously
        // (RssRepository.getArticles is a suspend function, so we read its cache here)
        val articles = try {
            val prefs = context.getSharedPreferences("tunespark_rss_cache", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("cached_articles", null)
            if (jsonStr != null) {
                val jsonArray = org.json.JSONArray(jsonStr)
                val list = mutableListOf<Article>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(
                        Article(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            description = obj.optString("description", ""),
                            thumbnail = obj.optString("thumbnail", ""),
                            source = obj.getString("source"),
                            url = obj.getString("url"),
                            publishedDate = obj.getLong("publishedDate"),
                            category = obj.optString("category", "")
                        )
                    )
                }
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
        cachedArticles = articles
        return articles
    }

    /**
     * Returns the epoch millis for the start of today (00:00:00 local time).
     */
    private fun startOfTodayMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Builds a human-readable summary of the context for inclusion in the AI
     * prompt. This is what gets passed to the script generator so the AI has
     * full awareness of the user's day.
     */
    fun buildContextPrompt(context: CommentaryContext): String {
        val sb = StringBuilder()

        // Time & user
        sb.append("Current time: ${context.currentTime} (${context.timeOfDay}).\n")
        context.userName?.let { sb.append("User's name: $it.\n") }

        // Weather
        context.weather?.let {
            sb.append("Current weather in ${it.cityName}: ${it.temperature}°C, ${it.description} ${it.emoji}.\n")
        }

        // Today's listening history
        if (context.todaySongs.isNotEmpty()) {
            sb.append("Songs the user has listened to today (most recent first):\n")
            context.todaySongs.take(10).forEachIndexed { index, (song, _) ->
                sb.append("  ${index + 1}. '${song.title}' by ${song.artists.joinToString(", ") { a -> a.name }}\n")
            }
        } else {
            sb.append("The user has not listened to any songs yet today.\n")
        }

        // Session metadata
        sb.append("Songs played in this session: ${context.songsPlayedThisSession}.\n")

        // Discover feed articles (for briefings)
        if (context.discoverArticles.isNotEmpty()) {
            sb.append("Relevant news headlines from the user's interests:\n")
            context.discoverArticles.take(5).forEach { article ->
                sb.append("  - ${article.title} (${article.source})\n")
            }
        }

        // Previous commentaries from today (so the AI doesn't repeat topics)
        if (context.previousCommentaries.isNotEmpty()) {
            sb.append("\nPrevious AI commentaries from today (do NOT repeat these topics or articles):\n")
            context.previousCommentaries.forEachIndexed { index, script ->
                sb.append("  ${index + 1}. $script\n")
            }
        }

        return sb.toString()
    }

    /**
     * Returns the most recent commentary scripts from today, most recent first.
     */
    private fun getPreviousCommentaries(prefs: SharedPreferences): List<String> {
        val current = prefs.getString(KEY_PREVIOUS_COMMENTARIES, null) ?: return emptyList()
        val list = mutableListOf<String>()
        try {
            val jsonArray = org.json.JSONArray(current)
            for (i in 0 until jsonArray.length()) {
                list.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            // Ignore corrupt data
        }
        return list
    }
}
