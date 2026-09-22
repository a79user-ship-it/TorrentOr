package com.example.torrentor

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RSS feeds: the feed list, the last fetched items of every feed, the
 * auto-refresh interval and the code that downloads and parses a feed.
 *
 * TorrentService calls maybeAutoRefresh() regularly, so feeds are refreshed
 * on the chosen interval even when the app screen is closed. The RSS screen
 * uses the same functions for the manual "Refresh now" button.
 */
object RssManager {

    data class RssItem(
        val title: String,
        val link: String,
        val sizeText: String,
        // the item's web page; searched for the torrent when the item is added
        val page: String = ""
    )

    data class RefreshResult(
        val feedsOk: Int,
        val feedsFailed: Int,
        val newItems: Int
    )

    // Minutes. 0 = auto refresh is off.
    val INTERVAL_OPTIONS = intArrayOf(0, 5, 15, 30, 60, 180, 360, 720, 1440)

    private const val DEFAULT_INTERVAL_MINUTES = 30
    private const val MAX_ITEMS_PER_FEED = 200

    private const val PREFS_FEEDS = "rss_feeds"
    private const val PREFS_SETTINGS = "rss_settings"
    private const val PREFS_CACHE = "rss_cache"
    private const val PREFS_AUTO = "rss_auto"
    private const val PREFS_SEEN = "rss_seen"
    private const val PREFS_ADDED = "rss_added"

    // Raised when the way feeds are read changes. The first refresh after that
    // treats what is in the feed as already known (nothing is auto-downloaded).
    private const val PARSER_VERSION = 2

    // how many "already handled" items are remembered per feed
    private const val MAX_SEEN = 2000

    // at most this many items are added automatically per refresh
    private const val MAX_AUTO_ADD_PER_REFRESH = 25

    private val refreshing = AtomicBoolean(false)
    private val seenLock = Any()

    // ---------------------------------------------------------------- feeds

    fun loadFeeds(context: Context): List<String> {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_FEEDS, Context.MODE_PRIVATE)
        val savedText = prefs.getString("feeds", "") ?: ""

        return savedText
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun saveFeeds(context: Context, feeds: List<String>) {
        context.applicationContext
            .getSharedPreferences(PREFS_FEEDS, Context.MODE_PRIVATE)
            .edit()
            .putString("feeds", feeds.distinct().joinToString("\n"))
            .commit()
    }

    // ---------------------------------------------------------------- items

    fun loadItems(context: Context, feed: String): List<RssItem> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            .getString("items_$feed", null) ?: return emptyList()

        return try {
            val array = JSONArray(raw)
            val items = mutableListOf<RssItem>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                items.add(
                    RssItem(
                        title = obj.optString("t", "Untitled"),
                        link = obj.optString("l", ""),
                        sizeText = obj.optString("s", ""),
                        page = obj.optString("p", "")
                    )
                )
            }

            items
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveItems(context: Context, feed: String, items: List<RssItem>) {
        val array = JSONArray()

        for (item in items.take(MAX_ITEMS_PER_FEED)) {
            array.put(
                JSONObject()
                    .put("t", item.title)
                    .put("l", item.link)
                    .put("s", item.sizeText)
                    .put("p", item.page)
            )
        }

        context.applicationContext
            .getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            .edit()
            .putString("items_$feed", array.toString())
            .apply()
    }

    fun removeItems(context: Context, feed: String) {
        context.applicationContext
            .getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
            .edit()
            .remove("items_$feed")
            .apply()
    }

    // Removes everything stored for a feed: items, auto-download setting and
    // the list of items that were already handled.
    fun removeFeedData(context: Context, feed: String) {
        val app = context.applicationContext

        removeItems(app, feed)

        app.getSharedPreferences(PREFS_AUTO, Context.MODE_PRIVATE)
            .edit()
            .remove("auto_$feed")
            .remove("baseline_$feed")
            .commit()

        app.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .remove("title_$feed")
            .remove("last_$feed")
            .remove("pv_$feed")
            .commit()

        synchronized(seenLock) {
            app.getSharedPreferences(PREFS_SEEN, Context.MODE_PRIVATE)
                .edit()
                .remove("seen_$feed")
                .commit()

            app.getSharedPreferences(PREFS_ADDED, Context.MODE_PRIVATE)
                .edit()
                .remove("added_$feed")
                .commit()
        }
    }

