package com.example.torrentor

/**
 * 1337x.to has no public API, only a website. This parses its search
 * results page with plain regex rather than adding an HTML-parsing library.
 *
 * FRAGILE BY NATURE: if 1337x changes its page layout, only this provider
 * breaks (the others are unaffected) until the patterns below are updated.
 * The domain is also blocked on some ISPs/networks, the same as YTS.
 *
 * The search results page does not include a magnet link, only a link to
 * each torrent's own page. So results from here have resultPageUrl set but
 * magnetUri/torrentUrl left blank; OnlineSearchAdder resolves the magnet
 * from that page the moment the user taps Add, not during the search
 * itself (fetching all result pages up front would be slow and heavy).
 */
class Provider1337x : SearchProvider {
    override val name: String = "1337x"

    companion object {
        // One capture group per result row: page path, name, seeds, leech, date, size.
        private val ROW_PATTERN = Regex(
            """<a href="(/torrent/\d+/[^"]+)"[^>]*>\s*([^<]+?)\s*</a>.*?""" +
                    """<td class="coll-2 seeds">\s*(\d+)\s*</td>\s*""" +
                    """<td class="coll-3 leeches"[^>]*>\s*(\d+)\s*</td>\s*""" +
                    """<td class="coll-date">([^<]+)</td>\s*""" +
                    """<td class="coll-4 size[^"]*">([^<]+)""",
            RegexOption.DOT_MATCHES_ALL
        )

        // Extracts the magnet link from a torrent's own page (used by
        // OnlineSearchAdder, not during search).
        val MAGNET_PATTERN = Regex("""href="(magnet:\?[^"]+)"""")
    }

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://1337x.to/search/${SearchHttp.encode(query)}/1/"
        val body = SearchHttp.get(url)

        val matches = ROW_PATTERN.findAll(body).toList()

        if (matches.isEmpty()) {
            // Either genuinely no results, or the page layout changed and
            // the pattern above no longer matches anything.
            return emptyList()
        }

        val results = mutableListOf<TorrentSearchResult>()

        for (match in matches) {
            val (path, rawName, seeds, leech, date, size) = match.destructured
            val title = unescapeHtml(rawName)

            results.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeText = size.trim(),
                    seeders = seeds.toIntOrNull() ?: -1,
                    leechers = leech.toIntOrNull() ?: -1,
                    publicationDate = date.trim(),
                    resultPageUrl = "https://1337x.to$path"
                )
            )
        }

        return results
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }
}
