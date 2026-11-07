package com.tunespark.music.lyrics

/**
 * Everything a lyrics provider may need to find the right song.
 *
 * [title] and [artist] are already cleaned of YouTube promotional noise by the
 * caller. [videoId] is the bare YouTube id (never the media-id with the queue
 * qualifier), so providers that index by video can do an exact lookup.
 */
data class LyricsRequest(
    val title: String,
    val artist: String,
    val durationSec: Int = -1,
    val videoId: String? = null,
)

/**
 * A usable lyrics payload returned by a provider.
 *
 * [synced] is true when [rawLyrics] contains timestamps the renderer can use.
 * Plain text is still valid, but the aggregator prefers a synced result from a
 * lower-priority provider over plain text from a higher-priority one.
 */
data class LyricsProviderResult(
    val providerId: String,
    val rawLyrics: String,
    val synced: Boolean,
)

interface LyricsProvider {
    val id: String

    /**
     * Returns null when this provider has no lyrics for the request. Throwing
     * is allowed too; the aggregator treats any failure as "not found".
     */
    suspend fun fetch(request: LyricsRequest): LyricsProviderResult?
}
