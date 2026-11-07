package com.tunespark.music.lyrics

/**
 * Metadata for one selectable lyrics source. The order of [all] is the
 * recommended default priority (best first).
 */
data class LyricsProviderInfo(
    val id: String,
    val displayName: String,
    val description: String,
)

object LyricsProviderRegistry {
    const val UNISON = "unison"
    const val LRCLIB = "lrclib"
    const val KUGOU = "kugou"

    /**
     * Listed in the default priority order: LRCLib → KuGou → Unison.
     * Better Lyrics is intentionally absent: its unified API needs a browser
     * Turnstile challenge plus a JWT, which cannot be completed headlessly.
     */
    val all: List<LyricsProviderInfo> = listOf(
        LyricsProviderInfo(
            id = LRCLIB,
            displayName = "LRCLib",
            description = "Open, community-maintained lyrics database with synced and plain lyrics.",
        ),
        LyricsProviderInfo(
            id = KUGOU,
            displayName = "KuGou",
            description = "Large catalogue with line-synced LRC lyrics. Also the source behind Better Lyrics' Legato provider.",
        ),
        LyricsProviderInfo(
            id = UNISON,
            displayName = "Unison",
            description = "Better Lyrics' community database. Often provides word- or line-synced lyrics.",
        ),
    )

    /** Best-first default order used when the user has not customised it. */
    val defaultOrder: List<String> = all.map { it.id }

    val supportedIds: Set<String> = all.map { it.id }.toSet()

    fun info(id: String): LyricsProviderInfo? = all.firstOrNull { it.id == id }

    fun create(id: String): LyricsProvider? = when (id) {
        UNISON -> UnisonLyricsProvider
        LRCLIB -> LrcLibLyricsProvider
        KUGOU -> KuGouLyricsProvider
        else -> null
    }

    /**
     * Keeps the user's saved order, drops unknown ids, and appends any newly
     * supported provider at its recommended position. This makes new providers
     * appear automatically without resetting the user's customisation.
     */
    fun normalizeOrder(saved: List<String>): List<String> {
        val result = saved.filter { it in supportedIds }.distinct().toMutableList()
        defaultOrder.forEach { id ->
            if (id !in result) result.add(id)
        }
        return result
    }

    /**
     * All providers are enabled by default. A null/absent saved set means the
     * user has never changed the setting, so return every supported id.
     */
    fun normalizeEnabled(saved: Set<String>?): Set<String> {
        if (saved == null) return supportedIds
        return saved.filter { it in supportedIds }.toSet()
    }
}
