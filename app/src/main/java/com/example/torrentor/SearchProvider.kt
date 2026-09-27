package com.example.torrentor

/**
 * One external search source (a public API, a Torznab indexer, an RSS-style
 * search endpoint, ...). Each provider is independent: OnlineSearchManager
 * runs them all and one provider failing never affects the others.
 */
interface SearchProvider {
    val name: String

    // Blocking. OnlineSearchManager calls this on a background thread.
    // Throw on failure (timeout, bad response, etc.) - the manager catches it
    // and reports it against this provider's name only.
    @Throws(Exception::class)
    fun search(query: String): List<TorrentSearchResult>
}
