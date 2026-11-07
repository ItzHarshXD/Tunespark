package com.tunespark.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun resolvesProvidersInSavedOrderSkippingDisabledOnes() {
        val providers = LyricsRepository.resolveProviders(
            order = listOf(
                LyricsProviderRegistry.KUGOU,
                LyricsProviderRegistry.LRCLIB,
                LyricsProviderRegistry.UNISON,
            ),
            enabled = setOf(LyricsProviderRegistry.KUGOU, LyricsProviderRegistry.UNISON),
            overrideProviderId = null,
        )

        assertEquals(
            listOf(LyricsProviderRegistry.KUGOU, LyricsProviderRegistry.UNISON),
            providers.map { it.id },
        )
    }

    @Test
    fun onTheSpotOverrideWinsEvenWhenProviderIsDisabled() {
        val providers = LyricsRepository.resolveProviders(
            order = LyricsProviderRegistry.defaultOrder,
            enabled = setOf(LyricsProviderRegistry.LRCLIB),
            overrideProviderId = LyricsProviderRegistry.KUGOU,
        )

        assertEquals(listOf(LyricsProviderRegistry.KUGOU), providers.map { it.id })
    }

    @Test
    fun unknownOverrideProducesNoProviders() {
        assertTrue(
            LyricsRepository.resolveProviders(
                order = LyricsProviderRegistry.defaultOrder,
                enabled = LyricsProviderRegistry.supportedIds,
                overrideProviderId = "unknown-provider",
            ).isEmpty(),
        )
    }
}
