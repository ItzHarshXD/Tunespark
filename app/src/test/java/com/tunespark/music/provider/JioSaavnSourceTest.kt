package com.tunespark.music.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JioSaavnSourceTest {

    private fun candidate(
        title: String,
        artist: String,
        duration: String = "210",
        album: String = "",
        supports320: Boolean = false,
    ) = RawJioSaavnSong(
        id = "song-id",
        title = title,
        moreInfo = RawJioSaavnMoreInfo(
            album = album,
            duration = duration,
            has320 = kotlinx.serialization.json.JsonPrimitive(supports320),
            artistMap = RawJioSaavnArtistMap(
                primaryArtists = listOf(RawJioSaavnArtist(name = artist)),
            ),
        ),
    )

    private fun stream(provider: AudioProvider, kbps: Int?) =
        ResolvedStream(url = "https://example.test/audio", provider = provider, codec = "AAC", bitrateKbps = kbps)

    @Test
    fun exactRecordingScoresAboveMatchThreshold() {
        val score = JioSaavnSource.scoreMatch(
            title = "Kesariya",
            artist = "Arijit Singh",
            album = "Brahmastra",
            durationSeconds = 268,
            explicit = false,
            candidate = candidate("Kesariya", "Arijit Singh", duration = "268", album = "Brahmastra"),
        )

        assertTrue(score >= 60)
    }

    @Test
    fun differentTitleIsRejected() {
        val score = JioSaavnSource.scoreMatch(
            title = "Kesariya",
            artist = "Arijit Singh",
            album = null,
            durationSeconds = 268,
            explicit = false,
            candidate = candidate("Deva Deva", "Arijit Singh", duration = "268"),
        )

        assertEquals(0, score)
    }

    @Test
    fun remixIsNotTreatedAsTheSameRecording() {
        val score = JioSaavnSource.scoreMatch(
            title = "Kesariya",
            artist = "Arijit Singh",
            album = null,
            durationSeconds = 268,
            explicit = false,
            candidate = candidate("Kesariya (Remix)", "Arijit Singh", duration = "268"),
        )

        assertEquals(0, score)
    }

    @Test
    fun liveVersionIsNotTreatedAsTheSameRecording() {
        val score = JioSaavnSource.scoreMatch(
            title = "Kesariya",
            artist = "Arijit Singh",
            album = null,
            durationSeconds = 268,
            explicit = false,
            candidate = candidate("Kesariya - Live", "Arijit Singh", duration = "268"),
        )

        assertEquals(0, score)
    }

    @Test
    fun aLargeDurationGapRejectsTheCandidate() {
        val score = JioSaavnSource.scoreMatch(
            title = "Kesariya",
            artist = "Arijit Singh",
            album = null,
            durationSeconds = 268,
            explicit = false,
            candidate = candidate("Kesariya", "Arijit Singh", duration = "310"),
        )

        assertTrue(score < 60)
    }

    @Test
    fun normalizeIgnoresFeaturingCreditsAndPunctuation() {
        assertEquals(
            JioSaavnSource.normalize("Kesariya"),
            JioSaavnSource.normalize("Kesariya (feat. Someone)"),
        )
    }

    @Test
    fun shortTitleDoesNotMatchALongerWord() {
        val score = JioSaavnSource.scoreMatch(
            title = "Hum",
            artist = "Arijit Singh",
            album = null,
            durationSeconds = 240,
            explicit = false,
            candidate = candidate("Humma Humma", "Arijit Singh", duration = "240"),
        )

        assertEquals(0, score)
    }

    @Test
    fun versionMarkerDetectionUsesWholeWords() {
        assertEquals(emptySet<String>(), JioSaavnSource.versionMarkers("Undercover"))
        assertEquals(setOf("remix"), JioSaavnSource.versionMarkers("Song (Remix)"))
        assertEquals(setOf("live"), JioSaavnSource.versionMarkers("Song - Live"))
    }

    @Test
    fun prefersVerified320OverLowerYouTube() {
        assertTrue(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, 128),
                jiosaavn = stream(AudioProvider.JIOSAAVN, 320),
            )
        )
    }

    @Test
    fun keepsYouTubeWhenBothAre320() {
        assertFalse(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, 320),
                jiosaavn = stream(AudioProvider.JIOSAAVN, 320),
            )
        )
    }

    @Test
    fun keepsYouTubeAtEqual160Tier() {
        assertFalse(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, 160),
                jiosaavn = stream(AudioProvider.JIOSAAVN, 160),
            )
        )
    }

    @Test
    fun neverReplacesWorkingStreamWithLowQuality() {
        assertFalse(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, 96),
                jiosaavn = stream(AudioProvider.JIOSAAVN, 96),
            )
        )
    }

    @Test
    fun unknownJioSaavnBitrateIsNeverCalledBetter() {
        assertFalse(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, 128),
                jiosaavn = stream(AudioProvider.JIOSAAVN, null),
            )
        )
    }

    @Test
    fun topTierJioSaavnIsUsedWhenYouTubeBitrateIsUnknown() {
        assertTrue(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, null),
                jiosaavn = stream(AudioProvider.JIOSAAVN, 320),
            )
        )
        assertFalse(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = stream(AudioProvider.YOUTUBE_MUSIC, null),
                jiosaavn = stream(AudioProvider.JIOSAAVN, 160),
            )
        )
    }

    @Test
    fun jioSaavnIsUsedWhenYouTubeHasNoStream() {
        assertTrue(
            JioSaavnSource.shouldPreferJioSaavn(
                youtube = null,
                jiosaavn = stream(AudioProvider.JIOSAAVN, 160),
            )
        )
        assertFalse(JioSaavnSource.shouldPreferJioSaavn(null, null))
    }
}
