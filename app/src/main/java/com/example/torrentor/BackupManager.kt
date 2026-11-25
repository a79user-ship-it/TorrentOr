package com.example.torrentor

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup & Restore (v2.08).
 *
 * Backup format v1 - a plain ZIP file:
 *   manifest.json            format marker, version, counts, app version
 *   prefs/<name>.json        one file per SharedPreferences file
 *   torrents/<n>.torrent     copies of .torrent files used by saved torrents
 *   resume/<hash>.fastresume libtorrent resume data (piece progress)
 *
 * Downloaded data itself is never part of a backup, only the list of
 * torrents and everything needed to continue them without a full recheck.
 */
object BackupManager {

    const val FORMAT_NAME = "TorrentOrBackup"
    const val FORMAT_VERSION = 1

    class BackupException(message: String) : Exception(message)

    data class Preview(
        val formatVersion: Int,
        val createdAt: Long,
        val appVersion: String,
        val torrentCount: Int,
        val torrentFileCount: Int,
        val resumeCount: Int,
        val settingsFileCount: Int
    )

    data class RestoreResult(
        val torrentsInBackup: Int,
        val torrentsAdded: Int,
        val torrentsAlreadyPresent: Int,
        val torrentsSkipped: Int,
        val resumeFiles: Int,
        val settingsFiles: Int
    )

    // Everything the app persists in SharedPreferences, except caches that
    // are rebuilt on their own (rss_cache).
    private val PREF_FILES = listOf(
        "connection_settings",
        "deleted_torrents",
        "engine_settings",
        "execution_log_settings",
        "file_priorities",
        "firstlast_priority",
        "global_speed_limits",
        "global_stats",
        "paused_torrents",
        "prefs",
        "save_folder_settings",
        "sequential_download",
        "storage_settings",
        "torrent_dates",
        "torrent_dht_disabled",
        "torrent_pex_disabled",
        "torrent_lsd_disabled",
        "network_features",
        "torrent_list_view",
        "torrent_save_folders",
        "torrent_speed_limits",
        "torrent_store",
        "torrent_queue",
        "web_ui",
        "online_search_settings",
        "notification_settings",
        "rss_feeds",
        "rss_settings",
        "rss_auto",
        "rss_seen",
        "rss_added"
    )

    private const val MAX_TEXT_BYTES = 8L * 1024 * 1024
    private const val MAX_TORRENT_BYTES = 32L * 1024 * 1024
    private const val MAX_RESUME_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 50000

    private val prefNameRegex = Regex("^prefs/([A-Za-z0-9_]{1,64})\\.json$")
    private val torrentNameRegex = Regex("^torrents/([0-9]{1,6})\\.torrent$")
    private val resumeNameRegex = Regex("^resume/([0-9a-f]{40}|[0-9a-f]{64})\\.fastresume$")
    private val hexHashRegex = Regex("^([0-9a-f]{40}|[0-9a-f]{64})$")

    // ---------------------------------------------------------------- create

