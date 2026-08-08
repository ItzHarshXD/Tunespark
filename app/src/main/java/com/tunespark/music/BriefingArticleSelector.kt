package com.tunespark.music

import android.content.Context
import com.tunespark.music.ai.ArticleContentExtractor
import com.tunespark.music.rss.RssRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Selects a random article from the user's Discover feed for the Briefing
 * commentary element.
 *
 * Selection rules:
 *  1. Only articles whose page can actually be scraped are eligible. If the
 *     extracted text is null/blank or shorter than [MIN_MEANINGFUL_LENGTH],
 *     the article is skipped and the next candidate is tried.
 *  2. Articles that have already been used in a previous briefing are skipped
 *     so the same article is never repeated (tracked in [SessionManager]).
 *  3. The candidate list is shuffled so the pick is random each time.
 *
 * If no article can be scraped, null is returned and the briefing element is
 * simply skipped for that commentary (other elements still apply).
 */
object BriefingArticleSelector {

    /**
     * Minimum meaningful article content length (in characters) before we
     * consider the scrape successful. Mirrors ArticleSummaryService so the
     * briefing never wastes tokens on boilerplate content.
     */
    private const val MIN_MEANINGFUL_LENGTH = 200

    /**
     * Maximum number of articles to attempt scraping before giving up. This
     * bounds the network cost of finding a scrapable article.
     */
    private const val MAX_SCRAPE_ATTEMPTS = 8

    /**
     * Picks a random scrapable article that hasn't been used before.
     *
     * @param context Android context.
     * @return A [BriefingArticle] with the scraped content, or null if no
     *         eligible article could be found.
     */
    suspend fun selectBriefingArticle(context: Context): BriefingArticle? = withContext(Dispatchers.IO) {
        // 1. Get the user's Discover feed articles (cached by RssRepository)
        val articles = try {
            RssRepository.getArticles(context)
        } catch (e: Exception) {
            android.util.Log.e("BriefingArticleSelector", "Failed to load articles: ${e.message}")
            emptyList()
        }

        if (articles.isEmpty()) {
            android.util.Log.w("BriefingArticleSelector", "No articles available for briefing.")
            return@withContext null
        }

        // 2. Exclude articles already used in previous briefings
        val usedUrls = SessionManager.getUsedBriefingArticleUrls(context)
        val candidates = articles
            .filter { it.url !in usedUrls }
            .shuffled()

        if (candidates.isEmpty()) {
            android.util.Log.w("BriefingArticleSelector", "All articles have been used for briefings. Resetting used list.")
            SessionManager.clearUsedBriefingArticleUrls(context)
            return@withContext selectBriefingArticle(context)
        }

        // 3. Try scraping candidates one by one until one succeeds
        var attempts = 0
        for (article in candidates) {
            if (attempts >= MAX_SCRAPE_ATTEMPTS) break
            attempts++

            val extractedText = try {
                ArticleContentExtractor.extractArticleText(article.url)
            } catch (e: Exception) {
                android.util.Log.e("BriefingArticleSelector", "Scrape failed for ${article.url}: ${e.message}")
                null
            }

            if (extractedText.isNullOrBlank() || extractedText.trim().length < MIN_MEANINGFUL_LENGTH) {
                android.util.Log.d("BriefingArticleSelector", "Skipping unscrapable article: ${article.title}")
                continue
            }

            // 4. Clean the content (collapse whitespace, cap length) and mark as used
            val cleanedContent = cleanArticleText(extractedText)
            SessionManager.addUsedBriefingArticleUrl(context, article.url)

            android.util.Log.d("BriefingArticleSelector", "Selected briefing article: ${article.title}")
            return@withContext BriefingArticle(
                url = article.url,
                title = article.title,
                thumbnail = article.thumbnail,
                source = article.source,
                content = cleanedContent
            )
        }

        android.util.Log.w("BriefingArticleSelector", "No scrapable article found after $attempts attempts.")
        null
    }

    /**
     * Cleans extracted article text: trims, collapses excessive whitespace,
     * and caps the length to keep API usage reasonable.
     */
    private fun cleanArticleText(text: String): String {
        val collapsed = text
            .replace(Regex("\\s+"), " ")
            .trim()
        // Cap at ~6000 characters to keep token usage reasonable
        return if (collapsed.length > 6000) collapsed.take(6000) else collapsed
    }
}