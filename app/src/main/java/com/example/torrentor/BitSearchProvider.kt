package com.example.torrentor

import org.json.JSONArray
import org.json.JSONObject

/**
 * bitsearch.eu - has a documented public JSON API (GET /api/v1/search),
 * no API key required on the free tier (200 requests/day per IP). General
 * purpose, like SolidTorrents and The Pirate Bay above.
 *
 * The search endpoint's own response doesn't include a ready-made magnet
 * link (only /api/v1/torrent/:id does), so the magnet is built from the
 * returned infohash the same way PirateBayProvider/SolidTorrentsProvider
 * already do.
 */
class BitSearchProvider : SearchProvider {
    override val name: String = "BitSearch"

    companion object {
        private val CATEGORY_NAMES = mapOf(
            1 to "Other",
            2 to "Movies",
            3 to "TV",
            4 to "Anime",
            5 to "Softwares",
            6 to "Games",
            7 to "Music",
            8 to "AudioBook",
            9 to "Ebook/Course",
            10 to "XXX"
        )
    }

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://bitsearch.eu/api/v1/search" +
                "?q=${SearchHttp.encode(query)}&limit=20"

        val body = SearchHttp.get(url)
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: JSONArray()

        val list = mutableListOf<TorrentSearchResult>()

        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val hash = item.optString("infohash", "")

            if (hash.isBlank()) continue

            val title = item.optString("title", "Untitled")
            val id = item.optString("id", "")
            val createdAt = item.optString("createdAt", "")
            val categoryId = item.optInt("category", -1)

            list.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeBytes = item.optLong("size", 0L),
                    seeders = item.optInt("seeders", -1),
                    leechers = item.optInt("leechers", -1),
                    publicationDate = if (createdAt.length >= 10) {
                        createdAt.substring(0, 10)
                    } else {
                        createdAt
                    },
                    category = CATEGORY_NAMES[categoryId] ?: "",
                    infoHash = hash,
                    magnetUri = SearchHttp.magnetFromHash(hash, title),
                    torrentUrl = "",
                    resultPageUrl = if (id.isNotBlank()) {
                        "https://bitsearch.eu/torrent/$id"
                    } else {
                        ""
                    }
                )
            )
        }

        return list
    }
}
