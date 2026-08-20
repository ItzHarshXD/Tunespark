package com.tunespark.music.update

object VersionComparator {

    /**
     * Cleans up a version string by removing leading 'v' or 'V' and trimming whitespace.
     */
    fun sanitizeVersion(version: String): String {
        return version.trim().removePrefix("v").removePrefix("V").trim()
    }

    /**
     * Compares two semantic version strings.
     * Returns:
     *   > 0 if [v1] is newer than [v2]
     *   < 0 if [v1] is older than [v2]
     *   0 if [v1] and [v2] are equivalent
     */
    fun compare(v1: String, v2: String): Int {
        val clean1 = sanitizeVersion(v1)
        val clean2 = sanitizeVersion(v2)

        if (clean1 == clean2) return 0
        if (clean1.isEmpty() && clean2.isEmpty()) return 0
        if (clean1.isEmpty()) return -1
        if (clean2.isEmpty()) return 1

        val parts1 = clean1.split("-", limit = 2)
        val parts2 = clean2.split("-", limit = 2)

        val mainParts1 = parts1[0].split(".").mapNotNull { it.toIntOrNull() }
        val mainParts2 = parts2[0].split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(mainParts1.size, mainParts2.size)
        for (i in 0 until maxLength) {
            val num1 = mainParts1.getOrElse(i) { 0 }
            val num2 = mainParts2.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }

        // If main version numbers are identical, check pre-release tags:
        // A version without pre-release tag is greater than one with a pre-release tag (e.g. 1.0.0 > 1.0.0-rc1)
        val pre1 = parts1.getOrNull(1)
        val pre2 = parts2.getOrNull(1)

        return when {
            pre1 == null && pre2 == null -> 0
            pre1 == null && pre2 != null -> 1
            pre1 != null && pre2 == null -> -1
            else -> (pre1 ?: "").compareTo(pre2 ?: "")
        }
    }

    /**
     * Returns true if [candidateVersion] is strictly newer than [baseVersion].
     */
    fun isNewer(candidateVersion: String, baseVersion: String): Boolean {
        return compare(candidateVersion, baseVersion) > 0
    }
}
