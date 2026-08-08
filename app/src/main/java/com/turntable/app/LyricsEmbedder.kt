package com.turntable.app

import android.content.Context
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File

/**
 * Finds lyrics for a track — checking the file's own tags first, then
 * LRCLIB (a free, open lyrics database) — and embeds the result into the
 * file if it wasn't already there or was only plain (unsynced) text, so
 * future lookups are instant and offline from then on.
 *
 * This modifies the actual file, but never touches the original directly:
 * it copies to a private temp file, does all reading/writing there via
 * jaudiotagger (a proven tagging library, not hand-rolled binary parsing),
 * and only overwrites the original once every step has succeeded. If
 * anything goes wrong at any point, the original file is left untouched.
 */
object LyricsEmbedder {

    data class LyricsResult(
        val text: String?,
        val synced: Boolean,
        val source: String,       // "embedded file", "LRCLIB", or "none"
        val justEmbedded: Boolean // true if this call just wrote the text into the file
    )

    private val WRITABLE_EXTENSIONS = setOf("mp3", "flac", "m4a")

    fun findAndEmbed(context: Context, track: Track): LyricsResult {
        // 1. Already has something embedded and time-synced? Nothing to do.
        val existing = LyricsReader.read(context, track.uri, track.name)
        if (existing != null && LrcParser.isSynced(existing)) {
            return LyricsResult(existing, true, "embedded file", false)
        }

        val ext = extOf(track.name)
        if (ext !in WRITABLE_EXTENSIONS) {
            // Can't write to this format — just show whatever's already there, if anything.
            return if (!existing.isNullOrBlank()) LyricsResult(existing, false, "embedded file", false)
            else LyricsResult(null, false, "none", false)
        }

        // 2. Nothing synced locally — try LRCLIB. This is the only network
        // call anywhere in the app.
        val tempFile = try {
            File.createTempFile("turntable_tag_", ".$ext", context.cacheDir)
        } catch (e: Exception) {
            return fallback(existing)
        }

        try {
            val copied = try {
                context.contentResolver.openInputStream(Uri.parse(track.uri))?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) { false }
            if (!copied) return fallback(existing)

            val audioFile = try { AudioFileIO.read(tempFile) } catch (e: Exception) { null }
                ?: return fallback(existing)
            val tag = audioFile.tagOrCreateAndSetDefault

            // Prefer the file's own Title/Artist tags, but fall back to a
            // filename guess ("Artist - Title") when tags are missing —
            // this is what previously caused files with existing plain
            // (untagged) lyrics to be skipped entirely.
            val (guessedArtist, guessedTitle) = guessArtistTitle(track.name)
            val title = safeTagField(tag, FieldKey.TITLE) ?: guessedTitle
            val artist = safeTagField(tag, FieldKey.ARTIST) ?: guessedArtist
            if (title.isNullOrBlank() || artist.isNullOrBlank()) return fallback(existing)

            val album = safeTagField(tag, FieldKey.ALBUM)
            val durationSec = try { audioFile.audioHeader?.trackLength } catch (e: Exception) { null }
                ?.takeIf { it > 0 }

            // Exact match first, fuzzy search as a fallback.
            val lookup = LrcLibClient.get(title, artist, album, durationSec)
                ?: LrcLibClient.search(title, artist, durationSec)

            val lyricsText = lookup?.syncedLyrics ?: lookup?.plainLyrics
            if (lyricsText.isNullOrBlank()) return fallback(existing)
            val isSynced = lookup?.syncedLyrics?.isNotBlank() == true

            // Don't rewrite the file if this is identical to what's already there.
            if (lyricsText == existing) {
                return LyricsResult(lyricsText, isSynced, "embedded file", false)
            }

            return try {
                tag.setField(FieldKey.LYRICS, lyricsText)
                audioFile.commit()
                context.contentResolver.openOutputStream(Uri.parse(track.uri), "wt")?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }
                LyricsResult(lyricsText, isSynced, "LRCLIB", true)
            } catch (e: Exception) {
                // Found it, but couldn't write it back — still show it, just not embedded.
                LyricsResult(lyricsText, isSynced, "LRCLIB", false)
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun fallback(existing: String?): LyricsResult =
        if (!existing.isNullOrBlank()) LyricsResult(existing, false, "embedded file", false)
        else LyricsResult(null, false, "none", false)

    private fun safeTagField(tag: Tag, key: FieldKey): String? =
        (try { tag.getFirst(key) } catch (e: Exception) { null })?.takeIf { it.isNotBlank() }

    /** Splits a filename like "Artist - Title" into (artist, title); falls back to (null, wholeName). */
    private fun guessArtistTitle(fileName: String): Pair<String?, String> {
        val dot = fileName.lastIndexOf('.')
        val name = if (dot > 0) fileName.substring(0, dot) else fileName
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to name.trim()
        }
    }

    private fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
    }
}