    // ------------------------------------------------ per-feed information

    // The feed's own title when it has been read, otherwise the web address.
    fun feedTitle(context: Context, feed: String): String {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getString("title_$feed", "") ?: ""

        if (saved.isNotBlank()) {
            return saved
        }

        return try {
            URL(feed).host.ifBlank { feed }
        } catch (_: Throwable) {
            feed
        }
    }

    fun lastFeedRefreshMillis(context: Context, feed: String): Long {
        return context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getLong("last_$feed", 0L)
    }

    // ---------------------------------------------- items added by the user

    private fun loadAdded(app: Context, feed: String): LinkedHashSet<String> {
        val raw = app.getSharedPreferences(PREFS_ADDED, Context.MODE_PRIVATE)
            .getString("added_$feed", "") ?: ""

        val set = LinkedHashSet<String>()

        for (part in raw.split("\n")) {
            if (part.isNotBlank()) {
                set.add(part)
            }
        }

        return set
    }

    // Ids of the items that were added to the downloads (by hand or by
    // auto-download). Used to show "Added" next to an item.
    fun addedKeys(context: Context, feed: String): Set<String> {
        return synchronized(seenLock) {
            loadAdded(context.applicationContext, feed)
        }
    }

    fun isAdded(keys: Set<String>, item: RssItem): Boolean {
        return keys.contains(fingerprint(item.link))
    }

    // The web page of an item on the torrent site, or "" when there is none.
    // Only http(s) addresses count, a magnet link is not a web page.
    fun itemWebPage(item: RssItem): String {
        val page = item.page.trim()

        if (page.startsWith("http://", ignoreCase = true) ||
            page.startsWith("https://", ignoreCase = true)
        ) {
            return page
        }

        val link = item.link.trim()

        // no page given: the link itself when it is a web address and not
        // a direct .torrent download
        if ((link.startsWith("http://", ignoreCase = true) ||
                    link.startsWith("https://", ignoreCase = true)) &&
            !link.contains(".torrent", ignoreCase = true)
        ) {
            return link
        }

        return ""
    }

    private fun markAdded(app: Context, feed: String, item: RssItem) {
        synchronized(seenLock) {
            val set = loadAdded(app, feed)
            set.add(fingerprint(item.link))

            var list = set.toList()

            if (list.size > MAX_SEEN) {
                list = list.takeLast(MAX_SEEN)
            }

            app.getSharedPreferences(PREFS_ADDED, Context.MODE_PRIVATE)
                .edit()
                .putString("added_$feed", list.joinToString("\n"))
                .commit()
        }
    }

    // ------------------------------------------------- auto-download setting

