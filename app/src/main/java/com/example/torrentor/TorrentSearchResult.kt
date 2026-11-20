package com.example.torrentor

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One normalized search result, regardless of which provider it came from.
 * Only fields a provider actually supplied are filled in; everything else
 * stays blank/zero rather than being guessed.
 *
 * alsoFoundOn lists other providers that returned the very same torrent
 * (same info hash); it is filled in by the merge step, never by providers.
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
    val resultPageUrl: String = "",
    val alsoFoundOn: List<String> = emptyList()
) {
    // The info hash as 40 lowercase hex characters, taken from the infoHash
    // field or, failing that, from the magnet link. Accepts 40-char hex and
    // 32-char base32 (both are valid in magnets). Blank when unknown.
    fun normalizedHash(): String {
        val fromField = hashFrom(infoHash)
        if (fromField.isNotEmpty()) return fromField

        val marker = "btih:"
        val idx = magnetUri.lowercase(Locale.ROOT).indexOf(marker)
        if (idx < 0) return ""

        val rest = magnetUri.substring(idx + marker.length)
        val end = rest.indexOfFirst { it == '&' || it == '#' }
        return hashFrom(if (end >= 0) rest.substring(0, end) else rest)
    }

    // Used to spot the same torrent returned by more than one provider.
    // Results with different info hashes are never treated as the same.
    // Falls back to a name+size fingerprint only when no hash is available.
    fun dedupeKey(): String {
        val hash = normalizedHash()

        if (hash.isNotEmpty()) {
            return "hash:$hash"
        }

        return "ns:" + name.trim().lowercase(Locale.ROOT) + ":" + sizeBytes
    }

    // Best-effort parse of publicationDate to epoch milliseconds, 0 when
    // unknown/unparseable. Providers use different formats (epoch, ISO,
    // yyyy-MM-dd, RFC-822); anything else is treated as unknown.
    fun dateMillis(): Long {
        val t = publicationDate.trim()
        if (t.isEmpty()) return 0L

        if (t.all { it.isDigit() }) {
            val v = t.toLongOrNull() ?: return 0L
            return if (t.length <= 10) v * 1000L else v
        }

        try {
            if (ISO_LIKE.containsMatchIn(t)) {
                val core = t.substring(0, 19).replace('T', ' ')
                return parseWith("yyyy-MM-dd HH:mm:ss", core, true)
            }

            if (DATE_ONLY.containsMatchIn(t)) {
                return parseWith("yyyy-MM-dd", t.substring(0, 10), true)
            }

            for (pattern in listOf(
                "EEE, dd MMM yyyy HH:mm:ss Z",
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "dd MMM yyyy"
            )) {
                val ms = parseWith(pattern, t, false)
                if (ms > 0L) return ms
            }
        } catch (_: Throwable) {
        }

        return 0L
    }

    // Rough content type guessed from the name and category, used only by
    // the "file type" filter. Returns "Other" when nothing matches.
    fun fileType(): String {
        val text = (name + " " + category).lowercase(Locale.ROOT)

        fun has(vararg words: String) = words.any { text.contains(it) }

        return when {
            has("1080p", "720p", "2160p", "480p", "x264", "x265", "h264", "h265", "hevc",
                "bluray", "blu-ray", "webrip", "web-dl", "hdtv", "dvdrip", "brrip",
                ".mkv", ".mp4", ".avi", "movie", "tv", "video", "anime") -> "Video"
            has("flac", "mp3", "aac", "ogg", "lossless", "discography", "album", "audio",
                "music", "soundtrack") -> "Audio"
            has("epub", "mobi", "azw", "pdf", "cbz", "cbr", "ebook", "book", "comic",
                "magazine") -> "Books"
            has("repack", "fitgirl", "game", "ps4", "ps5", "xbox", "switch") -> "Games"
            has(".exe", ".msi", ".apk", ".dmg", ".iso", "setup", "portable", "software",
                "windows", "linux", "ubuntu", "debian", "app") -> "Software"
            else -> "Other"
        }
    }

    // Folds a second copy of the same torrent (same dedupeKey) into this
    // one, keeping the best-known value of each field. The copy with more
    // seeders is the "primary" (its provider/links win); on a tie this
    // copy stays primary. Never called for results with different hashes.
    fun mergedWith(other: TorrentSearchResult): TorrentSearchResult {
        val primary = if (other.seeders > seeders) other else this
        val secondary = if (primary === this) other else this

        fun pick(a: String, b: String) = if (a.isNotBlank()) a else b

        val sources = LinkedHashSet<String>()
        sources.addAll(primary.alsoFoundOn)
        sources.addAll(secondary.alsoFoundOn)
        sources.add(secondary.sourceName)
        sources.remove(primary.sourceName)

        val datePrimary = if (primary.dateMillis() > 0L || secondary.dateMillis() == 0L) {
            primary.publicationDate
        } else {
            secondary.publicationDate
        }

        val bytes = if (primary.sizeBytes > 0L) primary.sizeBytes else secondary.sizeBytes

        return primary.copy(
            name = pick(primary.name, secondary.name),
            sizeBytes = bytes,
            sizeText = if (primary.sizeBytes > 0L || primary.sizeText.isNotBlank()) {
                pick(primary.sizeText, secondary.sizeText)
            } else {
                secondary.sizeText
            },
            seeders = maxOf(primary.seeders, secondary.seeders),
            leechers = maxOf(primary.leechers, secondary.leechers),
            publicationDate = pick(datePrimary, secondary.publicationDate),
            category = pick(primary.category, secondary.category),
            infoHash = pick(primary.infoHash, secondary.infoHash),
            magnetUri = pick(primary.magnetUri, secondary.magnetUri),
            torrentUrl = pick(primary.torrentUrl, secondary.torrentUrl),
            resultPageUrl = pick(primary.resultPageUrl, secondary.resultPageUrl),
            alsoFoundOn = sources.toList()
        )
    }

    companion object {
        private val ISO_LIKE = Regex("""^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}""")
        private val DATE_ONLY = Regex("""^\d{4}-\d{2}-\d{2}""")
        private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        private fun parseWith(pattern: String, text: String, utc: Boolean): Long {
            return try {
                val f = SimpleDateFormat(pattern, Locale.US)
                if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
                f.isLenient = false
                f.parse(text)?.time ?: 0L
            } catch (_: Throwable) {
                0L
            }
        }

        private fun hashFrom(raw: String): String {
            val s = raw.trim()

            if (s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return s.lowercase(Locale.ROOT)
            }

            if (s.length == 32) {
                val up = s.uppercase(Locale.ROOT)
                if (up.all { BASE32.indexOf(it) >= 0 }) {
                    var buffer = 0L
                    var bits = 0
                    val sb = StringBuilder()

                    for (c in up) {
                        buffer = (buffer shl 5) or BASE32.indexOf(c).toLong()
                        bits += 5

                        if (bits >= 8) {
                            bits -= 8
                            val byte = ((buffer shr bits) and 0xFF).toInt()
                            sb.append("%02x".format(byte))
                        }
                    }

                    return if (sb.length == 40) sb.toString() else ""
                }
            }

            return ""
        }
    }
}
