package com.tunespark.music

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StreamUrlResolver {
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

    suspend fun resolveStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        var fetchedUrl: String? = null
        for (client in clients) {
            try {
                val result = YouTube.player(videoId = videoId, client = client)
                if (result.isSuccess) {
                    val response = result.getOrNull()
                    val status = response?.playabilityStatus?.status

                    if (status == "OK") {
                        val formats = (response.streamingData?.adaptiveFormats ?: emptyList()) +
                                (response.streamingData?.formats ?: emptyList())
                        // Prefer audio-only formats, but accept any direct URL as a
                        // last resort so playback can survive client response changes.
                        val audioFormat = formats.firstOrNull { it.isAudio && !it.url.isNullOrBlank() }
                            ?: formats.firstOrNull { it.mimeType.startsWith("audio/") && !it.url.isNullOrBlank() }
                            ?: formats.firstOrNull { !it.url.isNullOrBlank() }

                        if (audioFormat?.url != null) {
                            fetchedUrl = audioFormat.url
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fetchedUrl
    }
}
