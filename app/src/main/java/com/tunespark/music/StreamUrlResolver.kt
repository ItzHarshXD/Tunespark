package com.tunespark.music

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.tunespark.music.provider.AudioProvider
import com.tunespark.music.provider.AudioSourceSelector
import com.tunespark.music.provider.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap

object StreamUrlResolver {
    // Cache for playback tracking URLs mapping videoId -> videostatsPlaybackUrl
    val playbackTrackingCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 100
            }
        }
    )

    // YouTube may block or omit stream URLs for some client profiles. Try several
    // InnerTube clients and use the first playable audio stream we can resolve.
    private val clients = listOf(
        YouTubeClient.ANDROID_VR_1_43_32,
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        YouTubeClient.TVHTML5,
        YouTubeClient.ANDROID_VR_1_61_48,
        YouTubeClient.ANDROID_NO_SDK,
        YouTubeClient.WEB_REMIX,
        YouTubeClient.IOS,
        YouTubeClient.WEB,
        YouTubeClient.MOBILE
    )

    /**
     * Resolves the best source for a song.
     *
     * YouTube Music is always resolved first. When the user has enabled the
     * JioSaavn source and a confident match exists, [AudioSourceSelector]
     * compares the verified renditions and may choose JioSaavn instead.
     *
     * The returned [ResolvedStream] carries the provider, codec and the
     * bitrate of the rendition that will actually play — never a requested
     * quality.
     */
    suspend fun resolveStream(
        songId: String,
        title: String?,
        artist: String?,
        album: String?,
        durationSeconds: Int?,
        jioSaavnEnabled: Boolean,
        forceAlternate: Boolean = false,
        explicit: Boolean = false,
        failedProvider: AudioProvider? = null,
    ): ResolvedStream? = withContext(Dispatchers.IO) {
        AudioSourceSelector.resolve(
            songId = songId,
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSeconds,
            jioSaavnEnabled = jioSaavnEnabled,
            forceAlternate = forceAlternate,
            explicit = explicit,
            failedProvider = failedProvider,
        )
    }

    /**
     * Backwards-compatible YouTube-only entry point used by older call sites.
     * New code should call [resolveStream] so JioSaavn can be considered.
     */
    suspend fun resolveStreamUrl(videoId: String): String? =
        resolveYouTubeStream(videoId)?.url

    /**
     * Resolves a direct YouTube Music stream and reports its real codec and
     * bitrate from the selected InnerTube format.
     */
    suspend fun resolveYouTubeStream(videoId: String): ResolvedStream? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null

        var resolved: ResolvedStream? = null
        for (client in clients) {
            try {
                val result = YouTube.player(videoId = videoId, client = client)
                if (result.isSuccess) {
                    val response = result.getOrNull()
                    val status = response?.playabilityStatus?.status

                    if (status == "OK") {
                        val formats = (response.streamingData?.adaptiveFormats ?: emptyList()) +
                                (response.streamingData?.formats ?: emptyList())

                        // Prefer genuine audio-only formats, but accept any direct
                        // URL as a last resort so playback survives client changes.
                        val audioFormats = formats.filter { it.isAudio && !it.url.isNullOrBlank() }
                        val candidates = audioFormats.ifEmpty {
                            formats.filter { !it.url.isNullOrBlank() }
                        }

                        // Prefer the original (non-auto-dubbed) audio track, then
                        // the highest actual bitrate the client offered.
                        val format = candidates
                            .filter { it.isOriginal }
                            .ifEmpty { candidates }
                            .maxByOrNull { it.averageBitrate ?: it.bitrate }

                        val url = format?.url
                        if (url != null) {
                            resolved = ResolvedStream(
                                url = url,
                                provider = AudioProvider.YOUTUBE_MUSIC,
                                codec = codecOf(format.mimeType),
                                bitrateKbps = ((format.averageBitrate ?: format.bitrate) / 1000)
                                    .takeIf { it > 0 },
                            )
                            val trackingUrl = response.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                            if (trackingUrl != null) {
                                playbackTrackingCache[videoId] = trackingUrl
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        resolved
    }

    /** Maps an InnerTube mime type to a human-readable codec name. */
    internal fun codecOf(mimeType: String): String? {
        val lower = mimeType.lowercase()
        return when {
            lower.contains("opus") -> "Opus"
            lower.contains("mp4a") || lower.contains("aac") || lower.contains("audio/mp4") -> "AAC"
            lower.contains("mp3") || lower.contains("mpeg") -> "MP3"
            lower.contains("vorbis") -> "Vorbis"
            else -> mimeType.substringAfter("audio/", "")
                .substringBefore(";")
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }
}
