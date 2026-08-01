package com.tunespark.music.rss

/**
 * A normalized article model used across the Discover feed.
 *
 * All RSS feeds are parsed and normalized into this common model so the UI
 * can render a consistent, merged feed regardless of the source format.
 */
data class Article(
    val id: String,
    val title: String,
    val description: String,
    val thumbnail: String,
    val source: String,
    val url: String,
    val publishedDate: Long,
    val category: String
) {
    /**
     * Returns a human-readable relative time string (e.g. "2h ago", "3d ago").
     */
    fun timeAgo(): String {
        val now = System.currentTimeMillis()
        val diff = now - publishedDate
        if (diff < 0) return "Just now"
        val minutes = diff / 60_000
        if (minutes < 1) return "Just now"
        if (minutes < 60) return "${minutes}m ago"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ago"
        val days = hours / 24
        if (days < 7) return "${days}d ago"
        val weeks = days / 7
        if (weeks < 5) return "${weeks}w ago"
        val months = days / 30
        if (months < 12) return "${months}mo ago"
        val years = days / 365
        return "${years}y ago"
    }
}