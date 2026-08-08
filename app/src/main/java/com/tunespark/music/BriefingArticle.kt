package com.tunespark.music

/**
 * A news article selected for the Briefing commentary element.
 *
 * The [content] is the successfully scraped article text that gets sent to
 * the AI for a spoken, radio-host-style summary. The other fields are used
 * both in the AI prompt and for the "Open Article" card shown in the UI
 * while the briefing commentary is playing.
 */
data class BriefingArticle(
    val url: String,
    val title: String,
    val thumbnail: String,
    val source: String,
    val content: String
)