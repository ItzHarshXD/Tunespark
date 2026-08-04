package com.tunespark.music.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Locally caches generated AI article summaries keyed by article URL.
 *
 * Summaries are stored in SharedPreferences so the same article is never
 * summarized twice unless the cache is cleared.
 */
object ArticleSummaryCache {

    private const val PREFS_NAME = "tunespark_article_summaries"
    private const val KEY_SUMMARIES = "article_summaries"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the cached summary for the given article URL, or null if not cached.
     */
    fun getSummary(context: Context, articleUrl: String): String? {
        return try {
            val jsonStr = getPrefs(context).getString(KEY_SUMMARIES, "{}") ?: "{}"
            val json = JSONObject(jsonStr)
            json.optString(articleUrl, "").ifBlank { null }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Caches the generated summary for the given article URL.
     */
    fun saveSummary(context: Context, articleUrl: String, summary: String) {
        try {
            val jsonStr = getPrefs(context).getString(KEY_SUMMARIES, "{}") ?: "{}"
            val json = JSONObject(jsonStr)
            json.put(articleUrl, summary)
            getPrefs(context).edit()
                .putString(KEY_SUMMARIES, json.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clears all cached article summaries.
     */
    fun clearCache(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_SUMMARIES)
            .apply()
    }
}