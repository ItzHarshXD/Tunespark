package com.tunespark.music.provider

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import kotlin.math.abs

/**
 * Tunespark's adapter for the JioSaavn catalogue.
 *
 * Responsibilities kept here (and out of [JioSaavnService]):
 * - converting search results into Tunespark's existing [SongItem] model;
 * - deciding whether a JioSaavn row is really the same recording;
 * - comparing the actually available renditions;
 * - resolving a playable stream and deciding when a fallback is warranted.
 */
object JioSaavnSource {
    /**
     * A JioSaavn rendition below this rate is never worth replacing a working
     * YouTube Music stream with (YouTube normally serves ~128-160 kbps Opus).
     * It is still playable when JioSaavn is the only source for a track.
     */
    private const val MIN_REPLACEMENT_KBPS = 160

    /** Required confidence before two catalogue rows are treated as one song. */
    private const val MIN_MATCH_SCORE = 60

    /** ------------------------------------------------------------------ */
    /** Search + metadata mapping                                           */
    /** ------------------------------------------------------------------ */

    suspend fun search(query: String, limit: Int = 10): List<SongItem> =
        JioSaavnService.searchSongs(query, limit)
            .mapNotNull { it.toSongItem() }
            // The catalogue frequently returns clean and explicit copies of the
            // same recording. Keep one row per (title, version, primary artists),
            // and prefer the explicit copy when both exist (the user's setting is
            // applied later by the rest of the app). Version markers stay in the
            // key so a remix/live take is not collapsed into the original.
            .groupBy {
                "${normalize(it.title)}|${versionMarkers(it.title)}|" +
                    it.artists.joinToString(",") { a -> normalize(a.name) }
            }
            .values
            .map { rows -> rows.firstOrNull { it.explicit } ?: rows.first() }
            .distinctBy { it.id }

    internal fun RawJioSaavnSong.toSongItem(): SongItem? {
        val rawId = id.trim()
        val title = title.trim()
        if (rawId.isBlank() || title.isBlank()) return null

        val artistNames = moreInfo.primaryArtists.ifEmpty { listOf("Unknown Artist") }
        val thumbnail = image
            .replace(Regex("150x150|50x50"), "500x500")
            .replace(Regex("^http://"), "https://")

        return SongItem(
            id = AudioProviderIds.jioSaavnSongId(rawId),
            title = title,
            artists = artistNames.map { Artist(name = it, id = null) },
            album = albumName?.let { Album(name = it, id = moreInfo.albumId) },
            duration = durationSeconds,
            thumbnail = thumbnail,
            explicit = isExplicit,
        )
    }

    /** ------------------------------------------------------------------ */
    /** Matching                                                            */
    /** ------------------------------------------------------------------ */

    /**
     * Finds the JioSaavn row that is the same recording as [song], if any.
     *
     * Deliberately conservative: a title match is mandatory, a conflicting
     * artist is penalised, and a large duration gap rejects the candidate.
     * This prevents replacing a track with a remix, live version or a
     * different recording that happens to share a title.
     */
    suspend fun findBestMatch(song: SongItem): RawJioSaavnSong? {
        if (AudioProviderIds.isJioSaavnId(song.id)) return null
        return findBestMatch(
            title = song.title,
            artist = song.artists.firstOrNull()?.name.orEmpty(),
            album = song.album?.name,
            durationSeconds = song.duration,
            explicit = song.explicit,
        )
    }

