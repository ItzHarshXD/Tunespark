package com.tunespark.music

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.pages.BrowseResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Unique identity of a mood / genre. All mood & genre tiles share a single browseId
 * (`FEmusic_moods_and_genres_category`) and are distinguished only by their `params`,
 * so every dedupe / cache / grid key must combine the two.
 */
private fun cacheKey(browseId: String, params: String?): String =
    "$browseId|${params.orEmpty()}"

/**
 * Data layer behind the Search screen's **Explore** feed — YouTube Music's "Moods &
 * genres" catalogue, surfaced in this app as plain "Explore".
 *
 * Everything here consumes only the existing public `:innertube` APIs
 * (`YouTube.moodAndGenres()` + `YouTube.browse(browseId, params)`), so the
 * independent module stays untouched. Both calls are anonymous-safe, which is why
 * Explore behaves identically signed in and signed out.
 *
 * Two caches make the whole flow feel instant:
 *  - [pageCache]   — the browsed page of every mood / genre the user has opened or
 *                    whose banner has been resolved (keyed by `browseId|params`,
 *                    session-only).
 *  - [bannerCache] — the resolved banner artwork url per mood / genre, derived from
 *                    the **first section's first playlist** of that genre's page.
 *
 * On top of those, the catalogue and every resolved banner url are **persisted to
 * disk** (SharedPreferences JSON), so a cold start renders the full grid instantly
 * (the images themselves come straight from Coil's disk cache) and the network is
 * only touched again when the snapshot goes stale or the user explicitly retries.
 */
object ExploreFeed {

    /**
     * One tile of the Explore grid: a single YouTube Music mood / genre plus the
     * derived presentation fields (stable random [color], resolved [bannerUrl]).
     */
    data class ExploreEntry(
        val title: String,
        val browseId: String,
        val params: String?,
        val color: Long,
        val bannerUrl: String? = null,
    ) {
        /**
         * Unique identity of this mood / genre. Mood & genre tiles share ONE browseId
         * (`FEmusic_moods_and_genres_category`) and are told apart only by their
         * params — so both the grid item key and the caches must use this.
         */
        val key: String
            get() = cacheKey(browseId, params)
    }

    /** How many genre pages are browsed concurrently while prefetching banners. */
    private const val BANNER_CONCURRENCY = 5

    /**
     * Rich, deep tones that always carry white text well — every mood / genre picks
     * one pseudo-randomly (stable per title) so the grid reads as colourful and
     * varied, like YouTube Music's own mood tiles but with random colours.
     */
    private val PALETTE = longArrayOf(
        0xFFE53935, // red
        0xFFD81B60, // pink
        0xFF8E24AA, // purple
        0xFF5E35B1, // deep purple
        0xFF3949AB, // indigo
        0xFF1E88E5, // blue
        0xFF00897B, // teal
        0xFF00838F, // cyan
        0xFF2E7D32, // green
        0xFF558B2F, // light green
        0xFFEF6C00, // orange
        0xFFF4511E, // deep orange
        0xFF6D4C41, // brown
        0xFF546E7A, // blue grey
        0xFF283593, // dark indigo
        0xFFAD1457, // dark pink
    )

    @Volatile
    private var entriesCache: List<ExploreEntry>? = null

    private val pageCache = ConcurrentHashMap<String, BrowseResult>()
    private val bannerCache = ConcurrentHashMap<String, String>()

    // ── Disk persistence (SharedPreferences JSON — the SessionManager pattern) ──
    // The catalogue and the resolved banner urls are persisted, so a cold start
    // renders the whole grid instantly from disk + Coil's image cache instead of
    // replaying ~30 browse calls. The network is only hit again when the snapshot
    // goes stale (or on an explicit retry) — never static, just not wasteful.
    private const val PREFS_NAME = "tunespark_explore_cache"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_BANNERS = "banners"
    private const val KEY_UPDATED_AT = "updated_at"

    /** How often the persisted catalogue is refreshed in the background. */
    private const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Instant, offline-first restore of the last fetched catalogue + banner urls.
     * Seeds the in-memory caches and returns the entries, or null when nothing has
     * been persisted yet (first launch / cleared data).
     */
    fun loadPersisted(context: Context): List<ExploreEntry>? {
        entriesCache?.let { return it }

        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return null
        return try {
            prefs(context).getString(KEY_BANNERS, null)?.let { rawBanners ->
                val obj = JSONObject(rawBanners)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    bannerCache[key] = obj.optString(key)
                }
            }

            val array = JSONArray(raw)
            val entries = mutableListOf<ExploreEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val title = obj.optString("title")
                val browseId = obj.optString("browseId")
                if (title.isBlank() || browseId.isBlank()) continue
                val params = obj.optString("params").ifBlank { null }
                entries += ExploreEntry(
                    title = title,
                    browseId = browseId,
                    params = params,
                    color = obj.optLong("color", 0xFF546E7A),
                    bannerUrl = bannerCache[cacheKey(browseId, params)],
                )
            }
            if (entries.isEmpty()) null else entries.also { entriesCache = it }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** True when the persisted snapshot is missing or older than [maxAgeMs]. */
    fun isStale(context: Context, maxAgeMs: Long = STALE_AFTER_MS): Boolean {
        val updatedAt = prefs(context).getLong(KEY_UPDATED_AT, 0L)
        return updatedAt <= 0L || System.currentTimeMillis() - updatedAt > maxAgeMs
    }

