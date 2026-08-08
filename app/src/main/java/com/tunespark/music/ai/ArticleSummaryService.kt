package com.tunespark.music.ai

import android.content.Context
import com.tunespark.music.SessionManager

/**
 * Orchestrates the AI article summary generation flow:
 *
 *  1. Check the local cache first (never summarize the same article twice).
 *  2. Fetch the article webpage using the article URL.
 *  3. Extract the main article content using a Mozilla Readability-style heuristic.
 *  4. Clean the extracted content.
 *  5. Send only the cleaned article text to the existing Gemini model
 *     (reusing the same API key and generation pipeline used elsewhere).
 *  6. Cache the generated summary locally.
 *
 * If the article page cannot be scraped, a fixed sentinel string
 * ([UNABLE_TO_GET_DATA]) is returned instead of throwing, so the UI can
 * reliably detect unscraped articles and show a friendly fallback.
 */
object ArticleSummaryService {

    /**
     * Fixed sentinel returned when the article page could not be scraped.
     * The UI checks for this exact string to show the "unable to get data"
     * fallback with an "Open Article" button.
     */
    const val UNABLE_TO_GET_DATA = "Unable to get data"

    /**
     * Minimum meaningful article content length (in characters) before we
     * consider the scrape successful. If the extracted text is shorter than
     * this, it's almost certainly boilerplate (nav labels, "Loading...",
     * cookie banners, etc.) and not real article content — so we skip the AI
     * call entirely and return the unscraped sentinel instead.
     */
    private const val MIN_MEANINGFUL_LENGTH = 200

    /**
     * Returns a summary for the given article.
     *
     * If a cached summary exists, it is returned immediately without any API call.
     * Otherwise the article is fetched, extracted, cleaned, summarized via Gemini,
     * and cached.
     *
     * If the article page cannot be scraped, [UNABLE_TO_GET_DATA] is returned
     * (and cached) so the UI can show a consistent, reliable fallback.
     *
     * @param context Android context.
     * @param articleUrl The article URL to summarize.
     * @param articleTitle The article title (used in the prompt for context).
     * @return The generated summary text, or [UNABLE_TO_GET_DATA] if unscraped.
     * @throws Exception If the Gemini API key is missing or generation fails.
     */
    suspend fun getOrGenerateSummary(
        context: Context,
        articleUrl: String,
        articleTitle: String
    ): String {
        // 1. Reuse cached summary if available
        ArticleSummaryCache.getSummary(context, articleUrl)?.let { cached ->
            return cached
        }

        // 2. Fetch the article webpage and extract main content
        val articleText = ArticleContentExtractor.extractArticleText(articleUrl)

        // 3. If the page could not be scraped (null/blank), OR the extracted
        //    content is too short to be meaningful (e.g. just a nav label or
        //    "Loading..." text), return the fixed sentinel directly WITHOUT
        //    calling the AI model. This avoids wasting tokens on content we
        //    already know is unusable.
        if (articleText.isNullOrBlank() || articleText.trim().length < MIN_MEANINGFUL_LENGTH) {
            ArticleSummaryCache.saveSummary(context, articleUrl, UNABLE_TO_GET_DATA)
            return UNABLE_TO_GET_DATA
        }

        // 4. Clean the extracted content (trim, collapse whitespace, limit length)
        val cleanedText = cleanArticleText(articleText)

        // 5. Generate the summary using the existing Gemini pipeline
        val apiKey = SessionManager.getGeminiApiKey(context)
        if (apiKey.isBlank()) {
            throw Exception("Please add your Gemini API key in AI Settings first.")
        }

        val prompt = buildSummaryPrompt(articleTitle, cleanedText)
        val summary = GeminiTextGenerator.generateText(apiKey, prompt)

        // If the model returned the sentinel (e.g. it decided the content was
        // unusable), treat it as unscraped and cache it.
        val finalSummary = if (summary.isBlank()) UNABLE_TO_GET_DATA else summary
        ArticleSummaryCache.saveSummary(context, articleUrl, finalSummary)

        return finalSummary
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

    /**
     * Builds a simple, casual summary prompt.
     *
     * The summary is written for everyday readers: a quick one-line takeaway
     * followed by a few short, plain points. No labels, no classification,
     * no academic tone. If the model has no usable article content, it must
     * return the exact sentinel [UNABLE_TO_GET_DATA] so the UI can show the
     * unscraped fallback.
     */
    private fun buildSummaryPrompt(title: String, articleText: String): String {
        return """
            You are writing a quick, casual summary for a music app's news feed.
            Keep it simple and easy to read for a normal person — not a report,
            not a study document, not academic.

            Format:
            - First line: one short, friendly sentence that captures the main
              takeaway. Do NOT start it with "Summary:" or any label.
            - Then: 2-4 short bullet points with the key details.
              Start each bullet with "- " and keep each one to a single clear,
              simple sentence.
            - No headings, no labels like "KEY DEVELOPMENT" or "WHY IT MATTERS",
              no classification, no asterisks, no markdown.
            - Keep the whole thing short and scannable.

            If you do not have any usable article content, respond with exactly
            this line and nothing else: $UNABLE_TO_GET_DATA

            Article title: $title

            Article content:
            $articleText
        """.trimIndent()
    }
}