    suspend fun findBestMatch(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        explicit: Boolean = false,
    ): RawJioSaavnSong? {
        if (title.isBlank()) return null

        val query = listOf(title.trim(), artist.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val candidates = JioSaavnService.searchSongs(query, 10)
        if (candidates.isEmpty()) return null

        return candidates
            .map { candidate -> candidate to scoreMatch(title, artist, album, durationSeconds, explicit, candidate) }
            .filter { (_, score) -> score >= MIN_MATCH_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    /**
     * Finds the matching row and fetches its details (including the stream
     * rendition) in one pass, so source selection does not search twice.
     */
    suspend fun resolveMatch(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        explicit: Boolean = false,
    ): JioSaavnSongDetails? {
        val match = findBestMatch(title, artist, album, durationSeconds, explicit) ?: return null
        return JioSaavnService.getSongDetails(match.id)
    }

    fun toResolvedStream(
        details: JioSaavnSongDetails?,
        allowLowQuality: Boolean = false,
    ): ResolvedStream? {
        val stream = details?.stream ?: return null
        if (stream.url.isBlank()) return null
        val kbps = stream.kbps
        if (!allowLowQuality && kbps != null && kbps < MIN_REPLACEMENT_KBPS) return null

        return ResolvedStream(
            url = stream.url,
            provider = AudioProvider.JIOSAAVN,
            // JioSaavn's `.mp4` files carry AAC audio. It is a lossy codec and
            // is never described as lossless.
            codec = "AAC",
            bitrateKbps = kbps,
        )
    }

    internal fun scoreMatch(song: SongItem, candidate: RawJioSaavnSong): Int =
        scoreMatch(
            title = song.title,
            artist = song.artists.firstOrNull()?.name.orEmpty(),
            album = song.album?.name,
            durationSeconds = song.duration,
            explicit = song.explicit,
            candidate = candidate,
        )

    internal fun scoreMatch(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        explicit: Boolean,
        candidate: RawJioSaavnSong,
    ): Int {
        val targetTitle = normalize(title)
        val candidateTitle = normalize(candidate.title)
        if (targetTitle.isBlank() || candidateTitle.isBlank()) return 0

        // A remix, live take, cover or karaoke track is a different recording,
        // even when its title normalises to the same words. Only compare rows
        // that carry the same version markers.
        if (versionMarkers(title) != versionMarkers(candidate.title)) return 0

        val titleExact = targetTitle == candidateTitle
        // Word-level containment only: "Hum" must not match "Humma".
        val targetTokens = targetTitle.split(" ").filter { it.isNotBlank() }.toSet()
        val candidateTokens = candidateTitle.split(" ").filter { it.isNotBlank() }.toSet()
        val titleContains = targetTokens.containsAll(candidateTokens) ||
            candidateTokens.containsAll(targetTokens)
        if (!titleExact && !titleContains) return 0

        var score = if (titleExact) 50 else 30

        val targetArtists = artist.split(",")
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val candidateArtists = candidate.moreInfo.primaryArtists
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()

        when {
            targetArtists.intersect(candidateArtists).isNotEmpty() -> score += 25
            targetArtists.any { target ->
                candidateArtists.any { candidateName ->
                    candidateName.contains(target) || target.contains(candidateName)
                }
            } -> score += 15
            targetArtists.isNotEmpty() && candidateArtists.isNotEmpty() -> score -= 20
        }

        val targetAlbum = album?.let(::normalize)?.takeIf { it.isNotBlank() }
        val candidateAlbum = candidate.albumName?.let(::normalize)?.takeIf { it.isNotBlank() }
        if (targetAlbum != null && candidateAlbum != null && targetAlbum == candidateAlbum) {
            score += 15
        }

        val candidateDuration = candidate.durationSeconds
        if (durationSeconds != null && durationSeconds > 0 && candidateDuration != null) {
            val diff = abs(durationSeconds - candidateDuration)
            score += when {
                diff <= 3 -> 15
                diff <= 8 -> 8
                diff > 20 -> -25
                else -> 0
            }
        }

        if (explicit == candidate.isExplicit) score += 3

        return score
    }

    /** ------------------------------------------------------------------ */
    /** Stream resolution + quality comparison                              */
    /** ------------------------------------------------------------------ */

    suspend fun resolveStream(
        saavnSongId: String,
        allowLowQuality: Boolean = false,
    ): ResolvedStream? {
        val details = JioSaavnService.getSongDetails(saavnSongId) ?: return null
        return toResolvedStream(details, allowLowQuality)
    }

    /**
     * Decides whether a JioSaavn rendition is genuinely worth switching to.
     *
     * A higher number is not automatically better: the decision only swaps
     * when the verified JioSaavn rendition clearly beats what YouTube Music
     * already offers. Equal tiers keep YouTube to avoid needless switching.
     */
    fun shouldPreferJioSaavn(
        youtube: ResolvedStream?,
        jiosaavn: ResolvedStream?,
    ): Boolean {
        if (jiosaavn == null) return false
        if (youtube == null) return true

        val jioKbps = jiosaavn.bitrateKbps
        val ytKbps = youtube.bitrateKbps

        // Never replace a working stream with a thin rendition.
        if (jioKbps != null && jioKbps < MIN_REPLACEMENT_KBPS) return false

        // An unverified JioSaavn bitrate can never be called "better".
        if (jioKbps == null) return false

        // If YouTube's rate is unknown, only a verified top-tier rendition
        // justifies a switch.
        if (ytKbps == null) return jioKbps >= 320

        return when {
            jioKbps >= 320 && ytKbps < 320 -> true
            jioKbps >= 160 && ytKbps < 160 -> true
            else -> false
        }
    }

    /** Audio-version markers that make two rows different recordings. */
    private val VERSION_MARKERS = listOf(
        "remix", "live", "cover", "instrumental", "karaoke",
        "acoustic", "unplugged", "reprise", "lofi", "lo-fi",
        "slowed", "reverb", "extended", "sped up",
    )

    internal fun versionMarkers(title: String): Set<String> {
        val lower = title.lowercase()
        return VERSION_MARKERS.filter { marker ->
            Regex("(^|[^a-z0-9])${Regex.escape(marker)}([^a-z0-9]|$)").containsMatchIn(lower)
        }.toSet()
    }

    internal fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\b(feat\\.?|ft\\.?|featuring|with)\\b.*$"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
