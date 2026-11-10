package com.tunespark.music.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.tunespark.music.PlaybackService
import com.tunespark.music.SessionManager
import com.tunespark.music.StreamUrlResolver
import com.tunespark.music.provider.ResolvedStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * One Cast-capable device shown in the in-app picker.
 */
data class CastDevice(
    val id: String,
    val name: String,
    val deviceType: Int,
    val isSelected: Boolean,
)

/**
 * App-wide Google Cast controller.
 *
 * Playback design: the local ExoPlayer remains the single source of truth for
 * the UI, queue, notification and Android Auto. While casting, the phone keeps
 * advancing the same queue **muted**, and this controller mirrors every
 * transport change onto the receiver's Default Media Receiver:
 *
 *  - each new track is resolved to a direct stream URL and loaded on the device
 *    (with artwork, title and artist metadata) at the current position,
 *  - play / pause / seek / skip are forwarded as they happen,
 *  - disconnecting simply un-mutes the phone, which has stayed at the receiver's
 *    position the whole time.
 *
 * This keeps every existing playback path untouched while giving a normal
 * "cast from the Radio screen" experience.
 */
object CastManager {

    // ---- Observable state (consumed by the Radio screen / dialog) ----------

    /** A Cast session is connected to a device. */
    var isCasting by mutableStateOf(false)
        private set

    /** A device is currently being connected to. */
    var isConnecting by mutableStateOf(false)
        private set

    /** Id of the route currently being connected to (shown as "Connecting…"). */
    var connectingDeviceId by mutableStateOf<String?>(null)
        private set

    /** Friendly name of the connected device, when casting. */
    var connectedDeviceName by mutableStateOf<String?>(null)
        private set

    /** Devices discovered on the local network for the picker. */
    var devices by mutableStateOf<List<CastDevice>>(emptyList())
        private set

    /** The receiver is currently playing. */
    var castIsPlaying by mutableStateOf(false)
        private set

    /**
     * True once the receiver has reported a playable state (playing or paused)
     * for the loaded track. Used to ignore the empty/initial cast status until
     * the first track is actually on the device.
     */
    var remotePlaybackStateKnown by mutableStateOf(false)
        private set

    /** True once this session has sent a track to the receiver. */
    var remoteTrackLoaded by mutableStateOf(false)
        private set

    /** Cast SDK initialised successfully (Play Services available). */
    var isAvailable by mutableStateOf(false)
        private set

    // ---- Internals ---------------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var discoveryCallback: MediaRouter.Callback? = null
    private var remoteClient: RemoteMediaClient? = null
    private var loadJob: Job? = null

    /**
     * Supplies the phone player's play intent. Set by MainActivity so the
     * intent can be snapshotted *before* the Cast session starts and route
     * hand-off has a chance to transiently pause local playback.
     */
    var playWhenReadyProvider: (() -> Boolean)? = null

    /** Play intent captured when the user picks a device. */
    var connectPlayIntent: Boolean? = null
        private set

    /** Autoplay to use for the first load after connecting. */
    private var pendingAutoplay: Boolean? = null

    /** Short window in which a receiver that ignored autoplay is resumed. */
    private var remoteAutoResumeUntil = 0L

    /**
     * Called when the receiver's position changed because of a device-side
     * control (TV remote, Google Home, Nest Hub, …). The phone player should
     * seek to the reported position so the in-app UI stays in sync.
     */
    var onRemoteSeek: ((Long) -> Unit)? = null

    /** Track currently loaded on the receiver (video id), used to skip duplicates. */
    private var loadedVideoId: String? = null

    // Position tracking used to distinguish receiver-side seeks (TV remote)
    // from the normal passage of time and from our own seek/load commands.
    private var lastRemotePositionMs = -1L
    private var lastRemotePositionAt = 0L
    private var lastRemoteWasPlaying = false
    private var remoteSeekGuardUntil = 0L

    private const val REMOTE_SEEK_TOLERANCE_MS = 4_000L
    private const val REMOTE_SEEK_GUARD_MS = 2_500L
    private const val REMOTE_AUTO_RESUME_WINDOW_MS = 6_000L

