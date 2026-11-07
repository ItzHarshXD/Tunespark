package com.tunespark.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderRegistryTest {

    @Test
    fun defaultOrderContainsEverySupportedProvider() {
        assertEquals(
            LyricsProviderRegistry.supportedIds,
            LyricsProviderRegistry.defaultOrder.toSet(),
        )
    }

    @Test
    fun defaultOrderIsLrcLibThenKuGouThenUnison() {
        assertEquals(
            listOf(
                LyricsProviderRegistry.LRCLIB,
                LyricsProviderRegistry.KUGOU,
                LyricsProviderRegistry.UNISON,
            ),
            LyricsProviderRegistry.defaultOrder,
        )
    }

    @Test
    fun normalizeOrderKeepsUserOrderAndAppendsMissingProviders() {
        val order = LyricsProviderRegistry.normalizeOrder(
            listOf(LyricsProviderRegistry.KUGOU, "unknown-provider"),
        )

        assertEquals(LyricsProviderRegistry.KUGOU, order.first())
        assertTrue(order.contains(LyricsProviderRegistry.UNISON))
        assertTrue(order.contains(LyricsProviderRegistry.LRCLIB))
        assertFalse(order.contains("unknown-provider"))
    }

    @Test
    fun normalizeOrderDoesNotDuplicateProviders() {
        val order = LyricsProviderRegistry.normalizeOrder(
            listOf(
                LyricsProviderRegistry.LRCLIB,
                LyricsProviderRegistry.LRCLIB,
                LyricsProviderRegistry.UNISON,
            ),
        )

        assertEquals(order.distinct(), order)
    }

    @Test
    fun absentEnabledPreferenceMeansEverythingIsOn() {
        assertEquals(
            LyricsProviderRegistry.supportedIds,
            LyricsProviderRegistry.normalizeEnabled(null),
        )
    }

    @Test
    fun enabledPreferenceDropsUnknownIdsButKeepsKnownOnes() {
        val enabled = LyricsProviderRegistry.normalizeEnabled(
            setOf(LyricsProviderRegistry.LRCLIB, "unknown-provider"),
        )

        assertEquals(setOf(LyricsProviderRegistry.LRCLIB), enabled)
    }

    @Test
    fun userCanDisableEveryProvider() {
        assertTrue(LyricsProviderRegistry.normalizeEnabled(emptySet()).isEmpty())
    }
}
