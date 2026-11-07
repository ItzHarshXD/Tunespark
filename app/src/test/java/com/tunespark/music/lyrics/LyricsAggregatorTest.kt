package com.tunespark.music.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAggregatorTest {

    private class FakeProvider(
        override val id: String,
        private val result: LyricsProviderResult? = null,
        private val error: Throwable? = null,
    ) : LyricsProvider {
        override suspend fun fetch(request: LyricsRequest): LyricsProviderResult? {
            error?.let { throw it }
            return result
        }
    }

    private val request = LyricsRequest(
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        durationSec = 213,
        videoId = "dQw4w9WgXcQ",
    )

    private fun result(id: String, synced: Boolean) = LyricsProviderResult(
        providerId = id,
        rawLyrics = if (synced) "[00:01.00]line" else "line",
        synced = synced,
    )

    @Test
    fun returnsFirstSyncedResultInPriorityOrder() = runBlocking {
        val result = LyricsAggregator.fetch(
            providers = listOf(
                FakeProvider("first", result("first", synced = true)),
                FakeProvider("second", result("second", synced = true)),
            ),
            request = request,
        )

        assertEquals("first", result?.providerId)
    }

    @Test
    fun skipsProvidersThatThrowOrReturnNothing() = runBlocking {
        val result = LyricsAggregator.fetch(
            providers = listOf(
                FakeProvider("broken", error = IllegalStateException("network")),
                FakeProvider("empty"),
                FakeProvider("working", result("working", synced = true)),
            ),
            request = request,
        )

        assertEquals("working", result?.providerId)
    }

    @Test
    fun syncedResultFromLowerPriorityBeatsPlainResultFromHigherPriority() = runBlocking {
        val result = LyricsAggregator.fetch(
            providers = listOf(
                FakeProvider("plain", result("plain", synced = false)),
                FakeProvider("synced", result("synced", synced = true)),
            ),
            request = request,
        )

        assertEquals("synced", result?.providerId)
        assertTrue(result?.synced == true)
    }

    @Test
    fun returnsFirstPlainResultWhenNoProviderHasTimestamps() = runBlocking {
        val result = LyricsAggregator.fetch(
            providers = listOf(
                FakeProvider("plain-one", result("plain-one", synced = false)),
                FakeProvider("plain-two", result("plain-two", synced = false)),
            ),
            request = request,
        )

        assertEquals("plain-one", result?.providerId)
    }

    @Test
    fun returnsNullWhenEveryProviderFails() = runBlocking {
        val result = LyricsAggregator.fetch(
            providers = listOf(
                FakeProvider("broken", error = IllegalStateException("network")),
                FakeProvider("empty"),
            ),
            request = request,
        )

        assertNull(result)
    }

    @Test
    fun emptyProviderListReturnsNull() = runBlocking {
        assertNull(LyricsAggregator.fetch(emptyList(), request))
    }
}
