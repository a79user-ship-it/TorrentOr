package com.example.torrentor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Per-torrent progress notifications and event notifications (started,
 * paused, resumed, seeding, failed, error, completed, low storage).
 *
 * TorrentService calls poll() from its normal 3-second update loop; this
 * class decides what (if anything) to show based on NotificationSettings.
 * It never touches the persistent "TorrentOr is running" notification and
 * never changes any torrent - the Pause/Resume/Stop buttons only send an
 * Intent back to TorrentService.
 *
 * Everything is best-effort: a failure here is swallowed so it can never
 * break the update loop or a download.
 */
class TorrentNotifier(private val ctx: Context) {

    companion object {
        const val CHANNEL_PROGRESS = "torrent_progress"
        const val CHANNEL_EVENTS = "torrent_events"
        const val CHANNEL_EVENTS_QUIET = "torrent_events_quiet"
        private const val GROUP_PROGRESS = "torrentor_progress"

        const val ACTION_PAUSE = "NOTIF_PAUSE"
        const val ACTION_RESUME = "NOTIF_RESUME"
        const val ACTION_STOP = "NOTIF_STOP"

        private const val SEP = " • "
    }

    private class Snap(
        val index: Int,
        val key: String,
        val hash: String,
        val name: String,
        val category: String,
        val state: String,
        val percent: Int,
        val down: String,
        val up: String,
        val size: String,
        val eta: String
    )

    private val nm = NotificationManagerCompat.from(ctx)

    // key -> category seen on the previous poll
    private val known = HashMap<String, String>()

    // key -> true once all wanted data was downloaded (kept across
    // "Checking files", so a recheck of a finished torrent is not a new finish)
    private val knownDone = HashMap<String, Boolean>()
    private var baselineDone = false

    // Right after the service starts, saved torrents are re-added and the
    // ones that were paused are paused again; none of that is a user action.
    private val createdAt = System.currentTimeMillis()
    private val firstSeen = HashMap<String, Long>()

    private val progressIds = HashMap<String, Int>()
    private val lastProgressPost = HashMap<String, Long>()
    private val lastProgressCategory = HashMap<String, String>()

    // Torrents the user dismissed with the Stop button; no notifications
    // for them until they are running again.
    private val stopped = HashSet<String>()

    private val errorNotified = HashSet<String>()
    private val trackerErrorNotified = HashSet<String>()
    private var lastTrackerCheck = 0L

    init {
        createChannels()
    }

