package com.tunespark.music.rss

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses RSS 2.0 and Atom XML feeds into a list of [Article] objects.
 *
 * Supports thumbnail extraction from:
 *  - media:content
 *  - media:thumbnail
 *  - enclosure
 *  - itunes:image
 *  - Atom <link rel="enclosure">
 *  - First <img> tag found inside the description HTML (fallback)
 */
object RssParser {

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",      // RFC 822 (most common RSS)
        "EEE, dd MMM yyyy HH:mm:ss zzz",    // RFC 822 with named zone
        "yyyy-MM-dd'T'HH:mm:ssZ",           // ISO 8601 with timezone
        "yyyy-MM-dd'T'HH:mm:ssXXX",         // ISO 8601 with offset
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ",       // ISO 8601 with millis
        "yyyy-MM-dd'T'HH:mm:ss",            // ISO 8601 without timezone
        "EEE, dd MMM yyyy HH:mm:ss",        // RFC 822 without zone
        "dd MMM yyyy HH:mm:ss Z",           // Day first
        "yyyy-MM-dd HH:mm:ss",              // Simple date time
        "EEE MMM dd HH:mm:ss zzz yyyy"      // Java Date.toString()
    )

    /**
     * Parses the given XML string into a list of [Article] objects.
     *
     * @param xml        The raw XML feed content.
     * @param sourceName The display name of the source (used as the article source).
     * @param category   The interest category this feed belongs to.
     * @param feedUrl    The feed URL (used to build stable article IDs).
     * @return A list of parsed articles, or an empty list if parsing fails.
     */
    fun parse(xml: String, sourceName: String, category: String, feedUrl: String): List<Article> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val articles = mutableListOf<Article>()
            var currentTag = ""
            var inItem = false

            var title = ""
            var description = ""
            var link = ""
            var pubDate = ""
            var enclosureUrl = ""
            var mediaContentUrl = ""
            var mediaThumbnailUrl = ""
            var itunesImageUrl = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (currentTag.lowercase()) {
                            "item", "entry" -> {
                                inItem = true
                                title = ""
                                description = ""
                                link = ""
                                pubDate = ""
                                enclosureUrl = ""
                                mediaContentUrl = ""
                                mediaThumbnailUrl = ""
                                itunesImageUrl = ""
                            }
                            "enclosure" -> {
                                if (inItem) {
                                    val url = parser.getAttributeValue(null, "url")
                                    if (!url.isNullOrBlank()) enclosureUrl = url
                                }
                            }
                            "media:content" -> {
                                if (inItem) {
                                    val url = parser.getAttributeValue(null, "url")
                                    if (!url.isNullOrBlank()) mediaContentUrl = url
                                }
                            }
                            "media:thumbnail" -> {
                                if (inItem) {
                                    val url = parser.getAttributeValue(null, "url")
                                    if (!url.isNullOrBlank()) mediaThumbnailUrl = url
                                }
                            }
                            "itunes:image" -> {
                                if (inItem) {
                                    val href = parser.getAttributeValue(null, "href")
                                    if (!href.isNullOrBlank()) itunesImageUrl = href
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem) {
                            when (currentTag.lowercase()) {
                                "title" -> title = parser.text?.trim() ?: ""
                                "description", "summary", "content:encoded" -> {
                                    description = parser.text?.trim() ?: ""
                                }
                                "link" -> {
                                    if (link.isBlank()) link = parser.text?.trim() ?: ""
                                }
                                "pubdate", "published", "updated", "dc:date" -> {
                                    if (pubDate.isBlank()) pubDate = parser.text?.trim() ?: ""
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "item", "entry" -> {
                                if (inItem) {
                                    val article = buildArticle(
                                        title = title,
                                        description = description,
                                        link = link,
                                        pubDate = pubDate,
                                        enclosureUrl = enclosureUrl,
                                        mediaContentUrl = mediaContentUrl,
                                        mediaThumbnailUrl = mediaThumbnailUrl,
                                        itunesImageUrl = itunesImageUrl,
                                        sourceName = sourceName,
                                        category = category,
                                        feedUrl = feedUrl
                                    )
                                    if (article != null) {
                                        articles.add(article)
                                    }
                                    inItem = false
                                }
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }

            articles
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun buildArticle(
        title: String,
        description: String,
        link: String,
        pubDate: String,
        enclosureUrl: String,
        mediaContentUrl: String,
        mediaThumbnailUrl: String,
        itunesImageUrl: String,
        sourceName: String,
        category: String,
        feedUrl: String
    ): Article? {
        if (title.isBlank()) return null

        // Determine the best thumbnail available
        val thumbnail = listOf(
            mediaContentUrl,
            mediaThumbnailUrl,
            enclosureUrl,
            itunesImageUrl,
            extractImageFromHtml(description)
        ).firstOrNull { !it.isNullOrBlank() }

        // Skip articles that have no real image (no favicon fallback)
        if (thumbnail.isNullOrBlank()) return null

        // Clean up description: strip HTML tags for a plain-text preview
        val cleanDescription = stripHtml(description).take(200)

        // Parse the published date; fall back to now if unparseable
        val published = parseDate(pubDate) ?: System.currentTimeMillis()

        // Build a stable ID from the link or title
        val id = if (link.isNotBlank()) {
            "rss_${link.hashCode()}"
        } else {
            "rss_${title.hashCode()}_${feedUrl.hashCode()}"
        }

        return Article(
            id = id,
            title = title,
            description = cleanDescription,
            thumbnail = thumbnail,
            source = sourceName,
            url = link,
            publishedDate = published,
            category = category
        )
    }

    /**
     * Extracts the first <img> src from an HTML string (used as a fallback
     * when no media:content / enclosure / media:thumbnail is present).
     *
     * Handles multiple attribute patterns:
     *  - src="..."
     *  - data-src="..." (lazy-loaded images)
     *  - data-lazy-src="..."
     *  - data-original="..."
     *  - srcset="..." (first candidate)
     */
    private fun extractImageFromHtml(html: String): String? {
        if (html.isBlank()) return null

        // Try standard src attribute first
        val srcRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val srcMatch = srcRegex.find(html)
        if (srcMatch != null) {
            val url = srcMatch.groupValues.getOrNull(1)
            if (!url.isNullOrBlank()) return url
        }

        // Try data-src (lazy-loaded images)
        val dataSrcRegex = Regex("""<img[^>]+data-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val dataSrcMatch = dataSrcRegex.find(html)
        if (dataSrcMatch != null) {
            val url = dataSrcMatch.groupValues.getOrNull(1)
            if (!url.isNullOrBlank()) return url
        }

        // Try data-lazy-src
        val dataLazySrcRegex = Regex("""<img[^>]+data-lazy-src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val dataLazySrcMatch = dataLazySrcRegex.find(html)
        if (dataLazySrcMatch != null) {
            val url = dataLazySrcMatch.groupValues.getOrNull(1)
            if (!url.isNullOrBlank()) return url
        }

        // Try data-original
        val dataOriginalRegex = Regex("""<img[^>]+data-original=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val dataOriginalMatch = dataOriginalRegex.find(html)
        if (dataOriginalMatch != null) {
            val url = dataOriginalMatch.groupValues.getOrNull(1)
            if (!url.isNullOrBlank()) return url
        }

        // Try srcset (first candidate URL)
        val srcsetRegex = Regex("""<img[^>]+srcset=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val srcsetMatch = srcsetRegex.find(html)
        if (srcsetMatch != null) {
            val srcset = srcsetMatch.groupValues.getOrNull(1)
            if (!srcset.isNullOrBlank()) {
                val firstCandidate = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                if (!firstCandidate.isNullOrBlank()) return firstCandidate
            }
        }

        return null
    }


    /**
     * Strips HTML tags from a string, returning plain text.
     */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .replace("\u0026amp;", "\u0026")
            .replace("\u0026lt;", "<")
            .replace("\u0026gt;", ">")
            .replace("\u0026quot;", "\"")
            .replace("\u0026#39;", "'")
            .replace("\u0026nbsp;", " ")
    }

    /**
     * Parses a date string using multiple common RSS/Atom date formats.
     */
    private fun parseDate(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val trimmed = dateStr.trim()
        for (format in dateFormats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.isLenient = false
                val date = sdf.parse(trimmed)
                if (date != null) return date.time
            } catch (_: Exception) {
                // Try the next format
            }
        }
        return null
    }
}