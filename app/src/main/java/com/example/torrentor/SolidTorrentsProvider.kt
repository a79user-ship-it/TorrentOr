package com.example.torrentor

import org.json.JSONArray
import org.json.JSONObject

/**
 * SolidTorrents - an open-source torrent meta-search engine with a
 * documented JSON search API (GET /api/v1/search?q=...). General purpose,
 * a useful fourth source alongside the category-specific providers above.
 */
class SolidTorrentsProvider : SearchProvider {
    override val name: String = "SolidTorrents"

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://solidtorrents.to/api/v1/search" +
                "?q=${SearchHttp.encode(query)}&limit=20"

        val body = SearchHttp.get(url)
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: JSONArray()

        val list = mutableListOf<TorrentSearchResult>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val hash = item.optString("infohash", item.optString("_id", ""))

            if (hash.isBlank()) continue

            val title = item.optString("title", "Untitled")
            val id = item.optString("_id", "")

            list.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeBytes = item.optLong("size", 0L),
                    seeders = item.optInt("swarm", item.optInt("seeders", -1)),
                    leechers = item.optInt("leechers", -1),
                    publicationDate = item.optString("imported", ""),
                    category = item.optString("category", ""),
                    infoHash = hash,
                    magnetUri = item.optString("magnet", SearchHttp.magnetFromHash(hash, title)),
                    torrentUrl = "",
                    resultPageUrl = if (id.isNotBlank()) {
                        "https://solidtorrents.to/view/$id"
                    } else {
                        ""
                    }
                )
            )
        }

        return list
    }
}
