package com.example.torrentor

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tiny HTTP helper shared by the search providers, so each provider file
 * only has to deal with parsing its own response format.
 */
object SearchHttp {

    fun encode(text: String): String {
        return URLEncoder.encode(text, "UTF-8")
    }

    // Blocking GET. Throws on a non-2xx response or a network error - the
    // caller (a provider, via OnlineSearchManager) is expected to catch it.
    @Throws(Exception::class)
    fun get(url: String, timeoutMs: Int = 10000): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TorrentOr/1.1")
        connection.setRequestProperty("Accept", "application/json, application/xml, */*")

        try {
            val code = connection.responseCode

            if (code !in 200..299) {
                throw Exception("HTTP $code")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    // Blocking GET for binary content (a .torrent file).
    @Throws(Exception::class)
    fun getBytes(url: String, timeoutMs: Int = 15000): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TorrentOr/1.1")

        try {
            val code = connection.responseCode

            if (code !in 200..299) {
                throw Exception("HTTP $code")
            }

            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    // Builds a magnet URI from a bare 40-char (or 32-char base32) info hash,
    // for providers that give a hash but no ready-made magnet link.
    fun magnetFromHash(hash: String, displayName: String): String {
        val trimmed = hash.trim()

        if (trimmed.isBlank()) return ""

        val encodedName = encode(displayName)

        return "magnet:?xt=urn:btih:$trimmed&dn=$encodedName"
    }
}