    // ------------------------------------------------------------ channels

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            val manager = ctx.getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS, "Torrent Progress", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Live progress of each downloading torrent" }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS, "Torrent Events", NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Started, paused, resumed, seeding, failed and error alerts" }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_EVENTS_QUIET, "Torrent Events (silent)", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Same alerts, without sound or vibration" }
            )
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------- helpers

    private fun key(hash: String): String =
        hash.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun goodHash(hash: String): Boolean =
        hash.isNotBlank() && hash != "Hash not ready" && hash != "Invalid torrent" &&
                !hash.startsWith("ERROR")

    private fun canNotify(): Boolean {
        return try {
            nm.areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }
    }

    private fun categoryOf(state: String): String {
        return when {
            state.startsWith("Paused (error") || state.startsWith("Missing files") -> "error"
            // Waiting for a free download slot (libtorrent's own queue); the
            // user never paused it, so it must not count as paused/resumed.
            state.startsWith("Paused (queued") -> "queued"
            state.startsWith("Paused") -> "paused"
            state.startsWith("Checking") -> "checking"
            state.startsWith("Seeding") -> "seeding"
            state.startsWith("Downloading") || state.startsWith("Stalled") -> "downloading"
            else -> "other"
        }
    }

    // Status line format (see getDetailedStatus in torrentor.cpp):
    // Name - State - NN% - down - up - Size a / b - Seeds - Peers - ETA
    // Parsed from the end so a name containing the separator still works.
    private fun parse(line: String, hash: String, index: Int): Snap? {
        val parts = line.split(SEP)
        if (parts.size < 9) return null

        val tail = parts.takeLast(8)
        val name = parts.dropLast(8).joinToString(SEP).trim().ifBlank { "Torrent" }
        val state = tail[0].trim()
        val pct = Regex("(\\d+(?:\\.\\d+)?)%").find(tail[1])?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0

        return Snap(
            index = index,
            key = key(hash),
            hash = hash,
            name = name,
            category = categoryOf(state),
            state = state,
            percent = pct.coerceIn(0, 100),
            down = tail[2].trim(),
            up = tail[3].trim(),
            size = tail[4].trim().removePrefix("Size").trim(),
            eta = tail[7].trim()
        )
    }

    private fun openDetailsIntent(hash: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (hash != null) putExtra("OPEN_TORRENT_HASH", hash)
        }

        return PendingIntent.getActivity(
            ctx,
            (hash ?: "app").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Tap opens the app on a specific screen (e.g. "storage" = Storage Space).
    private fun openScreenIntent(screen: String): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_SCREEN", screen)
        }

        return PendingIntent.getActivity(
            ctx,
            ("screen:" + screen).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun serviceAction(action: String, hash: String): PendingIntent {
        val intent = Intent(ctx, TorrentService::class.java)
            .putExtra("ACTION", action)
            .putExtra("TORRENT_HASH", hash)

        return PendingIntent.getService(
            ctx,
            (action + hash).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifyId(seed: String): Int = ("ev:$seed").hashCode()

    private fun progressId(key: String): Int = ("pr:$key").hashCode()

    // ------------------------------------------------------ event messages

    // Shows one event notification. defaultChannel is used when sound is
    // allowed (so the existing "Download Complete" / "Low Disk Space"
    // channels keep any per-channel settings the user already made).
    fun postEvent(
        type: String,
        title: String,
        text: String,
        hash: String?,
        idSeed: String,
        defaultChannel: String = CHANNEL_EVENTS,
        icon: Int = android.R.drawable.stat_sys_warning,
        openScreen: String? = null
    ) {
        try {
            if (!NotificationSettings.isTypeEnabled(ctx, type)) return
            if (hash != null && NotificationSettings.isTorrentDisabled(ctx, hash)) return
            if (!canNotify()) return

            val quiet = NotificationSettings.quiet(ctx)

            val builder = NotificationCompat.Builder(
                ctx, if (quiet) CHANNEL_EVENTS_QUIET else defaultChannel
            )
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(icon)
                .setContentIntent(
                    when {
                        hash != null -> openDetailsIntent(hash)
                        openScreen != null -> openScreenIntent(openScreen)
                        else -> openAppIntent()
                    }
                )
                .setAutoCancel(true)
                .setPriority(
                    if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT
                )

            if (!quiet && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
            }

            nm.notify(notifyId(idSeed), builder.build())
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    fun completed(name: String, hash: String) {
        postEvent(
            type = "completed",
            title = "Download complete",
            text = name,
            hash = hash,
            idSeed = "completed:" + key(hash),
            defaultChannel = "torrent_complete",
            icon = android.R.drawable.stat_sys_download_done
        )
    }

    fun lowStorage(mbFree: Long) {
        // Clear a previous one first, so a new warning alerts again instead of
        // silently updating the old notification in place.
        try {
            nm.cancel(notifyId("lowstorage"))
        } catch (_: Throwable) {
        }

        postEvent(
            type = "lowstorage",
            title = "Low disk space",
            text = "Only $mbFree MB free - downloads may fail if space runs out",
            hash = null,
            idSeed = "lowstorage",
            defaultChannel = "low_space_warning",
            openScreen = "storage"
        )
    }

    // -------------------------------------------------------------- polling

    fun poll() {
        try {
            pollInternal()
        } catch (_: Throwable) {
        }
    }

    // Called by the service when a torrent is removed or the user taps Stop.
    fun stop(hash: String) {
        val k = key(hash)
        if (k.isEmpty()) return
        stopped.add(k)
        cancelProgress(k)
    }

    private fun cancelProgress(k: String) {
        val id = progressIds.remove(k) ?: return
        lastProgressPost.remove(k)
        lastProgressCategory.remove(k)

        try {
            nm.cancel(id)
        } catch (_: Throwable) {
        }
    }

    private fun pollInternal() {
        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        val lines = if (
            status.isBlank() || status == "No torrents" || status == "No active torrents"
        ) {
            emptyList()
        } else {
            status.lines().filter { it.isNotBlank() }
        }

        val snaps = ArrayList<Snap>()

        // A torrent only counts once it is really in the user's list. While
        // the "fetching metadata / choose files" screen is open the torrent
        // exists in the session (paused, and briefly resumed to fetch
        // metadata) but is not saved yet - cancelling that screen must not
        // produce paused/resumed alerts.
        val saved = loadSavedKeys()

        for (i in lines.indices) {
            val hash = try {
                TorrentNative.getTorrentHash(i + 1)
            } catch (_: Throwable) {
                ""
            }

            if (!goodHash(hash)) continue
            val snap = parse(lines[i], hash, i + 1) ?: continue
            if (snap.key.isEmpty()) continue
            if (saved != null && snap.key !in saved) continue
            snaps.add(snap)
        }

        val present = snaps.map { it.key }.toSet()

        // Torrents that disappeared (removed): drop their notifications.
        for (k in known.keys.toList()) {
            if (k !in present) {
                known.remove(k)
                knownDone.remove(k)
                firstSeen.remove(k)
                errorNotified.remove(k)
                trackerErrorNotified.remove(k)
                stopped.remove(k)
                cancelProgress(k)
            }
        }

        for (k in progressIds.keys.toList()) {
            if (k !in present) cancelProgress(k)
        }

        val events = HashMap<String, MutableList<Snap>>()

        fun addEvent(type: String, snap: Snap) {
            events.getOrPut(type) { ArrayList() }.add(snap)
        }

        for (snap in snaps) {
            val prev = known[snap.key]
            val wasDone = knownDone[snap.key]
            known[snap.key] = snap.category

            val nowMs = System.currentTimeMillis()
            val seenAt = firstSeen.getOrPut(snap.key) { nowMs }

            // No events during start-up restore or right after a torrent
            // first appears (it may be re-paused/queued while loading).
            val startupQuiet = nowMs - createdAt < 30_000L
            val newTorrentQuiet = nowMs - seenAt < 10_000L

            // "Done" = everything the user selected has been downloaded.
            // percent covers only the selected files, so a partial download
            // reaches 100% while libtorrent still reports it as downloading.
            if (snap.category != "checking") {
                knownDone[snap.key] = snap.percent >= 100
            }

            if (snap.category != "error") errorNotified.remove(snap.key)

            // A stopped torrent is tracked again once it runs again.
            if (snap.key in stopped && snap.category != "paused") stopped.remove(snap.key)

            if (!baselineDone || startupQuiet) continue

            if (prev == null) {
                if (snap.category == "downloading" && snap.percent < 100) addEvent("started", snap)
            } else if (prev != snap.category && !newTorrentQuiet) {
                when {
                    snap.category == "paused" -> addEvent("paused", snap)
                    prev == "paused" && snap.category != "error" -> addEvent("resumed", snap)
                }
            }

            // Finished while running (not a first sighting, not a recheck):
            // a full download starts seeding; a partial one (some files
            // unselected) has finished the selected files and seeds those.
            if (prev != null && wasDone == false && snap.percent >= 100 &&
                (snap.category == "seeding" || snap.category == "downloading")
            ) {
                addEvent("seeding", snap)
            }

            if (snap.category == "error" && snap.key !in errorNotified) {
                errorNotified.add(snap.key)
                addEvent(if (snap.percent < 100) "failed" else "error", snap)
            }
        }

        baselineDone = true

        emitEvents(events)
        checkTrackers(snaps)
        updateProgress(snaps)
    }

    // Info-hashes saved in the persistent torrent list ("torrent_store").
    // Returns null (= treat every torrent as saved) when the list can't be
    // read or contains older entries that carry no hash.
    private fun loadSavedKeys(): Set<String>? {
        try {
            val raw = ctx.getSharedPreferences("torrent_store", Context.MODE_PRIVATE)
                .getString("entries", "") ?: ""

            val keys = HashSet<String>()

            for (entry in raw.split("\n").filter { it.isNotBlank() }) {
                val parts = entry.split("||", limit = 4)

                when (parts.getOrNull(0)) {
                    "MAGNET" -> {
                        val h = TorrentSearchResult(
                            name = "", sourceName = "", magnetUri = parts.getOrNull(1) ?: ""
                        ).normalizedHash()

                        if (h.isEmpty()) return null
                        keys.add(key(h))
                    }

                    "FILE" -> {
                        if (parts.size < 4) return null
                        keys.add(key(parts[1]))
                    }
                }
            }

            return keys
        } catch (_: Throwable) {
            return null
        }
    }

    private fun emitEvents(events: Map<String, List<Snap>>) {
        for ((type, list) in events) {
            val titles = mapOf(
                "started" to "Download started",
                "paused" to "Torrent paused",
                "resumed" to "Torrent resumed",
                "seeding" to "Seeding started",
                "failed" to "Download failed",
                "error" to "Torrent error"
            )
            val title = titles[type] ?: continue

            val visible = list.filter { !NotificationSettings.isTorrentDisabled(ctx, it.hash) }
            if (visible.isEmpty()) continue

            if (visible.size > 2) {
                // e.g. "Pause All": one summary instead of a pile of alerts.
                postEvent(
                    type,
                    if (type == "seeding") "Downloads finished, seeding started" else title,
                    "${visible.size} torrents", null,
                    "summary:$type"
                )
            } else {
                for (s in visible) {
                    val text = if (type == "failed" || type == "error") {
                        s.name + "\n" + s.state
                    } else {
                        s.name
                    }

                    // A partial download (some files unselected) is "finished"
                    // while libtorrent still calls it downloading.
                    val t = if (type == "seeding" && s.category != "seeding") {
                        "Download finished, seeding started"
                    } else {
                        title
                    }

                    postEvent(type, t, text, s.hash, "$type:${s.key}")
                }
            }
        }
    }

    // Tracker error = every tracker of the torrent has failed. Checked at
    // most once a minute, and only while that notification type is on.
    private fun checkTrackers(snaps: List<Snap>) {
        if (!NotificationSettings.isTypeEnabled(ctx, "error")) return

        val now = System.currentTimeMillis()
        if (now - lastTrackerCheck < 60_000L) return
        lastTrackerCheck = now

        for (snap in snaps) {
            if (snap.category == "paused" || snap.category == "error") continue

            val text = try {
                TorrentNative.getTorrentTrackerStatus(snap.index)
            } catch (_: Throwable) {
                ""
            }

            val total = Regex("Status: ").findAll(text).count()
            val failed = Regex("Status: Error / Failed").findAll(text).count()

            if (total > 0 && failed == total) {
                if (trackerErrorNotified.add(snap.key)) {
                    postEvent(
                        "error", "Tracker error",
                        snap.name + "\nAll trackers failed or did not respond",
                        snap.hash, "tracker:" + snap.key
                    )
                }
            } else if (total > 0) {
                trackerErrorNotified.remove(snap.key)
            }
        }
    }

    // ------------------------------------------------------ progress cards

    private fun updateProgress(snaps: List<Snap>) {
        if (!NotificationSettings.progressEnabled(ctx) || !canNotify()) {
            for (k in progressIds.keys.toList()) cancelProgress(k)
            return
        }

        val now = System.currentTimeMillis()
        val intervalMs = NotificationSettings.intervalSeconds(ctx) * 1000L

        for (snap in snaps) {
            val wanted = snap.percent < 100 &&
                    snap.category != "seeding" &&
                    snap.key !in stopped &&
                    !NotificationSettings.isTorrentDisabled(ctx, snap.hash)

            if (!wanted) {
                cancelProgress(snap.key)
                continue
            }

            val last = lastProgressPost[snap.key] ?: 0L
            val changed = lastProgressCategory[snap.key] != snap.category

            // small margin: the poll loop runs every 1 s and its timing jitters
            if (!changed && now - last < intervalMs - 150L) continue

            try {
                postProgress(snap)
                lastProgressPost[snap.key] = now
                lastProgressCategory[snap.key] = snap.category
            } catch (_: SecurityException) {
            } catch (_: Throwable) {
            }
        }
    }

    private fun postProgress(snap: Snap) {
        val paused = snap.category == "paused" || snap.category == "error"
        val indeterminate = snap.category == "checking"

        val summary = when {
            paused -> "${snap.state} • ${snap.percent}%"
            snap.category == "queued" -> "Queued • ${snap.percent}%"
            indeterminate -> "Checking files • ${snap.percent}%"
            else -> "${snap.percent}% • ${snap.down} • ${snap.up}"
        }

        val big = StringBuilder()
        big.append("Status: ").append(snap.state)
        big.append("\nProgress: ").append(snap.percent).append("%")
        big.append("\nDownloaded: ").append(snap.size)
        big.append("\nSpeed: ").append(snap.down).append("  ").append(snap.up)
        if (!paused) big.append("\n").append(snap.eta)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setContentTitle(snap.name)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big.toString()))
            .setSmallIcon(
                if (paused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download
            )
            .setProgress(100, snap.percent, indeterminate)
            .setOngoing(!paused)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openDetailsIntent(snap.hash))

        if (paused) {
            builder.addAction(
                android.R.drawable.ic_media_play, "Resume", serviceAction(ACTION_RESUME, snap.hash)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause, "Pause", serviceAction(ACTION_PAUSE, snap.hash)
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", serviceAction(ACTION_STOP, snap.hash)
        )
        builder.addAction(
            android.R.drawable.ic_menu_view, "Open TorrentOr", openAppIntent()
        )

        val id = progressId(snap.key)
        progressIds[snap.key] = id
        nm.notify(id, builder.build())
    }
}
