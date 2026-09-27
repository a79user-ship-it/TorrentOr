package com.example.torrentor

import org.json.JSONObject

class YtsProvider : SearchProvider {
    override val name: String = "YTS"

    companion object {
        // YTS has changed domains before (yts.mx -> web.yts.gg) and may again.
        // Try each one in order and only fail if all of them fail.
        private val DOMAINS = listOf(
            "web.yts.gg",
            "yts.mx"
        )
    }

    override fun search(query: String): List<TorrentSearchResult> {
        var lastError: Exception? = null
        for (domain in DOMAINS) {
            try {
                return searchDomain(domain, query)
            } catch (e: Exception) {
                lastError = e
                AppLog.info("YTS: $domain failed (${e.message}), trying next domain")
            }
        }
        throw lastError ?: Exception("no YTS domain reachable")
    }

    private fun searchDomain(domain: String, query: String): List<TorrentSearchResult> {
        val url = "https://$domain/api/v2/list_movies.json" +
                "?query_term=${SearchHttp.encode(query)}&limit=20"
        val body = SearchHttp.get(url)

        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            val snippet = body.trim().take(80).replace("\n", " ")
            throw Exception("$domain did not return JSON, got: \"$snippet...\" - may be blocked on this network")
        }

        if (root.optString("status") != "ok") {
            throw Exception("$domain: unexpected response: ${root.optString("status_message", "no message")}")
        }

        val data = root.optJSONObject("data")
        if (data == null) {
            AppLog.warning("YTS: $domain response had no \"data\" object for query \"$query\"")
            return emptyList()
        }

        val movieCount = data.optInt("movie_count", -1)
        val movies = data.optJSONArray("movies")
        if (movies == null || movies.length() == 0) {
            AppLog.info("YTS: 0 results for \"$query\" via $domain (server reports movie_count=$movieCount)")
            return emptyList()
        }

        AppLog.info("YTS: $domain succeeded, movie_count=$movieCount")

        val results = mutableListOf<TorrentSearchResult>()
        for (i in 0 until movies.length()) {
            val movie = movies.getJSONObject(i)
            val title = movie.optString("title_long", movie.optString("title", "Untitled"))
            val pageUrl = movie.optString("url", "")
            val torrents = movie.optJSONArray("torrents") ?: continue

            for (j in 0 until torrents.length()) {
                val torrent = torrents.getJSONObject(j)
                val hash = torrent.optString("hash", "")
                val quality = torrent.optString("quality", "")
                val type = torrent.optString("type", "")
                if (hash.isBlank()) continue

                val displayName = if (quality.isBlank()) title else "$title [$quality]"

                results.add(
                    TorrentSearchResult(
                        name = if (type.isBlank()) displayName else "$displayName [$type]",
                        sourceName = name,
                        sizeBytes = torrent.optLong("size_bytes", 0L),
                        sizeText = torrent.optString("size", ""),
                        seeders = torrent.optInt("seeds", -1),
                        leechers = torrent.optInt("peers", -1),
                        publicationDate = torrent.optString("date_uploaded", ""),
                        category = "Movies",
                        infoHash = hash,
                        magnetUri = SearchHttp.magnetFromHash(hash, displayName),
                        torrentUrl = torrent.optString("url", ""),
                        resultPageUrl = pageUrl
                    )
                )
            }
        }
        return results
    }
}
