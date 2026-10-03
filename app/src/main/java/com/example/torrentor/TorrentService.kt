package com.example.torrentor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File

class TorrentService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val interval = 3000L
    private var restored = false

    // Feature 5: Custom Save Folder. This is now just the fallback default -
    // see getGlobalSaveFolder() / getSavedSaveFolder() below for the real,
    // user-editable values. Kept as a constant (not a prefs read) so there
    // is always a safe value even before any prefs file exists.
    private val DEFAULT_SAVE_PATH = "/storage/emulated/0/Download"

    private var lastSessionDownload = 0L
    private var lastSessionUpload = 0L

    // Fixed id so each new low-space notification replaces the last one
    // instead of stacking up a new row every time an episode starts.
    private val LOW_SPACE_NOTIFICATION_ID = 9001

    // Feature 4: UPnP discovery listens for SSDP multicast responses, which
    // on many devices need this lock held to actually arrive. Held only
    // while UPnP is enabled and this service is running - acquired in
    // onCreate() (if UPnP is on) or when the Connection screen turns UPnP
    // on, released when it's turned off or the service is destroyed.
    private var upnpMulticastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()

        createChannel()
        updateNotification("Starting TorrentOr...")

        AppLog.init(applicationContext)
        AppLog.info("TorrentOr engine starting")

        // Apply the saved protocol encryption mode before the session and the
        // torrents start, so the very first connections already use it.
        try {
            EngineExtras.setEncryptionMode(
                EngineExtras.loadEncryptionMode(applicationContext)
            )
        } catch (e: Throwable) {
            AppLog.error(
                "Could not apply the saved encryption mode: " +
                        e.javaClass.simpleName + " " + (e.message ?: "")
            )
        }

        // Feature 4: apply the saved Connection settings before the session
        // (and therefore before any torrent) starts, same reasoning as the
        // encryption mode above. applyConnectionSettings() is safe to call
        // before startSession() - it just stores the values for the native
        // session's first settings_pack to pick up.
        applySavedConnectionSettings()
        applySavedGlobalSpeedLimits()

        TorrentNative.startSession(getGlobalSaveFolder())

        restoreSavedTorrents()
        startUpdates()
    }

    override fun onDestroy() {
        releaseUpnpMulticastLock()
        super.onDestroy()
    }

    // ------------------------------------------------------- Feature 4: Connection

    private fun connectionPrefs() =
        getSharedPreferences("connection_settings", MODE_PRIVATE)

    // ------------------------------------------------------- Feature 1: Speed Control
    // Global limits only - no scheduling. Bytes/second, 0 = unlimited.

    private fun globalSpeedLimitPrefs() =
        getSharedPreferences("global_speed_limits", MODE_PRIVATE)

    private fun applySavedGlobalSpeedLimits() {
        val prefs = globalSpeedLimitPrefs()
        val uploadLimit = prefs.getInt("upload_limit", 0)
        val downloadLimit = prefs.getInt("download_limit", 0)

        try {
            TorrentNative.applyGlobalSpeedLimits(uploadLimit, downloadLimit)
        } catch (e: Throwable) {
            AppLog.error("Could not apply saved global speed limits: ${e.message}")
        }
    }

    // ---- Feature 1: Speed Control (per-torrent override) ----
    // Saved per torrent, keyed by info-hash, as "upload|download" in
    // bytes/second. Unlike First/Last Piece Priority, set_upload_limit/
    // set_download_limit need no metadata, so - like Sequential Download -
    // this is simply re-sent every pass. Cheap and idempotent, so no
    // "applied" tracking or retry scheduler is needed.

    private fun torrentSpeedLimitPrefs() =
        getSharedPreferences("torrent_speed_limits", MODE_PRIVATE)

    private fun getSavedTorrentSpeedLimits(hash: String): Pair<Int, Int>? {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return null

        val saved = torrentSpeedLimitPrefs().getString(key, null) ?: return null
        val parts = saved.split("|")
        val uploadLimit = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val downloadLimit = parts.getOrNull(1)?.toIntOrNull() ?: 0

        return Pair(uploadLimit, downloadLimit)
    }

    private fun removeSavedTorrentSpeedLimits(hash: String) {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        torrentSpeedLimitPrefs().edit().remove(key).apply()
    }

    // ------------------------------------------------------- Feature 5: Custom Save Folder
    // Two layers: a global default folder (used by any torrent with no
    // override) and, separately, a per-torrent override keyed by info-hash.
    // Both prefs files are read/written under the same names from
    // MainActivity directly (same pattern as Sequential Download / First-
    // Last Piece Priority), so a folder picked there is visible here
    // immediately without going through an Intent.

    private fun saveFolderPrefs() =
        getSharedPreferences("save_folder_settings", MODE_PRIVATE)

    private fun getGlobalSaveFolder(): String =
        saveFolderPrefs().getString("global_save_path", DEFAULT_SAVE_PATH)
            ?: DEFAULT_SAVE_PATH

    private fun torrentSaveFolderPrefs() =
        getSharedPreferences("torrent_save_folders", MODE_PRIVATE)

    private fun getSavedSaveFolder(hash: String): String? {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return null

        return torrentSaveFolderPrefs().getString(key, null)
    }

    private fun saveSaveFolder(hash: String, path: String) {
        val key = normalizeHashForKey(hash)
        if (key.isBlank() || path.isBlank()) return

        torrentSaveFolderPrefs().edit().putString(key, path).apply()
    }

    private fun removeSaveFolder(hash: String) {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        torrentSaveFolderPrefs().edit().remove(key).apply()
    }

    private fun reapplySavedTorrentSpeedLimits() {
        val count = getActiveTorrentCount()

        for (index in 1..count) {
            val hash = try {
                TorrentNative.getTorrentHash(index)
            } catch (_: Throwable) {
                ""
            }

            if (!isGoodHash(hash)) continue

            val limits = getSavedTorrentSpeedLimits(hash) ?: continue

            try {
                TorrentNative.setTorrentSpeedLimits(
                    hash,
                    limits.first,
                    limits.second
                )
            } catch (_: Throwable) {
                // Harmless - this runs every time the service handles
                // any action, not just at restore, so it will catch up.
            }
        }
    }

    private fun applySavedConnectionSettings() {
        val prefs = connectionPrefs()

        val utpIn = prefs.getBoolean("utp_in", true)
        val utpOut = prefs.getBoolean("utp_out", true)
        val tcpIn = prefs.getBoolean("tcp_in", true)
        val tcpOut = prefs.getBoolean("tcp_out", true)
        val upnpEnabled = prefs.getBoolean("upnp_enabled", true)
        val natpmpEnabled = prefs.getBoolean("natpmp_enabled", true)
        val savedPort = prefs.getInt("listen_port", 6881)
        val randomPortOnStart = prefs.getBoolean("random_port_on_start", false)

        // "Random port on start" picks a fresh port every time the service
        // starts (app open or boot), rather than a port that's remembered
        // across restarts. The chosen port is NOT written back into
        // "listen_port" so the user's own saved port (if they turn this
        // back off) is never overwritten by a random one.
        val listenPort = if (randomPortOnStart) {
            val randomPort = (10000..65000).random()
            AppLog.info("Connection: random port on start - using $randomPort for this session")
            randomPort
        } else {
            savedPort
        }

        try {
            TorrentNative.applyConnectionSettings(
                utpIn,
                utpOut,
                tcpIn,
                tcpOut,
                upnpEnabled,
                natpmpEnabled,
                listenPort
            )
        } catch (e: Throwable) {
            AppLog.error(
                "Could not apply the saved connection settings: " +
                        e.javaClass.simpleName + " " + (e.message ?: "")
            )
        }

        updateUpnpMulticastLock(upnpEnabled)
    }

    // Called both at startup (above) and whenever the Connection screen
    // changes the UPnP toggle (via ACTION = CONNECTION_SETTINGS_CHANGED
    // below), so the lock always matches the current setting without
    // MainActivity needing to know anything about WifiManager.
    private fun updateUpnpMulticastLock(upnpEnabled: Boolean) {
        try {
            if (upnpEnabled) {
                if (upnpMulticastLock?.isHeld != true) {
                    val wifiManager = applicationContext
                        .getSystemService(WIFI_SERVICE) as? WifiManager

                    val lock = wifiManager?.createMulticastLock("TorrentOrUpnpSsdp")
                    lock?.setReferenceCounted(false)
                    lock?.acquire()
                    upnpMulticastLock = lock

                    if (lock != null) {
                        AppLog.info("UPnP: multicast lock acquired (for SSDP discovery)")
                    }
                }
            } else {
                releaseUpnpMulticastLock()
            }
        } catch (e: Throwable) {
            AppLog.error(
                "Could not update the UPnP multicast lock: " +
                        e.javaClass.simpleName + " " + (e.message ?: "")
            )
        }
    }

    private fun releaseUpnpMulticastLock() {
        try {
            val lock = upnpMulticastLock
            if (lock != null && lock.isHeld) {
                lock.release()
                AppLog.info("UPnP: multicast lock released")
            }
        } catch (e: Throwable) {
            AppLog.error(
                "Could not release the UPnP multicast lock: " +
                        e.javaClass.simpleName + " " + (e.message ?: "")
            )
        } finally {
            upnpMulticastLock = null
        }
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

        // Feature 5: Custom Save Folder - an optional per-torrent override
        // for this one add, from whichever screen sent the Intent. Falls
        // back to the global default folder when not provided, which is
        // the same behavior as before this feature existed.
        val requestedSavePath = intent?.getStringExtra("SAVE_PATH")
        val effectiveSavePath =
            if (requestedSavePath.isNullOrBlank()) getGlobalSaveFolder() else requestedSavePath

        when {

            action == "SAVE_MAGNET_ONLY" -> {
                if (!magnet.isNullOrEmpty()) {
                    val normalizedMagnet = normalizeMagnet(magnet)
                    val hash = extractHashFromMagnet(normalizedMagnet)

                    // This fires right after the user has explicitly chosen
                    // to download a magnet they just added from the magnet
                    // box's metadata/file-selection screen (Download
                    // Selected / Download All / Download All Without
                    // Waiting). That screen adds the torrent natively
                    // straight away, bypassing this Service entirely, so
                    // this was the ONLY place that could have cleared a
                    // stale "deleted" flag for this path - and it never
                    // did, which is why a torrent added this way could
                    // stay permanently invisible to restoreSavedTorrents
                    // even after being re-added many times.
                    if (isGoodHash(hash)) {
                        val wasDeleted = isDeletedHash(hash)
                        removeDeletedHash(hash)
                        removePausedHash(hash)

                        if (wasDeleted) {
                            AppLog.info(
                                "Add (magnet box): cleared stale 'deleted' flag for hash $hash"
                            )
                        }
                    }

                    saveAddedDateIfMissing(hash)
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

            // Feature 4: MainActivity already called TorrentNative.applyConnectionSettings()
            // directly and saved the new values to SharedPreferences before sending this -
            // this only exists so the UPnP multicast lock (owned by this Service, since it
            // must keep working whether or not the app is in the foreground) stays in sync
            // with the new UPnP setting. It never touches any torrent, pause state or file
            // selection.
            action == "CONNECTION_SETTINGS_CHANGED" -> {
                val upnpEnabled = intent.getBooleanExtra("UPNP_ENABLED", true)
                updateUpnpMulticastLock(upnpEnabled)
            }

            action == "PAUSE_ALL" -> {
                saveAllCurrentPausedHashes()
                TorrentNative.pauseAll()
                AppLog.info("All torrents paused")
            }

            action == "RESUME_ALL" -> {
                TorrentNative.resumeAll()
                clearAllPausedHashes()
                AppLog.info("All torrents resumed")
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
                        removeSavedFileSelection(hashBeforeRemove)
                        removeSequentialDownload(hashBeforeRemove)
                        removeFirstLastPriority(hashBeforeRemove)
                        removeSavedTorrentSpeedLimits(hashBeforeRemove)
                        removeSaveFolder(hashBeforeRemove)
                        // must run before the saved entry is removed (it needs the path)
                        deleteStoredTorrentCopy(hashBeforeRemove)
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

                val alreadyAdded =
                    hash.isNotBlank() && TorrentNative.hasTorrentHash(hash)

                // Explicitly adding something always means "I want this
                // torrent", even if it was removed before - a stale
                // "deleted" flag from an earlier removal must never make
                // it vanish again the next time the service restarts, so
                // that is cleared unconditionally. The paused flag is
                // different: adding something that is already in the list
                // (for example "Add all" from an RSS feed) must leave its
                // pause state alone.
                AppLog.info(
                    "Add magnet: hash=$hash alreadyAdded=$alreadyAdded " +
                    "wasDeleted=${isDeletedHash(hash)} wasPaused=${isPausedHash(hash)}"
                )

                if (isGoodHash(hash)) {
                    removeDeletedHash(hash)
                    if (!alreadyAdded) {
                        removePausedHash(hash)
                    }
                }

                AppLog.info(
                    "Add magnet: after clearing, hash=$hash " +
                    "isDeletedHash=${isDeletedHash(hash)}"
                )

                if (alreadyAdded) {
                    saveAddedDateIfMissing(hash)
                    saveMagnetEntry(normalizedMagnet)
                } else {
                    TorrentNative.addMagnet(
                        normalizedMagnet,
                        effectiveSavePath
                    )

                    if (isGoodHash(hash)) {
                        saveSaveFolder(hash, effectiveSavePath)
                    }

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

                val alreadyAdded = TorrentNative.hasTorrentHash(hash)

                AppLog.info(
                    "Add file: hash=$hash alreadyAdded=$alreadyAdded " +
                    "wasDeleted=${isDeletedHash(hash)} wasPaused=${isPausedHash(hash)}"
                )

                // Explicitly adding a .torrent file always means "I want
                // this torrent", even if it was removed before, so any
                // stale "deleted" flag is cleared unconditionally. An
                // existing torrent still keeps its saved pause state.
                removeDeletedHash(hash)
                if (!alreadyAdded) {
                    removePausedHash(hash)
                }

                AppLog.info(
                    "Add file: after clearing, hash=$hash " +
                    "isDeletedHash=${isDeletedHash(hash)}"
                )

                if (alreadyAdded) {
                    saveAddedDateIfMissing(hash)
                    saveFileEntry(hash, filePath, selected)
                } else {
                    if (selected.isNotBlank()) {
                        TorrentNative.addTorrentFileSelected(
                            filePath,
                            effectiveSavePath,
                            selected
                        )
                    } else {
                        TorrentNative.addTorrentFile(
                            filePath,
                            effectiveSavePath
                        )
                    }

                    saveSaveFolder(hash, effectiveSavePath)

                    if (selected.isNotBlank()) {
                        saveFileSelection(hash, selected)
                    } else {
                        removeSavedFileSelection(hash)
                    }

                    saveAddedDateIfMissing(hash)
                    saveFileEntry(hash, filePath, selected)
                }
            }
        }

        savePermanentGlobalStats()
        checkCompletedTorrentDates()

        // Feature 3: catches a torrent that was just added this call (by
        // magnet or .torrent file) and already has a saved Sequential
        // Download / First-Last Piece Priority choice waiting for it -
        // e.g. from the add-time file-selection screens in MainActivity,
        // which can only persist the choice (no handle exists yet when
        // the user taps Download there). restoreSavedTorrents() only
        // covers torrents present when the service starts, not ones
        // added afterward, so this call is what actually applies a
        // freshly-added torrent's saved setting. Cheap and idempotent -
        // already-applied torrents are skipped via appliedPieceSettingsKeys.
        if (!reapplySavedPieceSettings()) {
            scheduleSavedPieceSettingsRetry()
        }

        // Feature 1: same reasoning as the piece-settings call above -
        // catches a torrent that was just added this call and already
        // has a saved per-torrent speed limit waiting for it.
        reapplySavedTorrentSpeedLimits()

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

        if (entries.isNotEmpty()) {
            AppLog.info("Restoring ${entries.size} saved torrent(s)")
        }

        for (entry in entries) {
            val parts = entry.split("||", limit = 4)

            when (parts.getOrNull(0)) {

                "MAGNET" -> {
                    val magnet = parts.getOrNull(1) ?: ""
                    val hash = extractHashFromMagnet(magnet)

                    if (isDeletedHash(hash)) {
                        // This used to be a silent "continue" - the entry
                        // dropped out of cleanedEntries below with no
                        // trace anywhere, so a stale "deleted" flag could
                        // make a torrent vanish on restart with nothing
                        // in the Execution Log to explain it.
                        AppLog.warning(
                            "Restore: not re-adding a torrent marked " +
                            "deleted (hash $hash). If this is wrong, " +
                            "re-add it from its magnet link."
                        )
                        continue
                    }

                    if (magnet.isNotBlank()) {
                        if (
                            hash.isBlank() ||
                            !TorrentNative.hasTorrentHash(hash)
                        ) {
                            // Feature 5: restore into the folder this
                            // torrent was originally added to (or moved
                            // to later), not just whatever the global
                            // default currently is.
                            val restoreSavePath =
                                getSavedSaveFolder(hash) ?: getGlobalSaveFolder()

                            if (isPausedHash(hash)) {
                                TorrentNative.addMagnetPaused(
                                    magnet,
                                    restoreSavePath
                                )
                            } else {
                                TorrentNative.addMagnet(
                                    magnet,
                                    restoreSavePath
                                )
                            }
                        }

                        saveAddedDateIfMissing(hash)
                        Log.d("TorrentOr", "RESTORE magnet hash=$hash paused=${isPausedHash(hash)} deleted=${isDeletedHash(hash)}")
                        AppLog.info("Restore: magnet hash=$hash paused=${isPausedHash(hash)} deleted=${isDeletedHash(hash)}")
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
                            AppLog.warning(
                                "Restore: not re-adding a torrent marked " +
                                "deleted (hash $hash). If this is wrong, " +
                                "re-add it from its .torrent file."
                            )
                            continue
                        }

                        if (File(path).exists()) {
                            if (!TorrentNative.hasTorrentHash(hash)) {
                                // Feature 5: same reasoning as the magnet
                                // branch above - restore into the saved
                                // per-torrent folder, falling back to the
                                // current global default.
                                val restoreSavePath =
                                    getSavedSaveFolder(hash) ?: getGlobalSaveFolder()

                                if (isPausedHash(hash)) {
                                    if (selected.isNotBlank()) {
                                        TorrentNative.addTorrentFileSelectedPaused(
                                            path,
                                            restoreSavePath,
                                            selected
                                        )
                                    } else {
                                        TorrentNative.addTorrentFilePaused(
                                            path,
                                            restoreSavePath
                                        )
                                    }
                                } else {
                                    if (selected.isNotBlank()) {
                                        TorrentNative.addTorrentFileSelected(
                                            path,
                                            restoreSavePath,
                                            selected
                                        )
                                    } else {
                                        TorrentNative.addTorrentFile(
                                            path,
                                            restoreSavePath
                                        )
                                    }
                                }
                            }

                            saveAddedDateIfMissing(hash)
                            Log.d("TorrentOr", "RESTORE file hash=$hash paused=${isPausedHash(hash)} deleted=${isDeletedHash(hash)}")
                            AppLog.info("Restore: file hash=$hash paused=${isPausedHash(hash)} deleted=${isDeletedHash(hash)}")
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

        // Priorities are not kept by the engine, so put the saved selection back.
        // Magnets need their metadata first, so keep retrying until they are ready.
        if (!reapplySavedFileSelections()) {
            scheduleSavedFileSelectionRetry()
        }

        // Feature 3: neither Sequential Download nor First/Last Piece
        // Priority is kept by the engine across a restart either - same
        // retry-until-ready treatment as file selection above.
        if (!reapplySavedPieceSettings()) {
            scheduleSavedPieceSettingsRetry()
        }

        // Feature 1: per-torrent speed limit overrides - no metadata
        // dependency, so no retry scheduler needed (see note above
        // reapplySavedTorrentSpeedLimits).
        reapplySavedTorrentSpeedLimits()

        handler.postDelayed({
            applyAllSavedPausedStatesOnce()
        }, 10000L)
    }

    private val appliedSelectionKeys = mutableSetOf<String>()

    private fun getActiveTorrentCount(): Int {
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
            return 0
        }

        return status.lines().count { it.isNotBlank() }
    }

    // Returns true when there is nothing left to apply.
    private fun reapplySavedFileSelections(): Boolean {
        val count = getActiveTorrentCount()
        var pending = false

        for (index in 1..count) {
            val hash = try {
                TorrentNative.getTorrentHash(index)
            } catch (_: Throwable) {
                ""
            }

            if (!isGoodHash(hash)) {
                pending = true
                continue
            }

            val key = normalizeHashForKey(hash)

            if (appliedSelectionKeys.contains(key)) {
                continue
            }

            val saved = getSavedFileSelection(hash) ?: continue

            val files = try {
                TorrentNative.getTorrentFilesByIndex(index)
            } catch (_: Throwable) {
                ""
            }

            if (
                files.isBlank() ||
                files == "Metadata not ready" ||
                files == "Invalid torrent" ||
                files == "No files"
            ) {
                pending = true
                continue
            }

            try {
                TorrentNative.setTorrentFilePriorities(index, saved)
                appliedSelectionKeys.add(key)
                Log.d("TorrentOr", "RESTORE file selection hash=$hash selected=$saved")
            } catch (_: Throwable) {
                pending = true
            }
        }

        return !pending
    }

    private fun scheduleSavedFileSelectionRetry() {
        var attempts = 0

        handler.postDelayed(object : Runnable {
            override fun run() {
                attempts++

                val done = try {
                    reapplySavedFileSelections()
                } catch (_: Throwable) {
                    true
                }

                if (!done && attempts < 120) {
                    handler.postDelayed(this, 5000L)
                }
            }
        }, 5000L)
    }

    // ---- Feature 3: Sequential Download & First/Last Piece Priority ----
    // Saved per torrent, keyed by info-hash, in their own prefs files -
    // same shape as file selection above. Sequential download has no
    // metadata dependency, so it is simply re-sent every pass (cheap and
    // idempotent). First/last priority needs metadata to know which piece
    // index is actually "last", so - like file selection - it is tracked
    // in appliedPieceSettingsKeys and retried until the native call
    // reports the metadata is ready.

    private val appliedPieceSettingsKeys = mutableSetOf<String>()

    private fun sequentialDownloadPrefs() =
        getSharedPreferences("sequential_download", MODE_PRIVATE)

    private fun firstLastPriorityPrefs() =
        getSharedPreferences("firstlast_priority", MODE_PRIVATE)

    private fun getSavedSequentialDownload(hash: String): Boolean {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return false

        return sequentialDownloadPrefs().getBoolean(key, false)
    }

    private fun removeSequentialDownload(hash: String) {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        sequentialDownloadPrefs().edit().remove(key).apply()
    }

    private fun getSavedFirstLastPriority(hash: String): Boolean {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return false

        return firstLastPriorityPrefs().getBoolean(key, false)
    }

    private fun removeFirstLastPriority(hash: String) {
        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        firstLastPriorityPrefs().edit().remove(key).apply()
    }

    // Returns true when there is nothing left to apply.
    private fun reapplySavedPieceSettings(): Boolean {
        val count = getActiveTorrentCount()
        var pending = false

        for (index in 1..count) {
            val hash = try {
                TorrentNative.getTorrentHash(index)
            } catch (_: Throwable) {
                ""
            }

            if (!isGoodHash(hash)) {
                pending = true
                continue
            }

            val key = normalizeHashForKey(hash)

            val sequential = getSavedSequentialDownload(hash)
            try {
                TorrentNative.setSequentialDownload(hash, sequential)
            } catch (_: Throwable) {
                // Harmless to retry next pass alongside first/last below.
            }

            if (appliedPieceSettingsKeys.contains(key)) {
                continue
            }

            val firstLast = getSavedFirstLastPriority(hash)

            if (!firstLast) {
                // Nothing to apply - a freshly added torrent already has
                // default piece priorities, so there is no "off" state
                // to restore.
                appliedPieceSettingsKeys.add(key)
                continue
            }

            val applied = try {
                TorrentNative.setFirstLastPiecePriority(hash, true)
            } catch (_: Throwable) {
                false
            }

            if (applied) {
                appliedPieceSettingsKeys.add(key)
            } else {
                pending = true
            }
        }

        return !pending
    }

    private fun scheduleSavedPieceSettingsRetry() {
        var attempts = 0

        handler.postDelayed(object : Runnable {
            override fun run() {
                attempts++

                val done = try {
                    reapplySavedPieceSettings()
                } catch (_: Throwable) {
                    true
                }

                if (!done && attempts < 120) {
                    handler.postDelayed(this, 5000L)
                }
            }
        }, 5000L)
    }

    // ---- File selection saved per torrent (same prefs file MainActivity uses) ----

    private fun fileSelectionKey(hash: String): String {
        return "selected_" + normalizeHashForKey(hash)
    }

    // null = nothing saved, "" = saved with no file selected
    private fun getSavedFileSelection(hash: String): String? {
        if (!isGoodHash(hash)) return null
        if (normalizeHashForKey(hash).isBlank()) return null

        return getSharedPreferences("file_priorities", MODE_PRIVATE)
            .getString(fileSelectionKey(hash), null)
    }

    private fun saveFileSelection(hash: String, selectedIndexes: String) {
        if (!isGoodHash(hash)) return
        if (normalizeHashForKey(hash).isBlank()) return

        getSharedPreferences("file_priorities", MODE_PRIVATE)
            .edit()
            .putString(fileSelectionKey(hash), selectedIndexes)
            .commit()
    }

    private fun removeSavedFileSelection(hash: String) {
        if (!isGoodHash(hash)) return
        if (normalizeHashForKey(hash).isBlank()) return

        getSharedPreferences("file_priorities", MODE_PRIVATE)
            .edit()
            .remove(fileSelectionKey(hash))
            .commit()
    }

    // Deletes the private copy of the .torrent file that TorrentOr made in its
    // own storage, so a removed torrent cannot be re-created from it.
    private fun deleteStoredTorrentCopy(hash: String) {
        if (!isGoodHash(hash)) return

        for (entry in getSavedEntries()) {
            if (!entryMatchesHash(entry, hash)) continue

            val parts = entry.split("||", limit = 4)
            if (parts.getOrNull(0) != "FILE") continue

            val path = when {
                parts.size >= 4 -> parts.getOrNull(2) ?: ""
                parts.size == 3 -> parts.getOrNull(1) ?: ""
                else -> ""
            }

            if (path.isBlank()) continue

            try {
                val file = File(path)
                val ownDir = filesDir.canonicalPath + File.separator

                // only ever delete our own copy, never a file the user owns
                if (file.canonicalPath.startsWith(ownDir)) {
                    file.delete()
                }
            } catch (_: Throwable) {
            }
        }
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

        // commit() (not apply()) so the list survives the process being killed
        prefs.edit()
            .putString(
                "entries",
                entries.distinct().joinToString("\n")
            )
            .commit()
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
            .commit()
    }

    private fun removeDeletedHash(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("deleted_torrents", MODE_PRIVATE)

        prefs.edit()
            .remove(key)
            .commit()
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
            .commit()
    }

    private fun removePausedHash(hash: String) {
        if (!isGoodHash(hash)) return

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return

        val prefs = getSharedPreferences("paused_torrents", MODE_PRIVATE)

        prefs.edit()
            .remove(key)
            .commit()
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
            .commit()
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

    // Returns true the FIRST time this hash is recorded as completed (i.e.
    // the exact moment it finished), false on every later check - that is
    // the signal checkCompletedTorrentDates() uses to fire a notification
    // only once per torrent, not on every 3-second status poll.
    private fun saveCompletedDateIfMissing(hash: String): Boolean {
        if (!isGoodHash(hash)) return false

        val key = normalizeHashForKey(hash)
        if (key.isBlank()) return false

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)

        if (!prefs.contains("completed_$key")) {
            prefs.edit()
                .putLong("completed_$key", System.currentTimeMillis())
                .apply()

            return true
        }

        return false
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
                    val justCompleted = saveCompletedDateIfMissing(hash)

                    if (justCompleted) {
                        showTorrentCompleteNotification(extractTorrentName(line), hash)
                    }
                }
            }
        }
    }

    private fun extractPercent(line: String): Int {
        val match = Regex("""(\d+)%""").find(line)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    // The status line looks like "Name • State • 100% • ..." (the same
    // format MainActivity parses for the torrent list), so the name is
    // everything before the first " • ".
    private fun extractTorrentName(line: String): String {
        val name = line.split(" \u2022 ").firstOrNull()?.trim()
        return if (name.isNullOrBlank()) "A torrent" else name
    }

    // A real, user-visible notification (separate from the silent ongoing
    // "TorrentOr is running" one) the moment a torrent finishes. Tapping it
    // opens the app, same as tapping the app icon.
    private fun showTorrentCompleteNotification(name: String, hash: String) {
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("COMPLETED_TORRENT_HASH", hash)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                hash.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, "torrent_complete")
                .setContentTitle("Download complete")
                .setContentText(name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(name))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(this).notify(hash.hashCode(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - the ongoing service
            // notification still works, this one is just skipped.
        } catch (e: Throwable) {
        }
    }

    // Low Disk Space Warning. A simple StatFs check against a configurable
    // threshold (same prefs the Storage Space screen in MainActivity writes
    // to). Warn-only, per design - this never pauses, resumes, or touches
    // any torrent. lowSpaceWarned stops the Execution Log being spammed
    // every 3 seconds while space stays low; it resets once space recovers
    // above the threshold, so a later drop warns again.
    private var lowSpaceWarned = false

    private fun storageSettingsPrefs() =
        getSharedPreferences("storage_settings", MODE_PRIVATE)

    private fun checkLowDiskSpace() {
        try {
            // Feature 5: individual torrents can now live in different
            // custom folders, but this check still monitors the global
            // default folder's volume - a reasonable approximation, since
            // most custom folders end up on the same storage partition.
            val stat = android.os.StatFs(getGlobalSaveFolder())
            val free = stat.availableBytes
            val thresholdMb = storageSettingsPrefs().getInt("low_space_threshold_mb", 500)
            val thresholdBytes = thresholdMb.toLong() * 1024L * 1024L

            if (free < thresholdBytes) {
                if (!lowSpaceWarned) {
                    lowSpaceWarned = true

                    val mbFree = free / (1024 * 1024)

                    AppLog.warning(
                        "Low disk space: only ${mbFree} MB free " +
                        "(threshold ${thresholdMb} MB). Downloads may fail if space runs out."
                    )

                    // Warn-only, same as the log line above - this never
                    // pauses or touches any torrent. One notification per
                    // "episode" (lowSpaceWarned resets once space recovers
                    // above the threshold), so it doesn't repeat every
                    // 3-second poll while space stays low.
                    try {
                        val notification =
                            NotificationCompat.Builder(this, "low_space_warning")
                                .setContentTitle("Low disk space")
                                .setContentText(
                                    "Only $mbFree MB free - downloads may fail " +
                                            "if space runs out"
                                )
                                .setSmallIcon(android.R.drawable.stat_sys_warning)
                                .setAutoCancel(true)
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .build()

                        NotificationManagerCompat.from(this).notify(
                            LOW_SPACE_NOTIFICATION_ID,
                            notification
                        )
                    } catch (e: SecurityException) {
                        // POST_NOTIFICATIONS not granted - the Execution
                        // Log warning above still recorded it.
                    } catch (e: Throwable) {
                    }
                }
            } else {
                lowSpaceWarned = false
            }
        } catch (_: Throwable) {
            // Not fatal - a storage read error should never crash the update loop.
        }
    }

    private fun startUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                savePermanentGlobalStats()
                checkCompletedTorrentDates()
                checkLowDiskSpace()

                updateNotification(
                    TorrentNative.getDetailedStatus()
                )

                // collect the engine's log lines for the Execution Log screen
                AppLog.pullEngineLog()

                // refresh the RSS feeds when the chosen interval has passed
                try {
                    RssManager.maybeAutoRefresh(applicationContext)
                } catch (_: Throwable) {
                }

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

            // (added) Separate, higher-importance channel just for "a
            // torrent finished" alerts, so it can make a sound/pop up
            // without touching the always-silent ongoing service one.
            val completeChannel = NotificationChannel(
                "torrent_complete",
                "Download Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when a torrent finishes downloading"
            }

            manager.createNotificationChannel(completeChannel)

            // (added) Separate channel for low-disk-space warnings, so it
            // can be muted independently of the download-complete and
            // ongoing-service channels.
            val lowSpaceChannel = NotificationChannel(
                "low_space_warning",
                "Low Disk Space",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Warns when free storage drops below your threshold"
            }

            manager.createNotificationChannel(lowSpaceChannel)
        }
    }
}
