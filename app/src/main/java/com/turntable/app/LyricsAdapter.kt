package com.turntable.app

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter : RecyclerView.Adapter<LyricsAdapter.LineViewHolder>() {

    var lines: List<LrcParser.LyricLine> = emptyList()
        private set
    var activeIndex: Int = -1
        private set

    fun submit(newLines: List<LrcParser.LyricLine>) {
        lines = newLines
        activeIndex = -1
        notifyDataSetChanged()
    }

    /** Returns true if the active line actually changed (caller uses this to decide whether to scroll). */
    fun setActiveIndex(index: Int): Boolean {
        if (index == activeIndex) return false
        val old = activeIndex
        activeIndex = index
        if (old in lines.indices) notifyItemChanged(old)
        if (activeIndex in lines.indices) notifyItemChanged(activeIndex)
        return true
    }

    class LineViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false) as TextView
        return LineViewHolder(view)
    }

    override fun getItemCount() = lines.size

    override fun onBindViewHolder(holder: LineViewHolder, position: Int) {
        val line = lines[position]
        holder.textView.text = line.text
        val isActive = position == activeIndex
        if (isActive) {
            holder.textView.setTextColor(0xFFFFFFFF.toInt()) // pure white, fully opaque
            holder.textView.textSize = 22f
            holder.textView.alpha = 1f
            holder.textView.setTypeface(holder.textView.typeface, android.graphics.Typeface.BOLD)
        } else {
            holder.textView.setTextColor(0xFF8B8797.toInt())
            holder.textView.textSize = 18f
            holder.textView.alpha = 0.55f
            holder.textView.setTypeface(holder.textView.typeface, android.graphics.Typeface.NORMAL)
        }
    }
}
