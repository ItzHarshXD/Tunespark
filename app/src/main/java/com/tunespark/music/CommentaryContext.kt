package com.tunespark.music

import com.metrolist.innertube.models.SongItem
import com.tunespark.music.rss.Article
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A snapshot of all relevant information for the current day that any AI
 * commentary feature can request. This is the single source of truth for
 * context passed to the AI script generator.
 *
 * The context is scoped to the current calendar day (00:00–23:59 in the
 * user's local timezone) and is automatically reset when a new day begins.
 *
 * The design is modular and scalable: new context sources can be added as
 * fields here without changing the commentary generation pipeline.
 */
data class CommentaryContext(
    /** Day key like "2026-08-04" used to detect day rollover. */
    val dateKey: String,
    /** Human-readable current time like "9:43 PM". */
    val currentTime: String,
    /** Time-of-day bucket: "morning", "afternoon", "evening", or "night". */
    val timeOfDay: String,
    /** The signed-in user's display name, if available. */
    val userName: String?,
    /** Songs listened to today (most recent first), with timestamps. */
    val todaySongs: List<Pair<SongItem, Long>>,
    /** Current weather at the user's saved location, if enabled. */
    val weather: WeatherInfo?,
    /** Relevant Discover feed articles for the user's interests. */
    val discoverArticles: List<Article>,
    /** Epoch millis when the current listening session started. */
    val sessionStartTime: Long,
    /** Number of songs played in the current session. */
    val songsPlayedThisSession: Int,
    /** The currently playing song as "'Title' by Artist", or null. */
    val currentSong: String?,
    /** Upcoming songs as "'Title' by Artist" strings. */
    val upcomingSongs: List<String>,
    /** Previous AI commentary scripts from today (most recent first). */
    val previousCommentaries: List<String> = emptyList()
) {
    companion object {
        /** Returns the current day key in the user's local timezone. */
        fun currentDateKey(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        }

        /** Returns a human-readable current time like "9:43 PM". */
        fun currentTimeString(): String {
            val cal = Calendar.getInstance()
            val hourVal = cal.get(Calendar.HOUR)
            val hour = if (hourVal == 0) 12 else hourVal
            val minute = String.format("%02d", cal.get(Calendar.MINUTE))
            val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
            return "$hour:$minute $amPm"
        }

        /** Returns the time-of-day bucket based on the current hour. */
        fun currentTimeOfDay(): String {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..11 -> "morning"
                in 12..16 -> "afternoon"
                in 17..20 -> "evening"
                else -> "night"
            }
        }
    }
}