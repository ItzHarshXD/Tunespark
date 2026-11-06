package com.tunespark.music.provider

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata

/**
 * The audio catalogues Tunespark can stream from.
 *
 * YouTube Music is the app's default and fallback source. JioSaavn is an
 * optional second source: it is only used when it can be matched confidently
 * to the same recording and when it genuinely offers a better rendition.
 */
enum class AudioProvider(val displayName: String) {
    YOUTUBE_MUSIC("YouTube Music"),
    JIOSAAVN("JioSaavn"),
}

/**
 * A concrete, playable rendition.
 *
 * [codec] and [bitrateKbps] are only populated when the provider actually
 * reported them. They are never guessed from a requested quality: a null
 * value means "unknown", not "assume 320".
 */
data class ResolvedStream(
    val url: String,
    val provider: AudioProvider,
    val codec: String?,
    val bitrateKbps: Int?,
)

/**
 * JioSaavn song ids are namespaced inside Tunespark's existing [SongItem.id]
 * field so the whole playback pipeline (queue placeholders, history, resume)
 * keeps working without changing the `:innertube` module's models.
 */
object AudioProviderIds {
    const val JIOSAAVN_PREFIX = "jiosaavn:"

    fun isJioSaavnId(id: String): Boolean = id.startsWith(JIOSAAVN_PREFIX)

    fun jioSaavnSongId(rawId: String): String = "$JIOSAAVN_PREFIX$rawId"

    fun bareJioSaavnId(id: String): String =
        id.substringBefore('#').removePrefix(JIOSAAVN_PREFIX)

    /** Strips the app's `#q<n>` unique-mediaId qualifier from any provider id. */
    fun bareMediaId(mediaId: String): String = mediaId.substringBefore('#')
}

/**
 * Helpers for carrying verified stream information through Media3's
 * [MediaMetadata] so the UI (and the playback service) can show the source
 * that is actually playing, not the one that was requested.
 */
object StreamMetadata {
    const val KEY_PROVIDER = "tunespark.stream.provider"
    const val KEY_CODEC = "tunespark.stream.codec"
    const val KEY_BITRATE_KBPS = "tunespark.stream.bitrateKbps"
    const val KEY_ALBUM = "tunespark.song.album"
    const val KEY_DURATION_SECONDS = "tunespark.song.durationSeconds"

    fun withStreamInfo(metadata: MediaMetadata, stream: ResolvedStream): MediaMetadata {
        val extras = Bundle(metadata.extras ?: Bundle())
        extras.putString(KEY_PROVIDER, stream.provider.name)
        stream.codec?.takeIf { it.isNotBlank() }?.let { extras.putString(KEY_CODEC, it) }
        stream.bitrateKbps?.let { extras.putInt(KEY_BITRATE_KBPS, it) }
        return metadata.buildUpon().setExtras(extras).build()
    }

    fun withSongInfo(
        metadata: MediaMetadata,
        album: String?,
        durationSeconds: Int?,
    ): MediaMetadata {
        val extras = Bundle(metadata.extras ?: Bundle())
        album?.takeIf { it.isNotBlank() }?.let { extras.putString(KEY_ALBUM, it) }
        durationSeconds?.takeIf { it > 0 }?.let { extras.putInt(KEY_DURATION_SECONDS, it) }
        return metadata.buildUpon().setExtras(extras).build()
    }

    fun album(metadata: MediaMetadata): String? =
        metadata.extras?.getString(KEY_ALBUM) ?: metadata.albumTitle?.toString()

    fun durationSeconds(metadata: MediaMetadata): Int? =
        metadata.extras?.getInt(KEY_DURATION_SECONDS, -1)?.takeIf { it > 0 }

    fun provider(metadata: MediaMetadata): AudioProvider? =
        metadata.extras?.getString(KEY_PROVIDER)?.let { name ->
            AudioProvider.entries.firstOrNull { it.name == name }
        }

    /**
     * The single "provider · codec · bitrate" line shown in Now Playing.
     * Returns null when stats are disabled or nothing verified is available.
     *
     * Example: `JioSaavn · AAC · 320 kbps`.
     */
    fun displayLine(metadata: MediaMetadata, includeStats: Boolean = true): String? {
        if (!includeStats) return null

        val provider = provider(metadata)
        val codec = metadata.extras?.getString(KEY_CODEC)?.takeIf { it.isNotBlank() }
        val bitrate = metadata.extras?.getInt(KEY_BITRATE_KBPS, -1)?.takeIf { it > 0 }

        if (provider == null && codec == null) return null

        return buildString {
            if (provider != null) {
                append(provider.displayName)
                if (codec != null || bitrate != null) append(" · ")
            }
            if (codec != null) append(codec)
            if (bitrate != null) {
                append(" · ").append(bitrate).append(" kbps")
            }
        }.takeIf { it.isNotBlank() }
    }
}

/** Provider-aware share links (JioSaavn tracks have no YouTube video id). */
object SongLinks {
    fun shareUrl(songId: String, title: String, artist: String): String {
        val bareId = AudioProviderIds.bareMediaId(songId)
        return if (AudioProviderIds.isJioSaavnId(songId)) {
            val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
            "https://www.jiosaavn.com/search/${Uri.encode(query)}"
        } else {
            "https://music.youtube.com/watch?v=$bareId"
        }
    }
}
