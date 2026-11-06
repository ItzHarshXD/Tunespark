package com.tunespark.music

import android.app.PendingIntent
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
import com.tunespark.music.provider.AudioProvider
import com.tunespark.music.provider.AudioProviderIds
import com.tunespark.music.provider.ResolvedStream
import com.tunespark.music.provider.StreamMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastSeededVideoId: String? = null
    private val seedingVideoIds = mutableSetOf<String>()
    // Stream resolutions currently in flight (videoId set), so a restore and a
    // play-press racing each other can never kick off duplicate network calls.
    private val resolvingVideoIds = mutableSetOf<String>()
    // Song whose alternate provider has already been attempted after a
    // playback error, so recovery can never loop between sources. It is
    // cleared only when playback moves to a different media item.
    private var alternateAttemptedMediaId: String? = null

    // Restore-session state: the service rebuilt the last saved song paused at
    // its saved position. Queue seeding, AI commentary and session bookkeeping
    // are deferred until the user actually presses play.
    private var isRestoredPaused = false
    private var pendingRestoreSeekMs = -1L

    private var playlistSongsPlayedSinceCommentary = 0
    private var lastCommentaryCheckedVideoId: String? = null

    fun resetPlaylistCounter() {
        playlistSongsPlayedSinceCommentary = 0
        lastCommentaryCheckedVideoId = null
    }

    fun clearSeededVideoIds() {
        lastSeededVideoId = null
        seedingVideoIds.clear()
    }

    companion object {
        private var _isPlaylistMode = false
        var isPlaylistMode: Boolean
            get() = _isPlaylistMode
            set(value) {
                _isPlaylistMode = value
                if (value) {
                    instance?.resetPlaylistCounter()
                } else {
                    instance?.clearSeededVideoIds()
                }
            }

        var instance: PlaybackService? = null
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

        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Keep the recovery guard when the same item is swapped for a
                // playable stream (replaceMediaItem reuses the mediaId).
                if (mediaItem?.mediaId != alternateAttemptedMediaId) {
                    alternateAttemptedMediaId = null
                }
                if (mediaItem == null) {
                    // Queue cleared (e.g. the Radio screen's "Stop Radio") —
                    // never resurrect a deliberately stopped session next launch.
                    isRestoredPaused = false
                    pendingRestoreSeekMs = -1L
                    PlaybackStateStore.clear(this@PlaybackService)
                    return
                }
                persistPlaybackState(exoPlayer)
                handleCurrentMediaItem(exoPlayer, mediaItem)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // The user pressed play on a restored (paused) session — now is
                // the moment to run the normal queue-seeding pipeline for the
                // restored song.
                if (playWhenReady && isRestoredPaused) {
                    isRestoredPaused = false
                    pendingRestoreSeekMs = -1L
                    exoPlayer.currentMediaItem?.let { handleCurrentMediaItem(exoPlayer, it) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    exoPlayer.currentMediaItem?.let { handleCurrentMediaItem(exoPlayer, it) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val currentItem = exoPlayer.currentMediaItem
                if (currentItem != null) {
                    if (isUnresolvedMediaItem(currentItem)) {
                        resolveCurrentMediaItem(exoPlayer, currentItem)
                    } else {
                        tryAlternateSourceOrSkip(exoPlayer, currentItem, error)
                    }
                }
            }
        })

        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.tunespark.music.action.SHOW_PLAYER"
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(CustomSessionCallback())
            .setSessionActivity(pendingIntent)
            .build()

        // Resume feature: rebuild the last saved song paused at its saved
        // timestamp before the first controller connects.
        restoreLastPlaybackSession(exoPlayer)

        // Periodically persist the playback snapshot so an unexpected process
        // death while backgrounded (system kill) still resumes at the latest
        // position. Cheap SharedPreferences writes every 5 seconds.
        serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                persistPlaybackState(exoPlayer)
            }
        }
    }

    /**
     * Resume feature: rebuilds the last saved playback snapshot as a paused
     * session at the saved position. The song is queued with the app's standard
     * "tunespark://unresolved/<videoId>" placeholder, so the normal lazy stream
     * resolution pipeline resolves it (and its duration) in the background —
     * the user sees the paused song with its timestamp and pressing play
     * continues exactly where they left off.
     */
    private fun restoreLastPlaybackSession(exoPlayer: ExoPlayer) {
        val saved = PlaybackStateStore.load(this) ?: return
        if (saved.videoId.isBlank()) return

        val restoredItem = MediaItem.Builder()
            .setUri(android.net.Uri.parse(unresolvedMediaUri(saved.videoId)))
            .setMediaId(saved.videoId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(saved.title)
                    .setArtist(saved.artist)
                    .setArtworkUri(
                        if (saved.thumbnail.isNotBlank()) android.net.Uri.parse(saved.thumbnail) else null
                    )
                    .build()
            )
            .build()

        isRestoredPaused = true
        pendingRestoreSeekMs = saved.positionMs

        exoPlayer.setMediaItem(restoredItem, saved.positionMs)
        exoPlayer.playWhenReady = false
        exoPlayer.prepare()
        android.util.Log.d(
            PLAYBACK_SERVICE_TAG,
            "restoreLastPlaybackSession: restored '${saved.title}' paused at ${saved.positionMs}ms"
        )
    }

    /**
     * Persists the current playback snapshot (song metadata + position) so the
     * session can be resumed after the app is closed or the process is killed.
     * AI-commentary interludes are skipped (only real songs are resumable), and
     * an emptied queue clears the snapshot instead.
     */
    private fun persistPlaybackState(player: Player) {
        val item = player.currentMediaItem
        if (item == null) {
            if (player.mediaItemCount == 0) PlaybackStateStore.clear(this)
            return
        }
        if (isCommentaryMediaItem(item)) return
        val videoId = videoIdFromMediaId(item.mediaId)
        if (videoId.isBlank()) return
        val metadata = item.mediaMetadata
        PlaybackStateStore.save(
            context = this,
            videoId = videoId,
            title = metadata.title?.toString() ?: "",
            artist = metadata.artist?.toString() ?: "",
            thumbnail = metadata.artworkUri?.toString() ?: "",
            positionMs = player.currentPosition
        )
    }

    private fun handleCurrentMediaItem(exoPlayer: ExoPlayer, mediaItem: MediaItem) {
        val videoId = videoIdFromMediaId(mediaItem.mediaId)
        if (videoId.isBlank()) return

        if (isCommentaryMediaItem(mediaItem)) {
            preFetchNextMediaItem(exoPlayer)
            return
        }

        // A restored (paused) session must stay quiet: resolve the stream in the
        // background (so the duration/timestamps show and play is instant) but
        // defer commentary, radio seeding and session bookkeeping until the
        // user actually presses play (see onPlayWhenReadyChanged).
        if (isRestoredPaused) {
            if (isUnresolvedMediaItem(mediaItem)) {
                resolveCurrentMediaItem(exoPlayer, mediaItem)
            }
            return
        }

        // Record that a real song has been played in the current session
        CommentaryContextManager.recordSongPlayed(this)

        if (isUnresolvedMediaItem(mediaItem)) {
            resolveCurrentMediaItem(exoPlayer, mediaItem)
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

        if (!SessionManager.isCommentaryEnabled(this)) {
            return
        }

        // Only generate between-songs commentary if at least one automatic between-songs
        // element (Humour, Briefing) is checked.
        val selectedCommentary = SessionManager.getSelectedCommentary(this)
        val hasAutoBetweenSongsElements = "Humour" in selectedCommentary || "Briefing" in selectedCommentary
        if (!hasAutoBetweenSongsElements) {
            return
        }

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
                    val id = videoIdFromMediaId(item.mediaId)
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
                            Toast.makeText(this@PlaybackService, "AI is writing commentary for the playlist...", Toast.LENGTH_SHORT).show()
                        }

                        // Build the centralized daily context for this commentary
                        val contextPrompt = CommentaryContextManager.buildContextPrompt(
                            CommentaryContextManager.getCurrentContext(this@PlaybackService)
                        )

                        // Get the user's selected commentary elements (e.g. Humour), excluding manual Music Context
                        val selectedElements = SessionManager.getSelectedCommentary(this@PlaybackService) - "Music Context"

                        // If the Briefing element is enabled, pick a random scrapable
                        // article from the Discover feed to potentially include.
                        val briefingArticle = if ("Briefing" in selectedElements) {
                            BriefingArticleSelector.selectBriefingArticle(this@PlaybackService)
                        } else null

                        val (audioFile, script) = TtsService.generateCommentaryAudio(
                            context = this@PlaybackService,
                            currentSong = currentSongInfo,
                            upcomingSongs = upcomingSongsList,
                            contextPrompt = contextPrompt,
                            commentaryElements = selectedElements,
                            isSessionOpener = false,
                            briefingArticle = briefingArticle
                        )

                        val commentaryItem = buildCommentaryMediaItem(audioFile, script, briefingArticle)

                        // Record the commentary so the AI has context about what
                        // was already covered today (prevents topic repetition).
                        CommentaryContextManager.recordCommentary(this@PlaybackService, script)

                        withContext(Dispatchers.Main) {
                            if (exoPlayer.currentMediaItemIndex == currentIndex) {
                                exoPlayer.addMediaItem(currentIndex + 1, commentaryItem)
                                playlistSongsPlayedSinceCommentary = 0
                                Toast.makeText(this@PlaybackService, "AI Commentary generated successfully!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(PLAYBACK_SERVICE_TAG, "Failed to create playlist commentary: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PlaybackService, "AI Commentary failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    fun generateAndQueueMusicContextForCurrentSong(lyrics: String) {
        val player = mediaSession?.player ?: return
        val currentItem = player.currentMediaItem ?: return
        val title = currentItem.mediaMetadata.title?.toString() ?: "Unknown Song"
        val artist = currentItem.mediaMetadata.artist?.toString() ?: "Unknown Artist"

        serviceScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlaybackService, "Gathering context for '$title'...", Toast.LENGTH_SHORT).show()
                }

                // 1. Fetch context data concurrently
                val contextData = MusicContextScraper.fetchMusicContext(title, artist)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlaybackService, "AI is generating Music Context script...", Toast.LENGTH_SHORT).show()
                }

                // 2. Generate the commentary audio
                val (audioFile, script) = TtsService.generateCommentaryAudio(
                    context = this@PlaybackService,
                    currentSong = "'$title' by $artist",
                    upcomingSongs = emptyList(),
                    contextPrompt = null,
                    commentaryElements = setOf("Music Context"),
                    isSessionOpener = false,
                    briefingArticle = null,
                    musicContextData = contextData,
                    musicContextLyrics = lyrics.ifBlank { null }
                )

                // 3. Build and insert the commentary item
                val commentaryItem = buildMusicContextMediaItem(audioFile, script)

                withContext(Dispatchers.Main) {
                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex != -1) {
                        player.addMediaItem(currentIndex + 1, commentaryItem)
                        Toast.makeText(this@PlaybackService, "Music Context queued to play next!", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(PLAYBACK_SERVICE_TAG, "generateAndQueueMusicContextForCurrentSong failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlaybackService, "Failed to generate Music Context: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildMusicContextMediaItem(audioFile: java.io.File, script: String): MediaItem {
        val commentaryId = "commentary_${System.currentTimeMillis()}"
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle("Music Context")
            .setArtist("Tunespark Radio")
            .setDescription(script)
            .setArtworkUri(commentaryArtworkUri(this, "music_context"))

        return MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(audioFile))
            .setMediaId(commentaryId)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun isCommentaryMediaItem(mediaItem: MediaItem): Boolean {
        return mediaItem.mediaId.startsWith("commentary_")
    }

    private fun unresolvedMediaUri(videoId: String): String {
        return "$UNRESOLVED_MEDIA_SCHEME://$UNRESOLVED_MEDIA_HOST/$videoId"
    }

    /**
     * Extracts the real YouTube videoId from a mediaId. Queue items that were
     * added multiple times carry a unique "#q<n>" qualifier suffix (Media3
     * requires mediaIds to be unique across the timeline), which is stripped
     * here so playback routing always works with the bare videoId.
     */
    private fun videoIdFromMediaId(mediaId: String): String = mediaId.substringBefore('#')

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

    /**
     * Resolves [mediaItem] through the provider selector and swaps the
     * placeholder for a real playable item. The chosen provider, codec and
     * bitrate are stored in the item's metadata so Now Playing reports the
     * rendition that actually plays.
     */
    private fun resolveCurrentMediaItem(exoPlayer: ExoPlayer, mediaItem: MediaItem) {
        val videoId = videoIdFromMediaId(mediaItem.mediaId)
        if (videoId.isBlank()) return
        // De-dupe concurrent resolutions (a restore and a play-press can race
        // into this method for the same videoId).
        if (!resolvingVideoIds.add(videoId)) return
        serviceScope.launch(Dispatchers.IO) {
            android.util.Log.d(PLAYBACK_SERVICE_TAG, "resolveCurrentMediaItem started for videoId: $videoId")
            val resolved = resolveStreamForItem(mediaItem)
            if (resolved != null) {
                android.util.Log.d(PLAYBACK_SERVICE_TAG, "resolveCurrentMediaItem chose ${resolved.provider.displayName} for videoId: $videoId")
                withContext(Dispatchers.Main) {
                    resolvingVideoIds.remove(videoId)
                    val currentIndex = exoPlayer.currentMediaItemIndex
                    if (currentIndex < exoPlayer.mediaItemCount && videoIdFromMediaId(exoPlayer.getMediaItemAt(currentIndex).mediaId) == videoId) {
                        val originalItem = exoPlayer.getMediaItemAt(currentIndex)
                        val resolvedItem = buildPlayableMediaItem(
                            stream = resolved,
                            metadata = originalItem.mediaMetadata,
                            // Preserve the item's unique mediaId (repeats carry a
                            // "#q<n>" qualifier that must survive the swap).
                            mediaId = originalItem.mediaId
                        )
                        exoPlayer.replaceMediaItem(currentIndex, resolvedItem)
                        exoPlayer.prepare()
                        // Never force playback: a restored session stays paused at
                        // its saved position until the user presses play.
                        if (exoPlayer.playWhenReady) {
                            exoPlayer.play()
                        } else if (pendingRestoreSeekMs > 0) {
                            // Guarantee the saved timestamp survived the item swap.
                            exoPlayer.seekTo(pendingRestoreSeekMs)
                        }
                        pendingRestoreSeekMs = -1L
                        if (isPlaylistMode) {
                            if (!isRestoredPaused) checkAndInsertPlaylistCommentary(exoPlayer, videoId)
                            preFetchNextMediaItem(exoPlayer)
                        } else {
                            if (!isRestoredPaused) seedRecommendations(exoPlayer, videoId)
                            preFetchNextMediaItem(exoPlayer)
                        }
                    }
                }
            } else {
                android.util.Log.e(PLAYBACK_SERVICE_TAG, "resolveCurrentMediaItem failed to resolve URL for videoId: $videoId")
                withContext(Dispatchers.Main) {
                    resolvingVideoIds.remove(videoId)
                    val currentIndex = exoPlayer.currentMediaItemIndex
                    if (currentIndex < exoPlayer.mediaItemCount && videoIdFromMediaId(exoPlayer.getMediaItemAt(currentIndex).mediaId) == videoId) {
                        Toast.makeText(this@PlaybackService, "Failed to resolve stream for this song. Skipping...", Toast.LENGTH_SHORT).show()
                        skipCurrentMediaItem(exoPlayer)
                    }
                }
            }
        }
    }

    /**
     * Resolves the best eligible source for a queue item. Metadata is read
     * from the item itself, so lazy queue resolution can match JioSaavn with
     * the same album/duration information the search result carried.
     */
    private suspend fun resolveStreamForItem(
        mediaItem: MediaItem,
        forceAlternate: Boolean = false,
        failedProvider: AudioProvider? = null,
    ): ResolvedStream? {
        val videoId = videoIdFromMediaId(mediaItem.mediaId)
        if (videoId.isBlank()) return null
        val metadata = mediaItem.mediaMetadata
        return StreamUrlResolver.resolveStream(
            songId = videoId,
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            album = StreamMetadata.album(metadata),
            durationSeconds = StreamMetadata.durationSeconds(metadata),
            jioSaavnEnabled = SessionManager.isJioSaavnEnabled(this),
            forceAlternate = forceAlternate,
            failedProvider = failedProvider,
        )
    }

    /**
     * A resolved stream failed mid-playback. Try the other provider first,
     * resuming at the same position; only skip the song when neither source
     * can play it. Each item is attempted at most once to prevent loops.
     */
    private fun tryAlternateSourceOrSkip(
        exoPlayer: ExoPlayer,
        mediaItem: MediaItem,
        error: PlaybackException,
    ) {
        val mediaId = mediaItem.mediaId
        if (alternateAttemptedMediaId == mediaId) {
            android.util.Log.e(PLAYBACK_SERVICE_TAG, "Playback error on resolved item: $mediaId, error: ${error.message}", error)
            serviceScope.launch(Dispatchers.Main) {
                Toast.makeText(this@PlaybackService, "Playback error, skipping song...", Toast.LENGTH_SHORT).show()
                skipCurrentMediaItem(exoPlayer)
            }
            return
        }
        alternateAttemptedMediaId = mediaId

        val position = exoPlayer.currentPosition
        serviceScope.launch(Dispatchers.IO) {
            val alternate = resolveStreamForItem(
                mediaItem = mediaItem,
                forceAlternate = true,
                failedProvider = StreamMetadata.provider(mediaItem.mediaMetadata),
            )
            withContext(Dispatchers.Main) {
                val currentIndex = exoPlayer.currentMediaItemIndex
                val stillCurrent = currentIndex in 0 until exoPlayer.mediaItemCount &&
                    exoPlayer.getMediaItemAt(currentIndex).mediaId == mediaId
                if (alternate != null && stillCurrent) {
                    val originalItem = exoPlayer.getMediaItemAt(currentIndex)
                    val resolvedItem = buildPlayableMediaItem(
                        stream = alternate,
                        metadata = originalItem.mediaMetadata,
                        mediaId = originalItem.mediaId
                    )
                    exoPlayer.replaceMediaItem(currentIndex, resolvedItem)
                    exoPlayer.prepare()
                    // Preserve the current playback position across the swap.
                    exoPlayer.seekTo(position)
                    exoPlayer.play()
                    android.util.Log.d(PLAYBACK_SERVICE_TAG, "Fell back to ${alternate.provider.displayName} for $mediaId at ${position}ms")
                } else {
                    android.util.Log.e(PLAYBACK_SERVICE_TAG, "Playback error on resolved item: $mediaId, error: ${error.message}", error)
                    Toast.makeText(this@PlaybackService, "Playback error, skipping song...", Toast.LENGTH_SHORT).show()
                    skipCurrentMediaItem(exoPlayer)
                }
            }
        }
    }

    private fun skipCurrentMediaItem(exoPlayer: ExoPlayer) {
        val currentIndex = exoPlayer.currentMediaItemIndex
        if (currentIndex < 0 || currentIndex >= exoPlayer.mediaItemCount) return
        
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.removeMediaItem(currentIndex)
            exoPlayer.prepare()
            exoPlayer.play()
        } else {
            exoPlayer.stop()
            exoPlayer.removeMediaItem(currentIndex)
            Toast.makeText(this, "No more playable songs in queue", Toast.LENGTH_SHORT).show()
        }
    }

    private fun preFetchNextMediaItem(exoPlayer: ExoPlayer) {
        val nextIndex = exoPlayer.currentMediaItemIndex + 1
        if (nextIndex < exoPlayer.mediaItemCount) {
            val nextItem = exoPlayer.getMediaItemAt(nextIndex)
            if (isUnresolvedMediaItem(nextItem)) {
                val nextVideoId = videoIdFromMediaId(nextItem.mediaId)
                serviceScope.launch(Dispatchers.IO) {
                    android.util.Log.d(PLAYBACK_SERVICE_TAG, "preFetchNextMediaItem started for videoId: $nextVideoId")
                    val resolved = resolveStreamForItem(nextItem)
                    if (resolved != null) {
                        android.util.Log.d(PLAYBACK_SERVICE_TAG, "preFetchNextMediaItem pre-fetched ${resolved.provider.displayName} for videoId: $nextVideoId")
                        withContext(Dispatchers.Main) {
                            if (nextIndex < exoPlayer.mediaItemCount && videoIdFromMediaId(exoPlayer.getMediaItemAt(nextIndex).mediaId) == nextVideoId) {
                                val originalItem = exoPlayer.getMediaItemAt(nextIndex)
                                val resolvedItem = buildPlayableMediaItem(
                                    stream = resolved,
                                    metadata = originalItem.mediaMetadata,
                                    // Preserve the item's unique mediaId (repeats
                                    // carry a "#q<n>" qualifier that must survive).
                                    mediaId = originalItem.mediaId
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

        val selectedCommentary = SessionManager.getSelectedCommentary(this)
        val hasAutoBetweenSongsElements = "Humour" in selectedCommentary || "Briefing" in selectedCommentary
        val commentaryEnabled = SessionManager.isCommentaryEnabled(this) && hasAutoBetweenSongsElements
        val N = if (commentaryEnabled) SessionManager.getCommentaryBlockSize(this) else 50

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

        val refillThreshold = if (commentaryEnabled) 2 else MIN_UPCOMING_ITEMS_BEFORE_REFILL
        if (upcomingSongsCount >= refillThreshold) {
            seedingVideoIds.remove(videoId)
            return
        }

        val currentItem = exoPlayer.currentMediaItem
        val artist = currentItem?.mediaMetadata?.artist?.toString() ?: ""
        val title = currentItem?.mediaMetadata?.title?.toString() ?: ""

        serviceScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations started for videoId: $videoId")
                // JioSaavn ids are not YouTube video ids, so asking InnerTube's
                // watch-next endpoint for them would only fail. Use the title +
                // artist fallback search instead, which returns YouTube tracks.
                val nextRecommendations = if (AudioProviderIds.isJioSaavnId(videoId)) {
                    emptyList()
                } else {
                    YouTube.next(WatchEndpoint(videoId = videoId))
                        .onFailure { exception ->
                            android.util.Log.e(PLAYBACK_SERVICE_TAG, "seedRecommendations failed via YouTube.next: ${exception.message}", exception)
                        }
                        .getOrNull()
                        ?.items
                        ?: emptyList()
                }

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
                        .map { index -> videoIdFromMediaId(exoPlayer.getMediaItemAt(index).mediaId) }
                        .toSet()
                }

                val accumulatedSongs = mutableListOf<SongItem>()
                accumulatedSongs.addAll(nextRecommendations.filterIsInstance<SongItem>())

                if (!commentaryEnabled && accumulatedSongs.size < 50) {
                    var attempts = 0
                    while (accumulatedSongs.size < 50 && attempts < 3) {
                        attempts++
                        val lastSongId = accumulatedSongs.lastOrNull()?.id ?: videoId
                        // A JioSaavn id has no YouTube watch-next page; the
                        // title/artist fallback below handles that case.
                        if (AudioProviderIds.isJioSaavnId(lastSongId)) break
                        val extraResult = YouTube.next(WatchEndpoint(videoId = lastSongId))
                        val extraRecs = extraResult.getOrNull()?.items?.filterIsInstance<SongItem>() ?: emptyList()
                        val uniqueExtra = extraRecs.filter { item -> item.id !in accumulatedSongs.map { it.id } && item.id !in existingVideoIds }
                        if (uniqueExtra.isEmpty()) break
                        accumulatedSongs.addAll(uniqueExtra)
                    }
                }

                var filteredRecs = accumulatedSongs
                    .filter { it.id !in existingVideoIds }
                    .take(itemsNeeded)

                if (filteredRecs.isEmpty()) {
                    filteredRecs = fallbackSearch()
                        .filter { it.id !in existingVideoIds }
                        .take(itemsNeeded)
                }

                if (filteredRecs.isNotEmpty()) {
                    val finalSongsToAppend = filteredRecs.take(itemsNeeded)

                    // STEP 1: Add songs to the queue IMMEDIATELY so playback never stops,
                    // even if commentary generation fails or times out later
                    withContext(Dispatchers.Main) {
                        val songMediaItems = finalSongsToAppend.map(::buildUnresolvedMediaItem)
                        exoPlayer.addMediaItems(songMediaItems)
                        lastSeededVideoId = videoId
                        preFetchNextMediaItem(exoPlayer)
                        android.util.Log.d(PLAYBACK_SERVICE_TAG, "seedRecommendations appended ${songMediaItems.size} songs to ExoPlayer")
                    }

                    // STEP 2: Generate commentary (if enabled) and insert it before the songs.
                    // If this fails, songs are already queued — playback continues without commentary.
                    if (commentaryEnabled && !isFirstStart) {
                        val commentaryItem = createCommentaryItem(exoPlayer, finalSongsToAppend)
                        if (commentaryItem != null) {
                            withContext(Dispatchers.Main) {
                                // Find the first appended song and insert commentary before it
                                val firstAppendedId = finalSongsToAppend.first().id
                                for (i in 0 until exoPlayer.mediaItemCount) {
                                    if (exoPlayer.getMediaItemAt(i).mediaId == firstAppendedId) {
                                        exoPlayer.addMediaItem(i, commentaryItem)
                                        android.util.Log.d(PLAYBACK_SERVICE_TAG, "Commentary inserted at position $i before songs")
                                        break
                                    }
                                }
                            }
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
        stream: ResolvedStream,
        metadata: MediaMetadata,
        mediaId: String,
    ): MediaItem {
        return MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(mediaId)
            .setMediaMetadata(StreamMetadata.withStreamInfo(metadata, stream))
            .build()
    }

    private fun buildUnresolvedMediaItem(song: SongItem): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artists.joinToString(", ") { it.name })
            .setArtworkUri(android.net.Uri.parse(song.thumbnail))
            .build()
        return MediaItem.Builder()
            .setUri(unresolvedMediaUri(song.id))
            .setMediaId(song.id)
            .setMediaMetadata(StreamMetadata.withSongInfo(metadata, song.album?.name, song.duration))
            .build()
    }

    private fun buildCommentaryMediaItem(
        audioFile: java.io.File,
        script: String,
        briefingArticle: BriefingArticle? = null
    ): MediaItem {
        val commentaryId = "commentary_${System.currentTimeMillis()}"
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(if (briefingArticle != null) "AI Briefing" else "AI Commentary")
            .setArtist("Tunespark Radio")
            .setDescription(script)

        // If this commentary includes a briefing, store the article metadata
        // in the MediaItem so the Radio screen can show the "Open Article" card.
        if (briefingArticle != null) {
            metadataBuilder
                .setSubtitle(briefingArticle.url)
                .setAlbumTitle(briefingArticle.title)
                .setAlbumArtist(briefingArticle.source)
                .setArtworkUri(commentaryArtworkUri(this, "briefing"))
        }

        return MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(audioFile))
            .setMediaId(commentaryId)
            .setMediaMetadata(metadataBuilder.build())
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
            Toast.makeText(this@PlaybackService, "AI is writing commentary for the next set of songs...", Toast.LENGTH_SHORT).show()
        }

        return try {
            android.util.Log.d(PLAYBACK_SERVICE_TAG, "createCommentaryItem: Generating commentary audio...")

            // Build the centralized daily context for this commentary
            val contextPrompt = CommentaryContextManager.buildContextPrompt(
                CommentaryContextManager.getCurrentContext(this@PlaybackService)
            )

            // Get the user's selected commentary elements (e.g. Humour), excluding manual Music Context
            val selectedElements = SessionManager.getSelectedCommentary(this@PlaybackService) - "Music Context"

            // If the Briefing element is enabled, pick a random scrapable
            // article from the Discover feed to potentially include.
            val briefingArticle = if ("Briefing" in selectedElements) {
                BriefingArticleSelector.selectBriefingArticle(this@PlaybackService)
            } else null

            val (audioFile, script) = TtsService.generateCommentaryAudio(
                context = this@PlaybackService,
                currentSong = currentSongInfo,
                upcomingSongs = upcomingSongsList,
                contextPrompt = contextPrompt,
                commentaryElements = selectedElements,
                isSessionOpener = false,
                briefingArticle = briefingArticle
            )
            android.util.Log.d(PLAYBACK_SERVICE_TAG, "createCommentaryItem: Successfully generated commentary audio: ${audioFile.absolutePath}")

            // Record the commentary so the AI has context about what was
            // already covered today (prevents topic repetition).
            CommentaryContextManager.recordCommentary(this@PlaybackService, script)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlaybackService, "AI Commentary generated successfully!", Toast.LENGTH_SHORT).show()
            }
            buildCommentaryMediaItem(audioFile, script, briefingArticle)
        } catch (e: Exception) {
            android.util.Log.e(PLAYBACK_SERVICE_TAG, "createCommentaryItem failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PlaybackService, "AI Commentary failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        // Snapshot the final paused position before the service tears down, so
        // the next launch resumes this song exactly where the user left off.
        player?.let { persistPlaybackState(it) }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.player?.let { persistPlaybackState(it) }
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        instance = null
        super.onDestroy()
    }
}
