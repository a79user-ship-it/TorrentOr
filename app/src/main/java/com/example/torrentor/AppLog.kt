package com.example.torrentor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Execution log, in the spirit of qBittorrent's "Execution Log".
 *
 * Both the app (RSS, startup, ...) and the torrent engine (torrent added,
 * finished, tracker errors, file errors, ...) write here. Every line has a
 * timestamp and a level (INFO, WARNING or ERROR). The last MAX_ENTRIES lines
 * are kept in memory and in a private file, so the log survives restarts.
 */
object AppLog {

    enum class Level(val label: String) {
        INFO("INFO"),
        WARNING("WARNING"),
        ERROR("ERROR")
    }

    data class Entry(
        val id: Long,
        val timeMillis: Long,
        val level: Level,
        val message: String
    )

    private const val MAX_ENTRIES = 1000
    private const val FILE_NAME = "execution.log"

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var nextId = 1L
    private var logFile: File? = null
    private var initialized = false
    private val writer = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true

            val file = File(context.applicationContext.filesDir, FILE_NAME)
            logFile = file

            try {
                if (file.exists()) {
                    val lines = file.readLines()
                    val recent =
                        if (lines.size > MAX_ENTRIES) lines.takeLast(MAX_ENTRIES) else lines

                    for (line in recent) {
                        val parts = line.split("\t", limit = 3)
                        if (parts.size < 3) continue

                        val time = parts[0].toLongOrNull() ?: continue

                        val level = levelFromName(parts[1])

                        entries.addLast(Entry(nextId++, time, level, parts[2]))
                    }

                    // the file grew past the limit, write it back trimmed
                    if (lines.size > MAX_ENTRIES) {
                        file.writeText(
                            entries.joinToString("") { entry ->
                                "${entry.timeMillis}\t${entry.level.name}\t${entry.message}\n"
                            }
                        )
                    }
                }
            } catch (_: Throwable) {
                // an unreadable log file must never stop the app
            }
        }
    }

    fun add(level: Level, message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ').trim()
        if (clean.isEmpty()) return

        val entry = synchronized(lock) {
            val created = Entry(nextId++, System.currentTimeMillis(), level, clean)
            entries.addLast(created)

            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }

            created
        }

        appendToFile(entry)
    }

    fun info(message: String) = add(Level.INFO, message)
    fun warning(message: String) = add(Level.WARNING, message)
    fun error(message: String) = add(Level.ERROR, message)

    // Also understands the names of older log files (NORMAL, CRITICAL).
    private fun levelFromName(name: String): Level {
        return when (name.trim().uppercase()) {
            "WARNING" -> Level.WARNING
            "ERROR", "CRITICAL" -> Level.ERROR
            else -> Level.INFO
        }
    }

    private fun appendToFile(entry: Entry) {
        val file = synchronized(lock) { logFile } ?: return
        val line = "${entry.timeMillis}\t${entry.level.name}\t${entry.message}\n"

        try {
            writer.execute {
                try {
                    file.appendText(line)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun clear() {
        val file = synchronized(lock) {
            entries.clear()
            logFile
        } ?: return

        try {
            writer.execute {
                try {
                    file.writeText("")
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun snapshot(): List<Entry> {
        return synchronized(lock) { entries.toList() }
    }

    fun formatTime(millis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(millis))
    }

    // Example: 2026-09-20 15:43:19 [WARNING] tracker error ...
    fun formatLine(entry: Entry): String {
        return "${formatTime(entry.timeMillis)} [${entry.level.label}] ${entry.message}"
    }

    fun exportText(levels: Set<Level>): String {
        return snapshot()
            .filter { levels.contains(it.level) }
            .joinToString("\n") { formatLine(it) }
    }

    /**
     * Collects the lines the native engine has queued (torrent added,
     * finished, tracker and file errors, ...) and adds them to this log.
     * Each native line looks like "I|message" (N, I, W or C, then the text).
     */
    private var engineLogWarned = false
    private var ipv6NoticeShown = false

    // libtorrent listens on IPv4 and on IPv6 ([::]). On a network without IPv6
    // the announce over [::] to a tracker always fails with "unreachable" while
    // the IPv4 announce works, so torrents run fine. These lines only add noise.
    private fun isIpv6Unreachable(text: String): Boolean {
        return text.contains("[::]") && text.contains("unreachable", ignoreCase = true)
    }

    fun pullEngineLog() {
        val raw = try {
            EngineExtras.drainEngineLog()
        } catch (e: Throwable) {
            // Usually the native library is older than the app code.
            // Say so once instead of failing silently.
            if (!engineLogWarned) {
                engineLogWarned = true
                warning(
                    "Engine log is not available: " +
                            e.javaClass.simpleName + " " + (e.message ?: "")
                )
            }

            return
        }

        if (raw.isBlank()) return

        for (line in raw.split("\n")) {
            if (line.length < 3 || line[1] != '|') continue

            val level = when (line[0]) {
                'C' -> Level.ERROR
                'W' -> Level.WARNING
                else -> Level.INFO
            }

            val text = line.substring(2)

            if (isIpv6Unreachable(text)) {
                // say it once, then leave these lines out
                if (!ipv6NoticeShown) {
                    ipv6NoticeShown = true

                    add(
                        Level.INFO,
                        "Tracker announces over IPv6 are unreachable on this network " +
                                "and are skipped. IPv4 announces are used, this is normal. " +
                                "Further messages of this kind are hidden."
                    )
                }

                continue
            }

            add(level, text)
        }
    }
}

/**
 * Native functions that are not part of TorrentNative: the engine's log
 * queue and the protocol encryption mode.
 */
object EngineExtras {

    init {
        System.loadLibrary("torrent-rasterbar")
        System.loadLibrary("torrentor")
    }

    external fun drainEngineLog(): String
    external fun setEncryptionMode(mode: Int)
    external fun getEncryptionMode(): Int

    // 0 = allow encryption (default), 1 = require encryption, 2 = disable encryption
    fun encryptionLabel(mode: Int): String {
        return when (mode) {
            1 -> "Require"
            2 -> "Disable"
            else -> "Allow"
        }
    }

    fun loadEncryptionMode(context: Context): Int {
        val mode = context.applicationContext
            .getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
            .getInt("encryption_mode", 0)

        return if (mode in 0..2) mode else 0
    }

    fun saveEncryptionMode(context: Context, mode: Int) {
        context.applicationContext
            .getSharedPreferences("engine_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("encryption_mode", mode)
            .commit()
    }
}
