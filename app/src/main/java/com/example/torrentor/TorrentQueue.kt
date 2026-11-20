package com.example.torrentor

import android.content.Context

/**
 * Queue order of the torrent list (v2.09): Move to Top / Up / Down /
 * Bottom. Shared by the app and the Web UI so both show the same order.
 *
 * The order is a list of info-hashes (never list indexes) saved in the
 * "torrent_queue" prefs, key "order". Torrents that are not in the list
 * yet (newly added) count as being at the bottom, in their normal order.
 * It only changes the display order ("Queue Order" sort); the download
 * engine runs every torrent at once, as before.
 */
object TorrentQueue {

    const val SORT_MODE = "Queue Order"

    private const val PREFS = "torrent_queue"
    private const val KEY = "order"

    fun normalize(hash: String): String =
        hash.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    fun load(context: Context): List<String> {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""

        return text.split("\n").map { normalize(it) }.filter { it.isNotEmpty() }
    }

    /** hash -> position (0 = top). Unknown hashes are not in the map. */
    fun rankMap(context: Context): Map<String, Int> {
        val map = HashMap<String, Int>()

        load(context).forEachIndexed { i, h ->
            if (!map.containsKey(h)) map[h] = i
        }

        return map
    }

    /**
     * Moves [selected] torrents and saves the new order.
     *
     * allHashes = every torrent, in the engine's own order.
     * visible   = the ones currently shown (filter/search); Up/Down skip
     *             hidden torrents so a press always visibly moves.
     * dir       = "top", "up", "down" or "bottom".
     * Returns false if nothing was changed.
     */
    fun move(
        context: Context,
        dir: String,
        allHashes: List<String>,
        selected: Set<String>,
        visible: Set<String>
    ): Boolean {
        val all = allHashes.map { normalize(it) }.filter { it.isNotEmpty() }
        val sel = selected.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val vis = visible.map { normalize(it) }.toSet()

        if (all.isEmpty() || sel.none { it in all }) return false

        val rank = rankMap(context)

        // Current order: ranked ones first, the rest keep the engine order.
        val order = all.sortedBy { rank[it] ?: Int.MAX_VALUE }.toMutableList()
        val before = order.toList()

        when (dir) {
            "top" -> {
                val picked = order.filter { it in sel }
                val rest = order.filter { it !in sel }
                order.clear()
                order.addAll(picked)
                order.addAll(rest)
            }

            "bottom" -> {
                val picked = order.filter { it in sel }
                val rest = order.filter { it !in sel }
                order.clear()
                order.addAll(rest)
                order.addAll(picked)
            }

            "up" -> {
                for (i in 0 until order.size) {
                    val h = order[i]
                    if (h !in sel) continue

                    var j = i - 1
                    while (j >= 0 && order[j] !in vis) j--

                    if (j >= 0 && order[j] !in sel) {
                        order.removeAt(i)
                        order.add(j, h)
                    }
                }
            }

            "down" -> {
                for (i in order.size - 1 downTo 0) {
                    val h = order[i]
                    if (h !in sel) continue

                    var j = i + 1
                    while (j < order.size && order[j] !in vis) j++

                    if (j < order.size && order[j] !in sel) {
                        order.removeAt(i)
                        order.add(j, h)
                    }
                }
            }

            else -> return false
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, order.joinToString("\n"))
            .commit()

        return order != before
    }
}
