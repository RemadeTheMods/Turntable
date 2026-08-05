package com.turntable.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrackAdapter(
    private val onPlay: (Int) -> Unit,
    private val onToggleHide: (Int) -> Unit,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    var tracks: List<Track> = emptyList()
        private set
    var currentIndex: Int = -1
        private set
    var isPlaying: Boolean = false
        private set

    fun submit(newTracks: List<Track>, newCurrentIndex: Int, playing: Boolean) {
        tracks = newTracks
        currentIndex = newCurrentIndex
        isPlaying = playing
        notifyDataSetChanged()
    }

    inner class TrackViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val num: TextView = itemView.findViewById(R.id.trackNum)
        val name: TextView = itemView.findViewById(R.id.trackName)
        val format: TextView = itemView.findViewById(R.id.trackFormat)
        val duration: TextView = itemView.findViewById(R.id.trackDuration)
        val hideBtn: android.widget.ImageButton = itemView.findViewById(R.id.hideBtn)
        val removeBtn: android.widget.ImageButton = itemView.findViewById(R.id.removeBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun getItemCount() = tracks.size

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        val isActive = position == currentIndex

        holder.num.text = (position + 1).toString()
        holder.num.visibility = if (isActive) android.view.View.INVISIBLE else android.view.View.VISIBLE

        holder.name.text = displayName(track.name)
        val ext = extOf(track.name).uppercase()
        holder.format.text = if (track.hidden) "$ext · hidden" else ext
        holder.duration.text = if (track.durationMs > 0) formatTime(track.durationMs) else "–:––"

        val alpha = if (track.hidden) 0.4f else 1f
        holder.itemView.alpha = alpha

        if (isActive) {
            holder.name.setTextColor(0xFFE8A33D.toInt())
        } else {
            holder.name.setTextColor(0xFFF2EFE9.toInt())
        }

        holder.hideBtn.setImageResource(if (track.hidden) R.drawable.ic_eye_off else R.drawable.ic_eye)

        holder.itemView.setOnClickListener {
            if (!track.hidden) onPlay(position)
        }
        holder.hideBtn.setOnClickListener { onToggleHide(position) }
        holder.removeBtn.setOnClickListener { onRemove(position) }
    }

    companion object {
        fun displayName(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0) fileName.substring(0, dot) else fileName
        }

        fun extOf(fileName: String): String {
            val dot = fileName.lastIndexOf('.')
            return if (dot > 0 && dot < fileName.length - 1) fileName.substring(dot + 1) else ""
        }

        fun formatTime(ms: Long): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format("%d:%02d", m, s)
        }
    }
}
