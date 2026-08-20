package com.tunespark.music

import com.tunespark.music.update.VersionComparator
import org.junit.Assert.*
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun testBasicSemverComparison() {
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.1.0", "1.0.9"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.9.9"))
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))

        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.1"))
        assertFalse(VersionComparator.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun testVersionWithVPrefix() {
        assertTrue(VersionComparator.isNewer("v1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("V1.0.1", "v1.0.0"))
        assertTrue(VersionComparator.isNewer("v2.0.0", "v1.0.0"))
        assertFalse(VersionComparator.isNewer("v1.0.0", "1.0.0"))
    }

    @Test
    fun testDifferingSegmentLengths() {
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0"))
        assertTrue(VersionComparator.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
    }

    @Test
    fun testPrereleaseComparison() {
        // Release version is newer than prerelease of same version
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-beta"))
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-rc1"))
        assertTrue(VersionComparator.isNewer("1.0.0-rc2", "1.0.0-rc1"))
        assertFalse(VersionComparator.isNewer("1.0.0-beta", "1.0.0"))
    }

    @Test
    fun testSanitizeVersion() {
        assertEquals("1.0.0", VersionComparator.sanitizeVersion("v1.0.0"))
        assertEquals("1.0.0", VersionComparator.sanitizeVersion("V1.0.0"))
        assertEquals("1.0.0", VersionComparator.sanitizeVersion(" 1.0.0 "))
        assertEquals("1.0.0-beta.1", VersionComparator.sanitizeVersion("v1.0.0-beta.1"))
    }
}
