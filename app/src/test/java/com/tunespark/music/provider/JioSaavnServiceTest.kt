package com.tunespark.music.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JioSaavnServiceTest {

    private val base = "https://aac.saavncdn.com/123/abcdefghij_160.mp4"

    @Test
    fun keepsTheVerifiedRenditionWhenNo320Exists() {
        val stream = selectBestJioSaavnStream(base, supports320 = false)

        assertEquals(base, stream?.url)
        assertEquals(160, stream?.kbps)
    }

    @Test
    fun rewritesTo320OnlyWhenTheCatalogueConfirmsIt() {
        val stream = selectBestJioSaavnStream(base, supports320 = true)

        assertEquals("https://aac.saavncdn.com/123/abcdefghij_320.mp4", stream?.url)
        assertEquals(320, stream?.kbps)
    }

    @Test
    fun reportsUnknownBitrateWhenTheRenditionCannotBeRecognised() {
        val stream = selectBestJioSaavnStream(
            "https://aac.saavncdn.com/123/abcdefghij.mp4",
            supports320 = true,
        )

        assertEquals("https://aac.saavncdn.com/123/abcdefghij.mp4", stream?.url)
        assertNull(stream?.kbps)
    }

    @Test
    fun blankUrlHasNoStream() {
        assertNull(selectBestJioSaavnStream("", supports320 = true))
    }

    @Test
    fun jioSaavnIdsAreNamespacedWithoutChangingTheRawId() {
        val namespaced = AudioProviderIds.jioSaavnSongId("raw123")

        assertEquals("jiosaavn:raw123", namespaced)
        assertEquals(true, AudioProviderIds.isJioSaavnId(namespaced))
        assertEquals("raw123", AudioProviderIds.bareJioSaavnId(namespaced))
        assertEquals("raw123", AudioProviderIds.bareJioSaavnId("jiosaavn:raw123#q2"))
    }
}
