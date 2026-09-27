package com.example.torrentor

/**
 * One normalized search result, regardless of which provider it came from.
 * Only fields a provider actually supplied are filled in; everything else
 * stays blank/zero rather than being guessed.
 */
data class TorrentSearchResult(
    val name: String,
    val sourceName: String,
    val sizeBytes: Long = 0L,
    val sizeText: String = "",
    val seeders: Int = -1,
    val leechers: Int = -1,
    val publicationDate: String = "",
    val category: String = "",
    val infoHash: String = "",
    val magnetUri: String = "",
    val torrentUrl: String = "",
    val resultPageUrl: String = ""
) {
    // Used to spot the same torrent returned by more than one provider.
    // Falls back to a name+size fingerprint when no info hash is available.
    fun dedupeKey(): String {
        val hash = infoHash.trim().lowercase()

        if (hash.length >= 32) {
            return "hash:$hash"
        }

        return "ns:" + name.trim().lowercase() + ":" + sizeBytes
    }
}
