package com.example.torrentor

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

// Shared "serve a file while it is still downloading" logic, used by both the
// Web UI (/stream/) and the in-app player bridge (StreamServer). Before each
// chunk is read, the native side is asked to make the needed pieces urgent
// and we wait until they are on disk, so a half-downloaded file never serves
// zero-filled gaps.
object StreamSupport {

    // Files the user chose to stream despite the low-space warning.
    private val lowSpaceAllowed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun allowLowSpace(torrentIndex: Int, fileIndex: Int) {
        lowSpaceAllowed.add(torrentIndex.toString() + "/" + fileIndex.toString())
    }

    private fun isLowSpaceAllowed(torrentIndex: Int, fileIndex: Int): Boolean {
        return lowSpaceAllowed.contains(torrentIndex.toString() + "/" + fileIndex.toString())
    }

    class FileInfo(val file: File, val size: Long, val done: Long, val savePath: String)

    fun resolveFile(torrentIndex: Int, fileIndex: Int): FileInfo? {
        val savePath = try {
            TorrentNative.getTorrentSavePath(torrentIndex)
        } catch (e: Throwable) {
            ""
        }
        if (savePath.isBlank()) return null

        val prep = try {
            TorrentNative.streamPrepare(torrentIndex, fileIndex)
        } catch (e: Throwable) {
            "ERR|native error"
        }
        val prepParts = prep.split("|")
        if (prepParts.size < 3 || prepParts[0] != "OK") return null
        val size = prepParts[1].toLongOrNull() ?: return null
        val done = prepParts.getOrNull(3)?.toLongOrNull() ?: 0L

        val fileData = try {
            TorrentNative.getTorrentFilesByIndex(torrentIndex)
        } catch (e: Throwable) {
            ""
        }
        var relativePath: String? = null
        for (line in fileData.split("\n")) {
            if (line.isBlank()) continue
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue
            if (parts[0].toIntOrNull() == fileIndex) {
                relativePath = parts[1]
                break
            }
        }
        if (relativePath == null) return null
        return FileInfo(File(savePath, relativePath), size, done, savePath)
    }

    // Returns a human-readable problem if the phone does not have enough free
    // space to hold the rest of this file (plus a 100 MB margin), else null.
    private fun formatBytes(b: Long): String {
        val gb = b / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(java.util.Locale.US, "%.2f GB", gb)
        else String.format(java.util.Locale.US, "%d MB", b / (1024 * 1024))
    }

    fun spaceProblem(info: FileInfo): String? {
        return try {
            var dir = File(info.savePath)
            while (!dir.exists() && dir.parentFile != null) dir = dir.parentFile!!
            val free = android.os.StatFs(dir.path).availableBytes
            val missing = (info.size - info.done).coerceAtLeast(0L)
            val margin = 100L * 1024 * 1024
            if (free < missing + margin) {
                "Only " + formatBytes(free) + " free on this device. This stream may fail if space runs out."
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun spaceProblem(torrentIndex: Int, fileIndex: Int): String? {
        val info = resolveFile(torrentIndex, fileIndex) ?: return null
        return spaceProblem(info)
    }

    fun contentType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "txt", "nfo", "sfv", "log", "md", "srt", "vtt", "ass", "ssa", "m3u", "cue" -> "text/plain; charset=utf-8"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun sendText(output: OutputStream, status: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    // Returns normally in every case; callers just close the socket after.
    fun serve(
        torrentIndex: Int,
        fileIndex: Int,
        rangeHeader: String?,
        output: OutputStream
    ) {
        val info = resolveFile(torrentIndex, fileIndex)
        if (info == null) {
            sendText(output, "404 Not Found", "Torrent or file not ready (metadata may still be loading)")
            return
        }

        val problem = spaceProblem(info)
        if (problem != null && info.done < info.size && info.size >= 50L * 1024 * 1024 && !isLowSpaceAllowed(torrentIndex, fileIndex)) {
            sendText(output, "507 Insufficient Storage", problem)
            return
        }

        val total = info.size
        var start = 0L
        var end = total - 1
        var partial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rp = rangeHeader.removePrefix("bytes=").split("-", limit = 2)
            val rs = rp.getOrNull(0)?.trim()?.toLongOrNull()
            val re = rp.getOrNull(1)?.trim()?.toLongOrNull()
            if (rs != null) {
                start = rs
                end = re ?: (total - 1)
                partial = true
            } else if (re != null) {
                start = (total - re).coerceAtLeast(0)
                end = total - 1
                partial = true
            }
        }
        if (end > total - 1) end = total - 1
        if (start < 0) start = 0

        if (total > 0 && start > end) {
            output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
            return
        }

        val length = if (total == 0L) 0L else end - start + 1
        val header = StringBuilder()
        header.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        header.append("Content-Type: ${contentType(info.file.name)}\r\n")
        header.append("Accept-Ranges: bytes\r\n")
        header.append("Content-Length: $length\r\n")
        if (partial) header.append("Content-Range: bytes $start-$end/$total\r\n")
        header.append("Connection: close\r\n\r\n")
        output.write(header.toString().toByteArray(StandardCharsets.UTF_8))
        output.flush()

        if (length <= 0) return

        var pos = start
        var remaining = length
        val buffer = ByteArray(65536)
        var raf: RandomAccessFile? = null

        try {
            while (remaining > 0) {
                val chunk = if (remaining < buffer.size) remaining.toInt() else buffer.size

                // Wait (up to 60 s) for the pieces covering this chunk; give up quietly
                // so the player can retry instead of receiving corrupt bytes.
                val ready = try {
                    TorrentNative.streamWait(torrentIndex, fileIndex, pos, chunk.toLong(), 60000)
                } catch (e: Throwable) {
                    false
                }
                if (!ready) break

                if (raf == null) {
                    if (!info.file.exists()) break
                    raf = RandomAccessFile(info.file, "r")
                }
                raf.seek(pos)
                val n = raf.read(buffer, 0, chunk)
                if (n <= 0) break
                output.write(buffer, 0, n)
                pos += n
                remaining -= n
            }
            output.flush()
        } catch (e: Exception) {
            // Player closed the connection (seek/stop) - normal.
        } finally {
            try { raf?.close() } catch (_: Exception) {}
        }
    }
}
