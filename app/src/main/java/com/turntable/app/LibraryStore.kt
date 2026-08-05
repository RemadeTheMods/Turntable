package com.turntable.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the track list (content:// URIs + hidden flags) between launches.
 * The actual audio files stay wherever the user picked them from (Files app,
 * Downloads, Google Drive, etc.) — we only ever store a reference to them,
 * using a persistable URI permission so we can keep reading them after the
 * app or phone restarts.
 */
object LibraryStore {

    private const val PREFS = "turntable_prefs"
    private const val KEY_TRACKS = "tracks_json"
    private const val KEY_LAST_URI = "last_uri"
    private const val KEY_LAST_POSITION_MS = "last_position_ms"
    private const val KEY_SHUFFLE_ON = "shuffle_on"

    fun load(context: Context): MutableList<Track> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TRACKS, null) ?: return mutableListOf()
        val result = mutableListOf<Track>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    Track(
                        uri = obj.getString("uri"),
                        name = obj.getString("name"),
                        hidden = obj.optBoolean("hidden", false),
                        durationMs = obj.optLong("durationMs", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupt or empty prefs — start fresh rather than crash.
        }
        return result
    }

    fun save(context: Context, tracks: List<Track>) {
        val arr = JSONArray()
        for (t in tracks) {
            val obj = JSONObject()
            obj.put("uri", t.uri)
            obj.put("name", t.name)
            obj.put("hidden", t.hidden)
            obj.put("durationMs", t.durationMs)
            arr.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TRACKS, arr.toString()).apply()
    }

    /** Remembers exactly which track was playing and how far into it, like Spotify. */
    fun saveLastPlayback(context: Context, uri: String, positionMs: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_URI, uri)
            .putLong(KEY_LAST_POSITION_MS, positionMs)
            .apply()
    }

    /** Returns (uri, positionMs), or null if nothing was ever saved. */
    fun loadLastPlayback(context: Context): Pair<String, Long>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(KEY_LAST_URI, null) ?: return null
        val pos = prefs.getLong(KEY_LAST_POSITION_MS, 0L)
        return uri to pos
    }

    fun saveShuffleOn(context: Context, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SHUFFLE_ON, on).apply()
    }

    fun loadShuffleOn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SHUFFLE_ON, false)
    }
}
