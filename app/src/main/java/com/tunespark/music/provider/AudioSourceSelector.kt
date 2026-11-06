package com.tunespark.music.provider

import com.tunespark.music.StreamUrlResolver
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Chooses the best eligible source for one song.
 *
 * YouTube Music is resolved first and always remains the fallback. JioSaavn
 * is only consulted when the user enabled it, and only replaces the YouTube
 * stream when [JioSaavnSource.shouldPreferJioSaavn] says the verified
 * rendition is genuinely better.
 *
 * A small decision cache keeps the same song on the same source for the whole
 * session, which prevents repeated provider switching.
 */
object AudioSourceSelector {
    private data class Decision(
        val provider: AudioProvider,
        val jioSaavnId: String?,
    )

    private val decisions = Collections.synchronizedMap(
        object : LinkedHashMap<String, Decision>(100, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Decision>?,
            ): Boolean = size > 200
        }
    )

    /**
     * Resolves a playable rendition for [songId].
     *
     * @param songId Tunespark song id (bare YouTube id or `jiosaavn:<id>`).
     * @param forceAlternate used by playback-error recovery: asks for the
     *        other provider than the one that just failed.
     */
    suspend fun resolve(
        songId: String,
        title: String?,
        artist: String?,
        album: String?,
        durationSeconds: Int?,
        jioSaavnEnabled: Boolean,
        forceAlternate: Boolean = false,
        explicit: Boolean = false,
        failedProvider: AudioProvider? = null,
    ): ResolvedStream? {
        val bareId = AudioProviderIds.bareMediaId(songId)

        // A native JioSaavn result has no YouTube counterpart to compare with.
        if (AudioProviderIds.isJioSaavnId(songId)) {
            // Playback-error recovery has no other provider to try for a
            // JioSaavn-only track.
            if (forceAlternate) return null
            return JioSaavnSource.resolveStream(
                saavnSongId = AudioProviderIds.bareJioSaavnId(songId),
                allowLowQuality = true,
            )
        }

        val youtube = StreamUrlResolver.resolveYouTubeStream(bareId)

        val cached = decisions[songId]
        // A restored session may hold a resolved item whose decision was not
        // cached in this process; the item's own metadata names the provider
        // that just failed.
        val previousProvider = cached?.provider ?: failedProvider

        if (!jioSaavnEnabled || title.isNullOrBlank()) {
            // No decision is cached while JioSaavn is off (or metadata is too
            // thin to match), so re-enabling the source can evaluate the song
            // afresh instead of being pinned to an earlier YouTube choice.
            decisions.remove(songId)
            // With JioSaavn unavailable, the only possible alternate for a
            // failed JioSaavn item is YouTube; for a failed YouTube item there
            // is nothing else to try.
            return when {
                !forceAlternate -> youtube
                previousProvider == AudioProvider.JIOSAAVN -> youtube
                else -> null
            }
        }

        // Resolve the JioSaavn side from the cached match id, or search once.
        val details = if (cached?.jioSaavnId != null) {
            JioSaavnService.getSongDetails(cached.jioSaavnId)
        } else {
            JioSaavnSource.resolveMatch(
                title = title,
                artist = artist.orEmpty(),
                album = album,
                durationSeconds = durationSeconds,
                explicit = explicit,
            )
        }

        val jiosaavn = JioSaavnSource.toResolvedStream(
            details,
            // Low-quality renditions are only acceptable when there is no
            // YouTube stream to fall back to (or when recovering from one).
            allowLowQuality = forceAlternate || youtube == null,
        )
        val matchId = details?.song?.id?.takeIf { it.isNotBlank() }

        if (jiosaavn == null) {
            decisions[songId] = Decision(AudioProvider.YOUTUBE_MUSIC, matchId)
            // Recovery asked for the provider that did NOT just fail. If only
            // the failed provider is available, report no alternative so the
            // service can skip instead of replaying the same broken URL.
            if (forceAlternate && previousProvider == AudioProvider.YOUTUBE_MUSIC) return null
            return youtube
        }

        val preferJioSaavn = when {
            // The caller's previous source just failed: pick the other one.
            forceAlternate && previousProvider == AudioProvider.JIOSAAVN -> false
            forceAlternate && previousProvider == AudioProvider.YOUTUBE_MUSIC -> true
            // Unknown failed provider: YouTube is the default first attempt, so
            // JioSaavn is the only meaningful alternate left.
            forceAlternate && previousProvider == null -> true
            // A previously made decision is honoured, so a song never flips
            // sources between plays in the same session.
            previousProvider != null -> previousProvider == AudioProvider.JIOSAAVN
            else -> JioSaavnSource.shouldPreferJioSaavn(youtube, jiosaavn)
        }

        val chosen = if (preferJioSaavn) jiosaavn else youtube ?: jiosaavn

        // Never hand back the provider that just failed.
        if (forceAlternate && chosen.provider == previousProvider) return null

        decisions[songId] = Decision(chosen.provider, matchId)
        return chosen
    }

    /** Clears the session decision cache (used by tests / diagnostics). */
    fun clearCache() = decisions.clear()
}
