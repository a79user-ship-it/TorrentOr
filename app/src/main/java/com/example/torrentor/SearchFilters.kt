package com.example.torrentor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The Online Search filter set. A value of 0 (or blank/empty) means
 * "no limit" for that field. Results whose seeders/leechers/size/date are
 * unknown are NOT hidden by a numeric limit - a provider that simply does
 * not report a value should not make its results vanish.
 */
data class SearchFilters(
    val minSeeders: Int = 0,
    val maxLeechers: Int = 0,
    val minSizeMb: Long = 0L,
    val maxSizeMb: Long = 0L,
    val minHealth: Int = 0,
    val providers: Set<String> = emptySet(),
    val category: String = "",
    val fileType: String = ""
) {
    fun isActive(): Boolean {
        return minSeeders > 0 || maxLeechers > 0 || minSizeMb > 0 || maxSizeMb > 0 ||
                minHealth > 0 || providers.isNotEmpty() || category.isNotBlank() ||
                fileType.isNotBlank()
    }

    // Health/availability = seeders + leechers (known values only); a
    // result with neither value known has unknown health and passes.
    fun matches(r: TorrentSearchResult): Boolean {
        if (providers.isNotEmpty() &&
            r.sourceName !in providers &&
            r.alsoFoundOn.none { it in providers }
        ) return false

        if (minSeeders > 0 && r.seeders >= 0 && r.seeders < minSeeders) return false
        if (maxLeechers > 0 && r.leechers >= 0 && r.leechers > maxLeechers) return false

        if (r.sizeBytes > 0L) {
            val mb = r.sizeBytes / (1024L * 1024L)
            if (minSizeMb > 0 && mb < minSizeMb) return false
            if (maxSizeMb > 0 && mb > maxSizeMb) return false
        }

        if (minHealth > 0 && (r.seeders >= 0 || r.leechers >= 0)) {
            val health = maxOf(r.seeders, 0) + maxOf(r.leechers, 0)
            if (health < minHealth) return false
        }

        if (category.isNotBlank()) {
            val c = r.category.trim().ifBlank { UNCATEGORIZED }
            if (!c.equals(category, ignoreCase = true)) return false
        }

        if (fileType.isNotBlank() && r.fileType() != fileType) return false

        return true
    }

    fun toJson(): String {
        return JSONObject()
            .put("minSeeders", minSeeders)
            .put("maxLeechers", maxLeechers)
            .put("minSizeMb", minSizeMb)
            .put("maxSizeMb", maxSizeMb)
            .put("minHealth", minHealth)
            .put("providers", JSONArray(providers.toList()))
            .put("category", category)
            .put("fileType", fileType)
            .toString()
    }

    companion object {
        const val UNCATEGORIZED = "Uncategorized"
        val FILE_TYPES = listOf("Video", "Audio", "Books", "Games", "Software", "Other")

        val SORT_MODES = listOf(
            "Relevance", "Seeders", "Leechers", "Best ratio", "Largest",
            "Smallest", "Newest", "Oldest", "Provider", "Name"
        )

        private const val PREFS = "online_search_settings"

        fun fromJson(raw: String): SearchFilters {
            return try {
                val o = JSONObject(raw)
                val list = o.optJSONArray("providers")
                val set = mutableSetOf<String>()

                if (list != null) {
                    for (i in 0 until list.length()) set.add(list.optString(i))
                }

                SearchFilters(
                    minSeeders = o.optInt("minSeeders", 0).coerceAtLeast(0),
                    maxLeechers = o.optInt("maxLeechers", 0).coerceAtLeast(0),
                    minSizeMb = o.optLong("minSizeMb", 0L).coerceAtLeast(0L),
                    maxSizeMb = o.optLong("maxSizeMb", 0L).coerceAtLeast(0L),
                    minHealth = o.optInt("minHealth", 0).coerceAtLeast(0),
                    providers = set,
                    category = o.optString("category", ""),
                    fileType = o.optString("fileType", "")
                )
            } catch (_: Throwable) {
                SearchFilters()
            }
        }

        fun load(context: Context): SearchFilters {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("search_filters", "") ?: ""
            return if (raw.isBlank()) SearchFilters() else fromJson(raw)
        }

        fun save(context: Context, filters: SearchFilters) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("search_filters", filters.toJson()).apply()
        }

        fun loadSortMode(context: Context): String {
            val s = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("search_sort_mode", "Seeders") ?: "Seeders"
            return if (s in SORT_MODES) s else "Seeders"
        }

        fun saveSortMode(context: Context, mode: String) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("search_sort_mode", mode).apply()
        }

        // 0.0 .. 1.0 + small bonuses: how well a result name matches the
        // query words. Used only for the "Relevance" sort.
        fun relevance(query: String, name: String): Double {
            val words = query.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return 0.0

            val n = name.lowercase(Locale.ROOT)
            val nWords = n.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

            var score = 0.0
            for (w in words) {
                score += when {
                    w in nWords -> 1.0
                    n.contains(w) -> 0.6
                    else -> 0.0
                }
            }
            score /= words.size

            if (n.contains(words.joinToString(" "))) score += 0.5
            if (n.startsWith(words[0])) score += 0.1
            return score
        }

        // Sorts with unknown values (-1 / 0) always last, whatever the
        // direction, and breaks ties by seeders then name so the order is
        // stable.
        fun sort(
            list: List<TorrentSearchResult>,
            mode: String,
            query: String,
            favorites: Set<String> = emptySet()
        ): List<TorrentSearchResult> {
            val tie = compareByDescending<TorrentSearchResult> { it.seeders }
                .thenBy { it.name.lowercase(Locale.ROOT) }

            val dateCache = java.util.IdentityHashMap<TorrentSearchResult, Long>()
            val relCache = java.util.IdentityHashMap<TorrentSearchResult, Double>()
            fun date(r: TorrentSearchResult): Long = dateCache.getOrPut(r) { r.dateMillis() }
            fun rel(r: TorrentSearchResult): Double = relCache.getOrPut(r) { relevance(query, r.name) }

            fun ratio(r: TorrentSearchResult): Double {
                return if (r.seeders < 0) -1.0 else r.seeders.toDouble() / (maxOf(r.leechers, 0) + 1)
            }

            val primary: Comparator<TorrentSearchResult> = when (mode) {
                "Relevance" -> compareByDescending<TorrentSearchResult> { rel(it) }
                "Leechers" -> compareByDescending<TorrentSearchResult> { it.leechers }
                "Best ratio" -> compareByDescending<TorrentSearchResult> { ratio(it) }
                "Largest" -> compareByDescending<TorrentSearchResult> { it.sizeBytes }
                "Smallest" -> Comparator { a, b ->
                    when {
                        a.sizeBytes <= 0L && b.sizeBytes <= 0L -> 0
                        a.sizeBytes <= 0L -> 1
                        b.sizeBytes <= 0L -> -1
                        else -> a.sizeBytes.compareTo(b.sizeBytes)
                    }
                }
                "Newest" -> compareByDescending<TorrentSearchResult> { date(it) }
                "Oldest" -> Comparator { a, b ->
                    val x = date(a)
                    val y = date(b)
                    when {
                        x <= 0L && y <= 0L -> 0
                        x <= 0L -> 1
                        y <= 0L -> -1
                        else -> x.compareTo(y)
                    }
                }
                "Provider" -> Comparator { a, b ->
                    val fa = if (a.sourceName in favorites) 0 else 1
                    val fb = if (b.sourceName in favorites) 0 else 1
                    if (fa != fb) fa - fb
                    else a.sourceName.lowercase(Locale.ROOT)
                        .compareTo(b.sourceName.lowercase(Locale.ROOT))
                }
                "Name" -> compareBy<TorrentSearchResult> { it.name.lowercase(Locale.ROOT) }
                else -> compareByDescending<TorrentSearchResult> { it.seeders }
            }

            // relevance is expensive-ish; precompute is not needed at this size
            return list.sortedWith(primary.then(tie))
        }
    }
}
