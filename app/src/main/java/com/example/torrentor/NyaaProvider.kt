package com.example.torrentor

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * nyaa.si - anime and East Asian media tracker. No JSON API, but its search
 * page has a documented, stable RSS mode (?page=rss&q=...), which is the
 * supported way to consume it programmatically rather than scraping HTML.
 */
class NyaaProvider : SearchProvider {
    override val name: String = "Nyaa"

    override fun search(query: String): List<TorrentSearchResult> {
        val url = "https://nyaa.si/?page=rss&q=${SearchHttp.encode(query)}&s=seeders&o=desc"
        val body = SearchHttp.get(url)

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(body))

        val results = mutableListOf<TorrentSearchResult>()

        var inItem = false
        var title = ""
        var link = ""
        var guid = ""
        var pubDate = ""
        var infoHash = ""
        var seeders = -1
        var leechers = -1
        var sizeText = ""

        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "item" -> {
                            inItem = true
                            title = ""; link = ""; guid = ""; pubDate = ""; infoHash = ""
                            seeders = -1; leechers = -1; sizeText = ""
                        }
                        "title" -> if (inItem) title = parser.nextText()
                        "link" -> if (inItem) link = parser.nextText()
                        "guid" -> if (inItem) guid = parser.nextText()
                        "pubDate" -> if (inItem) pubDate = parser.nextText()
                        "nyaa:infoHash" -> if (inItem) infoHash = parser.nextText()
                        "nyaa:seeders" -> if (inItem) {
                            seeders = parser.nextText().toIntOrNull() ?: -1
                        }
                        "nyaa:leechers" -> if (inItem) {
                            leechers = parser.nextText().toIntOrNull() ?: -1
                        }
                        "nyaa:size" -> if (inItem) sizeText = parser.nextText()
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") {
                        if (title.isNotBlank() && infoHash.isNotBlank()) {
                            results.add(
                                TorrentSearchResult(
                                    name = title,
                                    sourceName = name,
                                    sizeText = sizeText,
                                    seeders = seeders,
                                    leechers = leechers,
                                    publicationDate = pubDate,
                                    infoHash = infoHash,
                                    magnetUri = SearchHttp.magnetFromHash(infoHash, title),
                                    torrentUrl = if (link.endsWith(".torrent")) link else "",
                                    resultPageUrl = guid.ifBlank { link }
                                )
                            )
                        }

                        inItem = false
                    }
                }
            }

            eventType = parser.next()
        }

        return results
    }
}
