package com.example.torrentor

/**
 * ext.to has no public API - only a website - so this parses its search
 * results page with plain regex, the same approach used for
 * Provider1337x and EztvProvider.
 *
 * FRAGILE BY NATURE, and more so than the others here: this pattern is a
 * best-effort guess at ext.to's results-table markup, written without
 * being able to fetch a live page from this environment (no internet
 * access in the build sandbox). If it returns 0 results for a query you
 * know has matches, check the Execution Log for the "ext.to: no rows
 * matched" line - it logs a snippet of the real page HTML. Send that
 * snippet back and the pattern below can be corrected in one pass, the
 * same way the YTS provider was fixed after its domain moved.
 */
class ExtToProvider : SearchProvider {
    override val name: String = "ext.to"

    companion object {
        // One row per capture: result page path, name, size, seeders, leechers.
        private val ROW_PATTERN = Regex(
            """<a[^>]*href="(/torrent/[^"]+)"[^>]*class="[^"]*title[^"]*"[^>]*>\s*([^<]+?)\s*</a>.*?""" +
                    """<td[^>]*class="[^"]*size[^"]*"[^>]*>\s*([^<]+?)\s*</td>.*?""" +
                    """<td[^>]*class="[^"]*seed[^"]*"[^>]*>\s*(\d+)\s*</td>.*?""" +
                    """<td[^>]*class="[^"]*leech[^"]*"[^>]*>\s*(\d+)\s*</td>""",
            RegexOption.DOT_MATCHES_ALL
        )

        // Pulled from a result's own page, the same way OnlineSearchAdder
        // resolves 1337x magnets on demand.
        val MAGNET_PATTERN = Regex("""href="(magnet:\?[^"]+)"""")
    }

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://ext.to/search/?q=${SearchHttp.encode(query)}"
        val body = SearchHttp.get(url)

        val matches = ROW_PATTERN.findAll(body).toList()

        if (matches.isEmpty()) {
            val snippet = body.trim().take(200).replace("\n", " ")
            AppLog.info("ext.to: no rows matched for \"$query\" (page snippet: \"$snippet...\")")
            return emptyList()
        }

        val results = mutableListOf<TorrentSearchResult>()

        for (match in matches) {
            val (path, rawName, size, seeds, leech) = match.destructured
            val title = unescapeHtml(rawName)

            results.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeText = size.trim(),
                    seeders = seeds.toIntOrNull() ?: -1,
                    leechers = leech.toIntOrNull() ?: -1,
                    resultPageUrl = if (path.startsWith("http")) path else "https://ext.to$path"
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