    /**
     * Writes the current snapshot to disk. [entries] null → only the banner map is
     * refreshed (used for incremental progress while banners resolve).
     */
    private fun persist(context: Context, entries: List<ExploreEntry>?) {
        try {
            val editor = prefs(context).edit()
            if (entries != null && entries.isNotEmpty()) {
                val array = JSONArray()
                entries.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("title", entry.title)
                            .put("browseId", entry.browseId)
                            .put("params", entry.params ?: "")
                            .put("color", entry.color)
                    )
                }
                editor.putString(KEY_ENTRIES, array.toString())
                editor.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            }
            val banners = JSONObject()
            bannerCache.forEach { (key, url) -> banners.put(key, url) }
            editor.putString(KEY_BANNERS, banners.toString())
            editor.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** A colour picked "randomly" per mood / genre, yet stable across recompositions. */
    fun colorFor(title: String): Long =
        PALETTE[(title.hashCode() and 0x7FFFFFFF) % PALETTE.size]

    /** The already-fetched page of a mood / genre, if any (used for instant opens). */
    fun cachedPage(browseId: String, params: String?): BrowseResult? =
        pageCache[cacheKey(browseId, params)]

    /** Snapshot of the banner urls resolved so far (seeds the grid on re-entry). */
    fun cachedBanners(): Map<String, String> = HashMap(bannerCache)

    /**
     * Loads the Explore catalogue (`FEmusic_moods_and_genres`) and flattens the
     * genre groups into one ordered list. Order of preference: in-memory cache →
     * disk snapshot → network. A failed network fetch returns an empty list so the
     * UI can offer a retry (the persisted snapshot is never wiped by it).
     */
    suspend fun loadEntries(context: Context, forceRefresh: Boolean = false): List<ExploreEntry> {
        if (!forceRefresh) {
            entriesCache?.let { return it }
            loadPersisted(context)?.let { return it }
        }

        val sections = withContext(Dispatchers.IO) {
            runCatching { YouTube.moodAndGenres().getOrNull() }.getOrNull()
        }.orEmpty()

        val seen = HashSet<String>()
        val entries = mutableListOf<ExploreEntry>()
        sections.forEach { group ->
            group.items.forEach { item ->
                val browseId = item.endpoint.browseId
                // Mood & genre tiles share ONE browseId and differ only by params —
                // dedupe (and cache) on the combined key, never on the browseId alone.
                val key = cacheKey(browseId, item.endpoint.params)
                if (item.title.isBlank() || browseId.isBlank() || !seen.add(key)) return@forEach
                entries += ExploreEntry(
                    title = item.title,
                    browseId = browseId,
                    params = item.endpoint.params,
                    color = colorFor(item.title),
                    bannerUrl = bannerCache[key],
                )
            }
        }

        if (entries.isNotEmpty()) {
            entriesCache = entries
            persist(context, entries)
        }
        return entries
    }

    /**
     * Browsed page of one mood / genre: every section (Trending now, Playlists,
     * New releases, …) with its `YTItem`s. Successful pages are cached so opening a
     * genre whose banner was already prefetched is instant.
     */
    suspend fun loadGenrePage(browseId: String, params: String?): BrowseResult? {
        val key = cacheKey(browseId, params)
        pageCache[key]?.let { return it }
        val page = withContext(Dispatchers.IO) {
            runCatching { YouTube.browse(browseId, params).getOrNull() }.getOrNull()
        } ?: return null
        pageCache[key] = page
        bannerOf(page)?.let { bannerCache[key] = it }
        return page
    }

    /** Drops a cached page so a retry actually hits the network again. */
    fun invalidatePage(browseId: String, params: String?) {
        pageCache.remove(cacheKey(browseId, params))
    }

    /**
     * The banner artwork of a mood / genre: the **first playlist of the page's first
     * section** (that is what YouTube Music puts on the tile). When a section carries
     * no playlists at all, the first item with any artwork is used instead so a tile
     * is never left blank.
     */
    fun bannerOf(page: BrowseResult?): String? {
        val sections = page?.items.orEmpty()

        // First section → first playlist with artwork.
        sections.forEach { section ->
            section.items
                .firstOrNull { item -> item is PlaylistItem && !item.thumbnail.isNullOrBlank() }
                ?.thumbnail
                ?.let { return it }
        }

        // Fallback: the first item on the page carrying any artwork.
        return sections.asSequence()
            .flatMap { section -> section.items.asSequence() }
            .firstNotNullOfOrNull { item -> item.thumbnail?.takeIf { it.isNotBlank() } }
    }

    /**
     * Resolves the banner image of every Explore tile, in small chunks so the grid
     * fills in progressively instead of waiting on ~30 round trips at once.
     *
     * Tiles whose banner is already known (memory **or** the disk snapshot) are
     * skipped entirely, so a cold start with a persisted snapshot performs no browse
     * calls at all. Progress is written to disk after every chunk, so even a kill
     * mid-prefetch keeps everything resolved so far.
     *
     * MUST be called from a Main-dispatched scope: [onBanner] is invoked as each
     * tile's banner lands so the caller can update its Compose state directly.
     */
    suspend fun prefetchBanners(
        context: Context,
        entries: List<ExploreEntry>,
        onBanner: (key: String, url: String) -> Unit,
    ) {
        entries
            .filter { it.browseId.isNotBlank() && !bannerCache.containsKey(it.key) }
            .chunked(BANNER_CONCURRENCY)
            .forEach { chunk ->
                coroutineScope {
                    chunk.map { entry ->
                        async {
                            val page = withContext(Dispatchers.IO) {
                                runCatching {
                                    YouTube.browse(entry.browseId, entry.params).getOrNull()
                                }.getOrNull()
                            } ?: return@async

                            pageCache[entry.key] = page
                            val url = bannerOf(page) ?: return@async
                            bannerCache[entry.key] = url
                            onBanner(entry.key, url)
                        }
                    }.awaitAll()
                }
                // Persist incrementally so banners survive the process being killed.
                persist(context, null)
            }
    }
}
