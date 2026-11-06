package com.tunespark.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamUrlResolverTest {

    @Test
    fun mapsKnownMimeTypesToCodecNames() {
        assertEquals("Opus", StreamUrlResolver.codecOf("audio/webm; codecs=opus"))
        assertEquals("AAC", StreamUrlResolver.codecOf("audio/mp4; codecs=mp4a.40.2"))
        assertEquals("MP3", StreamUrlResolver.codecOf("audio/mpeg"))
        assertEquals("Vorbis", StreamUrlResolver.codecOf("audio/ogg; codecs=vorbis"))
    }

    @Test
    fun unknownMimeTypeFallsBackToItsSubtype() {
        assertEquals("flac", StreamUrlResolver.codecOf("audio/flac"))
        assertNull(StreamUrlResolver.codecOf("audio/"))
    }
}
