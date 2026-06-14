package com.example.torrentor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

class TorrentService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val interval = 3000L
    private var restored = false

    private val savePath = "/storage/emulated/0/Download"

    private var lastSessionDownload = 0L
    private var lastSessionUpload = 0L

    override fun onCreate() {
        super.onCreate()

        createChannel()
        updateNotification("Starting TorrentOr...")

        TorrentNative.startSession(savePath)

        restoreSavedTorrents()
        startUpdates()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        updateNotification("TorrentOr running...")

        val magnet = intent?.getStringExtra("MAGNET")
        val filePath = intent?.getStringExtra("TORRENT_PATH")
        val selectedIndexes = intent?.getStringExtra("SELECTED_INDEXES")
        val action = intent?.getStringExtra("ACTION")

        when {

            action == "SAVE_MAGNET_ONLY" -> {
                if (!magnet.isNullOrEmpty()) {
                    val normalizedMagnet = normalizeMagnet(magnet)
                    saveAddedDateIfMissing(extractHashFromMagnet(normalizedMagnet))
                    saveMagnetEntry(normalizedMagnet)
                }
            }

            action == "CLEAR_SAVED_TORRENTS" -> {
                clearSavedTorrents()
                clearTorrentDates()
            }

            action == "PAUSE_ALL" -> {
                TorrentNative.pauseAll()
            }

            action == "RESUME_ALL" -> {
                TorrentNative.resumeAll()
            }

            action == "REMOVE_TORRENT" -> {
                val index = intent.getIntExtra("TORRENT_INDEX", -1)
                val deleteFiles = intent.getBooleanExtra("DELETE_FILES", false)

                if (index > 0) {
                    val hashBeforeRemove = try {
                        TorrentNative.getTorrentHash(index)
                    } catch (e: Throwable) {
                        ""
                    }

                    TorrentNative.removeTorrent(index, deleteFiles)

                    if (isGoodHash(hashBeforeRemove)) {
                        removeSavedTorrentByHash(hashBeforeRemove)
                        removeTorrentDates(hashBeforeRemove)
                    } else {
                        removeSavedTorrent(index)
                    }
                }
            }

            !magnet.isNullOrEmpty() -> {
                val normalizedMagnet = normalizeMagnet(magnet)
                val hash = extractHashFromMagnet(normalizedMagnet)

                if (
                    hash.isNotBlank() &&
                    TorrentNative.hasTorrentHash(hash)
                ) {
                    saveAddedDateIfMissing(hash)
                    saveMagnetEntry(normalizedMagnet)
                    TorrentNative.resumeAll()
                } else {
                    TorrentNative.addMagnet(
                        normalizedMagnet,
                        savePath
                    )

                    saveAddedDateIfMissing(hash)
                    saveMagnetEntry(normalizedMagnet)
                    TorrentNative.resumeAll()
                }
            }

            !filePath.isNullOrEmpty() -> {
                val selected = selectedIndexes ?: ""

                val hash = try {
                    TorrentNative.getTorrentFileHash(filePath)
                } catch (e: Throwable) {
                    ""
                }

                if (!isGoodHash(hash)) {
                    updateNotification("Invalid torrent file")
                    return START_STICKY
                }

                if (TorrentNative.hasTorrentHash(hash)) {
                    saveAddedDateIfMissing(hash)
                    saveFileEntry(hash, filePath, selected)
                    TorrentNative.resumeAll()
                } else {
                    if (selected.isNotBlank()) {
                        TorrentNative.addTorrentFileSelected(
                            filePath,
                            savePath,
                            selected
                        )
                    } else {
                        TorrentNative.addTorrentFile(
                            filePath,
                            savePath
                        )
                    }

                    saveAddedDateIfMissing(hash)
                    saveFileEntry(hash, filePath, selected)
                    TorrentNative.resumeAll()
                }
            }
        }

        savePermanentGlobalStats()
        checkCompletedTorrentDates()

        updateNotification(
            TorrentNative.getDetailedStatus()
        )

        return START_STICKY
    }

    private fun restoreSavedTorrents() {
        if (restored) return
        restored = true

        val cleanedEntries = mutableListOf<String>()
        val entries = getSavedEntries()

        for (entry in entries) {
            val parts = entry.split("||", limit = 4)

            when (parts.getOrNull(0)) {

                "MAGNET" -> {
                    val magnet = parts.getOrNull(1) ?: ""
                    val hash = extractHashFromMagnet(magnet)

                    if (magnet.isNotBlank()) {
                        if (
                            hash.isBlank() ||
                            !TorrentNative.hasTorrentHash(hash)
                        ) {
                            TorrentNative.addMagnet(
                                magnet,
                                savePath
                            )
                        }

                        saveAddedDateIfMissing(hash)

                        val cleaned = "MAGNET||$magnet"
                        if (!cleanedEntries.contains(cleaned)) {
                            cleanedEntries.add(cleaned)
                        }
                    }
                }

                "FILE" -> {
                    val parsed = parseFileEntry(parts)

                    if (parsed != null) {
                        val hash = parsed.hash
                        val path = parsed.path
                        val selected = parsed.selected

                        if (File(path).exists()) {
                            if (!TorrentNative.hasTorrentHash(hash)) {
                                if (selected.isNotBlank()) {
                                    TorrentNative.addTorrentFileSelected(
                                        path,
                                        savePath,
                                        selected
                                    )
                                } else {
                                    TorrentNative.addTorrentFile(
                                        path,
                                        savePath
                                    )
                                }
                            }

                            saveAddedDateIfMissing(hash)

                            val cleaned = "FILE||$hash||$path||$selected"
                            if (!cleanedEntries.contains(cleaned)) {
                                cleanedEntries.add(cleaned)
                            }
                        }
                    }
                }
            }
        }

        saveAllEntries(cleanedEntries)
        TorrentNative.resumeAll()
    }

    private data class FileEntry(
        val hash: String,
        val path: String,
        val selected: String
    )

    private fun parseFileEntry(parts: List<String>): FileEntry? {
        return when {
            parts.size >= 4 -> {
                val hash = parts.getOrNull(1) ?: ""
                val path = parts.getOrNull(2) ?: ""
                val selected = parts.getOrNull(3) ?: ""

                if (isGoodHash(hash) && path.isNotBlank()) {
                    FileEntry(hash, path, selected)
                } else {
                    null
                }
            }

            parts.size == 3 -> {
                val path = parts.getOrNull(1) ?: ""
                val selected = parts.getOrNull(2) ?: ""

                if (path.isBlank() || !File(path).exists()) {
                    null
                } else {
                    val hash = try {
                        TorrentNative.getTorrentFileHash(path)
                    } catch (e: Throwable) {
                        ""
                    }

                    if (isGoodHash(hash)) {
                        FileEntry(hash, path, selected)
                    } else {
                        null
                    }
                }
            }

            else -> null
        }
    }

    private fun normalizeMagnet(value: String): String {
        return if (value.startsWith("magnet:")) {
            value
        } else {
            "magnet:?xt=urn:btih:$value"
        }
    }

    private fun extractHashFromMagnet(magnet: String): String {
        val key = "btih:"
        val start = magnet.indexOf(key)

        if (start == -1) return ""

        val hashStart = start + key.length
        val end = magnet.indexOf("&", hashStart)

        return if (end == -1) {
            magnet.substring(hashStart).trim().lowercase()
        } else {
            magnet.substring(hashStart, end).trim().lowercase()
        }
    }

    private fun isGoodHash(hash: String): Boolean {
        return hash.isNotBlank() &&
                hash != "Hash not ready" &&
                hash != "Invalid torrent" &&
                !hash.startsWith("ERROR")
    }

    private fun getSavedEntries(): MutableList<String> {
        val prefs = getSharedPreferences("torrent_store", MODE_PRIVATE)
        val savedText = prefs.getString("entries", "") ?: ""

        return savedText
            .split("\n")
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
    }

    private fun saveMagnetEntry(magnet: String) {
        val entries = getSavedEntries()
        val hash = extractHashFromMagnet(magnet)

        val cleanedEntries = entries.filterNot { entry ->
            if (!entry.startsWith("MAGNET||")) {
                false
            } else {
                val oldMagnet = entry.split("||", limit = 2).getOrNull(1) ?: ""
                val oldHash = extractHashFromMagnet(oldMagnet)
                oldHash.isNotBlank() && oldHash == hash
            }
        }.toMutableList()

        val newEntry = "MAGNET||$magnet"

        if (!cleanedEntries.contains(newEntry)) {
            cleanedEntries.add(newEntry)
        }

        saveAllEntries(cleanedEntries)
    }

    private fun saveFileEntry(
        hash: String,
        path: String,
        selected: String
    ) {
        if (!isGoodHash(hash)) return

        val entries = getSavedEntries()

        val cleanedEntries = entries.filterNot { entry ->
            entryMatchesHash(entry, hash)
        }.toMutableList()

        val newEntry = "FILE||$hash||$path||$selected"

        cleanedEntries.add(newEntry)
        saveAllEntries(cleanedEntries)
    }

    private fun entryMatchesHash(entry: String, hash: String): Boolean {
        val parts = entry.split("||", limit = 4)

        return when (parts.getOrNull(0)) {
            "MAGNET" -> {
                val magnet = parts.getOrNull(1) ?: ""
                extractHashFromMagnet(magnet) == hash
            }

            "FILE" -> {
                when {
                    parts.size >= 4 -> {
                        parts.getOrNull(1) == hash
                    }

                    parts.size == 3 -> {
                        val path = parts.getOrNull(1) ?: ""
                        if (path.isBlank() || !File(path).exists()) {
                            false
                        } else {
                            val oldHash = try {
                                TorrentNative.getTorrentFileHash(path)
                            } catch (e: Throwable) {
                                ""
                            }

                            oldHash == hash
                        }
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

    private fun saveAllEntries(entries: List<String>) {
        val prefs = getSharedPreferences("torrent_store", MODE_PRIVATE)

        prefs.edit()
            .putString(
                "entries",
                entries.distinct().joinToString("\n")
            )
            .apply()
    }

    private fun removeSavedTorrentByHash(hash: String) {
        if (!isGoodHash(hash)) return

        val entries = getSavedEntries()
        val cleanedEntries = entries.filterNot { entry ->
            entryMatchesHash(entry, hash)
        }

        saveAllEntries(cleanedEntries)
    }

    private fun removeSavedTorrent(index: Int) {
        val entries = getSavedEntries()
        val i = index - 1

        if (i >= 0 && i < entries.size) {
            entries.removeAt(i)
        }

        saveAllEntries(entries)
    }

    private fun clearSavedTorrents() {
        val prefs = getSharedPreferences("torrent_store", MODE_PRIVATE)

        prefs.edit()
            .clear()
            .apply()
    }


    private fun saveAddedDateIfMissing(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)

        if (!prefs.contains("added_$key")) {
            prefs.edit()
                .putLong("added_$key", System.currentTimeMillis())
                .apply()
        }
    }

    private fun saveCompletedDateIfMissing(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)

        if (!prefs.contains("completed_$key")) {
            prefs.edit()
                .putLong("completed_$key", System.currentTimeMillis())
                .apply()
        }
    }

    private fun removeTorrentDates(hash: String) {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)

        prefs.edit()
            .remove("added_$key")
            .remove("completed_$key")
            .apply()
    }

    private fun clearTorrentDates() {
        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)

        prefs.edit()
            .clear()
            .apply()
    }

    private fun normalizeHashForKey(hash: String): String {
        return hash
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun checkCompletedTorrentDates() {
        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (e: Throwable) {
            ""
        }

        if (
            status.isBlank() ||
            status == "No torrents" ||
            status == "No active torrents"
        ) {
            return
        }

        val lines = status.lines().filter { it.isNotBlank() }

        for (i in lines.indices) {
            val index = i + 1
            val line = lines[i]
            val percent = extractPercent(line)

            if (percent >= 100 || line.contains("Seeding", ignoreCase = true)) {
                val hash = try {
                    TorrentNative.getTorrentHash(index)
                } catch (e: Throwable) {
                    ""
                }

                if (isGoodHash(hash)) {
                    saveCompletedDateIfMissing(hash)
                }
            }
        }
    }

    private fun extractPercent(line: String): Int {
        val match = Regex("""(\d+)%""").find(line)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    private fun startUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                savePermanentGlobalStats()
                checkCompletedTorrentDates()

                updateNotification(
                    TorrentNative.getDetailedStatus()
                )

                handler.postDelayed(
                    this,
                    interval
                )
            }
        })
    }

    private fun savePermanentGlobalStats() {
        val nativeStats = try {
            TorrentNative.getGlobalStatistics()
        } catch (e: Throwable) {
            ""
        }

        if (nativeStats.isBlank()) return

        val sessionDownloaded = findStatBytes(
            nativeStats,
            listOf(
                "Session download",
                "Session downloaded",
                "Downloaded",
                "Total downloaded",
                "All-time download"
            )
        )

        val sessionUploaded = findStatBytes(
            nativeStats,
            listOf(
                "Session upload",
                "Session uploaded",
                "Uploaded",
                "Total uploaded",
                "All-time upload"
            )
        )

        val prefs = getSharedPreferences("global_stats", MODE_PRIVATE)

        var allTimeDownload = prefs.getLong("all_time_download", 0L)
        var allTimeUpload = prefs.getLong("all_time_upload", 0L)

        if (sessionDownloaded >= 0) {
            if (sessionDownloaded >= lastSessionDownload) {
                allTimeDownload += sessionDownloaded - lastSessionDownload
            }

            lastSessionDownload = sessionDownloaded
        }

        if (sessionUploaded >= 0) {
            if (sessionUploaded >= lastSessionUpload) {
                allTimeUpload += sessionUploaded - lastSessionUpload
            }

            lastSessionUpload = sessionUploaded
        }

        prefs.edit()
            .putLong("all_time_download", allTimeDownload)
            .putLong("all_time_upload", allTimeUpload)
            .apply()
    }

    private fun findStatBytes(
        text: String,
        labels: List<String>
    ): Long {
        val lines = text.lines()

        for (line in lines) {
            for (label in labels) {
                if (line.trim().startsWith(label, ignoreCase = true)) {
                    val value = line.substringAfter(":").trim()
                    val parsed = parseByteValue(value)

                    if (parsed >= 0L) {
                        return parsed
                    }
                }
            }
        }

        return -1L
    }

    private fun parseByteValue(value: String): Long {
        val cleaned = value
            .replace(",", "")
            .trim()

        val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]+)?""")
            .find(cleaned)
            ?: return -1L

        val number = match.groupValues.getOrNull(1)?.toDoubleOrNull()
            ?: return -1L

        val unit = match.groupValues.getOrNull(2)?.lowercase() ?: "bytes"

        val multiplier = when (unit) {
            "b", "byte", "bytes" -> 1.0
            "kb" -> 1000.0
            "mb" -> 1000.0 * 1000.0
            "gb" -> 1000.0 * 1000.0 * 1000.0
            "tb" -> 1000.0 * 1000.0 * 1000.0 * 1000.0
            "kib" -> 1024.0
            "mib" -> 1024.0 * 1024.0
            "gib" -> 1024.0 * 1024.0 * 1024.0
            "tib" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }

        return (number * multiplier).toLong()
    }

    private fun updateNotification(text: String) {
        val notification: Notification =
            NotificationCompat.Builder(this, "torrent")
                .setContentTitle("TorrentOr")
                .setContentText(
                    text.lines().firstOrNull() ?: "Running"
                )
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(text)
                )
                .setSmallIcon(
                    android.R.drawable.stat_sys_download
                )
                .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "torrent",
                "Torrent Service",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }
}