    /** Writes a complete backup ZIP to [out]. Does not close [out]. */
    fun createBackup(context: Context, out: OutputStream, appVersion: String) {
        val zip = ZipOutputStream(out)
        zip.setLevel(6)

        // 1. SharedPreferences -> JSON
        val dumps = LinkedHashMap<String, JSONObject>()
        for (name in PREF_FILES) {
            dumps[name] = dumpPrefs(context, name)
        }

        // 2. Torrent list: embed .torrent files so FILE entries still work
        //    on another device / after the original copy is gone.
        val storeDump = dumps["torrent_store"] ?: JSONObject()
        val entriesObj = storeDump.optJSONObject("entries")
        val entriesText = entriesObj?.optString("v", "") ?: ""
        val newEntries = ArrayList<String>()
        val torrentFiles = ArrayList<Pair<String, File>>()
        var torrentCount = 0

        for (entry in entriesText.split("\n").filter { it.isNotBlank() }) {
            val parts = entry.split("||", limit = 4)

            when (parts.getOrNull(0)) {
                "MAGNET" -> {
                    torrentCount++
                    newEntries.add(entry)
                }
                "FILE" -> {
                    val pathIndex = if (parts.size >= 4) 2 else 1
                    val path = parts.getOrNull(pathIndex) ?: ""
                    val file = File(path)

                    if (path.isNotBlank() && file.isFile && file.canRead() &&
                        file.length() <= MAX_TORRENT_BYTES
                    ) {
                        val inner = "torrents/${torrentFiles.size}.torrent"
                        torrentFiles.add(inner to file)

                        val rewritten = parts.toMutableList()
                        rewritten[pathIndex] = "@$inner"
                        newEntries.add(rewritten.joinToString("||"))
                        torrentCount++
                    }
                    // A FILE entry whose .torrent is missing could not be
                    // restored anyway, so it is left out of the backup.
                }
            }
        }

        if (entriesObj != null) {
            storeDump.put(
                "entries",
                JSONObject().put("t", "s").put("v", newEntries.joinToString("\n"))
            )
        }

        // 3. Resume data
        val resumeDir = File(context.filesDir, "resume_data")
        val resumeFiles = (resumeDir.listFiles() ?: emptyArray())
            .filter {
                it.isFile && it.name.endsWith(".fastresume") &&
                    hexHashRegex.matches(it.name.removeSuffix(".fastresume")) &&
                    it.length() <= MAX_RESUME_BYTES
            }

        val manifest = JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", FORMAT_VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("appVersion", appVersion)
            .put("torrents", torrentCount)
            .put("torrentFiles", torrentFiles.size)
            .put("resumeFiles", resumeFiles.size)
            .put("settingsFiles", dumps.size)

        putText(zip, "manifest.json", manifest.toString(2))

        for ((name, dump) in dumps) {
            putText(zip, "prefs/$name.json", dump.toString())
        }

        for ((inner, file) in torrentFiles) {
            putFile(zip, inner, file)
        }

        for (file in resumeFiles) {
            putFile(zip, "resume/${file.name}", file)
        }

        zip.finish()
        zip.flush()
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun dumpPrefs(context: Context, name: String): JSONObject {
        val result = JSONObject()
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        for ((key, value) in prefs.all) {
            val o = JSONObject()
            when (value) {
                is Boolean -> o.put("t", "b").put("v", value)
                is Int -> o.put("t", "i").put("v", value)
                is Long -> o.put("t", "l").put("v", value)
                is Float -> o.put("t", "f").put("v", value.toDouble())
                is String -> o.put("t", "s").put("v", value)
                is Set<*> -> {
                    val arr = JSONArray()
                    value.forEach { if (it is String) arr.put(it) }
                    o.put("t", "ss").put("v", arr)
                }
                else -> continue
            }
            result.put(key, o)
        }

        return result
    }

    // --------------------------------------------------------------- restore

    /** Parsed + validated backup, staged in a cache folder. */
    private class Staged(
        val dir: File,
        val manifest: JSONObject,
        val prefs: Map<String, JSONObject>,
        val torrentFiles: Map<String, File>,
        val resumeFiles: Map<String, File>
    )

    fun readPreview(context: Context, input: InputStream): Preview {
        val staged = stage(context, input)

        try {
            val m = staged.manifest
            return Preview(
                formatVersion = m.optInt("version", 0),
                createdAt = m.optLong("createdAt", 0L),
                appVersion = m.optString("appVersion", "?"),
                torrentCount = countTorrents(staged.prefs),
                torrentFileCount = staged.torrentFiles.size,
                resumeCount = staged.resumeFiles.size,
                settingsFileCount = staged.prefs.size
            )
        } finally {
            staged.dir.deleteRecursively()
        }
    }

    private fun countTorrents(prefs: Map<String, JSONObject>): Int {
        val text = prefs["torrent_store"]
            ?.optJSONObject("entries")?.optString("v", "") ?: ""
        return text.split("\n").count {
            it.startsWith("MAGNET||") || it.startsWith("FILE||")
        }
    }

    private fun readLimited(zip: ZipInputStream, limit: Long, sink: OutputStream) {
        val buf = ByteArray(16 * 1024)
        var total = 0L

        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) {
                throw BackupException("The backup contains an unexpectedly large file.")
            }
            sink.write(buf, 0, n)
        }
    }

    /** Reads the ZIP, validates it, and writes torrent/resume files to cache. */
    private fun stage(context: Context, input: InputStream): Staged {
        val dir = File(context.cacheDir, "backup_stage")
        dir.deleteRecursively()
        dir.mkdirs()

        var manifest: JSONObject? = null
        val prefs = LinkedHashMap<String, JSONObject>()
        val torrentFiles = LinkedHashMap<String, File>()
        val resumeFiles = LinkedHashMap<String, File>()

        try {
            ZipInputStream(input).use { zip ->
                var count = 0

                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue

                    count++
                    if (count > MAX_ENTRIES) {
                        throw BackupException("The backup contains too many files.")
                    }

                    val name = entry.name

                    if (name == "manifest.json") {
                        val bos = ByteArrayOutputStream()
                        readLimited(zip, MAX_TEXT_BYTES, bos)
                        manifest = try {
                            JSONObject(bos.toString("UTF-8"))
                        } catch (_: Exception) {
                            throw BackupException("The backup's manifest is damaged.")
                        }
                        continue
                    }

                    val prefMatch = prefNameRegex.find(name)
                    if (prefMatch != null) {
                        val prefName = prefMatch.groupValues[1]
                        val bos = ByteArrayOutputStream()
                        readLimited(zip, MAX_TEXT_BYTES, bos)
                        val obj = try {
                            JSONObject(bos.toString("UTF-8"))
                        } catch (_: Exception) {
                            throw BackupException("Settings file $prefName is damaged.")
                        }
                        // Only files this version knows about are used.
                        if (prefName in PREF_FILES) prefs[prefName] = obj
                        continue
                    }

                    val torrentMatch = torrentNameRegex.find(name)
                    if (torrentMatch != null) {
                        val f = File(dir, "t_" + torrentMatch.groupValues[1])
                        f.outputStream().use { readLimited(zip, MAX_TORRENT_BYTES, it) }
                        torrentFiles[name] = f
                        continue
                    }

                    val resumeMatch = resumeNameRegex.find(name)
                    if (resumeMatch != null) {
                        val hash = resumeMatch.groupValues[1]
                        val f = File(dir, "r_$hash")
                        f.outputStream().use { readLimited(zip, MAX_RESUME_BYTES, it) }
                        resumeFiles[hash] = f
                    }
                    // anything else is ignored
                }
            }
        } catch (e: BackupException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw BackupException("This is not a valid TorrentOr backup file.")
        }

        val m = manifest
        if (m == null || m.optString("format") != FORMAT_NAME) {
            dir.deleteRecursively()
            throw BackupException("This is not a TorrentOr backup file.")
        }

        val version = m.optInt("version", 0)
        if (version < 1) {
            dir.deleteRecursively()
            throw BackupException("The backup's version is not recognised.")
        }
        if (version > FORMAT_VERSION) {
            dir.deleteRecursively()
            throw BackupException(
                "This backup was made by a newer version of TorrentOr " +
                    "(format $version). Update the app to restore it."
            )
        }

        return Staged(dir, m, prefs, torrentFiles, resumeFiles)
    }

    /**
     * Restores [input]. replace = true makes this device match the backup;
     * false merges the backup into what is already here (existing values and
     * running torrents always win).
     *
     * Throws BackupException with a readable message; on any failure while
     * applying, the previous settings are put back.
     */
    fun restore(context: Context, input: InputStream, replace: Boolean): RestoreResult {
        val staged = stage(context, input)

        // Snapshot for rollback
        val snapshot = LinkedHashMap<String, JSONObject>()
        for (name in PREF_FILES) snapshot[name] = dumpPrefs(context, name)

        val restoredTorrentsDir = File(context.filesDir, "restored_torrents")
        val pendingDir = File(context.filesDir, "resume_data_pending")
        val createdTorrentFiles = ArrayList<File>()

        try {
            val localStore = snapshot["torrent_store"]
                ?.optJSONObject("entries")?.optString("v", "") ?: ""
            val localEntries = localStore.split("\n").filter { it.isNotBlank() }
            val localHashes = localEntries.mapNotNull { entryHash(it) }.toSet()

            // ---- torrent list: resolve embedded .torrent files
            val backupStoreText = staged.prefs["torrent_store"]
                ?.optJSONObject("entries")?.optString("v", "") ?: ""
            val resolved = ArrayList<String>()
            var skipped = 0
            val backupHashes = LinkedHashSet<String>()
            var stamp = 0

            restoredTorrentsDir.mkdirs()

            for (entry in backupStoreText.split("\n").filter { it.isNotBlank() }) {
                val parts = entry.split("||", limit = 4)

                when (parts.getOrNull(0)) {
                    "MAGNET" -> {
                        resolved.add(entry)
                        entryHash(entry)?.let { backupHashes.add(it) }
                    }

                    "FILE" -> {
                        val pathIndex = if (parts.size >= 4) 2 else 1
                        val marker = parts.getOrNull(pathIndex) ?: ""
                        val src = if (marker.startsWith("@")) {
                            staged.torrentFiles[marker.substring(1)]
                        } else {
                            null
                        }

                        if (src == null) {
                            skipped++
                        } else {
                            val h = entryHash(entry) ?: ("n" + stamp)
                            stamp++
                            val target = File(
                                restoredTorrentsDir,
                                "restored_${h}_${System.currentTimeMillis()}.torrent"
                            )
                            src.copyTo(target, overwrite = true)
                            createdTorrentFiles.add(target)

                            val rewritten = parts.toMutableList()
                            rewritten[pathIndex] = target.absolutePath
                            resolved.add(rewritten.joinToString("||"))
                            entryHash(entry)?.let { backupHashes.add(it) }
                        }
                    }
                }
            }

            // ---- settings
            for ((name, dump) in staged.prefs) {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

                if (name == "torrent_store") {
                    val merged = ArrayList<String>()

                    if (replace) {
                        merged.addAll(resolved)
                    } else {
                        merged.addAll(localEntries)
                        for (e in resolved) {
                            val h = entryHash(e)
                            if (h == null || h !in localHashes) merged.add(e)
                        }
                    }

                    val ed = prefs.edit()
                    if (replace) ed.clear()
                    ed.putString("entries", merged.joinToString("\n"))
                    if (!ed.commit()) throw BackupException("Could not write the torrent list.")
                    continue
                }

                if (replace) {
                    val ed = prefs.edit().clear()
                    applyDump(ed, dump, null)
                    if (!ed.commit()) throw BackupException("Could not write settings ($name).")
                } else {
                    // A merge never brings old "deleted" flags along.
                    if (name == "deleted_torrents") continue

                    val ed = prefs.edit()
                    applyDump(ed, dump, prefs)
                    if (name == "rss_feeds") mergeFeeds(ed, prefs, dump)
                    if (!ed.commit()) throw BackupException("Could not write settings ($name).")
                }
            }

            // Torrents that come back must not stay flagged as deleted.
            val deleted = context.getSharedPreferences("deleted_torrents", Context.MODE_PRIVATE)
            val dEd = deleted.edit()
            for (h in backupHashes) dEd.remove(h)
            dEd.commit()

            // ---- resume data: staged for the service, which moves it into
            // place once the old torrents are out of the session.
            pendingDir.deleteRecursively()
            pendingDir.mkdirs()
            var resumeCount = 0

            for ((hash, file) in staged.resumeFiles) {
                if (!replace && hash in localHashes) continue
                file.copyTo(File(pendingDir, "$hash.fastresume"), overwrite = true)
                resumeCount++
            }

            val added = backupHashes.count { it !in localHashes }

            return RestoreResult(
                torrentsInBackup = backupHashes.size + skipped,
                torrentsAdded = if (replace) backupHashes.size else added,
                torrentsAlreadyPresent = if (replace) 0 else backupHashes.size - added,
                torrentsSkipped = skipped,
                resumeFiles = resumeCount,
                settingsFiles = staged.prefs.size
            )
        } catch (e: Exception) {
            // Roll back: put every settings file back as it was.
            for ((name, dump) in snapshot) {
                try {
                    val ed = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                        .edit().clear()
                    applyDump(ed, dump, null)
                    ed.commit()
                } catch (_: Exception) {
                }
            }
            createdTorrentFiles.forEach { it.delete() }
            pendingDir.deleteRecursively()

            if (e is BackupException) throw e
            throw BackupException("Restore failed and was undone: ${e.message}")
        } finally {
            staged.dir.deleteRecursively()
        }
    }

    /** onlyMissing != null: keys already in it are left alone. */
    private fun applyDump(
        ed: SharedPreferences.Editor,
        dump: JSONObject,
        onlyMissing: SharedPreferences?
    ) {
        val keys = dump.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            if (onlyMissing != null && onlyMissing.contains(key)) continue

            val o = dump.optJSONObject(key) ?: continue

            when (o.optString("t")) {
                "b" -> ed.putBoolean(key, o.optBoolean("v"))
                "i" -> ed.putInt(key, o.optInt("v"))
                "l" -> ed.putLong(key, o.optLong("v"))
                "f" -> ed.putFloat(key, o.optDouble("v").toFloat())
                "s" -> ed.putString(key, o.optString("v"))
                "ss" -> {
                    val arr = o.optJSONArray("v") ?: JSONArray()
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.optString(i))
                    ed.putStringSet(key, set)
                }
            }
        }
    }

    // RSS feed list is one newline-separated string: union instead of skip.
    private fun mergeFeeds(
        ed: SharedPreferences.Editor,
        local: SharedPreferences,
        dump: JSONObject
    ) {
        val theirs = dump.optJSONObject("feeds")?.optString("v", "") ?: return
        val mine = local.getString("feeds", "") ?: ""
        val lines = LinkedHashSet<String>()
        mine.split("\n").filter { it.isNotBlank() }.forEach { lines.add(it) }
        theirs.split("\n").filter { it.isNotBlank() }.forEach { lines.add(it) }
        ed.putString("feeds", lines.joinToString("\n"))
    }

    // ------------------------------------------------------------ hash utils

    /** Lower-case 40-char hex info-hash of a torrent_store entry, or null. */
    private fun entryHash(entry: String): String? {
        val parts = entry.split("||", limit = 4)

        return when (parts.getOrNull(0)) {
            "MAGNET" -> {
                val m = Regex("btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
                    .find(parts.getOrNull(1) ?: "")
                normalizeHash(m?.groupValues?.get(1) ?: "")
            }
            "FILE" -> if (parts.size >= 4) normalizeHash(parts[1]) else null
            else -> null
        }
    }

    private fun normalizeHash(raw: String): String? {
        val s = raw.trim()

        if ((s.length == 40 || s.length == 64) && s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return s.lowercase(Locale.ROOT)
        }

        if (s.length == 32) {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var bits = 0
            var value = 0
            val sb = StringBuilder()

            for (c in s.uppercase(Locale.ROOT)) {
                val idx = alphabet.indexOf(c)
                if (idx < 0) return null
                value = ((value shl 5) or idx) and 0xFFFF
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    sb.append("%02x".format((value shr bits) and 0xFF))
                }
            }

            return if (sb.length == 40) sb.toString() else null
        }

        return null
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0L) return "unknown"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