    fun initialize(context: Context) {
        if (castContext != null) return
        val app = context.applicationContext
        appContext = app
        try {
            val ctx = CastContext.getSharedInstance(app)
            castContext = ctx
            ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            isAvailable = true
            ctx.sessionManager.currentCastSession?.let { onCastConnected(it, alreadyLoaded = true) }
        } catch (t: Throwable) {
            t.printStackTrace()
            isAvailable = false
        }
    }

    // ---- Device discovery --------------------------------------------------

    fun startDiscovery(context: Context) {
        initialize(context)
        val ctx = castContext ?: return
        val selector = ctx.mergedSelector ?: return
        val router = mediaRouter ?: MediaRouter.getInstance(context.applicationContext).also {
            mediaRouter = it
        }
        if (discoveryCallback == null) {
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    publishRoutes()
                }

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    publishRoutes()
                }

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    publishRoutes()
                }
            }
            discoveryCallback = callback
            try {
                router.addCallback(
                    selector,
                    callback,
                    MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        publishRoutes()
    }

    fun stopDiscovery() {
        discoveryCallback?.let { callback ->
            try {
                mediaRouter?.removeCallback(callback)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        discoveryCallback = null
        devices = emptyList()
    }

    private fun publishRoutes() {
        val ctx = castContext ?: return
        val router = mediaRouter ?: return
        val selector = ctx.mergedSelector ?: return
        devices = try {
            router.routes
                .asSequence()
                .filter { !it.isDefault }
                .filter { it.playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE }
                .filter { it.matchesSelector(selector) && it.isEnabled }
                .map {
                    CastDevice(
                        id = it.id,
                        name = it.name?.toString().orEmpty(),
                        deviceType = it.deviceType,
                        isSelected = it.isSelected,
                    )
                }
                .distinctBy { it.id }
                .toList()
        } catch (t: Throwable) {
            t.printStackTrace()
            emptyList()
        }
    }

    // ---- Connecting / disconnecting ---------------------------------------

    fun connect(deviceId: String) {
        val router = mediaRouter ?: return
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        isConnecting = true
        connectingDeviceId = deviceId
        // Snapshot the play intent before starting the session: selecting a
        // Cast route can transiently pause the local player, which would
        // otherwise be mistaken for "the user wants the cast paused".
        connectPlayIntent = playWhenReadyProvider?.invoke()
        pendingAutoplay = connectPlayIntent
        try {
            // Selecting the Cast route lets the Cast SDK start the session with
            // the Default Media Receiver configured in [CastOptionsProvider].
            route.select()
        } catch (t: Throwable) {
            t.printStackTrace()
            isConnecting = false
            connectingDeviceId = null
        }
    }

    fun disconnect() {
        val ctx = castContext
        try {
            ctx?.sessionManager?.endCurrentSession(true)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        onCastDisconnected()
    }

    // ---- Session handling --------------------------------------------------

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            onCastConnected(session)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            onCastConnected(session, alreadyLoaded = true)
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            onCastDisconnected()
        }

        override fun onSessionStarting(session: CastSession) {
            isConnecting = true
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            isConnecting = false
            connectingDeviceId = null
            isCasting = false
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            isConnecting = true
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            isConnecting = false
        }
    }

    private fun onCastConnected(session: CastSession, alreadyLoaded: Boolean = false) {
        remoteClient?.unregisterCallback(remoteCallback)
        remoteClient = session.remoteMediaClient
        remoteClient?.registerCallback(remoteCallback)
        connectedDeviceName = session.castDevice?.friendlyName ?: "Cast device"
        isCasting = true
        isConnecting = false
        connectingDeviceId = null
        val state = remoteClient?.mediaStatus?.playerState
        castIsPlaying = state == MediaStatus.PLAYER_STATE_PLAYING
        remotePlaybackStateKnown = state == MediaStatus.PLAYER_STATE_PLAYING ||
            state == MediaStatus.PLAYER_STATE_PAUSED
        remoteTrackLoaded = alreadyLoaded && remoteClient?.mediaStatus?.mediaInfo != null
        lastRemotePositionMs = -1L
        lastRemoteWasPlaying = false
        remoteSeekGuardUntil = 0L
        remoteAutoResumeUntil = 0L
        publishRoutes()
    }

    private fun onCastDisconnected() {
        remoteClient?.unregisterCallback(remoteCallback)
        remoteClient = null
        isCasting = false
        isConnecting = false
        connectingDeviceId = null
        connectedDeviceName = null
        castIsPlaying = false
        remotePlaybackStateKnown = false
        remoteTrackLoaded = false
        loadedVideoId = null
        connectPlayIntent = null
        pendingAutoplay = null
        remoteAutoResumeUntil = 0L
        lastRemotePositionMs = -1L
        lastRemoteWasPlaying = false
        loadJob?.cancel()
        loadJob = null
        // Safety net: restore phone audio even if the screen is no longer
        // composed when the session ends.
        try {
            PlaybackService.instance?.setCastingMute(false)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = remoteClient?.mediaStatus ?: return
            val playerState = status.playerState
            val playing = playerState == MediaStatus.PLAYER_STATE_PLAYING
            // Only real transport states drive the phone player. Buffering and
            // idle are transitional during a load and must not flip play/pause.
            when (playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> {
                    castIsPlaying = true
                    remotePlaybackStateKnown = true
                }
                MediaStatus.PLAYER_STATE_PAUSED -> {
                    castIsPlaying = false
                    remotePlaybackStateKnown = true
                }
            }

            val now = SystemClock.elapsedRealtime()

            // If the receiver ignored autoplay during the short window after a
            // load, resume it automatically instead of making the user tap
            // play a second time.
            if (remoteAutoResumeUntil > 0) {
                when (playerState) {
                    MediaStatus.PLAYER_STATE_PLAYING -> remoteAutoResumeUntil = 0L
                    MediaStatus.PLAYER_STATE_PAUSED -> {
                        remoteAutoResumeUntil = 0L
                        castIsPlaying = true
                        try {
                            remoteClient?.play()
                        } catch (t: Throwable) {
                            t.printStackTrace()
                        }
                    }
                }
                if (now > remoteAutoResumeUntil) remoteAutoResumeUntil = 0L
            }

            // Detect a receiver-side seek (TV remote / Google Home) so the phone
            // player can follow. Our own seek/load commands open a short guard
            // window, and paused periods don't project a position, so neither
            // creates a false positive here.
            val position = status.streamPosition.coerceAtLeast(0L)
            if (
                playing &&
                lastRemoteWasPlaying &&
                lastRemotePositionMs >= 0 &&
                now > remoteSeekGuardUntil
            ) {
                val projected = lastRemotePositionMs + (now - lastRemotePositionAt)
                if (abs(position - projected) > REMOTE_SEEK_TOLERANCE_MS) {
                    remoteSeekGuardUntil = now + REMOTE_SEEK_GUARD_MS
                    onRemoteSeek?.invoke(position)
                }
            }
            lastRemotePositionMs = position
            lastRemotePositionAt = now
            lastRemoteWasPlaying = playing
        }
    }

    // ---- Remote playback control ------------------------------------------

    /**
     * Resolves [videoId] to a direct stream and loads it on the receiver.
     * Called for the current track when casting starts and again on every
     * queue transition while connected.
     */
    fun castTrack(
        videoId: String,
        title: String,
        artist: String,
        artwork: String?,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        val context = appContext ?: return
        val client = remoteClient ?: return
        if (videoId.isBlank()) return

        // Never send AI commentary to the receiver; the next real song will be
        // pushed automatically when the queue advances.
        if (videoId.startsWith("commentary_")) return

        if (loadedVideoId == videoId) {
            // Same track — just restore transport state (e.g. after a session
            // resume or a play/pause change that raced the load).
            if (loadJob?.isActive == true) return
            if (client.mediaStatus == null) {
                // No media on the receiver yet; fall through and load it.
            } else {
                val shouldPlay = pendingAutoplay ?: playWhenReady
                if (shouldPlay) client.play() else client.pause()
                return
            }
        }

        loadedVideoId = videoId
        val autoplay = pendingAutoplay ?: playWhenReady
        pendingAutoplay = null
        loadJob?.cancel()
        loadJob = scope.launch {
            val stream = try {
                withContext(Dispatchers.IO) {
                    StreamUrlResolver.resolveStream(
                        songId = videoId,
                        title = title.ifBlank { null },
                        artist = artist.ifBlank { null },
                        album = null,
                        durationSeconds = null,
                        jioSaavnEnabled = SessionManager.isJioSaavnEnabled(context),
                    )
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                null
            }

            if (stream == null) {
                loadedVideoId = null
                Toast.makeText(context, "Couldn't cast this song", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // The session may have ended while the stream was resolving.
            if (!isCasting || remoteClient == null) return@launch

            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                putString(MediaMetadata.KEY_ARTIST, artist)
                if (!artwork.isNullOrBlank()) {
                    try {
                        addImage(WebImage(Uri.parse(artwork)))
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }

            val mediaInfo = MediaInfo.Builder(stream.url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentTypeFor(stream))
                .setMetadata(metadata)
                .build()

            val request = MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(autoplay)
                .setCurrentTime(positionMs.coerceAtLeast(0L))
                .build()

            try {
                val now = SystemClock.elapsedRealtime()
                remoteSeekGuardUntil = now + REMOTE_SEEK_GUARD_MS
                remoteAutoResumeUntil = if (autoplay) now + REMOTE_AUTO_RESUME_WINDOW_MS else 0L
                // Let the first status update after a load establish the
                // baseline; it must never be interpreted as a device-side seek.
                lastRemotePositionMs = -1L
                lastRemoteWasPlaying = false
                remoteTrackLoaded = true
                remoteClient?.load(request)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    fun setRemotePlayback(playing: Boolean) {
        val client = remoteClient ?: return
        val state = client.mediaStatus?.playerState
        if (state == null) return
        // An explicit pause is user intent and must win over the one-shot
        // startup auto-resume guard.
        if (!playing) remoteAutoResumeUntil = 0L
        val alreadyInState = when {
            playing -> state == MediaStatus.PLAYER_STATE_PLAYING ||
                state == MediaStatus.PLAYER_STATE_BUFFERING
            else -> state == MediaStatus.PLAYER_STATE_PAUSED ||
                state == MediaStatus.PLAYER_STATE_IDLE
        }
        if (alreadyInState) return
        try {
            if (playing) client.play() else client.pause()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun seekTo(positionMs: Long) {
        val client = remoteClient ?: return
        try {
            val target = positionMs.coerceAtLeast(0L)
            val now = SystemClock.elapsedRealtime()
            remoteSeekGuardUntil = now + REMOTE_SEEK_GUARD_MS
            lastRemotePositionMs = target
            lastRemotePositionAt = now
            client.seek(target)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun contentTypeFor(stream: ResolvedStream): String {
        val decodedUrl = Uri.decode(stream.url).lowercase()
        return when {
            decodedUrl.contains("mime=audio/mpeg") -> "audio/mpeg"
            decodedUrl.contains("mime=audio/webm") -> "audio/webm"
            decodedUrl.contains("mime=audio/mp4") -> "audio/mp4"
            decodedUrl.contains("mime=audio/ogg") -> "audio/ogg"
            decodedUrl.contains(".mp3") -> "audio/mpeg"
            decodedUrl.contains(".webm") || decodedUrl.contains(".opus") -> "audio/webm"
            decodedUrl.contains(".ogg") -> "audio/ogg"
            decodedUrl.contains(".m4a") || decodedUrl.contains(".mp4") -> "audio/mp4"
            stream.codec?.equals("MP3", ignoreCase = true) == true -> "audio/mpeg"
            stream.codec?.equals("Opus", ignoreCase = true) == true -> "audio/webm"
            stream.codec?.equals("Vorbis", ignoreCase = true) == true -> "audio/webm"
            else -> "audio/mp4"
        }
    }
}
