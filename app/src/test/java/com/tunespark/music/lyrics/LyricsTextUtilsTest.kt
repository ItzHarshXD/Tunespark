package com.tunespark.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTextUtilsTest {

    @Test
    fun detectsLrcTimestamps() {
        assertTrue(hasTimestamps("[00:12.34]line"))
        assertTrue(hasTimestamps("[1:02.345]line"))
        assertFalse(hasTimestamps("just plain lyrics"))
    }

    @Test
    fun convertsUnisonTtmlParagraphsToLrc() {
        val ttml = """
            <tt><body><div>
              <p begin="0:04.915" end="0:08.538"><span begin="0:04.915">Hey</span> <span>there</span></p>
              <p begin="1:02.299" end="1:03.825">Second &amp; last</p>
            </div></body></tt>
        """.trimIndent()

        val lrc = UnisonLyricsProvider.ttmlToLrc(ttml)

        assertEquals(
            "[00:04.915] Hey there\n[01:02.299] Second & last",
            lrc,
        )
    }

    @Test
    fun ignoresTtmlParagraphsWithoutTiming() {
        val lrc = UnisonLyricsProvider.ttmlToLrc("<tt><body><p>no timing</p></body></tt>")
        assertTrue(lrc.isEmpty())
    }

    @Test
    fun similarityRecognizesContainsAndExactMatches() {
        assertEquals(1.0, matchSimilarity("Never Gonna Give You Up", "never gonna give you up"), 0.0001)
        assertTrue(matchSimilarity("Never Gonna Give You Up (Official Video)", "Never Gonna Give You Up") >= 0.8)
        assertTrue(matchSimilarity("Kesariya", "Different Song") < 0.5)
    }
}
