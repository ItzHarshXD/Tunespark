package com.tunespark.music.rss

import android.content.Context
import android.content.SharedPreferences
import com.tunespark.music.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches, parses, merges, deduplicates, sorts, and caches RSS articles.
 *
 * The repository:
 *  - Reads the user's enabled interest categories from [SessionManager].
 *  - Resolves the relevant RSS sources from [RssConfig].
 *  - Fetches all feeds concurrently (individual failures are isolated).
 *  - Parses each feed into [Article] objects.
 *  - Merges, deduplicates (by article ID), and randomizes the order.
 *  - Caches the result for ~25 minutes to avoid re-downloading feeds.
 */
object RssRepository {

    private const val PREFS_NAME = "tunespark_rss_cache"
    private const val KEY_CACHED_ARTICLES = "cached_articles"
    private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the merged, deduplicated, randomized list of articles for the user's
     * enabled interest categories. Uses the cache if it is still fresh.
     *
     * @param forceRefresh If true, bypasses the cache and fetches fresh data.
     */
    suspend fun getArticles(context: Context, forceRefresh: Boolean = false): List<Article> {
        // 1. Check cache first (unless force refresh)
        if (!forceRefresh) {
            val cached = loadFromCache(context)
            if (cached != null) return cached
        }

        // 2. Determine which categories are enabled
        val enabledCategories = SessionManager.getDiscoverCategories(context)
            .filter { it.second }
            .map { it.first }

        // 3. Resolve the RSS sources for those categories
        val sources = RssConfig.getSourcesForCategories(enabledCategories)
        if (sources.isEmpty()) return emptyList()

        // 4. Fetch all feeds concurrently; individual failures are isolated
        val results = withContext(Dispatchers.IO) {
            sources.map { source ->
                async {
                    try {
                        fetchAndParse(source)
                    } catch (e: Exception) {
                        // Isolate failures: one bad feed must not break the whole feed
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        // 5. Merge, deduplicate, and randomize the order
        val merged = results.flatten()
            .distinctBy { it.id }
            .shuffled()

        // 6. Cache the result
        saveToCache(context, merged)

        return merged
    }

    /**
     * Fetches a single RSS feed and parses it into articles.
     */
    private fun fetchAndParse(source: RssConfig.RssSource): List<Article> {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            if (body.isBlank()) return emptyList()

            val parsed = RssParser.parse(
                xml = body,
                sourceName = source.name,
                category = source.category,
                feedUrl = source.url
            )
            return parsed.take(RssConfig.MAX_ARTICLES_PER_FEED)
        }
    }

    /**
     * Loads cached articles if the cache is still fresh (within [RssConfig.CACHE_DURATION_MS]).
     */
    private fun loadFromCache(context: Context): List<Article>? {
        return try {
            val prefs = getPrefs(context)
            val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
            val now = System.currentTimeMillis()

            // Cache is stale
            if (now - timestamp > RssConfig.CACHE_DURATION_MS) return null

            val jsonStr = prefs.getString(KEY_CACHED_ARTICLES, null) ?: return null
            val jsonArray = JSONArray(jsonStr)
            val articles = mutableListOf<Article>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                articles.add(
                    Article(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        description = obj.optString("description", ""),
                        thumbnail = obj.optString("thumbnail", RssConfig.PLACEHOLDER_IMAGE),
                        source = obj.getString("source"),
                        url = obj.getString("url"),
                        publishedDate = obj.getLong("publishedDate"),
                        category = obj.optString("category", "")
                    )
                )
            }
            articles
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Serializes and stores the given articles in SharedPreferences.
     */
    private fun saveToCache(context: Context, articles: List<Article>) {
        try {
            val jsonArray = JSONArray()
            articles.forEach { article ->
                jsonArray.put(
                    JSONObject().apply {
                        put("id", article.id)
                        put("title", article.title)
                        put("description", article.description)
                        put("thumbnail", article.thumbnail)
                        put("source", article.source)
                        put("url", article.url)
                        put("publishedDate", article.publishedDate)
                        put("category", article.category)
                    }
                )
            }
            getPrefs(context).edit()
                .putString(KEY_CACHED_ARTICLES, jsonArray.toString())
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clears the cached articles (used when the user changes their interests).
     */
    fun clearCache(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_CACHED_ARTICLES)
            .remove(KEY_CACHE_TIMESTAMP)
            .apply()
    }
}