    fun isAutoDownload(context: Context, feed: String): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_AUTO, Context.MODE_PRIVATE)
            .getBoolean("auto_$feed", false)
    }

    /**
     * Turns "download every new item of this feed" on or off.
     *
     * When it is turned on, the items the feed already has are marked as
     * handled, so only items that appear afterwards are downloaded. (The
     * "Add all" button is for the existing ones.) If the feed has not been
     * fetched yet, the first fetch is used as that starting point.
     */
    fun setAutoDownload(context: Context, feed: String, enabled: Boolean) {
        val app = context.applicationContext

        val editor = app.getSharedPreferences(PREFS_AUTO, Context.MODE_PRIVATE).edit()
        editor.putBoolean("auto_$feed", enabled)

        if (enabled) {
            val cached = loadItems(app, feed)

            if (cached.isEmpty()) {
                editor.putBoolean("baseline_$feed", true)
            } else {
                markSeen(app, feed, cached)
                editor.remove("baseline_$feed")
            }
        } else {
            editor.remove("baseline_$feed")
        }

        editor.commit()
    }

    // An id for an item. Magnet links are identified by their info hash, so a
    // feed that changes the tracker list of a magnet does not look "new".
    private fun fingerprint(link: String): String {
        if (link.startsWith("magnet:", ignoreCase = true)) {
            val match = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(link)

            if (match != null) {
                return "b" + match.groupValues[1].lowercase()
            }
        }

        val digest = MessageDigest.getInstance("SHA-1")
            .digest(link.toByteArray(Charsets.UTF_8))

        return "h" + digest.take(8).joinToString("") { String.format("%02x", it) }
    }

    private fun loadSeen(app: Context, feed: String): LinkedHashSet<String> {
        val raw = app.getSharedPreferences(PREFS_SEEN, Context.MODE_PRIVATE)
            .getString("seen_$feed", "") ?: ""

        val set = LinkedHashSet<String>()

        for (part in raw.split("\n")) {
            if (part.isNotBlank()) {
                set.add(part)
            }
        }

        return set
    }

    // Remembers that these items were handled (added, or present when
    // auto-download was turned on), so they are never added automatically.
    fun markSeen(context: Context, feed: String, items: List<RssItem>) {
        val app = context.applicationContext

        synchronized(seenLock) {
            val set = loadSeen(app, feed)

            for (item in items) {
                set.add(fingerprint(item.link))
            }

            var list = set.toList()

            if (list.size > MAX_SEEN) {
                list = list.takeLast(MAX_SEEN)
            }

            app.getSharedPreferences(PREFS_SEEN, Context.MODE_PRIVATE)
                .edit()
                .putString("seen_$feed", list.joinToString("\n"))
                .commit()
        }
    }

    // ----------------------------------------------------------- add to list

    /**
     * Adds one feed item to the downloads. Blocking (a .torrent file or a web
     * page is downloaded first), so call it from a background thread.
     * Returns null when the item was handed to the engine, or the reason why
     * it could not be added.
     */
    fun addItem(context: Context, feed: String, item: RssItem): String? {
        val app = context.applicationContext
        AppLog.init(app)

        try {
            val target = resolveTarget(app, item)

            if (target.magnet != null) {
                sendToService(
                    app,
                    Intent(app, TorrentService::class.java).putExtra("MAGNET", target.magnet)
                )
            } else if (target.file != null) {
                sendToService(
                    app,
                    Intent(app, TorrentService::class.java)
                        .putExtra("TORRENT_PATH", target.file.absolutePath)
                )
            } else {
                throw Exception("no torrent or magnet link found")
            }

            markSeen(app, feed, listOf(item))
            markAdded(app, feed, item)
            AppLog.info("RSS: added to downloads: ${item.title}")

            return null
        } catch (e: Throwable) {
            val reason = e.message ?: e.javaClass.simpleName
            AppLog.error("RSS: could not add ${item.title}: $reason")

            return reason
        }
    }

    // What an item turned out to be: a magnet link or a saved .torrent file.
    private data class Target(val magnet: String?, val file: File?)

    // Finds something the engine can download for an item. The item's own
    // link is tried first, then the web page it came from.
    private fun resolveTarget(app: Context, item: RssItem): Target {
        val candidates = listOf(item.link.trim(), item.page.trim())
            .filter { it.isNotBlank() }
            .distinct()

        if (candidates.isEmpty()) {
            throw Exception("this item has no download link")
        }

        var lastReason = "no torrent or magnet link found"

        for (candidate in candidates) {
            try {
                return resolveAddress(app, candidate, 0)
            } catch (e: Exception) {
                lastReason = e.message ?: lastReason
            }
        }

        throw Exception(lastReason)
    }

    private fun resolveAddress(app: Context, address: String, depth: Int): Target {
        if (looksLikeMagnet(address)) {
            return Target(address.trim(), null)
        }

        if (!address.startsWith("http://", ignoreCase = true) &&
            !address.startsWith("https://", ignoreCase = true)
        ) {
            throw Exception("unsupported link: $address")
        }

        var current = address
        var redirects = 0

        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            // redirects are followed by hand: some sites redirect to a magnet link
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")

            try {
                val code = connection.responseCode

                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw Exception("redirect without a target")

                    if (looksLikeMagnet(location)) {
                        return Target(location.trim(), null)
                    }

                    redirects++

                    if (redirects > 5) {
                        throw Exception("too many redirects")
                    }

                    current = URL(URL(current), location).toString()
                    continue
                }

                if (code !in 200..299) {
                    throw Exception("HTTP $code")
                }

                val bytes = readLimited(connection.inputStream, 6 * 1024 * 1024)

                // a .torrent file is a bencoded dictionary: starts with 'd'
                // and contains an "info" entry
                if (
                    bytes.size >= 20 &&
                    bytes[0] == 'd'.code.toByte() &&
                    String(bytes, Charsets.ISO_8859_1).contains("4:info")
                ) {
                    val dir = File(app.filesDir, "rss_torrents")
                    dir.mkdirs()

                    // In the app's own files, not the cache: the torrent list
                    // points at this file and the cache can be cleared.
                    val file = File(dir, "rss_${fingerprint(current)}.torrent")
                    file.writeBytes(bytes)

                    return Target(null, file)
                }

                // Not a torrent file: a web page. Look for the torrent in it.
                val page = String(bytes, Charsets.UTF_8)

                val magnet = findMagnet(page)

                if (magnet != null) {
                    return Target(magnet, null)
                }

                if (depth < 2) {
                    val torrentAddress = findTorrentHref(page, current)

                    if (torrentAddress != null && torrentAddress != current) {
                        return resolveAddress(app, torrentAddress, depth + 1)
                    }
                }

                throw Exception("no torrent or magnet link found on the page")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)

            while (true) {
                val read = stream.read(buffer)

                if (read < 0) break

                output.write(buffer, 0, read)

                if (output.size() > limit) {
                    throw Exception("the download is too large")
                }
            }

            return output.toByteArray()
        }
    }

    private fun sendToService(app: Context, intent: Intent) {
        ContextCompat.startForegroundService(app, intent)
    }

    // Adds the items of an auto-download feed that were not handled before.
    private fun autoDownloadNewItems(app: Context, feed: String, items: List<RssItem>) {
        val autoPrefs = app.getSharedPreferences(PREFS_AUTO, Context.MODE_PRIVATE)

        // first fetch after turning auto-download on: this is the starting
        // point, what is in the feed now is not downloaded
        if (autoPrefs.getBoolean("baseline_$feed", false)) {
            markSeen(app, feed, items)
            autoPrefs.edit().remove("baseline_$feed").commit()

            AppLog.info(
                "RSS: $feed - auto-download is on, ${items.size} existing item(s) are skipped"
            )

            return
        }

        val seen = synchronized(seenLock) { loadSeen(app, feed) }
        val fresh = items.filter { !seen.contains(fingerprint(it.link)) }

        if (fresh.isEmpty()) {
            return
        }

        val batch = fresh.take(MAX_AUTO_ADD_PER_REFRESH)

        AppLog.info("RSS: $feed - auto-downloading ${batch.size} new item(s)")

        for (item in batch) {
            addItem(app, feed, item)
        }

        if (fresh.size > batch.size) {
            AppLog.info(
                "RSS: $feed - ${fresh.size - batch.size} more item(s) follow on the next refresh"
            )
        }
    }

    // ------------------------------------------------------------- interval

    fun intervalLabel(minutes: Int): String {
        return when {
            minutes <= 0 -> "Off"
            minutes < 60 -> "Every $minutes minutes"
            minutes == 60 -> "Every hour"
            minutes == 1440 -> "Every day"
            minutes % 60 == 0 -> "Every ${minutes / 60} hours"
            else -> "Every $minutes minutes"
        }
    }

    fun loadIntervalMinutes(context: Context): Int {
        val value = context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt("interval_minutes", DEFAULT_INTERVAL_MINUTES)

        return if (value < 0) DEFAULT_INTERVAL_MINUTES else value
    }

    fun saveIntervalMinutes(context: Context, minutes: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putInt("interval_minutes", minutes)
            .commit()
    }

    fun lastRefreshMillis(context: Context): Long {
        return context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getLong("last_refresh", 0L)
    }

    private fun saveLastRefresh(context: Context, millis: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putLong("last_refresh", millis)
            .commit()
    }

    // -------------------------------------------------------------- refresh

    fun isRefreshing(): Boolean = refreshing.get()

    // Fetches one feed, stores its items and handles auto-download.
    // Returns how many items are new. Throws when the feed cannot be read.
    private fun refreshOne(app: Context, feed: String, manual: Boolean): Int {
        val parsed = fetchFeed(feed)
        val items = parsed.items

        val settings = app.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)

        val known = loadItems(app, feed).map { fingerprint(it.link) }.toSet()
        val fresh = items.count { !known.contains(fingerprint(it.link)) }

        // the first read after the parser changed is a fresh start
        val parserChanged = settings.getInt("pv_$feed", 0) != PARSER_VERSION

        if (parsed.title.isNotBlank()) {
            settings.edit().putString("title_$feed", parsed.title).apply()
        }

        if (items.isEmpty()) {
            AppLog.warning("RSS: $feed has no items")
        } else {
            saveItems(app, feed, items)

            if (parserChanged) {
                markSeen(app, feed, items)
                AppLog.info("RSS: $feed - ${items.size} item(s) loaded")
            } else if (known.isEmpty()) {
                AppLog.info("RSS: $feed - ${items.size} item(s) loaded")
            } else if (fresh > 0) {
                AppLog.info("RSS: $feed - $fresh new item(s)")
            } else if (manual) {
                AppLog.info("RSS: $feed - no new items")
            }

            if (isAutoDownload(app, feed) && !parserChanged) {
                autoDownloadNewItems(app, feed, items)
            }
        }

        settings.edit()
            .putInt("pv_$feed", PARSER_VERSION)
            .putLong("last_$feed", System.currentTimeMillis())
            .commit()

        return if (parserChanged || known.isEmpty()) 0 else fresh
    }

    /**
     * Downloads every feed and stores the items. Blocking: call it from a
     * background thread. Returns null when another refresh is already running.
     *
     * A manual refresh writes a line for every feed to the Execution Log.
     * An automatic one only writes new items and errors, so the log is not
     * filled with "no new items" every few minutes.
     */
    fun refreshAll(context: Context, manual: Boolean): RefreshResult? {
        val app = context.applicationContext
        AppLog.init(app)

        if (!refreshing.compareAndSet(false, true)) {
            return null
        }

        try {
            val feeds = loadFeeds(app)

            if (feeds.isEmpty()) {
                return RefreshResult(0, 0, 0)
            }

            if (manual) {
                AppLog.info("RSS: refreshing ${feeds.size} feed(s)")
            }

            var ok = 0
            var failed = 0
            var newItems = 0

            for (feed in feeds) {
                try {
                    newItems += refreshOne(app, feed, manual)
                    ok++
                } catch (e: Throwable) {
                    failed++
                    AppLog.error(
                        "RSS: could not refresh $feed: " +
                                (e.message ?: e.javaClass.simpleName)
                    )
                }
            }

            saveLastRefresh(app, System.currentTimeMillis())

            if (manual) {
                if (failed > 0) {
                    AppLog.warning("RSS: refresh finished, $ok feed(s) ok, $failed failed")
                } else {
                    AppLog.info("RSS: refresh finished, $ok feed(s) ok")
                }
            }

            return RefreshResult(ok, failed, newItems)
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * Refreshes a single feed (the Refresh button of a feed). Blocking.
     * Returns null when another refresh is already running.
     */
    fun refreshFeed(context: Context, feed: String, manual: Boolean): RefreshResult? {
        val app = context.applicationContext
        AppLog.init(app)

        if (!refreshing.compareAndSet(false, true)) {
            return null
        }

        try {
            return try {
                val fresh = refreshOne(app, feed, manual)

                if (manual) {
                    AppLog.info("RSS: refreshed ${feedTitle(app, feed)}")
                }

                RefreshResult(1, 0, fresh)
            } catch (e: Throwable) {
                AppLog.error(
                    "RSS: could not refresh $feed: " +
                            (e.message ?: e.javaClass.simpleName)
                )

                RefreshResult(0, 1, 0)
            }
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * Called every few seconds by TorrentService. Starts a background refresh
     * when auto refresh is on and the interval has passed.
     */
    fun maybeAutoRefresh(context: Context) {
        val app = context.applicationContext

        val minutes = loadIntervalMinutes(app)
        if (minutes <= 0) return
        if (refreshing.get()) return

        val last = lastRefreshMillis(app)
        val now = System.currentTimeMillis()

        // not due yet (a clock set back counts as due, so it can never get stuck)
        if (last != 0L && now >= last && now - last < minutes * 60_000L) return

        if (loadFeeds(app).isEmpty()) return

        Thread {
            try {
                refreshAll(app, false)
            } catch (_: Throwable) {
            }
        }.start()
    }

    // ---------------------------------------------------------- feed parsing

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) TorrentOr/1.0"

    data class ParsedFeed(
        val title: String,
        val items: List<RssItem>
    )

    private fun fetchFeed(feedUrl: String): ParsedFeed {
        val connection = URL(feedUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)

        try {
            connection.inputStream.use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(input, null)
                return parseFeed(parser)
            }
        } finally {
            connection.disconnect()
        }
    }

    // Reads the text of the current element and leaves the parser on its end
    // tag (like nextText, but it also copes with child elements).
    private fun readText(parser: XmlPullParser): String {
        val builder = StringBuilder()
        var depth = 1

        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (builder.length < 30000) {
                        builder.append(parser.text)
                    }
                }
                XmlPullParser.END_DOCUMENT -> return builder.toString()
            }
        }

        return builder.toString()
    }

    /**
     * Reads an RSS or Atom feed. EVERY item is kept, even when its link is
     * only a web page (that page is searched for the torrent when the item is
     * added). The best download link of an item is chosen from:
     * a magnet tag, the link, an info hash, the enclosure, a .torrent address
     * or a magnet link written into the description.
     */
    private fun parseFeed(parser: XmlPullParser): ParsedFeed {
        val items = mutableListOf<RssItem>()

        var channelTitle = ""
        var inItem = false

        var title = ""
        var link = ""
        var guid = ""
        var magnetTag = ""
        var infoHash = ""
        var description = ""
        var enclosureUrl = ""
        var enclosureType = ""
        var enclosureLength = ""
        var sizeTag = ""

        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val full = (parser.name ?: "").lowercase()
                val local = full.substringAfterLast(':')

                if (full == "item" || full == "entry") {
                    inItem = true
                    title = ""
                    link = ""
                    guid = ""
                    magnetTag = ""
                    infoHash = ""
                    description = ""
                    enclosureUrl = ""
                    enclosureType = ""
                    enclosureLength = ""
                    sizeTag = ""
                } else if (!inItem) {
                    if (full == "title" && channelTitle.isBlank()) {
                        channelTitle = readText(parser).trim()
                    }
                } else if (full == "title") {
                    title = readText(parser).trim()
                } else if (full == "link") {
                    val href = parser.getAttributeValue(null, "href")

                    if (href != null) {
                        // Atom: <link href="..." rel="..."/>
                        val rel = parser.getAttributeValue(null, "rel") ?: ""
                        val type = parser.getAttributeValue(null, "type") ?: ""

                        if (rel == "enclosure" || type.contains("torrent", ignoreCase = true)) {
                            if (enclosureUrl.isBlank()) {
                                enclosureUrl = href
                                enclosureType = type
                            }
                        } else if (link.isBlank()) {
                            link = href
                        }
                    } else {
                        link = readText(parser).trim()
                    }
                } else if (full == "guid" || full == "id") {
                    guid = readText(parser).trim()
                } else if (local == "magneturi" || local == "magnet") {
                    magnetTag = readText(parser).trim()
                } else if (local == "infohash") {
                    infoHash = readText(parser).trim()
                } else if (local == "contentlength" || local == "size") {
                    sizeTag = readText(parser).trim()
                } else if (
                    full == "description" || full == "summary" ||
                    full == "content" || local == "encoded"
                ) {
                    description += " " + readText(parser)
                } else if (full == "enclosure") {
                    val url = parser.getAttributeValue(null, "url") ?: ""

                    if (url.isNotBlank() && enclosureUrl.isBlank()) {
                        enclosureUrl = url
                        enclosureType = parser.getAttributeValue(null, "type") ?: ""
                        enclosureLength = parser.getAttributeValue(null, "length") ?: ""
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                val full = (parser.name ?: "").lowercase()

                if (inItem && (full == "item" || full == "entry")) {
                    val page = when {
                        link.startsWith("http", ignoreCase = true) -> link
                        guid.startsWith("http", ignoreCase = true) -> guid
                        else -> ""
                    }

                    val best = chooseLink(
                        title, link, guid, magnetTag, infoHash,
                        description, enclosureUrl, enclosureType
                    )

                    val sizeText = when {
                        enclosureLength.isNotBlank() -> formatItemSize(enclosureLength)
                        sizeTag.isNotBlank() -> formatItemSize(sizeTag)
                        else -> ""
                    }

                    items.add(
                        RssItem(
                            title = title.ifBlank { "Untitled" },
                            link = best.ifBlank { page },
                            sizeText = sizeText,
                            page = page
                        )
                    )

                    inItem = false
                }
            }

            eventType = parser.next()
        }

        return ParsedFeed(channelTitle, items)
    }

    private fun looksLikeMagnet(text: String): Boolean {
        return text.trim().startsWith("magnet:", ignoreCase = true)
    }

    private fun looksLikeTorrentAddress(url: String): Boolean {
        val lower = url.trim().lowercase()

        return lower.startsWith("http") &&
                (lower.contains(".torrent") || lower.contains("/download"))
    }

    private fun chooseLink(
        title: String,
        link: String,
        guid: String,
        magnetTag: String,
        infoHash: String,
        description: String,
        enclosureUrl: String,
        enclosureType: String
    ): String {
        val text = description.replace("&amp;", "&")

        val hashMagnet = if (
            Regex("^[A-Fa-f0-9]{40}$").matches(infoHash.trim()) ||
            Regex("^[A-Za-z2-7]{32}$").matches(infoHash.trim())
        ) {
            "magnet:?xt=urn:btih:${infoHash.trim()}&dn=" +
                    java.net.URLEncoder.encode(title, "UTF-8")
        } else {
            ""
        }

        val enclosureIsTorrent = enclosureUrl.isNotBlank() && (
                enclosureType.contains("torrent", ignoreCase = true) ||
                        enclosureUrl.contains("torrent", ignoreCase = true) ||
                        enclosureType.isBlank()
                )

        val candidates = listOf(
            magnetTag.takeIf { looksLikeMagnet(it) } ?: "",
            link.takeIf { looksLikeMagnet(it) } ?: "",
            guid.takeIf { looksLikeMagnet(it) } ?: "",
            findMagnet(text) ?: "",
            hashMagnet,
            if (enclosureIsTorrent) enclosureUrl else "",
            link.takeIf { looksLikeTorrentAddress(it) } ?: "",
            findTorrentAddress(text) ?: "",
            guid.takeIf { looksLikeTorrentAddress(it) } ?: "",
            link.takeIf { it.startsWith("http", ignoreCase = true) } ?: "",
            enclosureUrl,
            guid.takeIf { it.startsWith("http", ignoreCase = true) } ?: ""
        )

        return candidates.firstOrNull { it.isNotBlank() }?.trim() ?: ""
    }

    // A magnet link written into text or HTML.
    private fun findMagnet(text: String): String? {
        val match = Regex(
            "magnet:\\?xt=urn:(?:btih|btmh):[A-Za-z0-9:]+[^\\s\"'<>]*",
            RegexOption.IGNORE_CASE
        ).find(text)

        return match?.value?.replace("&amp;", "&")
    }

    // A full http(s) address of a .torrent file written into text or HTML.
    private fun findTorrentAddress(text: String): String? {
        val match = Regex(
            "https?://[^\\s\"'<>]+\\.torrent(?:\\?[^\\s\"'<>]*)?",
            RegexOption.IGNORE_CASE
        ).find(text)

        return match?.value?.replace("&amp;", "&")
    }

    // href="....torrent" in a page, also relative ones.
    private fun findTorrentHref(html: String, baseUrl: String): String? {
        val match = Regex(
            "href\\s*=\\s*[\"']([^\"']+\\.torrent[^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)

        if (match != null) {
            val href = match.groupValues[1].replace("&amp;", "&")

            return try {
                URL(URL(baseUrl), href).toString()
            } catch (_: Throwable) {
                null
            }
        }

        return findTorrentAddress(html)
    }

    private fun formatItemSize(length: String): String {
        val trimmed = length.trim()
        val bytes = trimmed.toLongOrNull()

        // e.g. "1.4 GiB" from feeds that already write the size as text
        if (bytes == null) {
            return if (trimmed.isBlank()) "" else "Size: $trimmed"
        }

        if (bytes <= 0L) return ""

        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        val text = when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }

        return "Size: $text"
    }
}
