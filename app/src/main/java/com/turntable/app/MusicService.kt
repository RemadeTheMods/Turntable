package com.turntable.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

interface PlayerCallback {
    fun onTrackChanged(index: Int)
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onProgress(positionMs: Int, durationMs: Int)
    fun onDurationReady(index: Int, durationMs: Long)
    fun onError(message: String)
    fun onSleepTimerFinished()
}

enum class RepeatMode { OFF, ALL, ONE }

class MusicService : Service() {

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var audioManager: AudioManager? = null

    private var tracks: List<Track> = emptyList()
    private var currentIndex: Int = -1
    private var shuffleOn = false
    private val shuffleBag = mutableListOf<Int>()    // upcoming shuffled order, not yet played this cycle
    private val shuffleHistory = mutableListOf<Int>() // already played this cycle, for "previous"
    private var callback: PlayerCallback? = null
    private var consecutiveErrors = 0
    private var hasAudioFocus = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isPrepared = false
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private var sleepTimerRunnable: Runnable? = null
    private var sleepTimerEndsAtMs: Long = 0L
    private var tracksRemainingForStop: Int = -1 // -1 = inactive

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "TurntableSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { prev() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
            })
            isActive = true
        }

        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    fun setCallback(cb: PlayerCallback?) { callback = cb }

    fun setPlaylist(list: List<Track>) {
        tracks = list
        // The library changed shape (add/remove/hide) — start a fresh shuffle cycle
        // rather than risk stale indices.
        shuffleBag.clear()
        shuffleHistory.clear()
    }

    fun getCurrentIndex() = currentIndex
    fun isPlaying(): Boolean {
        if (!isPrepared) return false
        return try { mediaPlayer?.isPlaying == true } catch (e: Exception) { false }
    }
    fun isShuffleOn() = shuffleOn

    fun setShuffle(on: Boolean) {
        shuffleOn = on
        shuffleBag.clear()
        shuffleHistory.clear()
        LibraryStore.saveShuffleOn(applicationContext, on)
    }

    fun getRepeatMode() = repeatMode

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        LibraryStore.saveRepeatMode(applicationContext, mode)
    }

    /** Cycles Off -> Repeat All -> Repeat One -> Off, like most music apps' repeat button. */
    fun cycleRepeatMode(): RepeatMode {
        val next = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        setRepeatMode(next)
        return next
    }

    // ---------- sleep timer (time-based) ----------

    fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        cancelTrackCountTimer() // only one stop-condition active at a time
        val delayMs = minutes * 60_000L
        sleepTimerEndsAtMs = System.currentTimeMillis() + delayMs
        sleepTimerRunnable = Runnable {
            pause()
            sleepTimerRunnable = null
            sleepTimerEndsAtMs = 0L
            callback?.onSleepTimerFinished()
        }
        handler.postDelayed(sleepTimerRunnable!!, delayMs)
    }

    fun cancelSleepTimer() {
        sleepTimerRunnable?.let { handler.removeCallbacks(it) }
        sleepTimerRunnable = null
        sleepTimerEndsAtMs = 0L
    }

    fun isSleepTimerActive() = sleepTimerRunnable != null

    /** Minutes remaining, rounded up, or 0 if no timer is running. */
    fun getSleepTimerRemainingMinutes(): Int {
        if (sleepTimerRunnable == null) return 0
        val remainingMs = sleepTimerEndsAtMs - System.currentTimeMillis()
        return if (remainingMs <= 0) 0 else ((remainingMs + 59_999) / 60_000).toInt()
    }

    // ---------- sleep timer (track-count based) ----------

    /** Stops playback after [count] more track transitions happen (whether by
     *  natural completion or manual skip). */
    fun startTrackCountTimer(count: Int) {
        cancelSleepTimer()
        cancelTrackCountTimer()
        tracksRemainingForStop = count.coerceAtLeast(1)
    }

    fun cancelTrackCountTimer() {
        tracksRemainingForStop = -1
    }

    fun isTrackCountTimerActive() = tracksRemainingForStop > 0

    /** Tracks remaining before it stops, or 0 if no track-count timer is running. */
    fun getTracksRemainingForStop(): Int = if (tracksRemainingForStop > 0) tracksRemainingForStop else 0

    /** Builds a fresh shuffled order of every visible track, avoiding an immediate repeat. */
    private fun refillShuffleBag() {
        val visible = visibleIndices()
        val bag = visible.shuffled().toMutableList()
        if (bag.size > 1 && bag.first() == currentIndex) {
            val swapWith = (1 until bag.size).random()
            val tmp = bag[0]
            bag[0] = bag[swapWith]
            bag[swapWith] = tmp
        }
        shuffleBag.clear()
        shuffleBag.addAll(bag)
    }

    private fun visibleIndices(): List<Int> =
        tracks.indices.filter { !tracks[it].hidden }

    fun loadAndPlay(index: Int, autoplay: Boolean = true, startPositionMs: Long = 0L) {
        if (index < 0 || index >= tracks.size) return
        currentIndex = index
        val track = tracks[index]

        // Critical: stop the old progress ticker *before* touching the player.
        // Otherwise it keeps firing against whatever `mediaPlayer` now points to
        // (the new, still-preparing instance), and calling getDuration()/
        // getCurrentPosition() on a preparing player doesn't just fail quietly —
        // MediaPlayer's native layer treats it as a real playback error and fires
        // it to the error listener, which was skipping to yet another track and
        // repeating the same mistake — a runaway skip cascade.
        stopProgressUpdates()
        isPrepared = false

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer()
        mediaPlayer?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        try {
            mediaPlayer?.setDataSource(this, Uri.parse(track.uri))
            mediaPlayer?.setOnPreparedListener { mp ->
                isPrepared = true
                consecutiveErrors = 0
                if (startPositionMs > 0) {
                    try { mp.seekTo(startPositionMs.toInt()) } catch (e: Exception) { /* ignore */ }
                }
                callback?.onDurationReady(index, mp.duration.toLong())
                callback?.onProgress(mp.currentPosition, mp.duration)
                updateMetadata(track, mp.duration.toLong())
                if (autoplay) play() else updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                LibraryStore.saveLastPlayback(applicationContext, track.uri, mp.currentPosition.toLong())
            }
            mediaPlayer?.setOnCompletionListener {
                // Deferred via the handler rather than called inline: tearing down
                // and rebuilding the player from within its own completion callback
                // can leave the next MediaPlayer stuck mid-prepare on some devices.
                isPrepared = false
                val finishedIndex = currentIndex
                handler.post {
                    if (repeatMode == RepeatMode.ONE) {
                        loadAndPlay(finishedIndex, autoplay = true)
                    } else {
                        next(isAutoAdvance = true)
                    }
                }
            }
            mediaPlayer?.setOnErrorListener { _, _, _ ->
                isPrepared = false
                callback?.onError("This file could not be played")
                skipPastError()
                true
            }
            mediaPlayer?.prepareAsync()
            callback?.onTrackChanged(index)
        } catch (e: Exception) {
            // The file itself couldn't even be opened (moved, deleted, or a stale
            // permission grant) — don't just stop, move on to the next track.
            callback?.onError("This file could not be opened")
            skipPastError()
        }
    }

    /**
     * Advances past a track that failed to load or play. Guards against an
     * endless loop if every remaining track is broken (e.g. an entire folder
     * lost its permission grant) by giving up after one full pass.
     */
    private fun skipPastError() {
        consecutiveErrors++
        val visibleCount = visibleIndices().size.coerceAtLeast(1)
        if (consecutiveErrors < visibleCount) {
            handler.postDelayed({ next() }, 300)
        } else {
            consecutiveErrors = 0
            callback?.onError("None of these tracks could be played")
        }
    }

    fun play() {
        if (!isPrepared) return // still mid-preparation; onPrepared's autoplay handles this once ready
        val focusGranted = requestAudioFocus()
        if (!focusGranted) return
        try {
            mediaPlayer?.start()
            startForeground(NOTIF_ID, buildNotification(true))
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            callback?.onPlaybackStateChanged(true)
            startProgressUpdates()
        } catch (e: IllegalStateException) {
            // The player somehow ended up in a state where start() isn't valid
            // (can happen after a rapid track change on some devices). Rather
            // than leaving playback stuck with a dead Play button, reload the
            // current track fresh and try again.
            val idx = currentIndex
            if (idx != -1) loadAndPlay(idx, autoplay = true)
        } catch (e: Exception) {
            callback?.onError("Playback failed")
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) { /* not prepared yet */ }
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        callback?.onPlaybackStateChanged(false)
        stopProgressUpdates()
        persistLastPlayback()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(false))
        stopForeground(STOP_FOREGROUND_DETACH_COMPAT)
    }

    fun togglePlay() {
        if (currentIndex == -1) {
            val first = visibleIndices().firstOrNull() ?: return
            loadAndPlay(first)
            return
        }
        if (isPlaying()) pause() else play()
    }

    fun next(isAutoAdvance: Boolean = false) {
        if (tracksRemainingForStop == 0) {
            tracksRemainingForStop = -1
            stopAtEnd()
            callback?.onSleepTimerFinished() // reuses the same "timer ended" notification
            return
        }
        if (tracksRemainingForStop > 0) tracksRemainingForStop--

        val visible = visibleIndices()
        if (visible.isEmpty()) return
        val nextIndex: Int
        if (shuffleOn) {
            if (currentIndex != -1) shuffleHistory.add(currentIndex)
            if (shuffleBag.isEmpty()) {
                if (isAutoAdvance && repeatMode == RepeatMode.OFF) {
                    // Played through the whole shuffled cycle once — stop rather
                    // than silently starting a new shuffle round.
                    stopAtEnd()
                    return
                }
                refillShuffleBag()
            }
            nextIndex = if (shuffleBag.isNotEmpty()) shuffleBag.removeAt(0) else visible.first()
        } else {
            val pos = visible.indexOf(currentIndex)
            if (pos == -1) {
                nextIndex = visible.first()
            } else {
                val isLast = pos == visible.size - 1
                if (isLast && isAutoAdvance && repeatMode == RepeatMode.OFF) {
                    // Reached the end of the list naturally with repeat off —
                    // stop instead of wrapping back to track one.
                    stopAtEnd()
                    return
                }
                nextIndex = visible[(pos + 1) % visible.size]
            }
        }
        loadAndPlay(nextIndex)
    }

    /** Stops playback state cleanly at the end of the list without touching the
     *  (already naturally-completed) MediaPlayer's start/pause, which could
     *  throw depending on its exact native state at that moment. */
    private fun stopAtEnd() {
        isPrepared = false
        stopProgressUpdates()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        callback?.onPlaybackStateChanged(false)
        persistLastPlayback()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(false))
        stopForeground(STOP_FOREGROUND_DETACH_COMPAT)
    }

    fun prev() {
        val visible = visibleIndices()
        if (visible.isEmpty()) return

        if (shuffleOn && shuffleHistory.isNotEmpty()) {
            val prevIndex = shuffleHistory.removeAt(shuffleHistory.size - 1)
            // Put the track we're leaving back at the front of the bag so the
            // shuffle order picks up naturally if the person goes forward again.
            if (currentIndex != -1) shuffleBag.add(0, currentIndex)
            loadAndPlay(prevIndex)
            return
        }
        val currentPos = mediaPlayer?.currentPosition ?: 0
        if (currentPos > 3000 || shuffleOn) {
            seekTo(0)
            return
        }
        val pos = visible.indexOf(currentIndex)
        val prevIndex = if (pos == -1) visible.first() else visible[(pos - 1 + visible.size) % visible.size]
        loadAndPlay(prevIndex)
    }

    fun seekTo(ms: Int) {
        try {
            mediaPlayer?.seekTo(ms)
            updatePlaybackState(if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        } catch (e: Exception) { /* not prepared */ }
    }

    fun setVolume(vol: Float) {
        mediaPlayer?.setVolume(vol, vol)
    }

    fun getCurrentPositionMs(): Int {
        if (!isPrepared) return 0
        return try { mediaPlayer?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    }

    fun getDurationMs(): Int {
        if (!isPrepared) return 0
        return try { mediaPlayer?.duration ?: 0 } catch (e: Exception) { 0 }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Focus is gone for good (another app took over) — pause and
                // require a fresh request next time.
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Temporary interruption (a notification sound, a call, etc).
                // We still technically hold focus, so don't clear hasAudioFocus —
                // no need to re-request when it's handed back.
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
            }
        }
    }

    /**
     * Requests audio focus only if we don't already have it. Re-requesting on
     * every single track change is what was causing MIUI (and possibly other
     * OEM audio stacks) to immediately bounce back a spurious AUDIOFOCUS_LOSS,
     * which our own listener then obediently paused on.
     */
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val am = audioManager ?: return true

        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusListener, handler)
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        return granted
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusListener)
        }
        hasAudioFocus = false
    }

    private fun persistLastPlayback() {
        val track = tracks.getOrNull(currentIndex) ?: return
        LibraryStore.saveLastPlayback(applicationContext, track.uri, getCurrentPositionMs().toLong())
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        var lastSaveAt = System.currentTimeMillis()
        progressRunnable = object : Runnable {
            override fun run() {
                callback?.onProgress(getCurrentPositionMs(), getDurationMs())
                val now = System.currentTimeMillis()
                // Save position roughly every 5s — frequent enough to survive an
                // unexpected kill, cheap enough not to matter for battery/IO.
                if (now - lastSaveAt >= 5000) {
                    lastSaveAt = now
                    persistLastPlayback()
                }
                // Tight interval so the highlighted lyric line changes essentially
                // the instant the song crosses that timestamp, not up to half a
                // second late.
                handler.postDelayed(this, 120)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun updateMetadata(track: Track, durationMs: Long) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, TrackAdapter.displayName(track.name))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Turntable")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updatePlaybackState(state: Int) {
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, getCurrentPositionMs().toLong(), 1f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Turntable playback controls"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(playing: Boolean): android.app.Notification {
        val track = tracks.getOrNull(currentIndex)
        val title = track?.let { TrackAdapter.displayName(it.name) } ?: "Turntable"

        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = servicePendingIntent(ACTION_PREV)
        val playPauseIntent = servicePendingIntent(if (playing) ACTION_PAUSE else ACTION_PLAY)
        val nextIntent = servicePendingIntent(ACTION_NEXT)

        val playPauseIcon = if (playing) R.drawable.ic_pause else R.drawable.ic_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Turntable")
            .setContentIntent(contentPendingIntent)
            .setOngoing(playing)
            .addAction(R.drawable.ic_prev, "Previous", prevIntent)
            .addAction(playPauseIcon, if (playing) "Pause" else "Play", playPauseIntent)
            .addAction(R.drawable.ic_next, "Next", nextIntent)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        persistLastPlayback()
        stopProgressUpdates()
        cancelSleepTimer()
        try { unregisterReceiver(becomingNoisyReceiver) } catch (e: Exception) { }
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession?.isActive = false
        mediaSession?.release()
    }

    companion object {
        const val CHANNEL_ID = "turntable_playback"
        const val NOTIF_ID = 1
        const val ACTION_PLAY = "com.turntable.app.PLAY"
        const val ACTION_PAUSE = "com.turntable.app.PAUSE"
        const val ACTION_NEXT = "com.turntable.app.NEXT"
        const val ACTION_PREV = "com.turntable.app.PREV"

        // Service.STOP_FOREGROUND_DETACH is deprecated on newer APIs but keeps
        // the notification visible after leaving the foreground state, which is
        // what we want when the user pauses (so the notification/controls stay).
        const val STOP_FOREGROUND_DETACH_COMPAT = Service.STOP_FOREGROUND_DETACH
    }
}
