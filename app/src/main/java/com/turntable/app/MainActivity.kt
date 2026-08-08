package com.turntable.app

import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.gridlayout.widget.GridLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private lateinit var lyricsAdapter: LyricsAdapter
    private var isLyricsOverlayShowing = false
    private var isUserScrollingLyrics = false
    private var lastActiveLyricIndex = -1
    private val lyricsHandler = Handler(Looper.getMainLooper())
    private var lyricsResyncRunnable: Runnable? = null

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

                val savedRepeat = LibraryStore.loadRepeatMode(this@MainActivity)
                musicService?.setRepeatMode(savedRepeat)
                updateRepeatButtonUi(savedRepeat)

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
                updateRepeatButtonUi(musicService?.getRepeatMode() ?: RepeatMode.OFF)
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
        setupLyricsList()
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
        lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
        if (bound) {
            musicService?.setCallback(null)
            unbindService(connection)
            bound = false
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (isLyricsOverlayShowing) {
            closeLyricsOverlay()
        } else {
            super.onBackPressed()
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

    private fun setupLyricsList() {
        lyricsAdapter = LyricsAdapter()
        binding.lyricsList.layoutManager = LinearLayoutManager(this)
        binding.lyricsList.adapter = lyricsAdapter

        // Pad top/bottom by exactly half the list's own height, so the first
        // and last lines can still be scrolled all the way to dead-center —
        // computed from the real measured height rather than a guessed dp value.
        binding.lyricsList.doOnLayout { view ->
            val half = view.height / 2
            if (view.paddingTop != half) {
                view.setPadding(view.paddingLeft, half, view.paddingRight, half)
            }
        }

        // While the person is actively scrolling, stop auto-following playback
        // and show the "Re-sync" button. A few seconds after they
        // let go it re-syncs automatically too — the button just lets them jump
        // back immediately instead of waiting.
        binding.lyricsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        isUserScrollingLyrics = true
                        lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
                        binding.resyncBtn.visibility = View.VISIBLE
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
                        val runnable = Runnable { resyncLyricsNow() }
                        lyricsResyncRunnable = runnable
                        lyricsHandler.postDelayed(runnable, 4000)
                    }
                }
            }
        })

        binding.resyncBtn.setOnClickListener { resyncLyricsNow() }
    }

    /** Jumps straight back to the currently playing line and hides the resync button. */
    private fun resyncLyricsNow() {
        lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
        isUserScrollingLyrics = false
        binding.resyncBtn.visibility = View.GONE
        if (lastActiveLyricIndex >= 0) centerOnLyricLine(lastActiveLyricIndex)
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

        binding.repeatBtn.setOnClickListener {
            val newMode = musicService?.cycleRepeatMode() ?: RepeatMode.OFF
            updateRepeatButtonUi(newMode)
        }

        binding.lyricsBtn.setOnClickListener { openLyricsOverlay() }
        binding.lyricsBackBtn.setOnClickListener { closeLyricsOverlay() }
        binding.timerBtn.setOnClickListener { showSleepTimerDialog() }

        binding.expandControlsBtn.setOnClickListener { toggleExtraControls() }

        binding.bluetoothBtn.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open Bluetooth settings on this device", Toast.LENGTH_SHORT).show()
            }
        }

        binding.lyricsPlayBtn.setOnClickListener { musicService?.togglePlay() }
        binding.lyricsNextBtn.setOnClickListener { musicService?.next() }
        binding.lyricsPrevBtn.setOnClickListener { musicService?.prev() }

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

        binding.lyricsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = musicService?.getDurationMs() ?: 0
                    val pos = (progress / 1000f * duration).toLong()
                    binding.lyricsTimeCurrent.text = TrackAdapter.formatTime(pos)
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
                // Write access is needed to embed lyrics back into the file later,
                // when the person opens Lyrics for a track.
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                try {
                    // Some providers only grant read access — don't fail the
                    // whole add over it, lyrics embedding just won't work for this one.
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e2: Exception) { /* still usable this session even if persisting the grant failed */ }
            }

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

    private fun updateRepeatButtonUi(mode: RepeatMode) {
        when (mode) {
            RepeatMode.OFF -> {
                binding.repeatBtn.setImageResource(R.drawable.ic_repeat)
                binding.repeatBtn.isSelected = false
                binding.repeatBtn.contentDescription = "Repeat: off"
            }
            RepeatMode.ALL -> {
                binding.repeatBtn.setImageResource(R.drawable.ic_repeat)
                binding.repeatBtn.isSelected = true
                binding.repeatBtn.contentDescription = "Repeat: all"
            }
            RepeatMode.ONE -> {
                binding.repeatBtn.setImageResource(R.drawable.ic_repeat_one)
                binding.repeatBtn.isSelected = true
                binding.repeatBtn.contentDescription = "Repeat: one"
            }
        }
    }

    // ---------- lyrics ----------

    private fun openLyricsOverlay() {
        val idx = musicService?.getCurrentIndex() ?: -1
        if (idx !in tracks.indices) {
            Toast.makeText(this, "Load a track first", Toast.LENGTH_SHORT).show()
            return
        }
        val track = tracks[idx]

        isLyricsOverlayShowing = true
        isUserScrollingLyrics = false
        lastActiveLyricIndex = -1
        lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
        binding.resyncBtn.visibility = View.GONE

        binding.lyricsTrackTitle.text = TrackAdapter.displayName(track.name)
        binding.lyricsSyncStatus.text = ""
        binding.lyricsStatus.text = "Looking for lyrics…"
        binding.lyricsStatus.visibility = View.VISIBLE
        lyricsAdapter.submit(emptyList())

        // Slide up + fade in over the turntable.
        binding.lyricsOverlay.apply {
            translationY = height.toFloat().takeIf { it > 0 } ?: 400f
            alpha = 0f
            visibility = View.VISIBLE
            animate().translationY(0f).alpha(1f).setDuration(280).start()
        }

        fetchAndShowLyrics(track)
    }

    private fun closeLyricsOverlay() {
        lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
        binding.lyricsOverlay.animate()
            .translationY(binding.lyricsOverlay.height.toFloat())
            .alpha(0f)
            .setDuration(220)
            .withEndAction {
                binding.lyricsOverlay.visibility = View.GONE
                isLyricsOverlayShowing = false
            }
            .start()
    }

    private fun fetchAndShowLyrics(track: Track) {
        Thread {
            val result = LyricsEmbedder.findAndEmbed(this, track)

            runOnUiThread {
                // The person may have skipped to a different track while this
                // background lookup was running — don't show stale results.
                if (musicService?.getCurrentIndex() != tracks.indexOf(track)) return@runOnUiThread

                val text = result.text
                when {
                    result.synced && !text.isNullOrBlank() -> {
                        binding.lyricsStatus.visibility = View.GONE
                        val savedNote = if (result.justEmbedded) " (saved to file)" else ""
                        binding.lyricsSyncStatus.text = "Synced • ${result.source}$savedNote"
                        lyricsAdapter.submit(LrcParser.parse(text))
                        updateLyricsSyncPosition(musicService?.getCurrentPositionMs() ?: 0, force = true)
                    }
                    !text.isNullOrBlank() -> {
                        binding.lyricsStatus.visibility = View.GONE
                        val savedNote = if (result.justEmbedded) " (saved to file)" else ""
                        binding.lyricsSyncStatus.text = "Not time-synced • ${result.source}$savedNote"
                        lyricsAdapter.submit(listOf(LrcParser.LyricLine(0L, text)))
                    }
                    else -> {
                        binding.lyricsSyncStatus.text = ""
                        lyricsAdapter.submit(emptyList())
                        binding.lyricsStatus.text =
                            "No lyrics found.\n\nNothing embedded in this file, and no match on LRCLIB."
                        binding.lyricsStatus.visibility = View.VISIBLE
                    }
                }
            }
        }.start()
    }

    /** Called on every playback progress tick; advances the highlighted line
     *  and auto-scrolls to it, unless the person is currently scrolling manually. */
    private fun updateLyricsSyncPosition(positionMs: Int, force: Boolean = false) {
        if (!isLyricsOverlayShowing) return
        val lines = lyricsAdapter.lines
        if (lines.isEmpty() || lines.size == 1) return // single unsynced block — nothing to sync

        var newIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) newIndex = i else break
        }
        if (newIndex == -1) return

        val changed = lyricsAdapter.setActiveIndex(newIndex)
        lastActiveLyricIndex = newIndex
        if ((changed || force) && !isUserScrollingLyrics) {
            centerOnLyricLine(newIndex)
        }
    }

    private fun centerOnLyricLine(position: Int) {
        val layoutManager = binding.lyricsList.layoutManager as? LinearLayoutManager ?: return
        val scroller = object : LinearSmoothScroller(this) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
                return (boxStart + (boxEnd - boxStart) / 2) - (viewStart + (viewEnd - viewStart) / 2)
            }
        }
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
    }

    // ---------- collapsible extra controls ----------

    private fun toggleExtraControls() {
        val expanding = binding.extraControlsRow.visibility != View.VISIBLE
        if (expanding) {
            binding.extraControlsRow.visibility = View.VISIBLE
            binding.extraControlsRow.alpha = 0f
            binding.extraControlsRow.animate().alpha(1f).setDuration(200).start()
        } else {
            binding.extraControlsRow.animate().alpha(0f).setDuration(150)
                .withEndAction { binding.extraControlsRow.visibility = View.GONE }
                .start()
        }
        binding.expandControlsBtn.animate().rotation(if (expanding) 180f else 0f).setDuration(200).start()
    }

    // ---------- sleep timer ----------

    private val timePresets = listOf(
        "5 min" to 5, "10 min" to 10, "15 min" to 15, "30 min" to 30,
        "45 min" to 45, "1 hr" to 60, "1.5 hr" to 90
    )
    private val trackPresets = listOf(
        "1 track" to 1, "3 tracks" to 3, "5 tracks" to 5, "10 tracks" to 10
    )

    private fun showSleepTimerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sleep_timer, null)
        val timeTabBtn = dialogView.findViewById<TextView>(R.id.timeTabBtn)
        val trackTabBtn = dialogView.findViewById<TextView>(R.id.trackTabBtn)
        val timeOptionsContainer = dialogView.findViewById<View>(R.id.timeOptionsContainer)
        val trackOptionsContainer = dialogView.findViewById<View>(R.id.trackOptionsContainer)
        val timeChipGrid = dialogView.findViewById<GridLayout>(R.id.timeChipGrid)
        val trackChipGrid = dialogView.findViewById<GridLayout>(R.id.trackChipGrid)
        val customTimeRow = dialogView.findViewById<View>(R.id.customTimeRow)
        val customTrackRow = dialogView.findViewById<View>(R.id.customTrackRow)
        val customTimeInput = dialogView.findViewById<EditText>(R.id.customTimeInput)
        val customTrackInput = dialogView.findViewById<EditText>(R.id.customTrackInput)
        val customTimeSetBtn = dialogView.findViewById<View>(R.id.customTimeSetBtn)
        val customTrackSetBtn = dialogView.findViewById<View>(R.id.customTrackSetBtn)

        lateinit var dialog: androidx.appcompat.app.AlertDialog

        fun selectTab(showTime: Boolean) {
            timeOptionsContainer.visibility = if (showTime) View.VISIBLE else View.GONE
            trackOptionsContainer.visibility = if (showTime) View.GONE else View.VISIBLE
            timeTabBtn.background = getDrawable(if (showTime) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
            timeTabBtn.setTextColor(getColor(if (showTime) R.color.accent_ink else R.color.text_dim))
            trackTabBtn.background = getDrawable(if (!showTime) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
            trackTabBtn.setTextColor(getColor(if (!showTime) R.color.accent_ink else R.color.text_dim))
        }
        timeTabBtn.setOnClickListener { selectTab(true) }
        trackTabBtn.setOnClickListener { selectTab(false) }
        // Default to whichever kind of timer (if any) is already active.
        selectTab(!(musicService?.isTrackCountTimerActive() == true))

        fun makeChip(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.text_primary))
                background = getDrawable(R.drawable.bg_preset_chip)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                gravity = android.view.Gravity.CENTER
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                )
                params.width = 0
                params.setMargins(dp(4), dp(4), dp(4), dp(4))
                layoutParams = params
                setOnClickListener { onClick() }
            }
        }

        for ((label, minutes) in timePresets) {
            timeChipGrid.addView(makeChip(label) {
                musicService?.startSleepTimer(minutes)
                updateTimerLabel()
                Toast.makeText(this, "Playback will pause in $minutes min", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            })
        }
        timeChipGrid.addView(makeChip("Custom") {
            customTimeRow.visibility = if (customTimeRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        customTimeSetBtn.setOnClickListener {
            val minutes = customTimeInput.text.toString().toIntOrNull()
            if (minutes == null || minutes <= 0) {
                Toast.makeText(this, "Enter a valid number of minutes", Toast.LENGTH_SHORT).show()
            } else {
                musicService?.startSleepTimer(minutes)
                updateTimerLabel()
                Toast.makeText(this, "Playback will pause in $minutes min", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        for ((label, count) in trackPresets) {
            trackChipGrid.addView(makeChip(label) {
                musicService?.startTrackCountTimer(count)
                updateTimerLabel()
                Toast.makeText(this, "Playback will pause after $count more track${if (count == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            })
        }
        trackChipGrid.addView(makeChip("Custom") {
            customTrackRow.visibility = if (customTrackRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        customTrackSetBtn.setOnClickListener {
            val count = customTrackInput.text.toString().toIntOrNull()
            if (count == null || count <= 0) {
                Toast.makeText(this, "Enter a valid number of tracks", Toast.LENGTH_SHORT).show()
            } else {
                musicService?.startTrackCountTimer(count)
                updateTimerLabel()
                Toast.makeText(this, "Playback will pause after $count more track${if (count == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Sleep Timer")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)

        val timerActive = musicService?.isSleepTimerActive() == true || musicService?.isTrackCountTimerActive() == true
        if (timerActive) {
            builder.setNeutralButton("Turn Off") { _, _ ->
                musicService?.cancelSleepTimer()
                musicService?.cancelTrackCountTimer()
                updateTimerLabel()
                Toast.makeText(this, "Sleep timer turned off", Toast.LENGTH_SHORT).show()
            }
        }
        dialog = builder.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun updateTimerLabel() {
        val minutesRemaining = musicService?.getSleepTimerRemainingMinutes() ?: 0
        val tracksRemaining = musicService?.getTracksRemainingForStop() ?: 0
        binding.timerLabel.text = when {
            minutesRemaining > 0 -> "Sleep: ${minutesRemaining}m"
            tracksRemaining > 0 -> "Sleep: ${tracksRemaining}trk"
            else -> "Timer"
        }
    }

    // ---------- PlayerCallback ----------

    override fun onTrackChanged(index: Int) {
        runOnUiThread {
            updateNowPlayingUi()
            refreshList()
            if (isLyricsOverlayShowing && index in tracks.indices) {
                binding.lyricsTrackTitle.text = TrackAdapter.displayName(tracks[index].name)
                isUserScrollingLyrics = false
                lastActiveLyricIndex = -1
                lyricsResyncRunnable?.let { lyricsHandler.removeCallbacks(it) }
                binding.resyncBtn.visibility = View.GONE
                fetchAndShowLyrics(tracks[index])
            }
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
            binding.lyricsTimeCurrent.text = TrackAdapter.formatTime(positionMs.toLong())
            if (durationMs > 0) {
                binding.timeTotal.text = TrackAdapter.formatTime(durationMs.toLong())
                binding.lyricsTimeTotal.text = TrackAdapter.formatTime(durationMs.toLong())
                val pct = (positionMs.toFloat() / durationMs * 1000).toInt()
                binding.seekBar.progress = pct
                binding.lyricsSeekBar.progress = pct
            }
            if (musicService?.isSleepTimerActive() == true || musicService?.isTrackCountTimerActive() == true) updateTimerLabel()
            updateLyricsSyncPosition(positionMs)
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

    override fun onSleepTimerFinished() {
        runOnUiThread {
            updateTimerLabel()
            Toast.makeText(this, "Sleep timer ended — playback paused", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Small extension to flip the play/pause icon without repeating this in two callbacks.
 */
private fun ActivityMainBinding.playIconState(playing: Boolean) {
    val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play
    playBtn.setImageResource(icon)
    lyricsPlayBtn.setImageResource(icon)
}
