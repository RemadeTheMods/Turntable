package com.turntable.app

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Reads lyrics that are embedded inside a music file's own tags. This never
 * contacts the network or any third-party lyrics database — it only surfaces
 * text the file already carries, the same way a title or artist tag is read.
 * If a file has no embedded lyrics tag, [read] simply returns null.
 *
 * Supports:
 *  - MP3 (ID3v2.3 / ID3v2.4): the USLT "Unsynchronised lyrics" frame
 *  - FLAC: the Vorbis comment LYRICS or UNSYNCEDLYRICS field
 */
object LyricsReader {

    fun read(context: Context, uri: String, fileName: String): String? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                when (extOf(fileName)) {
                    "mp3" -> readId3Lyrics(input)
                    "flac" -> readFlacLyrics(input)
                    else -> null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
    }

    // ---------- shared stream helpers ----------

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) return null // EOF before we got everything we needed
            read += r
        }
        return buf
    }

    private fun skipExactly(input: InputStream, n: Long): Boolean {
        var remaining = n
        val discard = ByteArray(8192)
        while (remaining > 0) {
            val r = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
            if (r < 0) return false
            remaining -= r
        }
        return true
    }

    // ---------- MP3 / ID3v2 ----------

    private fun readId3Lyrics(input: InputStream): String? {
        val header = readExactly(input, 10) ?: return null
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
            return null // no ID3v2 tag at all
        }
        val majorVersion = header[3].toInt()
        val tagSize = synchSafeToInt(header[6], header[7], header[8], header[9])
        if (tagSize <= 0 || tagSize > 20_000_000) return null // sanity guard

        val tagBytes = readExactly(input, tagSize) ?: return null
        var pos = 0

        while (pos + 10 <= tagBytes.size) {
            val frameId = String(tagBytes, pos, 4, Charsets.ISO_8859_1)
            if (frameId.isBlank() || frameId[0] == '\u0000') break // padding reached

            val frameSize = if (majorVersion >= 4) {
                synchSafeToInt(tagBytes[pos + 4], tagBytes[pos + 5], tagBytes[pos + 6], tagBytes[pos + 7])
            } else {
                bigEndianToInt(tagBytes[pos + 4], tagBytes[pos + 5], tagBytes[pos + 6], tagBytes[pos + 7])
            }
            val frameStart = pos + 10
            val frameEnd = frameStart + frameSize
            if (frameSize <= 0 || frameEnd > tagBytes.size) break

            if (frameId == "USLT") {
                return parseUslt(tagBytes, frameStart, frameEnd)
            }
            pos = frameEnd
        }
        return null
    }

    private fun parseUslt(bytes: ByteArray, start: Int, end: Int): String? {
        if (start >= end) return null
        val encoding = bytes[start].toInt() and 0xFF
        var cursor = start + 1 + 3 // skip encoding byte + 3-byte language code

        val charset: Charset
        val terminatorLen: Int
        when (encoding) {
            0 -> { charset = Charsets.ISO_8859_1; terminatorLen = 1 }
            1 -> { charset = Charsets.UTF_16; terminatorLen = 2 }
            2 -> { charset = Charsets.UTF_16BE; terminatorLen = 2 }
            3 -> { charset = Charsets.UTF_8; terminatorLen = 1 }
            else -> { charset = Charsets.UTF_8; terminatorLen = 1 }
        }

        // Skip the content-descriptor string up to its null terminator.
        var descEnd = cursor
        if (terminatorLen == 1) {
            while (descEnd < end && bytes[descEnd].toInt() != 0) descEnd++
        } else {
            while (descEnd + 1 < end && !(bytes[descEnd].toInt() == 0 && bytes[descEnd + 1].toInt() == 0)) descEnd += 2
        }
        cursor = (descEnd + terminatorLen).coerceAtMost(end)
        if (cursor >= end) return null

        val text = String(bytes, cursor, end - cursor, charset).trim(' ', '\u0000')
        return text.ifBlank { null }
    }

    private fun synchSafeToInt(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        ((b0.toInt() and 0x7F) shl 21) or
        ((b1.toInt() and 0x7F) shl 14) or
        ((b2.toInt() and 0x7F) shl 7) or
        (b3.toInt() and 0x7F)

    private fun bigEndianToInt(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int =
        ((b0.toInt() and 0xFF) shl 24) or
        ((b1.toInt() and 0xFF) shl 16) or
        ((b2.toInt() and 0xFF) shl 8) or
        (b3.toInt() and 0xFF)

    // ---------- FLAC / Vorbis comment ----------

    private fun readFlacLyrics(input: InputStream): String? {
        val magic = readExactly(input, 4) ?: return null
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) {
            return null
        }

        while (true) {
            val blockHeader = readExactly(input, 4) ?: return null
            val isLast = (blockHeader[0].toInt() and 0x80) != 0
            val blockType = blockHeader[0].toInt() and 0x7F
            val blockLength = bigEndianToInt(0, blockHeader[1], blockHeader[2], blockHeader[3])

            if (blockType == 4) { // VORBIS_COMMENT
                val block = readExactly(input, blockLength) ?: return null
                return parseVorbisComment(block)
            } else {
                if (!skipExactly(input, blockLength.toLong())) return null
            }

            if (isLast) return null // reached the end of metadata, no lyrics field found
        }
    }

    private fun parseVorbisComment(block: ByteArray): String? {
        var pos = 0
        fun readLEInt(): Int {
            val v = (block[pos].toInt() and 0xFF) or
                    ((block[pos + 1].toInt() and 0xFF) shl 8) or
                    ((block[pos + 2].toInt() and 0xFF) shl 16) or
                    ((block[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        if (pos + 4 > block.size) return null
        val vendorLength = readLEInt()
        pos += vendorLength
        if (pos + 4 > block.size) return null
        val commentCount = readLEInt()

        repeat(commentCount) {
            if (pos + 4 > block.size) return null
            val len = readLEInt()
            if (len < 0 || pos + len > block.size) return null
            val comment = String(block, pos, len, Charsets.UTF_8)
            pos += len

            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "UNSYNCED LYRICS") {
                    val value = comment.substring(eq + 1).trim()
                    if (value.isNotBlank()) return value
                }
            }
        }
        return null
    }
}
