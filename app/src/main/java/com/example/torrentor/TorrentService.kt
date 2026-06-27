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
import android.util.Log
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
        val actionHash = intent?.getStringExtra("TORRENT_HASH") ?: ""
        val actionMagnet = intent?.getStringExtra("TORRENT_MAGNET") ?: ""

        when {

            action == "SAVE_MAGNET_ONLY" -> {
                if (!magnet.isNullOrEmpty()) {
                    val normalizedMagnet = normalizeMagnet(magnet)
                    saveAddedDateIfMissing(extractHashFromMagnet(normalizedMagnet))
                    saveMagnetEntry(normalizedMagnet)
                }
            }

            action == "CLEAR_SAVED_TORRENTS" -> {
                // Legacy action disabled for safety.
                // Use CLEAN_DELETED_SAVED_TORRENTS instead.
                updateNotification("Clear saved torrents disabled for safety")
            }

            action == "CLEAN_DELETED_SAVED_TORRENTS" -> {
                cleanDeletedSavedTorrentsOnly()
                updateNotification("Old deleted torrents cleaned")
            }

            action == "PAUSE_ALL" -> {
                saveAllCurrentPausedHashes()
                TorrentNative.pauseAll()
            }

            action == "RESUME_ALL" -> {
                TorrentNative.resumeAll()
                clearAllPausedHashes()
            }

            action == "PAUSE_TORRENT" -> {
                val index = intent.getIntExtra("TORRENT_INDEX", -1)

                if (index > 0) {
                    val hash = getBestHashForIndex(index, actionHash, actionMagnet)

                    TorrentNative.pauseTorrent(index)

                    if (isGoodHash(hash)) {
                        savePausedHash(hash)
                        Log.d("TorrentOr", "PAUSE saved hash=$hash index=$index")
                    } else {
                        Log.d("TorrentOr", "PAUSE failed bad hash index=$index hash=$hash")
                    }
                }
            }

            action == "RESUME_TORRENT" -> {
                val index = intent.getIntExtra("TORRENT_INDEX", -1)

                if (index > 0) {
                    val hash = getBestHashForIndex(index, actionHash, actionMagnet)

                    TorrentNative.resumeTorrent(index)

                    if (isGoodHash(hash)) {
                        removePausedHash(hash)
                        Log.d("TorrentOr", "RESUME removed paused hash=$hash index=$index")
                    } else {
                        Log.d("TorrentOr", "RESUME bad hash index=$index hash=$hash")
                    }
                }
            }

            action == "REMOVE_TORRENT" -> {
                val index = intent.getIntExtra("TORRENT_INDEX", -1)
                val deleteFiles = intent.getBooleanExtra("DELETE_FILES", false)

                if (index > 0) {
                    val hashBeforeRemove = getBestHashForIndex(index, actionHash, actionMagnet)

                    TorrentNative.removeTorrent(index, deleteFiles)

                    if (isGoodHash(hashBeforeRemove)) {
                        saveDeletedHash(hashBeforeRemove)
                        removePausedHash(hashBeforeRemove)
                        removeSavedTorrentByHash(hashBeforeRemove)
                        removeTorrentDates(hashBeforeRemove)
                        Log.d("TorrentOr", "DELETE saved hash=$hashBeforeRemove index=$index deleteFiles=$deleteFiles")
                    } else {
                        removeSavedTorrent(index)
                        Log.d("TorrentOr", "DELETE bad hash index=$index hash=$hashBeforeRemove")
                    }

                    cleanDeletedSavedTorrentsOnly()
                }
            }

            !magnet.isNullOrEmpty() -> {
                val normalizedMagnet = normalizeMagnet(magnet)
                val hash = extractHashFromMagnet(normalizedMagnet)

                if (isGoodHash(hash)) {
                    removeDeletedHash(hash)
                    removePausedHash(hash)
                }

                if (
                    hash.isNotBlank() &&
                    TorrentNative.hasTorrentHash(hash)
                ) {
                    saveAddedDateIfMissing(hash)
                    saveMagnetEntry(normalizedMagnet)
                } else {
                    TorrentNative.addMagnet(
                        normalizedMagnet,
                        savePath
                    )

                    saveAddedDateIfMissing(hash)
                    saveMagnetEntry(normalizedMagnet)
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

                removeDeletedHash(hash)
                removePausedHash(hash)

                if (TorrentNative.hasTorrentHash(hash)) {
                    saveAddedDateIfMissing(hash)
                    saveFileEntry(hash, filePath, selected)
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

                    if (isDeletedHash(hash)) {
                        continue
                    }

                    if (magnet.isNotBlank()) {
                        if (
                            hash.isBlank() ||
                            !TorrentNative.hasTorrentHash(hash)
                        ) {
                            if (isPausedHash(hash)) {
                                TorrentNative.addMagnetPaused(
                                    magnet,
                                    savePath
                                )
                            } else {
                                TorrentNative.addMagnet(
                                    magnet,
                                    savePath
                                )
                            }
                        }

                        saveAddedDateIfMissing(hash)
                        Log.d("TorrentOr", "RESTORE magnet hash=$hash paused=${isPausedHash(hash)} deleted=${isDeletedHash(hash)}")
                        applySavedPauseState(hash)

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

                        if (isDeletedHash(hash)) {
                            continue
                        }

                        if (File(path).exists()) {
                            if (!TorrentNative.hasTorrentHash(hash)) {
                                if (isPausedHash(hash)) {
                                    if (selected.isNotBlank()) {
                                        TorrentNative.addTorrentFileSelectedPaused(
                                            path,
                                            savePath,
                                            selected
                                        )
                                    } else {
                                        TorrentNative.addTorrentFilePaused(
                                            path,
                                            savePath
                                        )
                                    }
                                } else {
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
                            }

                            saveAddedDateIfMissing(hash)
                            Log.d("TorrentOr", "RESTORE file hash=$hash paused=${isPausedHash(hash)} deleted=${isDeletedHash(hash)}")
                            applySavedPauseState(hash)

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

        handler.postDelayed({
            applyAllSavedPausedStatesOnce()
        }, 10000L)
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
        val target = normalizeHashForKey(hash)
        if (target.isBlank()) return false

        val parts = entry.split("||", limit = 4)

        return when (parts.getOrNull(0)) {
            "MAGNET" -> {
                val magnet = parts.getOrNull(1) ?: ""
                normalizeHashForKey(extractHashFromMagnet(magnet)) == target
            }

            "FILE" -> {
                when {
                    parts.size >= 4 -> {
                        normalizeHashForKey(parts.getOrNull(1) ?: "") == target
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

                            normalizeHashForKey(oldHash) == target
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

    private fun getBestHashForIndex(
        index: Int,
        preferredHash: String = "",
        preferredMagnet: String = ""
    ): String {
        if (isGoodHash(preferredHash)) {
            return preferredHash
        }

        val magnetHash = extractHashFromMagnet(preferredMagnet)
        if (isGoodHash(magnetHash)) {
            return magnetHash
        }

        val nativeHash = try {
            TorrentNative.getTorrentHash(index)
        } catch (_: Throwable) {
            ""
        }

        if (isGoodHash(nativeHash)) {
            return nativeHash
        }

        val nativeMagnetHash = try {
            extractHashFromMagnet(TorrentNative.getTorrentMagnet(index))
        } catch (_: Throwable) {
            ""
        }

        if (isGoodHash(nativeMagnetHash)) {
            return nativeMagnetHash
        }

        val savedHash = getHashFromSavedEntryAtIndex(index)
        if (isGoodHash(savedHash)) {
            return savedHash
        }

        return ""
    }

    private fun getHashFromSavedEntryAtIndex(index: Int): String {
        val entries = getSavedEntries()
        val i = index - 1

        if (i < 0 || i >= entries.size) {
            return ""
        }

        return getHashFromSavedEntry(entries[i])
    }

    private fun saveDeletedHash(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("deleted_torrents", MODE_PRIVATE)

        prefs.edit()
            .putBoolean(key, true)
            .apply()
    }

    private fun removeDeletedHash(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("deleted_torrents", MODE_PRIVATE)

        prefs.edit()
            .remove(key)
            .apply()
    }

    private fun isDeletedHash(hash: String): Boolean {
        if (!isGoodHash(hash)) return false

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return false

        val prefs = getSharedPreferences("deleted_torrents", MODE_PRIVATE)

        return prefs.getBoolean(key, false)
    }

    private fun savePausedHash(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("paused_torrents", MODE_PRIVATE)

        prefs.edit()
            .putBoolean(key, true)
            .apply()
    }

    private fun removePausedHash(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("paused_torrents", MODE_PRIVATE)

        prefs.edit()
            .remove(key)
            .apply()
    }

    private fun isPausedHash(hash: String): Boolean {
        if (!isGoodHash(hash)) return false

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return false

        val prefs = getSharedPreferences("paused_torrents", MODE_PRIVATE)

        return prefs.getBoolean(key, false)
    }

    private fun clearAllPausedHashes() {
        val prefs = getSharedPreferences("paused_torrents", MODE_PRIVATE)

        prefs.edit()
            .clear()
            .apply()
    }

    private fun saveAllCurrentPausedHashes() {
        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
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
            val hash = getBestHashForIndex(i + 1)

            if (isGoodHash(hash)) {
                savePausedHash(hash)
            }
        }
    }

    private fun applySavedPauseState(hash: String) {
        if (!isPausedHash(hash)) {
            return
        }

        val index = findActiveTorrentIndexByHash(hash)

        if (index > 0) {
            try {
                TorrentNative.pauseTorrent(index)
            } catch (_: Throwable) {
            }
        }
    }

    private fun findActiveTorrentIndexByHash(hash: String): Int {
        if (!isGoodHash(hash)) return -1

        val target = normalizeHashForKey(hash)

        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        if (
            status.isBlank() ||
            status == "No torrents" ||
            status == "No active torrents"
        ) {
            return -1
        }

        val lines = status.lines().filter { it.isNotBlank() }

        for (i in lines.indices) {
            val currentHash = getBestHashForIndex(i + 1)

            if (
                isGoodHash(currentHash) &&
                normalizeHashForKey(currentHash) == target
            ) {
                return i + 1
            }
        }

        return -1
    }


    private fun applyAllSavedPausedStatesOnce() {
        val prefs = getSharedPreferences("paused_torrents", MODE_PRIVATE)

        for ((hash, value) in prefs.all) {
            if (value == true) {
                Log.d("TorrentOr", "REAPPLY paused hash=$hash")
                applySavedPauseState(hash)
            }
        }
    }

    private fun cleanDeletedSavedTorrentsOnly() {
        val entries = getSavedEntries()

        if (entries.isEmpty()) {
            return
        }

        val activeHashes = getActiveTorrentHashes()
        val cleanedEntries = mutableListOf<String>()

        for (entry in entries) {
            val hash = getHashFromSavedEntry(entry)
            val normalizedHash = normalizeHashForKey(hash)

            if (normalizedHash.isBlank()) {
                Log.d("TorrentOr", "CLEAN drop malformed entry=$entry")
                continue
            }

            if (isDeletedHash(hash)) {
                continue
            }

            if (activeHashes.isEmpty()) {
                cleanedEntries.add(entry)
                continue
            }

            if (
                normalizedHash.isNotBlank() &&
                activeHashes.contains(normalizedHash)
            ) {
                cleanedEntries.add(entry)
            }
        }

        saveAllEntries(cleanedEntries.distinct())
    }

    private fun getActiveTorrentHashes(): Set<String> {
        val activeHashes = mutableSetOf<String>()

        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        if (
            status.isBlank() ||
            status == "No torrents" ||
            status == "No active torrents"
        ) {
            return activeHashes
        }

        val lines = status.lines().filter { it.isNotBlank() }

        for (i in lines.indices) {
            val hash = getBestHashForIndex(i + 1)

            if (isGoodHash(hash)) {
                activeHashes.add(normalizeHashForKey(hash))
            }
        }

        return activeHashes
    }

    private fun getHashFromSavedEntry(entry: String): String {
        val parts = entry.split("||", limit = 4)

        return when (parts.getOrNull(0)) {
            "MAGNET" -> {
                val magnet = parts.getOrNull(1) ?: ""
                extractHashFromMagnet(magnet)
            }

            "FILE" -> {
                when {
                    parts.size >= 4 -> {
                        parts.getOrNull(1) ?: ""
                    }

                    parts.size == 3 -> {
                        val path = parts.getOrNull(1) ?: ""

                        if (path.isBlank() || !File(path).exists()) {
                            ""
                        } else {
                            try {
                                TorrentNative.getTorrentFileHash(path)
                            } catch (_: Throwable) {
                                ""
                            }
                        }
                    }

                    else -> ""
                }
            }

            else -> ""
        }
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