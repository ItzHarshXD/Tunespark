package com.tunespark.music.lyrics

import android.content.Context
import com.tunespark.music.SessionManager

/**
 * Android-facing entry point: reads the user's enabled providers and priority
 * order from [SessionManager], then delegates to [LyricsAggregator].
 */
object LyricsRepository {
    suspend fun fetch(
        context: Context,
        title: String,
        artist: String,
        durationSec: Int,
        videoId: String?,
        overrideProviderId: String? = null,
    ): LyricsProviderResult? {
        val providers = resolveProviders(
            order = SessionManager.getLyricsProviderOrder(context),
            enabled = SessionManager.getEnabledLyricsProviders(context),
            overrideProviderId = overrideProviderId,
        )

        if (providers.isEmpty()) return null

        return LyricsAggregator.fetch(
            providers = providers,
            request = LyricsRequest(
                title = title,
                artist = artist,
                durationSec = durationSec,
                videoId = videoId,
            ),
        )
    }

    /**
     * An on-the-spot choice from the Now Playing indicator wins over the saved
     * order and is honoured even when that provider is disabled in Settings.
     */
    internal fun resolveProviders(
        order: List<String>,
        enabled: Set<String>,
        overrideProviderId: String?,
    ): List<LyricsProvider> {
        if (overrideProviderId != null) {
            return listOfNotNull(LyricsProviderRegistry.create(overrideProviderId))
        }
        return order
            .asSequence()
            .filter { it in enabled }
            .mapNotNull { LyricsProviderRegistry.create(it) }
            .toList()
    }
}
