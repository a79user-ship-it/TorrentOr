package com.example.torrentor

/**
 * eztvx.to (an EZTV mirror) has no public search API - only a website -
 * so this parses its search results page with plain regex, the same
 * approach used for Provider1337x.
 *
 * FRAGILE BY NATURE: if eztvx.to changes its page layout, only this
 * provider breaks (the others are unaffected) until the pattern below is
 * updated. Unlike 1337x, EZTV's results table normally includes the
 * magnet link directly in the row, so no extra page fetch is needed to
 * add a result.
 *
 * This regex was written from EZTV's known classic table layout, not
 * tested against a live response (this environment has no internet
 * access). If it returns 0 results for a query you know has matches,
 * check the Execution Log for the "EZTV: no rows matched" diagnostic
 * line - it logs a snippet of the actual page so the pattern can be
 * corrected against what the site is really sending back.
 */
class EztvProvider : SearchProvider {
    override val name: String = "EZTV"

    companion object {
        // One row per capture: episode page path, name, magnet href, size, seeders.
        private val ROW_PATTERN = Regex(
            """<a href="(/ep/\d+/[^"]+)"[^>]*class="epinfo"[^>]*>([^<]+)</a>.*?""" +
                    """href="(magnet:\?[^"]+)"[^>]*title="Magnet Link".*?""" +
                    """<td[^>]*>\s*([\d.,]+\s*[KMGT]?B)\s*</td>.*?""" +
                    """<font[^>]*>\s*(\d+)\s*</font>""",
            RegexOption.DOT_MATCHES_ALL
        )
    }

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://eztvx.to/search/${SearchHttp.encode(query)}"
        val body = SearchHttp.get(url)

        val matches = ROW_PATTERN.findAll(body).toList()

        if (matches.isEmpty()) {
            // Either genuinely no results, or the page layout changed and
            // the pattern above no longer matches anything.
            val snippet = body.trim().take(200).replace("\n", " ")
            AppLog.info("EZTV: no rows matched for \"$query\" (page snippet: \"$snippet...\")")
            return emptyList()
        }

        val results = mutableListOf<TorrentSearchResult>()

        for (match in matches) {
            val (path, rawName, magnet, size, seeds) = match.destructured
            val title = unescapeHtml(rawName)
            val hash = extractHash(magnet)

            results.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeText = size.trim(),
                    seeders = seeds.toIntOrNull() ?: -1,
                    infoHash = hash,
                    magnetUri = magnet,
                    resultPageUrl = "https://eztvx.to$path"
                )
            )
        }

        return results
    }

    private fun extractHash(magnet: String): String {
        val match = Regex("btih:([a-fA-F0-9]{32,40})").find(magnet)
        return match?.groupValues?.get(1) ?: ""
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
