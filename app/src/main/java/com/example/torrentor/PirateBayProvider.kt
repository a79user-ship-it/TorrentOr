package com.example.torrentor

import org.json.JSONArray

/**
 * apibay.org - the unofficial but widely relied-upon public JSON API behind
 * The Pirate Bay. No key required. General-purpose (any category).
 */
class PirateBayProvider : SearchProvider {
    override val name: String = "The Pirate Bay"

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://apibay.org/q.php?q=${SearchHttp.encode(query)}"
        val body = SearchHttp.get(url)
        val array = JSONArray(body)

        val results = mutableListOf<TorrentSearchResult>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val hash = item.optString("info_hash", "")
            val id = item.optString("id", "")

            // apibay returns one placeholder row {"id":"0", "name":"No results returned"}
            // when nothing matches - skip it rather than showing a fake result.
            if (hash.isBlank() || id == "0") continue

            val title = item.optString("name", "Untitled")
            val sizeBytes = item.optString("size", "0").toLongOrNull() ?: 0L
            val addedEpoch = item.optString("added", "").toLongOrNull()

            results.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeBytes = sizeBytes,
                    sizeText = formatBytes(sizeBytes),
                    seeders = item.optString("seeders", "-1").toIntOrNull() ?: -1,
                    leechers = item.optString("leechers", "-1").toIntOrNull() ?: -1,
                    publicationDate = if (addedEpoch != null && addedEpoch > 0) {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date(addedEpoch * 1000L))
                    } else {
                        ""
                    },
                    category = item.optString("category", ""),
                    infoHash = hash,
                    magnetUri = SearchHttp.magnetFromHash(hash, title),
                    torrentUrl = "",
                    resultPageUrl = if (id.isNotBlank()) {
                        "https://thepiratebay.org/description.php?id=$id"
                    } else {
                        ""
                    }
                )
            )
        }

        return results
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""

        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0

        return if (gb >= 1) {
            String.format("%.2f GB", gb)
        } else {
            String.format("%.2f MB", mb)
        }
    }
}
