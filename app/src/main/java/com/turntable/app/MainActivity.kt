package com.turntable.app

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.turntable.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), PlayerCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackAdapter

    private var musicService: MusicService? = null
    private var bound = false
    private var isSeeking = false

    private val tracks = mutableListOf<Track>()

    private var recordAnimator: ObjectAnimator? = null
    private var labelAnimator: ObjectAnimator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as MusicService.LocalBinder
            musicService = localBinder.getService()
            bound = true
            musicService?.setCallback(this@MainActivity)
            musicService?.setPlaylist(tracks)

            val existingIndex = musicService?.getCurrentIndex() ?: -1
            if (existingIndex == -1 && tracks.isNotEmpty()) {
                // Fresh service instance — restore shuffle mode and resume exactly
                // where playback left off, the way Spotify does.
                val savedShuffle = LibraryStore.loadShuffleOn(this@MainActivity)
                musicService?.setShuffle(savedShuffle)
                binding.shuffleBtn.isSelected = savedShuffle

                val savedPlayback = LibraryStore.loadLastPlayback(this@MainActivity)
                val targetIndex = savedPlayback?.let { (uri, _) ->
                    tracks.indexOfFirst { it.uri == uri && !it.hidden }
                } ?: -1

                val startIndex = if (targetIndex != -1) targetIndex
                    else tracks.indices.firstOrNull { !tracks[it].hidden }

                if (startIndex != null) {
                    val startPos = if (targetIndex != -1) savedPlayback?.second ?: 0L else 0L
                    musicService?.loadAndPlay(startIndex, autoplay = false, startPositionMs = startPos)
                }
            } else {
                binding.shuffleBtn.isSelected = musicService?.isShuffleOn() ?: false
                updateNowPlayingUi()
            }
            refreshList()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            musicService = null
        }
    }

    private val openDocsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) handlePickedUris(uris) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way; notification just won't show controls if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tracks.addAll(LibraryStore.load(this))

        setupRecordAnimator()
        setupList()
        setupControls()
        refreshList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val serviceIntent = Intent(this, MusicService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            musicService?.setCallback(null)
            unbindService(connection)
            bound = false
        }
    }

    // ---------- setup ----------

    private fun setupRecordAnimator() {
        recordAnimator = ObjectAnimator.ofFloat(binding.record, View.ROTATION, 0f, 360f).apply {
            duration = 6000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        // The number spins right along with the disc — since a plain ringed
        // circle looks identical at every angle, the rotating digits are what
        // actually makes the spin visible (same trick a real record label uses).
        labelAnimator = ObjectAnimator.ofFloat(binding.recordInitial, View.ROTATION, 0f, 360f).apply {
            duration = 6000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun setupList() {
        adapter = TrackAdapter(
            onPlay = { index ->
                if (musicService?.getCurrentIndex() == index) musicService?.togglePlay()
                else musicService?.loadAndPlay(index)
            },
            onToggleHide = { index -> toggleHidden(index) },
            onRemove = { index -> removeTrack(index) }
        )
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = adapter
    }

    private fun setupControls() {
        binding.addBtn.setOnClickListener { openDocsLauncher.launch(arrayOf("audio/*")) }
        binding.emptyState.setOnClickListener { openDocsLauncher.launch(arrayOf("audio/*")) }

        binding.playBtn.setOnClickListener { musicService?.togglePlay() }
        binding.nextBtn.setOnClickListener { musicService?.next() }
        binding.prevBtn.setOnClickListener { musicService?.prev() }

        binding.shuffleBtn.setOnClickListener {
            val newState = !(musicService?.isShuffleOn() ?: false)
            musicService?.setShuffle(newState)
            binding.shuffleBtn.isSelected = newState
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = musicService?.getDurationMs() ?: 0
                    val pos = (progress / 1000f * duration).toLong()
                    binding.timeCurrent.text = TrackAdapter.formatTime(pos)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val duration = musicService?.getDurationMs() ?: 0
                val pos = ((seekBar?.progress ?: 0) / 1000f * duration).toInt()
                musicService?.seekTo(pos)
            }
        })

        binding.volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) musicService?.setVolume(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------- adding / removing / hiding ----------

    private fun handlePickedUris(uris: List<Uri>) {
        var added = false
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { /* some providers don't support persistable grants; still usable this session */ }

            val uriString = uri.toString()
            if (tracks.any { it.uri == uriString }) continue

            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Unknown track"
            tracks.add(Track(uri = uriString, name = name))
            added = true
        }
        if (!added) return

        LibraryStore.save(this, tracks)
        musicService?.setPlaylist(tracks)

        if ((musicService?.getCurrentIndex() ?: -1) == -1) {
            val firstVisible = tracks.indices.firstOrNull { !tracks[it].hidden }
            if (firstVisible != null) musicService?.loadAndPlay(firstVisible, autoplay = false)
        }
        refreshList()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    private fun toggleHidden(index: Int) {
        if (index !in tracks.indices) return
        val track = tracks[index]
        track.hidden = !track.hidden
        musicService?.setPlaylist(tracks)

        if (track.hidden && index == musicService?.getCurrentIndex()) {
            val visible = tracks.indices.filter { !tracks[it].hidden }
            if (visible.isEmpty()) {
                musicService?.pause()
                binding.trackTitle.text = "Nothing loaded"
                binding.trackSub.text = "All tracks are hidden — unhide one to keep listening"
                binding.recordInitial.text = "–"
            } else {
                musicService?.next()
            }
        }
        LibraryStore.save(this, tracks)
        refreshList()
    }

    private fun removeTrack(index: Int) {
        if (index !in tracks.indices) return
        val wasCurrent = index == musicService?.getCurrentIndex()
        tracks.removeAt(index)
        musicService?.setPlaylist(tracks)

        if (wasCurrent) {
            musicService?.pause()
            val visible = tracks.indices.filter { !tracks[it].hidden }
            val fallback = visible.firstOrNull { it >= index } ?: visible.lastOrNull()
            if (fallback != null) {
                musicService?.loadAndPlay(fallback, autoplay = false)
            } else {
                binding.trackTitle.text = "Nothing loaded"
                binding.trackSub.text = "Add some music to get started"
                binding.recordInitial.text = "–"
            }
        }
        LibraryStore.save(this, tracks)
        refreshList()
    }

    // ---------- UI helpers ----------

    private fun refreshList() {
        val currentIndex = musicService?.getCurrentIndex() ?: -1
        val playing = musicService?.isPlaying() ?: false
        adapter.submit(tracks.toList(), currentIndex, playing)
        binding.emptyState.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        binding.trackCount.text = if (tracks.isEmpty()) "" else "${tracks.size} track${if (tracks.size == 1) "" else "s"}"
    }

    private fun updateNowPlayingUi() {
        val idx = musicService?.getCurrentIndex() ?: -1
        if (idx in tracks.indices) {
            val t = tracks[idx]
            binding.trackTitle.text = TrackAdapter.displayName(t.name)
            binding.trackSub.text = "Track ${idx + 1} of ${tracks.size} · ${TrackAdapter.extOf(t.name).uppercase()}"
            binding.recordInitial.text = (idx + 1).toString()
            if (t.durationMs > 0) binding.timeTotal.text = TrackAdapter.formatTime(t.durationMs)
        }
        setPlayingUi(musicService?.isPlaying() ?: false)
    }

    private fun setPlayingUi(playing: Boolean) {
        binding.playIconState(playing)
        if (playing) {
            listOf(recordAnimator, labelAnimator).forEach { anim ->
                if (anim?.isPaused == true) anim.resume() else if (anim?.isRunning != true) anim?.start()
            }
        } else {
            recordAnimator?.pause()
            labelAnimator?.pause()
        }
        // Tonearm drops onto the record while playing, lifts away when paused —
        // same visual language as the desktop and web versions.
        binding.tonearm.animate()
            .rotation(if (playing) -8f else -38f)
            .setDuration(400)
            .start()
    }

    // ---------- PlayerCallback ----------

    override fun onTrackChanged(index: Int) {
        runOnUiThread {
            updateNowPlayingUi()
            refreshList()
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            setPlayingUi(isPlaying)
            refreshList()
        }
    }

    override fun onProgress(positionMs: Int, durationMs: Int) {
        runOnUiThread {
            if (isSeeking) return@runOnUiThread
            binding.timeCurrent.text = TrackAdapter.formatTime(positionMs.toLong())
            if (durationMs > 0) {
                binding.timeTotal.text = TrackAdapter.formatTime(durationMs.toLong())
                val pct = (positionMs.toFloat() / durationMs * 1000).toInt()
                binding.seekBar.progress = pct
            }
        }
    }

    override fun onDurationReady(index: Int, durationMs: Long) {
        runOnUiThread {
            if (index in tracks.indices) {
                tracks[index].durationMs = durationMs
                LibraryStore.save(this, tracks)
                if (index == musicService?.getCurrentIndex()) {
                    binding.timeTotal.text = TrackAdapter.formatTime(durationMs)
                }
                refreshList()
            }
        }
    }

    override fun onError(message: String) {
        runOnUiThread { binding.trackSub.text = message }
    }
}

/**
 * Small extension to flip the play/pause icon without repeating this in two callbacks.
 */
private fun ActivityMainBinding.playIconState(playing: Boolean) {
    playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
}
