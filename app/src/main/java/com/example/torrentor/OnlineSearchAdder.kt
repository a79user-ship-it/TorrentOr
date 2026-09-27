package com.example.torrentor

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Hands an online search result to TorrentOr's EXISTING magnet/.torrent
 * handling - the same TorrentService Intent path used everywhere else in
 * the app (RSS items, the manual magnet box, opening a .torrent file).
 * No second torrent engine, no new JNI.
 */
object OnlineSearchAdder {

    // Returns null on success, or an error message to show the user.
    fun add(context: Context, result: TorrentSearchResult): String? {
        val app = context.applicationContext

        if (result.magnetUri.isNotBlank()) {
            sendMagnet(app, result.magnetUri)
            AppLog.info("Search: magnet added: ${result.name} (${result.sourceName})")
            return null
        }

        if (result.torrentUrl.isNotBlank()) {
            return try {
                val file = downloadTorrentFile(app, result.torrentUrl, result.name)
                sendTorrentFile(app, file.absolutePath)
                AppLog.info("Search: torrent file added: ${result.name} (${result.sourceName})")
                null
            } catch (e: Throwable) {
                val message = e.message ?: e.javaClass.simpleName
                AppLog.error("Search: could not add ${result.name}: $message")
                message
            }
        }

        // Some providers (1337x) only give a page URL in their search
        // results; the magnet link is only on the torrent's own page.
        if (result.resultPageUrl.isNotBlank()) {
            return try {
                val magnet = resolveMagnetFromPage(result.resultPageUrl)
                    ?: return "Could not find a magnet link on this result's page"

                sendMagnet(app, magnet)
                AppLog.info("Search: magnet added: ${result.name} (${result.sourceName})")
                null
            } catch (e: Throwable) {
                val message = e.message ?: e.javaClass.simpleName
                AppLog.error("Search: could not add ${result.name}: $message")
                message
            }
        }

        return "This result has no magnet link or .torrent file"
    }

    @Throws(Exception::class)
    private fun resolveMagnetFromPage(pageUrl: String): String? {
        val html = SearchHttp.get(pageUrl)
        val match = Provider1337x.MAGNET_PATTERN.find(html) ?: return null
        return match.groupValues[1]
    }

    private fun sendMagnet(app: Context, magnet: String) {
        val intent = Intent(app, TorrentService::class.java)
        intent.putExtra("MAGNET", magnet)
        startService(app, intent)
    }

    private fun sendTorrentFile(app: Context, path: String) {
        val intent = Intent(app, TorrentService::class.java)
        intent.putExtra("TORRENT_PATH", path)
        startService(app, intent)
    }

    private fun startService(app: Context, intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    @Throws(Exception::class)
    private fun downloadTorrentFile(app: Context, url: String, displayName: String): File {
        val body = SearchHttp.getBytes(url)

        val dir = File(app.filesDir, "search_torrents")
        dir.mkdirs()

        val safeName = displayName
            .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
            .take(80)
            .ifBlank { "torrent" }

        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.torrent")
        file.writeBytes(body)

        return file
    }
}
