package com.tunespark.music

import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastSeededVideoId: String? = null
    private val seedingVideoIds = mutableSetOf<String>()
    private var isGeneratingCommentary = false

    private var playlistSongsPlayedSinceCommentary = 0
    private var lastCommentaryCheckedVideoId: String? = null

    private var fadePlayer: ExoPlayer? = null
    private var lastCrossfadeTriggeredMediaId: String? = null

    // FIX: Guard flag â€” prevents polling loop re-entry while a crossfade job is active
    @Volatile
    private var isCrossfadeActive = false

    fun resetPlaylistCounter() {
        playlistSongsPlayedSinceCommentary = 0
        lastCommentaryCheckedVideoId = null
    }

    companion object {
        private var _isPlaylistMode = false
        var isPlaylistMode: Boolean
            get() = _isPlaylistMode
            set(value) {
                _isPlaylistMode = value
                if (value) {
                    instance?.resetPlaylistCounter()
                }
            }

        private var instance: PlaybackService? = null
        private const val PLAYBACK_SERVICE_TAG = "PlaybackService"
        const val TARGET_UPCOMING_ITEMS = 20
        const val MIN_UPCOMING_ITEMS_BEFORE_REFILL = 5
        const val UNRESOLVED_MEDIA_SCHEME = "tunespark"
        const val UNRESOLVED_MEDIA_HOST = "unresolved"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
        }

        fadePlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, false)
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                // FIX: Only reset the crossfade trigger lock if we are NOT mid-sequence.
                // This prevents Case 2's internal seekToNextMediaItem() from resetting the lock
                // and causing a re-trigger of the commentary sequence.
                if (!isCrossfadeActive) {
                    lastCrossfadeTriggeredMediaId = null
                }
                // FIX: Don't run seeding side-effects while a commentary sequence is active,
                // as Case 2 calls seekToNextMediaItem internally.
                if (!isCrossfadeActive) {
                    handleCurrentMediaItem(exoPlayer, mediaItem)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !isCrossfadeActive) {
                    exoPlayer.currentMediaItem?.let { handleCurrentMediaItem(exoPlayer, it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentItem = exoPlayer.currentMediaItem
                if (currentItem != null && isUnresolvedMediaItem(currentItem)) {
                    resolveCurrentMediaItem(exoPlayer, currentItem.mediaId)
                }
            }
        })

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(CustomSessionCallback())
            .build()

        // Unified Crossfade Engine Polling Loop
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(50)
                try {
                    val crossfadeEnabled = SessionManager.getCrossfadeEnabled(this@PlaybackService)
                    if (!crossfadeEnabled) {
                        if (exoPlayer.volume != 1.0f) exoPlayer.volume = 1.0f
                        continue
                    }

                    // FIX: Skip the entire polling check while a crossfade job is running.
                    // This is the primary guard against re-entry, double-triggering, and
                    // volume state corruption from concurrent jobs.
                    if (isCrossfadeActive) continue

                    if (exoPlayer.isPlaying) {
                        val currentItem = exoPlayer.currentMediaItem
                        if (currentItem != null && currentItem.mediaId != lastCrossfadeTriggeredMediaId) {
                            val duration = exoPlayer.duration
                            val position = exoPlayer.currentPosition
                            val crossfadeDurationSec = SessionManager.getCrossfadeDuration(this@PlaybackService)
                            val D = crossfadeDurationSec * 1000L

                            if (duration > 0 && (duration - position) <= D) {
                                val nextIndex = exoPlayer.currentMediaItemIndex + 1
                                if (nextIndex < exoPlayer.mediaItemCount) {
                                    val nextItem = exoPlayer.getMediaItemAt(nextIndex)

                                    // Lock BEFORE any async work to prevent race condition
                                    lastCrossfadeTriggeredMediaId = currentItem.mediaId
                                    isCrossfadeActive = true

                                    val isNextCommentary = nextItem.mediaId.startsWith("commentary_")
                                    if (isNextCommentary) {
                                        executeCommentarySequence(exoPlayer, currentItem, nextItem, D)
                                    } else {
                                        if (!isUnresolvedMediaItem(nextItem)) {
                                            executeTrackCrossfade(exoPlayer, currentItem, nextItem, D)
                                        } else {
                                            // Not ready yet â€” release lock and retry next poll
                                            lastCrossfadeTriggeredMediaId = null
                                            isCrossfadeActive = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(PLAYBACK_SERVICE_TAG, "Error in crossfade loop: ${e.message}", e)
                    isCrossfadeActive = false
                }
            }
        }
    }

    private var crossfadeJob: kotlinx.coroutines.Job? = null

    /**
     * CASE 3 â€” Normal Song-to-Song Crossfade.
     *
     * FIX: Corrected operation order. The mainPlayer now seeks to the next song and warms up
     * its audio pipeline FIRST (at volume 0). Only then is song1's tail loaded onto fadePlayer.
     * This eliminates the brief silence gap that was caused by the old order where mainPlayer
     * was cut off while fadePlayer was still buffering.
     */
    private fun executeTrackCrossfade(
        mainPlayer: ExoPlayer,
        song1: MediaItem,
        song2: MediaItem,
        durationMs: Long
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            try {
                val currentPos = mainPlayer.currentPosition

                // Step 1: Immediately jump mainPlayer to Song 2 at volume 0 so its audio
                // pipeline starts warming up. No silence will be heard since volume is 0.
                mainPlayer.seekToNextMediaItem()
                mainPlayer.volume = 0f
                mainPlayer.play()

                // Step 2: Load Song 1's tail onto fadePlayer at the exact position,
                // so it continues seamlessly from where mainPlayer left off.
                fadePlayer?.setMediaItem(song1)
                fadePlayer?.prepare()
                fadePlayer?.seekTo(currentPos)
                fadePlayer?.volume = 1.0f
                fadePlayer?.play()

                // Wait for fadePlayer to be ready before starting the volume sweep
                while (fadePlayer?.playbackState == Player.STATE_BUFFERING) {
                    kotlinx.coroutines.delay(10)
                }

                // Step 3: Parallel linear crossfade
                val totalSteps = durationMs / 50L
                for (i in 0..totalSteps) {
                    val progress = i.toFloat() / totalSteps.toFloat()
                    fadePlayer?.volume = 1.0f - progress
                    mainPlayer.volume = progress
                    kotlinx.coroutines.delay(50)
                }

                fadePlayer?.stop()
                fadePlayer?.clearMediaItems()
                mainPlayer.volume = 1.0f
            } finally {
                // FIX: Always release the active guard in a finally block so that
                // a cancellation mid-animation never permanently locks the engine.
                isCrossfadeActive = false
                mainPlayer.volume = 1.0f
                fadePlayer?.stop()
                fadePlayer?.clearMediaItems()
                // After crossfade completes, run the normal seeding/prefetch logic
                // that was blocked during the fade.
                mainPlayer.currentMediaItem?.let { handleCurrentMediaItem(mainPlayer, it) }
            }
        }
    }

    /**
     * CASE 1 & 2 â€” Commentary Sequence.
     *
     * Commentary always plays on fadePlayer at full, constant volume (1.0f).
     * The mainPlayer (song) is ducked below it.
     *
     * CASE 1 (Short â‰¤ 13s): Music ducks to 0.15 background floor while voice plays.
     * CASE 2 (Long > 13s):  Music fades out fully, next song is advanced silently
     *                        in the background, then fades back in as commentary ends.
     *
     * FIX: getMediaDurationMs is moved to Dispatchers.IO (was blocking main thread).
     * FIX: isShortCommentary threshold raised to 13s and guarded against 0L return.
     * FIX: Duck-in curve corrected to convex recovery (was concave â€” sounded unnatural).
     * FIX: mainPlayer.volume is set BEFORE .play() in Case 2 resume to prevent volume flash.
     * FIX: isCrossfadeActive released in finally block to handle cancellation safely.
     */
    private fun executeCommentarySequence(
        mainPlayer: ExoPlayer,
        currentSong: MediaItem,
        commentaryItem: MediaItem,
        D: Long
    ) {
        crossfadeJob?.cancel()
        crossfadeJob = serviceScope.launch {
            try {
                val commUri = commentaryItem.localConfiguration?.uri?.toString()

                // FIX: Retrieve duration on IO thread â€” MediaMetadataRetriever is blocking I/O
                val commentaryDuration = withContext(Dispatchers.IO) {
                    getMediaDurationMs(commUri)
                }

                // FIX: Guard against getMediaDurationMs returning 0L on failure.
                // If we can't determine duration, default to Case 1 (safe duck behavior).
                // FIX: Raised threshold to 13s to match the diagram's Case 1/2 boundary.
                val isShortCommentary = commentaryDuration <= 0L || commentaryDuration <= 13000L

                val duckOutDuration = 600L   // Time to duck music down (ms)
                val duckInDuration = 1000L   // Time to bring music back up (ms)

                // Commentary always plays at FULL volume on the background player.
                // It never fades â€” the music ducks around it.
                fadePlayer?.setMediaItem(commentaryItem)
                fadePlayer?.prepare()
                fadePlayer?.volume = 1.0f
                fadePlayer?.play()

                // Wait for commentary to be ready before starting duck
                while (fadePlayer?.playbackState == Player.STATE_BUFFERING) {
                    kotlinx.coroutines.delay(10)
                }

                // Track whether we've already advanced the queue in Case 2
                var hasAdvancedQueue = false

                while (fadePlayer?.isPlaying == true) {
                    val commPos = fadePlayer?.currentPosition ?: 0L

                    if (isShortCommentary) {
                        // ================================================
                        // CASE 1: Short Commentary â€” Music ducks to 0.15f
                        // ================================================
                        when {
                            commPos < duckOutDuration -> {
                                // Exponential duck-out: fast at end (0â†’1 â†’ volume 1â†’0.15)
                                val progress = commPos.toFloat() / duckOutDuration.toFloat()
                                mainPlayer.volume = 1.0f - (0.85f * progress.pow(3f))
                            }
                            commPos < (commentaryDuration - duckInDuration) -> {
                                // Hold at background floor
                                mainPlayer.volume = 0.15f
                            }
                            else -> {
                                // FIX: Corrected convex duck-in curve.
                                // Old: 0.15 + (0.85 * progress^3) â€” starts slow, jumps at end (wrong)
                                // New: 0.15 + (0.85 * (1-(1-p)^3)) â€” rises quickly at first, eases in (natural)
                                val remaining = commentaryDuration - commPos
                                val progress = 1.0f - (remaining.toFloat() / duckInDuration.toFloat())
                                mainPlayer.volume = 0.15f + (0.85f * (1f - (1f - progress).pow(3f)))
                            }
                        }
                    } else {
                        // ====================================================================
                        // CASE 2: Long Commentary â€” Music fades fully out, next song advanced
                        // ====================================================================
                        when {
                            commPos < D -> {
                                // Exponential fade-out to silence
                                val progress = commPos.toFloat() / D.toFloat()
                                mainPlayer.volume = (1.0f - progress).pow(3f)
                            }
                            commPos < (commentaryDuration - D) -> {
                                // Silence zone â€” advance the queue behind the curtain once
                                mainPlayer.volume = 0f
                                if (!hasAdvancedQueue && mainPlayer.currentMediaItem?.mediaId == currentSong.mediaId) {
                                    hasAdvancedQueue = true
                                    mainPlayer.seekToNextMediaItem()
                                    // FIX: Set volume to 0 BEFORE pausing to prevent any volume flash
                                    mainPlayer.volume = 0f
                                    mainPlayer.pause()
                                }
                            }
                            else -> {
                                // Fade-in zone: convex swell back to full volume
                                val remaining = commentaryDuration - commPos
                                val progress = 1.0f - (remaining.toFloat() / D.toFloat())
                                val newVol = 1.0f - (1.0f - progress).pow(3f)

                                if (!mainPlayer.isPlaying) {
                                    // FIX: Set volume BEFORE calling play() to prevent a 1-frame full-volume flash
                                    mainPlayer.volume = newVol
                                    mainPlayer.play()
                                } else {
                                    mainPlayer.volume = newVol
                                }
                            }
                        }
                    }

                    kotlinx.coroutines.delay(30)
                }

                // Commentary finished â€” restore music to full volume
                mainPlayer.volume = 1.0f
                if (!mainPlayer.isPlaying) mainPlayer.play()

            } finally {
                // FIX: Always clean up in finally so cancellation mid-sequence
                // never leaves the engine locked or volume in a broken state.
                fadePlayer?.stop()
                fadePlayer?.clearMediaItems()
                mainPlayer.volume = 1.0f
                if (!mainPlayer.isPlaying) mainPlayer.play()
                isCrossfadeActive = false
                // Resume normal seeding/prefetch that was paused during the sequence
                mainPlayer.currentMediaItem?.let { handleCurrentMediaItem(mainPlayer, it) }
            }
        }
    }

    private fun handleCurrentMediaItem(exoPlayer: ExoPlayer, mediaItem: MediaItem) {
        val videoId = mediaItem.mediaId
        if (videoId.isBlank()) return

        if (isCommentaryMediaItem(mediaItem)) {
            preFetchNextMediaItem(exoPlayer)
            return
        }

        if (isUnresolvedMediaItem(mediaItem)) {
            resolveCurrentMediaItem(exoPlayer, videoId)
        } else {
            if (isPlaylistMode) {
                checkAndInsertPlaylistCommentary(exoPlayer, videoId)
                preFetchNextMediaItem(exoPlayer)
            } else {
                seedRecommendations(exoPlayer, videoId)
                preFetchNextMediaItem(exoPlayer)
            }
        }
    }

    private fun checkAndInsertPlaylistCommentary(exoPlayer: ExoPlayer, videoId: String) {
        if (videoId == lastCommentaryCheckedVideoId) return
        lastCommentaryCheckedVideoId = videoId

        val geminiKey = SessionManager.getGeminiApiKey(this)
        if (geminiKey.isBlank()) {
            android.util.Log.w(PLAYBACK_SERVICE_TAG, "checkAndInsertPlaylistCommentary: Gemini API Key is blank! Skipping commentary.")
            return
        }

        val N = SessionManager.getCommentaryBlockSize(this)
        playlistSongsPlayedSinceCommentary++

        if (playlistSongsPlayedSinceCommentary >= N) {
            val currentIndex = exoPlayer.currentMediaItemIndex
            val upcomingSongs = mutableListOf<SongItem>()

            for (i in (currentIndex + 1) until exoPlayer.mediaItemCount) {
                val item = exoPlayer.getMediaItemAt(i)
                if (!isCommentaryMediaItem(item)) {
                    val title = item.mediaMetadata.title?.toString() ?: ""
                    val artistName = item.mediaMetadata.artist?.toString() ?: ""
                    val id = item.mediaId
                    upcomingSongs.add(
                        SongItem(
                            id = id,
                            title = title,
                            artists = listOf(com.metrolist.innertube.models.Artist(artistName, null)),
                            thumbnail = item.mediaMetadata.artworkUri?.toString() ?: ""
                        )
                    )
                    if (upcomingSongs.size >= N) break
                }
            }

            if (upcomingSongs.isNotEmpty()) {
                val currentSongInfo = "'${exoPlayer.mediaMetadata.title}' by ${exoPlayer.mediaMetadata.artist}"
                val upcomingSongsList = upcomingSongs.map { "'${it.title}' by ${it.artists.joinToString(", ") { a -> a.name }}" }

                serviceScope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlaybackService, "AI DJ is writing commentary for the playlist...", Toast.LENGTH_SHORT).show()
                        }

                        val (audioFile, script) = TtsService.generateCommentaryAudio(
                            context = this@PlaybackService,
                            currentSong = currentSongInfo,
                            upcomingSongs = upcomingSongsList
                        )

                        val commentaryItem = buildCommentaryMediaItem(audioFile, script)
                        withContext(Dispatchers.Main) {
                            if (exoPlayer.currentMediaItemIndex == currentIndex) {
                                exoPlayer.addMediaItem(currentIndex + 1, commentaryItem)
                                playlistSongsPlayedSinceCommentary = 0
                                Toast.makeText(this@PlaybackService, "AI DJ Commentary generated successfully!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(PLAYBACK_SERVICE_TAG, "Failed to create playlist commentary: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlaybackService, "AI DJ Commentary failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun isCommentaryMediaItem(mediaItem: MediaItem): Boolean {
        return mediaItem.mediaId.startsWith("commentary_")
    }

    private fun unresolvedMediaUri(videoId: String): String {
        return "$UNRESOLVED_MEDIA_SCHEME://$UNRESOLVED_MEDIA_HOST/$videoId"
    }

    private fun isUnresolvedMediaItem(mediaItem: MediaItem): Boolean {
        val uri = mediaItem.localConfiguration?.uri ?: return true
        return uri.scheme == UNRESOLVED_MEDIA_SCHEME && uri.host == UNRESOLVED_MEDIA_HOST
    }

    private inner class CustomSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            val availableSessionCommands = connectionResult.availableSessionCommands.buildUpon()
            val availablePlayerCommands = connectionResult.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_SET_MEDIA_ITEM)
                .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()

            return MediaSession.ConnectionResult.accept(
                availableSessionCommands.build(),
                availablePlayerCommands
            )
        }
    }

    private fun resolveCurrentMediaItem(exoPlayer: ExoPlayer, videoId: String) {
        serviceScope.launch(Dispatchers.IO) {
            android.util.Log.d(PLAYBACK_SERVICE_TAG, "resolveCurrentMediaItem started for videoId: $videoId")
            val resolvedUrl = StreamUrlResolver.resolveStreamUrl(videoId)
            if (resolvedUrl != null) {
                android.util.Log.d(PLAYBACK_SERVICE_TAG, "resolveCurrentMediaItem successfully resolved URL for videoId: $videoId")
                withContext(Dispatchers.Main) {
                    val currentIndex = exoPlayer.currentMediaItemIndex
                    if (currentIndex < exoPlayer.mediaItemCount && exoPlayer.getMediaItemAt(currentIndex).mediaId == videoId) {
                        val originalItem = exoPlayer.getMediaItemAt(currentIndex)
                        val resolvedItem = buildPlayableMediaItem(
                            videoId = videoId,
                            streamUrl = resolvedUrl,
                            metadata = originalItem.mediaMetadata
                        )
                        exoPlayer.replaceMediaItem(currentIndex, resolvedItem)
                        exoPlayer.prepare()
                        exoPlayer.play()
                        if (isPlaylistMode) {
                            checkAndInsertPlaylistCommentary(exoPlayer, videoId)
                            preFetchNextMediaItem(exoPlayer)
                        } else {
                            seedRecommendations(exoPlayer, videoId)
                            preFetchNextMediaItem(exoPlayer)
                        }
                    }
                }
            } else {
                android.util.Log.e(PLAYBACK_SERVICE_TAG, "resolveCurrentMediaItem failed to resolve URL for videoId: $videoId")
            }
        }
    }

    private fun preFetchNextMediaItem(exoPlayer: ExoPlayer) {
        val nextIndex = exoPlayer.currentMediaItemIndex + 1
        if (nextIndex < exoPlayer.mediaItemCount) {
            val nextItem = exoPlayer.getMediaItemAt(nextIndex)
            if (isUnresolvedMediaItem(nextItem)) {
                val nextVideoId = nextItem.mediaId
                serviceScope.launch(Dispatchers.IO) {
                    android.util.Log.d(PLAYBACK_SERVICE_TAG, "preFetchNextMediaItem started for videoId: $nextVideoId")
                    val resolvedUrl = StreamUrlResolver.resolveStreamUrl(nextVideoId)
                    if (resolvedUrl != null) {
                        android.util.Log.d(PLAYBACK_SERVICE_TAG, "preFetchNextMediaItem successfully pre-fetched URL for videoId: $nextVideoId")
                        withContext(Dispatchers.Main) {
                            if (nextIndex < exoPlayer.mediaItemCount && exoPlayer.getMediaItemAt(nextIndex).mediaId == nextVideoId) {
                                val originalItem = exoPlayer.getMediaItemAt(nextIndex)
                                val resolvedItem = buildPlayableMediaItem(
                                    videoId = nextVideoId,
                                    streamUrl = resolvedUrl,
                                    metadata = originalItem.mediaMetadata
                                )
                                exoPlayer.replaceMediaItem(nextIndex, resolvedItem)
                            }
                        }
                    } else {
                        android.util.Log.e(PLAYBACK_SERVICE_TAG, "preFetchNextMediaItem failed to pre-fetch URL for videoId: $nextVideoId")
                    }
                }
            }
        }
    }

    private fun seedRecommendations(exoPlayer: ExoPlayer, videoId: String) {
        if (videoId == lastSeededVideoId) return
        if (!seedingVideoIds.add(videoId)) return

        val N = SessionManager.getCommentaryBlockSize(this)

        var totalSongsCount = 0
        for (i in 0 until exoPlayer.mediaItemCount) {
            val item = exoPlayer.getMediaItemAt(i)
            if (!isCommentaryMediaItem(item)) totalSongsCount++
        }

        val isFirstStart = totalSongsCount == 1 && N > 1
        val itemsNeeded = if (isFirstStart) N - 1 else N

        val currentIndex = exoPlayer.currentMediaItemIndex
        var upcomingSongsCount = 0
        for (i in (currentIndex + 1) until exoPlayer.mediaItemCount) {
            val item = exoPlayer.getMediaItemAt(i)
            if (!isCommentaryMediaItem(item)) upcomingSongsCount++
        }

        if (upcomingSongsCount >= 2) {
            seedingVideoIds.remove(videoId)
            return
        }

        val currentItem = exoPlayer.currentMediaItem
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: ""
        val title = currentItem?.mediaMetadata?.title?.toString() ?: ""

        serviceScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations started for videoId: $videoId")
                val nextResult = YouTube.next(WatchEndpoint(videoId = videoId))
                val nextRecommendations = nextResult
                    .onFailure { exception ->
                        android.util.Log.e(PLAYBACK_SERVICE_TAG, "seedRecommendations failed via YouTube.next: ${exception.message}", exception)
                    }
                    .getOrNull()
                    ?.items
                    ?: emptyList()

                android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations retrieved ${nextRecommendations.size} recommendations from YouTube.next")

                val fallbackSearch: suspend () -> List<SongItem> = {
                    val searchQuery = listOf(title, artist)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { artist.ifBlank { title } }

                    if (searchQuery.isBlank()) {
                        android.util.Log.w(PLAYBACK_SERVICE_TAG, "seedRecommendations has no title or artist for fallback search")
                        emptyList()
                    } else {
                        android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations falling back to search query: $searchQuery")
                        val searchResult = YouTube.search(query = searchQuery, filter = YouTube.SearchFilter.FILTER_SONG)
                        if (searchResult.isSuccess) {
                            val items = searchResult.getOrNull()?.items ?: emptyList()
                            items.filterIsInstance<SongItem>()
                                .also {
                                    android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations fallback search retrieved ${it.size} songs")
                                }
                        } else {
                            val searchEx = searchResult.exceptionOrNull()
                            android.util.Log.e(PLAYBACK_SERVICE_TAG, "seedRecommendations fallback search failed: ${searchEx?.message}", searchEx)
                            emptyList()
                        }
                    }
                }

                val existingVideoIds = withContext(Dispatchers.Main) {
                    (0 until exoPlayer.mediaItemCount)
                        .map { index -> exoPlayer.getMediaItemAt(index).mediaId }
                        .toSet()
                }

                var filteredRecs = nextRecommendations
                    .filter { it.id !in existingVideoIds }
                    .take(itemsNeeded)

                if (filteredRecs.isEmpty()) {
                    filteredRecs = fallbackSearch()
                        .filter { it.id !in existingVideoIds }
                        .take(itemsNeeded)
                }

                if (filteredRecs.isNotEmpty()) {
                    val finalSongsToAppend = filteredRecs.take(itemsNeeded)
                    val commentaryItem = if (isFirstStart) null else createCommentaryItem(exoPlayer, finalSongsToAppend)

                    withContext(Dispatchers.Main) {
                        if (exoPlayer.currentMediaItem?.mediaId == videoId) {
                            val mediaItems = mutableListOf<MediaItem>()
                            if (commentaryItem != null) {
                                mediaItems.add(commentaryItem)
                            }
                            mediaItems.addAll(finalSongsToAppend.map(::buildUnresolvedMediaItem))
                            exoPlayer.addMediaItems(mediaItems)
                            lastSeededVideoId = videoId
                            preFetchNextMediaItem(exoPlayer)
                            android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations successfully appended ${mediaItems.size} items to ExoPlayer")
                        }
                    }
                } else {
                    android.util.Log.w(PLAYBACK_SERVICE_TAG, "seedRecommendations got empty recommendations list, nothing to queue.")
                }
            } catch (e: Exception) {
                android.util.Log.e(PLAYBACK_SERVICE_TAG, "seedRecommendations unexpected error: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main) {
                    seedingVideoIds.remove(videoId)
                }
            }
        }
    }

    private fun buildPlayableMediaItem(
        videoId: String,
        streamUrl: String,
        metadata: MediaMetadata
    ): MediaItem {
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(videoId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun buildUnresolvedMediaItem(song: SongItem): MediaItem {
        return MediaItem.Builder()
            .setUri(unresolvedMediaUri(song.id))
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artists.joinToString(", ") { it.name })
                    .setArtworkUri(android.net.Uri.parse(song.thumbnail))
                    .build()
            )
            .build()
    }

    private fun buildCommentaryMediaItem(audioFile: java.io.File, script: String): MediaItem {
        val commentaryId = "commentary_${System.currentTimeMillis()}"
        return MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(audioFile))
            .setMediaId(commentaryId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("AI DJ Commentary")
                    .setArtist("TuneSpark AI DJ")
                    .setDescription(script)
                    .build()
            )
            .build()
    }

    private suspend fun createCommentaryItem(exoPlayer: ExoPlayer, upcomingSongItems: List<SongItem>): MediaItem? {
        val geminiKey = SessionManager.getGeminiApiKey(this@PlaybackService)
        if (geminiKey.isBlank()) {
            android.util.Log.w(PLAYBACK_SERVICE_TAG, "createCommentaryItem: Gemini API Key is blank! Skipping commentary.")
            return null
        }

        val upcomingSongsList = upcomingSongItems.map { "'${it.title}' by ${it.artists.joinToString(", ") { a -> a.name }}" }

        val currentSongInfo = withContext(Dispatchers.Main) {
            val item = exoPlayer.currentMediaItem
            if (item != null) {
                "'${item.mediaMetadata.title}' by ${item.mediaMetadata.artist}"
            } else null
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(this@PlaybackService, "AI DJ is writing commentary for the next set of songs...", Toast.LENGTH_SHORT).show()
        }

        return try {
            android.util.Log.d(PLAYBACK_SERVICE_TAG, "createCommentaryItem: Generating commentary audio...")
            val (audioFile, script) = TtsService.generateCommentaryAudio(
                context = this@PlaybackService,
                currentSong = currentSongInfo,
                upcomingSongs = upcomingSongsList
            )
            android.util.Log.d(PLAYBACK_SERVICE_TAG, "createCommentaryItem: Successfully generated commentary audio: ${audioFile.absolutePath}")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlaybackService, "AI DJ Commentary generated successfully!", Toast.LENGTH_SHORT).show()
            }
            buildCommentaryMediaItem(audioFile, script)
        } catch (e: Exception) {
            android.util.Log.e(PLAYBACK_SERVICE_TAG, "createCommentaryItem failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlaybackService, "AI DJ Commentary failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        player?.pause()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // FIX: This must only ever be called from Dispatchers.IO â€” it performs blocking I/O.
    private fun getMediaDurationMs(uriString: String?): Long {
        if (uriString == null) return 0L
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            if (uriString.startsWith("file://")) {
                retriever.setDataSource(uriString.substring(7))
            } else {
                retriever.setDataSource(uriString, HashMap())
            }
            val time = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            android.util.Log.e(PLAYBACK_SERVICE_TAG, "Error retrieving media duration: ${e.message}", e)
            0L
        } finally {
            try { retriever.release() } catch (ex: Exception) {}
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        fadePlayer?.release()
        fadePlayer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }
}