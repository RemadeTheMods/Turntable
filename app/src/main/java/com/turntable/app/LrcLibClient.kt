package com.turntable.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks up lyrics on LRCLIB (lrclib.net) — a free, open, no-API-key lyrics
 * database built specifically for this kind of use. Only called as a fallback
 * when [LyricsReader] finds nothing embedded in the file itself.
 */
object LrcLibClient {

    data class Result(val syncedLyrics: String?, val plainLyrics: String?)

    /**
     * Exact-match lookup (LRCLIB's /api/get). Wants an artist name to be
     * useful — pass whatever the file's own tags say. Falls through to
     * [search] at the call site if this comes back empty.
     */
    fun get(trackName: String, artistName: String, albumName: String?, durationSec: Int?): Result? {
        if (trackName.isBlank() || artistName.isBlank()) return null
        return try {
            val urlBuilder = StringBuilder("https://lrclib.net/api/get?track_name=")
                .append(URLEncoder.encode(trackName, "UTF-8"))
                .append("&artist_name=").append(URLEncoder.encode(artistName, "UTF-8"))
            if (!albumName.isNullOrBlank()) {
                urlBuilder.append("&album_name=").append(URLEncoder.encode(albumName, "UTF-8"))
            }
            if (durationSec != null && durationSec > 0) {
                urlBuilder.append("&duration=").append(durationSec)
            }

            val connection = URL(urlBuilder.toString()).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "TurntableAndroidApp/1.0 (personal use)")

            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)

            val synced = safeStringField(obj, "syncedLyrics")
            val plain = safeStringField(obj, "plainLyrics")
            if (synced == null && plain == null) null else Result(synced, plain)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * @param trackName required search term (usually the file's display name,
     *   or the title portion if we can split "Artist - Title" out of it)
     * @param artistName optional, improves match quality when known
     * @param durationSec optional, used to pick the best match among results
     */
    fun search(trackName: String, artistName: String?, durationSec: Int?): Result? {
        if (trackName.isBlank()) return null
        return try {
            val urlBuilder = StringBuilder("https://lrclib.net/api/search?track_name=")
                .append(URLEncoder.encode(trackName, "UTF-8"))
            if (!artistName.isNullOrBlank()) {
                urlBuilder.append("&artist_name=").append(URLEncoder.encode(artistName, "UTF-8"))
            }

            val connection = URL(urlBuilder.toString()).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "TurntableAndroidApp/1.0 (personal use)")

            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val results = JSONArray(body)
            if (results.length() == 0) return null

            var best: JSONObject? = null
            var bestDiff = Long.MAX_VALUE
            for (i in 0 until results.length()) {
                val obj = results.getJSONObject(i)
                val dur = obj.optInt("duration", -1)
                val diff = if (durationSec != null && dur >= 0) {
                    kotlin.math.abs(dur - durationSec).toLong()
                } else {
                    i.toLong() // no duration to compare — just prefer the first (best-ranked) result
                }
                if (best == null || diff < bestDiff) {
                    best = obj
                    bestDiff = diff
                }
            }

            val synced = safeStringField(best, "syncedLyrics")
            val plain = safeStringField(best, "plainLyrics")
            if (synced == null && plain == null) null else Result(synced, plain)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * org.json has a well-known gotcha: JSONObject.optString() on a field whose
     * value is a genuine JSON `null` returns the literal 4-character string
     * "null" rather than an actual null — which would otherwise silently break
     * every check that just tests for blankness. This checks isNull() first to
     * avoid that trap.
     */
    private fun safeStringField(obj: JSONObject?, key: String): String? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        val value = obj.optString(key, "")
        return value.takeIf { it.isNotBlank() }
    }
}
