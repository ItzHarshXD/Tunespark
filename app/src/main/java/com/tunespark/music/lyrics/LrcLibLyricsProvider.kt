package com.tunespark.music.lyrics

import com.metrolist.lrclib.LrcLib

/**
 * LRCLib provider. Preserves the multi-step lookup the app used before the
 * provider abstraction: strict duration match, relaxed duration match, then a
 * plain text search.
 */
object LrcLibLyricsProvider : LyricsProvider {
    override val id: String = LyricsProviderRegistry.LRCLIB

    override suspend fun fetch(request: LyricsRequest): LyricsProviderResult? {
        if (request.title.isBlank()) return null

        var raw = LrcLib.getLyrics(
            title = request.title,
            artist = request.artist,
            duration = request.durationSec,
        ).getOrNull()

        if (raw == null) {
            raw = LrcLib.getLyrics(
                title = request.title,
                artist = request.artist,
                duration = -1,
            ).getOrNull()
        }

        if (raw == null) {
            val tracks = LrcLib.lyrics(artist = request.artist, title = request.title).getOrNull()
            val matchedTrack = tracks?.firstOrNull {
                it.syncedLyrics != null || it.plainLyrics != null
            }
            raw = matchedTrack?.syncedLyrics ?: matchedTrack?.plainLyrics
        }

        if (raw.isNullOrBlank()) return null
        return LyricsProviderResult(
            providerId = id,
            rawLyrics = raw,
            synced = hasTimestamps(raw),
        )
    }
}
