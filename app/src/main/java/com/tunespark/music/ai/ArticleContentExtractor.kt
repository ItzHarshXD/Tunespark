package com.tunespark.music.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Evaluator
import java.util.concurrent.TimeUnit

/**
 * Fetches an article webpage and extracts the main article content using a
 * Mozilla Readability-style heuristic (implemented with jsoup).
 *
 * The extraction pipeline:
 *  1. Fetches the article HTML with a browser-like User-Agent.
 *  2. Parses the document with jsoup.
 *  3. Removes non-content elements (nav, footer, aside, scripts, ads, etc.).
 *  4. Scores candidate content nodes based on text density and comma/paragraph
 *     heuristics (the same core idea as Mozilla Readability).
 *  5. Cleans the winning node and returns plain text.
 */
object ArticleContentExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Tags that are never part of the main article body. */
    private val UNWANTED_TAGS = setOf(
        "script", "style", "noscript", "nav", "footer", "aside", "header",
        "form", "button", "iframe", "svg", "canvas", "figure", "figcaption",
        "dialog", "menu", "menuitem", "template", "object", "embed", "select",
        "option", "input", "textarea", "label", "video", "audio", "source",
        "picture", "ins", "del", "map", "area", "track", "param", "applet",
        "base", "basefont", "bdo", "bgsound", "blink", "frame", "frameset",
        "noframes", "marquee", "meta", "link", "title", "head"
    )

    /** Tags whose content is usually boilerplate / not article prose. */
    private val LOW_VALUE_TAGS = setOf(
        "ul", "ol", "table", "blockquote", "pre", "code", "address",
        "time", "cite", "q", "abbr", "acronym", "bdi", "bdo", "data",
        "datalist", "details", "summary", "output", "progress", "meter",
        "ruby", "rt", "rp", "s", "small", "sub", "sup", "u", "var", "wbr"
    )

    /** Tags that are strong signals of main content. */
    private val POSITIVE_TAGS = setOf(
        "article", "main", "section", "div", "p", "td", "pre", "blockquote"
    )

    /** Tags that are strong signals of non-content. */
    private val NEGATIVE_TAGS = setOf(
        "nav", "footer", "aside", "header", "form", "button", "iframe",
        "script", "style", "noscript", "figure", "figcaption", "dialog",
        "menu", "menuitem", "template", "object", "embed", "select", "option",
        "input", "textarea", "label", "video", "audio", "source", "picture",
        "ins", "del", "map", "area", "track", "param", "applet", "base",
        "basefont", "bdo", "bgsound", "blink", "frame", "frameset", "noframes",
        "marquee", "meta", "link", "title", "head", "time", "cite", "q",
        "abbr", "acronym", "bdi", "data", "datalist", "details", "summary",
        "output", "progress", "meter", "ruby", "rt", "rp", "s", "small",
        "sub", "sup", "u", "var", "wbr", "button", "fieldset", "legend"
    )

    /** Class/id patterns that indicate non-content regions. */
    private val NEGATIVE_PATTERNS = listOf(
        Regex("(?i)(^|[-_])(nav|navbar|navigation|menu|footer|header|sidebar|aside|advert|ads|banner|promo|related|recommend|share|social|comment|subscribe|newsletter|signup|login|breadcrumb|pagination|pager|widget|widgets|popup|modal|overlay|toolbar|search|author|bio|byline|meta|tags|tag-|category|categories|breadcrumbs|pagination|pager|widget|widgets|popup|modal|overlay|toolbar|search|author|bio|byline|meta|tags|tag-|category|categories)([-_]|$)"),
        Regex("(?i)(^|[-_])(ad|ads|advert|advertisement|sponsor|sponsored|promo|promotion|banner|billboard|teaser|related|recommend|recommended|share|social|social-share|comment|comments|subscribe|newsletter|signup|login|logout|breadcrumb|pagination|pager|widget|widgets|popup|modal|overlay|toolbar|search|author|bio|byline|meta|tags|tag-|category|categories)([-_]|$)"),
        Regex("(?i)(^|[-_])(footer|header|nav|menu|sidebar|aside|advert|ads|banner|promo|related|recommend|share|social|comment|subscribe|newsletter|signup|login|breadcrumb|pagination|pager|widget|widgets|popup|modal|overlay|toolbar|search|author|bio|byline|meta|tags|tag-|category|categories)([-_]|$)")
    )

    /** Class/id patterns that indicate main content. */
    private val POSITIVE_PATTERNS = listOf(
        Regex("(?i)(^|[-_])(article|content|main|body|post|entry|text|story|detail|page|single|blog|news|editorial|feature|featured|primary|main-content|post-content|entry-content|article-content|story-content|page-content|single-content|blog-content|news-content|editorial-content|feature-content|featured-content|primary-content)([-_]|$)")
    )

    /**
     * Fetches the article at [url] and returns the cleaned main article text.
     *
     * @param url The article URL.
     * @return The extracted plain-text article content, or null if extraction fails.
     */
    suspend fun extractArticleText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val html = fetchHtml(url) ?: return@withContext null
            val document = Jsoup.parse(html, url)

            // Remove unwanted elements first
            document.select(UNWANTED_TAGS.joinToString(",") { it }).remove()

            // Remove elements with negative class/id patterns
            document.select("*").forEach { element ->
                val classAndId = "${element.className()} ${element.id()}"
                if (classAndId.isNotBlank() && NEGATIVE_PATTERNS.any { it.containsMatchIn(classAndId) }) {
                    element.remove()
                }
            }

            // Find the best content candidate
            val candidates = findCandidates(document)
            val bestCandidate = candidates.maxByOrNull { it.score } ?: return@withContext null

            // Clean the winning node
            val cleaned = cleanNode(bestCandidate.element)
            val text = cleaned.text().trim()

            if (text.length < 80) {
                // Fallback: use the whole body text if the candidate was too short
                val bodyText = cleanNode(document.body()).text().trim()
                return@withContext if (bodyText.length >= text.length) bodyText else text
            }

            text
        } catch (e: Exception) {
            android.util.Log.e("ArticleContentExtractor", "Failed to extract article: ${e.message}")
            null
        }
    }

    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private data class Candidate(
        val element: Element,
        var score: Double
    )

    private fun findCandidates(document: Document): List<Candidate> {
        val candidates = mutableListOf<Candidate>()

        // Score all paragraph-containing block elements
        document.select("p, td, pre, blockquote, article, section, div, main").forEach { element ->
            val paragraphs = element.select("p").size
            if (paragraphs == 0) return@forEach

            val textLength = element.text().length
            val score = scoreNode(element, paragraphs, textLength)
            if (score > 0) {
                candidates.add(Candidate(element, score))
            }
        }

        // If no candidates found, fall back to body
        if (candidates.isEmpty()) {
            document.body()?.let { body ->
                candidates.add(Candidate(body, 1.0))
            }
        }

        return candidates
    }

    private fun scoreNode(element: Element, paragraphCount: Int, textLength: Int): Double {
        var score = 0.0

        // Base score from paragraph count
        score += paragraphCount.coerceAtMost(10) * 5.0

        // Text length bonus (longer prose = more likely main content)
        score += (textLength / 100.0).coerceAtMost(50.0)

        // Comma heuristic (Mozilla Readability): more commas = more likely prose
        val commas = element.select("p").sumOf { p ->
            p.text().count { it == ',' }
        }
        score += commas.coerceAtMost(100) * 0.5

        // Positive tag bonus
        val tagName = element.tagName().lowercase()
        if (tagName in POSITIVE_TAGS) score += 25.0

        // Positive class/id pattern bonus
        val classAndId = "${element.className()} ${element.id()}"
        if (classAndId.isNotBlank() && POSITIVE_PATTERNS.any { it.containsMatchIn(classAndId) }) {
            score += 30.0
        }

        // Negative tag penalty
        if (tagName in NEGATIVE_TAGS) score -= 40.0

        // Negative class/id pattern penalty
        if (classAndId.isNotBlank() && NEGATIVE_PATTERNS.any { it.containsMatchIn(classAndId) }) {
            score -= 50.0
        }

        // Low-value content penalty
        val lowValueCount = element.select(LOW_VALUE_TAGS.joinToString(",") { it }).size
        score -= lowValueCount * 2.0

        // Link density penalty (Mozilla Readability): high link density = less likely main content
        val links = element.select("a").size
        val linkDensity = if (textLength > 0) links.toDouble() / textLength.toDouble() else 0.0
        score -= linkDensity * 100.0

        return score
    }

    private fun cleanNode(element: Element): Element {
        // Remove remaining unwanted elements
        element.select(UNWANTED_TAGS.joinToString(",") { it }).remove()

        // Remove elements with negative patterns
        element.select("*").forEach { child ->
            val classAndId = "${child.className()} ${child.id()}"
            if (classAndId.isNotBlank() && NEGATIVE_PATTERNS.any { it.containsMatchIn(classAndId) }) {
                child.remove()
            }
        }

        // Remove empty paragraphs and excessive whitespace
        element.select("p").forEach { p ->
            val text = p.text().trim()
            if (text.isEmpty()) {
                p.remove()
            }
        }

        return element
    }
}