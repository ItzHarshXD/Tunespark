package com.tunespark.music.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Tries enabled providers in the user's priority order.
 *
 * The first synced result wins immediately. Plain results are held as a
 * fallback while lower-priority providers are still checked for timestamps,
 * so a synced source always beats an unsynced one regardless of order.
 */
object LyricsAggregator {
    suspend fun fetch(
        providers: List<LyricsProvider>,
        request: LyricsRequest,
    ): LyricsProviderResult? {
        var plainFallback: LyricsProviderResult? = null

        for (provider in providers) {
            currentCoroutineContext().ensureActive()
            val result = try {
                provider.fetch(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            if (result.rawLyrics.isBlank()) continue

            if (result.synced) return result
            if (plainFallback == null) plainFallback = result
        }

        return plainFallback
    }
}
