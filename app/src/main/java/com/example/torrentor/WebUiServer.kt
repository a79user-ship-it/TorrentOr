package com.example.torrentor

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Base64
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.cert.X509Certificate

/**
 * Feature: Web UI - a small built-in HTTP(S) server so TorrentOr can be
 * viewed and controlled from a browser on the same Wi-Fi/LAN, or from
 * outside it (see the "Web UI" screen for the remote-access and security
 * notes shown there) - no separate app, no cloud account (plain
 * java.net sockets, one short-lived thread per request). Off by
 * default; turned on/off and configured (username, password, port,
 * HTTPS) from that screen. Every route requires HTTP Basic Auth against
 * the saved username/password - a request with none saved yet, or the
 * wrong ones, is refused.
 *
 * Request parsing reads headers as raw bytes (1 byte = 1 char, same as
 * HTTP's own header syntax - never a multi-byte text decoder), then reads
 * exactly Content-Length raw bytes for the body. That is what lets this
 * same server accept a binary .torrent upload (handleUploadTorrent) and a
 * small JSON body (handleAdd, handleAddTorrentPath) through one code
 * path without corrupting either.
 *
 * HTTPS (optional, see start()): rather than ship one fixed certificate
 * with the app (which every install would then share, including its
 * private key), each device generates its OWN self-signed certificate
 * the first time HTTPS is turned on, and keeps it in a small PKCS12
 * keystore file inside this app's own private storage (never shared
 * with any other app). An earlier version tried to generate this key
 * straight in AndroidKeyStore, but that combination doesn't reliably
 * work for a plain javax.net.ssl TLS server across real devices, so the
 * key pair and certificate (built with Bouncy Castle, a well-established
 * pure-Java crypto library with no native code) are generated and held
 * as an ordinary software key instead - still private to this app, just
 * not hardware-backed. Being self-signed, a browser will show a
 * one-time "not private"/"certificate not trusted" warning to click
 * through on each new device that connects; that warning is expected
 * and is about this being self-signed, not a sign anything is wrong.
 */
object WebUiServer {

    private const val TLS_KEYSTORE_FILE = "webui_tls_keystore.p12"
    private const val TLS_KEY_ALIAS = "webui_tls"

    // Only protects the keystore FILE's own internal format - that file
    // already lives in this app's private storage, which the OS already
    // keeps other apps out of, so this does not need to be a secret.
    private val TLS_KEYSTORE_PASSWORD = "torrentor-webui".toCharArray()

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    @Volatile private var running = false
    private var appContext: Context? = null
    @Volatile private var boundPort = 0
    @Volatile private var usingHttps = false

    fun isRunning(): Boolean = running

    fun currentPort(): Int = boundPort

    fun isHttps(): Boolean = usingHttps

    fun webUiPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("web_ui", Context.MODE_PRIVATE)

    // Feature: magnet file selection. A torrent added via
    // /api/add_magnet_metadata exists in libtorrent (it has to, to fetch
    // metadata) but must not show up as a card in the list - the user
    // should only ever see it appear once they press Download. Tracked
    // by (normalized) info-hash, never by list index, since index is
    // just a position in g_handles and shifts whenever any torrent
    // anywhere in the list is removed - see the project's standing rule
    // on this. buildStatusJson() filters these out; handleConfirmMagnet
    // clears the entry (now a real torrent) and so does removing it
    // (handleTorrentAction's REMOVE_TORRENT - the magnet flow's own
    // Cancel button goes through that same route).
    private val pendingMagnetHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Returns null on success, or an error message (e.g. "port already in use").
    // If useHttps is true but setting up the self-signed certificate fails
    // for any reason, this falls back to plain HTTP rather than refusing
    // to start the server at all - isHttps() afterward tells you which one
    // actually came up.
    fun start(context: Context, port: Int, useHttps: Boolean = false): String? {
        if (running) return null

        val app = context.applicationContext
        appContext = app

        var https = false

        val socket = try {
            if (useHttps) {
                try {
                    val created = createHttpsServerSocket(app, port)
                    https = true
                    created
                } catch (e: Throwable) {
                    AppLog.error(
                        "Web UI: could not set up HTTPS (${e.message ?: e.javaClass.simpleName}), " +
                                "starting with plain HTTP instead"
                    )
                    ServerSocket(port)
                }
            } else {
                ServerSocket(port)
            }
        } catch (e: Throwable) {
            val message = e.message ?: "Could not open port $port"
            AppLog.error("Web UI: could not start on port $port: $message")
            return message
        }

        serverSocket = socket
        boundPort = port
        usingHttps = https
        running = true

        serverThread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    null
                } ?: break

                Thread {
                    try {
                        handleClient(app, client)
                    } catch (_: Throwable) {
                        // One bad/odd request must never take the server down.
                    } finally {
                        try {
                            client.close()
                        } catch (_: Throwable) {
                        }
                    }
                }.start()
            }
        }

        serverThread?.isDaemon = true
        serverThread?.start()

        AppLog.info("Web UI: started on port $port (${if (https) "HTTPS" else "HTTP"})")
        return null
    }

    // Builds (and, on first use, generates) a self-signed TLS server
    // socket, from a key+certificate kept in this app's own PKCS12
    // keystore file. See the class doc above for why this generates its
    // own key rather than shipping one fixed key with the app, and for
    // why it's a plain software key rather than an AndroidKeyStore one.
    private fun createHttpsServerSocket(context: Context, port: Int): ServerSocket {
        val keyManagers = getOrCreateHttpsKeyManagers(context)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, null, null)

        // createServerSocket(port) binds immediately to all interfaces,
        // the same as plain ServerSocket(port) above.
        return sslContext.serverSocketFactory.createServerSocket(port)
    }

    // Every IP address a browser might actually type into its address
    // bar to reach this device right now: loopback, every non-loopback
    // IPv4 address on any interface (the Wi-Fi IP, most commonly), and
    // the saved Tailscale IP if one is set. Modern browsers (Chrome
    // included) ignore a certificate's CN entirely for hostname
    // matching and only trust its Subject Alternative Name list - a
    // SAN-less cert (what this used to generate) is rejected outright,
    // with no "proceed anyway" option, which is exactly the "handshake
    // failed / connection refused" bug this fixes.
    private fun currentSanAddresses(context: Context): Set<String> {
        val addresses = mutableSetOf("127.0.0.1")

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                val ifaceAddresses = iface.inetAddresses
                while (ifaceAddresses.hasMoreElements()) {
                    val address = ifaceAddresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        address.hostAddress?.let { addresses.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val tailscaleIp = webUiPrefs(context)
            .getString("tailscale_ip", "")
            ?.trim()

        if (!tailscaleIp.isNullOrBlank()) {
            addresses.add(tailscaleIp)
        }

        return addresses
    }

    // True only if every address in requiredAddresses is already covered
    // by the stored certificate's own SAN list - i.e. the cert on disk
    // is still good enough and doesn't need regenerating.
    private fun certCoversAddresses(certificate: X509Certificate, requiredAddresses: Set<String>): Boolean {
        val sanEntries = try {
            certificate.subjectAlternativeNames
        } catch (_: Throwable) {
            null
        } ?: return false

        val covered = mutableSetOf<String>()
        for (entry in sanEntries) {
            val value = (entry as? List<*>)?.getOrNull(1) as? String
            if (value != null) covered.add(value)
        }

        return requiredAddresses.all { covered.contains(it) }
    }

    private fun getOrCreateHttpsKeyManagers(context: Context): Array<KeyManager> {
        val keystoreFile = File(context.filesDir, TLS_KEYSTORE_FILE)
        val keyStore = KeyStore.getInstance("PKCS12")
        val requiredAddresses = currentSanAddresses(context)

        var needsGeneration = !keystoreFile.exists()

        if (!needsGeneration) {
            try {
                FileInputStream(keystoreFile).use { keyStore.load(it, TLS_KEYSTORE_PASSWORD) }

                val existingCert = keyStore.getCertificate(TLS_KEY_ALIAS) as? X509Certificate

                if (existingCert == null || !certCoversAddresses(existingCert, requiredAddresses)) {
                    // Missing an address this device can now be reached at
                    // (a new Tailscale IP was just entered, or the Wi-Fi IP
                    // changed) - the old cert would just fail the same way
                    // again, so replace it with one that covers what's
                    // needed now.
                    needsGeneration = true
                }
            } catch (_: Throwable) {
                needsGeneration = true
            }
        }

        if (needsGeneration) {
            keyStore.load(null, TLS_KEYSTORE_PASSWORD)

            // Android ships its own cut-down security provider already
            // registered under the name "BC" (unrelated to this app),
            // which has no X.509 CertificateFactory of its own - asking
            // for "BC" then fails with "X.509 not found" unless the real
            // Bouncy Castle provider (from the bcprov/bcpkix dependency)
            // replaces it first.
            if (Security.getProvider("BC") !is BouncyCastleProvider) {
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }

            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.generateKeyPair()

            val subject = X500Name("CN=TorrentOr")
            val serial = BigInteger.valueOf(System.currentTimeMillis())
            val notBefore = Date()
            val notAfter = Date(notBefore.time + 30L * 365 * 24 * 60 * 60 * 1000)

            val certBuilder = JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, keyPair.public
            )

            val sanNames = requiredAddresses.map { GeneralName(GeneralName.iPAddress, it) }.toMutableList()
            sanNames.add(GeneralName(GeneralName.dNSName, "localhost"))
            certBuilder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sanNames.toTypedArray()))

            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certHolder = certBuilder.build(signer)
            val certificate = JcaX509CertificateConverter().setProvider("BC").getCertificate(certHolder)

            keyStore.setKeyEntry(
                TLS_KEY_ALIAS,
                keyPair.private,
                TLS_KEYSTORE_PASSWORD,
                arrayOf<Certificate>(certificate)
            )

            FileOutputStream(keystoreFile).use { keyStore.store(it, TLS_KEYSTORE_PASSWORD) }
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, TLS_KEYSTORE_PASSWORD)
        return keyManagerFactory.keyManagers
    }

    fun stop() {
        if (!running && serverSocket == null) return

        running = false
        usingHttps = false

        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }

        serverSocket = null
        serverThread = null
        boundPort = 0

        AppLog.info("Web UI: stopped")
    }

    // ---------------------------------------------------------- request handling

    private fun handleClient(context: Context, client: Socket) {
        client.soTimeout = 20000

        // For an HTTPS connection, force the TLS handshake here (rather
        // than letting it happen lazily on first read/write) and log the
        // real reason if it fails - a failure here was previously
        // swallowed silently by the generic catch around this whole
        // function, which is why a broken handshake just looked like a
        // closed connection with no clue in the Execution Log.
        if (client is javax.net.ssl.SSLSocket) {
            try {
                client.startHandshake()
            } catch (e: Throwable) {
                AppLog.error(
                    "Web UI: HTTPS handshake failed (${e.javaClass.simpleName}: " +
                            "${e.message ?: "no message"})"
                )
                return
            }
        }

        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()

        val requestLine = readHeaderLine(input) ?: return
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) return

        val method = requestParts[0]
        val fullPath = requestParts[1]
        val path = fullPath.substringBefore("?")
        val query = if (fullPath.contains("?")) fullPath.substringAfter("?") else ""

        val headers = mutableMapOf<String, String>()

        while (true) {
            val line = readHeaderLine(input) ?: break
            if (line.isEmpty()) break

            val separator = line.indexOf(":")
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)

        if (!isAuthorized(context, headers["authorization"])) {
            sendUnauthorized(output)
            return
        }

        try {
            when {
                method == "GET" && path == "/" ->
                    sendHtml(output, INDEX_HTML)

                method == "GET" && path == "/api/status" ->
                    sendJson(output, buildStatusJson(context))

                // Feature: "Global Statistics" - same "global_stats"
                // prefs TorrentService writes to and MainActivity's own
                // Statistics screen (buildQBittorrentGlobalStatisticsText)
                // reads, so it's always the same numbers as the app.
                method == "GET" && path == "/api/global_statistics" ->
                    handleGlobalStatistics(context, output)

                // Feature: "Execution Log" - same AppLog entries the
                // app's own Execution Log screen shows (shared process,
                // same in-memory/on-disk log), filtered by level.
                method == "GET" && path == "/api/execution_log" ->
                    handleExecutionLog(context, parseQueryParam(query, "levels"), output)

                method == "POST" && path == "/api/execution_log/clear" ->
                    handleExecutionLogClear(output)

                // Feature: Online Search - same OnlineSearchManager/
                // OnlineSearchAdder the app's own Online Search screen
                // uses (same providers, same enabled/disabled prefs),
                // just driven from the Web UI instead of an Activity.
                method == "POST" && path == "/api/online_search" ->
                    handleOnlineSearch(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/online_search_resolve" ->
                    handleOnlineSearchResolve(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                // Feature: RSS Feeds - same RssManager the app's own RSS
                // screen uses (same stored feed list, cached items,
                // auto-download settings and auto-refresh interval).
                method == "GET" && path == "/api/rss/feeds" ->
                    handleRssFeeds(context, output)

                method == "POST" && path == "/api/rss/add_feed" ->
                    handleRssAddFeed(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/rss/remove_feed" ->
                    handleRssRemoveFeed(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/rss/refresh" ->
                    handleRssRefresh(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/rss/set_auto" ->
                    handleRssSetAuto(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "GET" && path == "/api/rss/items" ->
                    handleRssItems(context, parseQueryParam(query, "feed"), output)

                method == "POST" && path == "/api/rss/resolve_item" ->
                    handleRssResolveItem(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/rss/mark_added" ->
                    handleRssMarkAdded(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "GET" && path == "/api/rss/interval" ->
                    handleRssGetInterval(context, output)

                method == "POST" && path == "/api/rss/interval" ->
                    handleRssSetInterval(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                // Feature: Save Folder - same "save_folder_settings" (global
                // default) and "torrent_save_folders" (per-torrent override)
                // prefs the app's own Save Folder screen and per-torrent
                // "Change Save Folder" button use. A per-torrent change
                // always goes through moveTorrentStorage(), same as the app.
                method == "GET" && path == "/api/save_folder" ->
                    handleGetSaveFolderApi(context, output)

                method == "POST" && path == "/api/save_folder" ->
                    handleSetSaveFolder(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "GET" && path == "/api/torrent_save_folder" ->
                    handleGetTorrentSaveFolder(context, parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "POST" && path == "/api/torrent_save_folder" ->
                    handleSetTorrentSaveFolder(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                // Feature: graphical folder picker for the Save Folder
                // panels - lists subfolders of a path, and creates a new
                // one, same plain File browsing as the app's own
                // showFolderPickerDialog/showCreateFolderDialog.
                method == "GET" && path == "/api/browse_folder" ->
                    handleBrowseFolder(context, parseQueryParam(query, "path"), output)

                method == "POST" && path == "/api/create_folder" ->
                    handleCreateFolder(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/add" ->
                    handleAdd(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "POST" && path == "/api/upload_torrent" ->
                    handleUploadTorrent(context, bodyBytes, output)

                method == "POST" && path == "/api/add_torrent_path" ->
                    handleAddTorrentPath(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                // Feature: file selection for magnets, the magnet-link
                // equivalent of upload_torrent/add_torrent_path above -
                // fetch metadata (paused), let the browser poll until the
                // file list is ready, then confirm which files to
                // download. See MainActivity's showMagnetMetadataScreen/
                // showMagnetFileSelection for the in-app version this
                // mirrors.
                method == "POST" && path == "/api/add_magnet_metadata" ->
                    handleAddMagnetMetadata(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                method == "GET" && (path == "/api/magnet_files" || path == "/api/torrent_files") ->
                    handleFilesList(context, parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "POST" && path == "/api/confirm_magnet" ->
                    handleConfirmMagnet(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                // Feature: the Web UI's "Peers" tab - the per-peer list
                // (IP, client software, TCP/uTP, progress, speeds) for
                // this whole torrent, same as MainActivity's own Peers
                // screen. Reuses TorrentNative.getTorrentPeers as-is,
                // which already returns a ready-to-display text block.
                method == "GET" && path == "/api/torrent_peers" ->
                    handleTorrentPeers(parseQueryParam(query, "index")?.toIntOrNull(), output)

                // Feature: "Trackers" and "Web Seeds" tabs - same text
                // MainActivity's own Trackers/Web Seeds buttons show,
                // built exactly the same way (same fallback chain).
                method == "GET" && path == "/api/torrent_trackers" ->
                    handleTorrentTrackers(parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "GET" && path == "/api/torrent_webseeds" ->
                    handleTorrentWebSeeds(parseQueryParam(query, "index")?.toIntOrNull(), output)

                // Feature: "Statistics" and "Comment" tabs - same text
                // MainActivity's own Statistics/Comment buttons show.
                method == "GET" && path == "/api/torrent_statistics" ->
                    handleTorrentStatistics(parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "GET" && path == "/api/torrent_comment" ->
                    handleTorrentComment(parseQueryParam(query, "index")?.toIntOrNull(), output)

                // Feature: "General", "Pieces" and "Swarm Health" tabs -
                // same text MainActivity's own General/Pieces/Swarm
                // buttons show (General also mirrors showGeneralDetails'
                // exact field list and sourcing, including the
                // "torrent_dates" Added/Completed lines).
                method == "GET" && path == "/api/torrent_general" ->
                    handleTorrentGeneral(context, parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "GET" && path == "/api/torrent_pieces" ->
                    handleTorrentPieces(parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "GET" && path == "/api/torrent_swarmhealth" ->
                    handleTorrentSwarmHealth(parseQueryParam(query, "index")?.toIntOrNull(), output)

                // Feature: Force Recheck / Force Reannounce - same native
                // calls as MainActivity's own buttons. Fire-and-forget,
                // like the app's own versions (no return value to report).
                method == "POST" && path.startsWith("/api/force_recheck/") ->
                    handleForceAction(
                        "recheck",
                        path.removePrefix("/api/force_recheck/").toIntOrNull(),
                        output
                    )

                method == "POST" && path.startsWith("/api/force_reannounce/") ->
                    handleForceAction(
                        "reannounce",
                        path.removePrefix("/api/force_reannounce/").toIntOrNull(),
                        output
                    )

                // Feature: change which files download on a torrent that's
                // already been added (not just at add-time) - the Web UI
                // equivalent of MainActivity's "Apply File Selection"
                // button in its Files tab. Reuses the exact same native
                // call and the same "file_priorities" prefs file, so a
                // selection made here is picked up by the app too.
                method == "POST" && path == "/api/set_file_priorities" ->
                    handleSetFilePriorities(context, String(bodyBytes, StandardCharsets.UTF_8), output)

                // Feature: view/play/download a torrent's files from the
                // browser. /stream is for inline viewing (a <video>/
                // <audio> element, a text viewer, an <img>) and sets
                // Content-Disposition: inline; /download forces a save-
                // to-device prompt instead. Both support byte-range
                // requests (handleFileServe), which is what lets a video
                // or audio element seek, rather than only ever play from
                // the start.
                method == "GET" && path.startsWith("/stream/") ->
                    handleStreamOrDownload(path.removePrefix("/stream/"), headers["range"], false, output)

                method == "GET" && path.startsWith("/download/") ->
                    handleStreamOrDownload(path.removePrefix("/download/"), headers["range"], true, output)

                method == "POST" && path == "/api/pause_all" ->
                    handleAllAction(context, "PAUSE_ALL", output)

                method == "POST" && path == "/api/resume_all" ->
                    handleAllAction(context, "RESUME_ALL", output)

                method == "POST" && path.startsWith("/api/pause/") ->
                    handleTorrentAction(
                        context,
                        "PAUSE_TORRENT",
                        path.removePrefix("/api/pause/").toIntOrNull(),
                        false,
                        output
                    )

                method == "POST" && path.startsWith("/api/resume/") ->
                    handleTorrentAction(
                        context,
                        "RESUME_TORRENT",
                        path.removePrefix("/api/resume/").toIntOrNull(),
                        false,
                        output
                    )

                method == "POST" && path.startsWith("/api/remove/") ->
                    handleTorrentAction(
                        context,
                        "REMOVE_TORRENT",
                        path.removePrefix("/api/remove/").toIntOrNull(),
                        query.contains("files=1") || query.contains("files=true"),
                        output
                    )

                else -> sendNotFound(output)
            }
        } catch (e: Throwable) {
            sendServerError(output, e.message ?: e.javaClass.simpleName)
        }
    }

    // Reads one line as raw bytes, one byte mapped to one char (the same
    // byte-for-byte rule HTTP's own header syntax uses) - never a
    // multi-byte text decoder, which would risk reading past where the
    // headers end and into a binary body. Returns null only at end of
    // stream with nothing read at all.
    private fun readHeaderLine(input: InputStream): String? {
        val buffer = StringBuilder()
        var any = false

        while (true) {
            val b = input.read()
            if (b == -1) return if (any) buffer.toString() else null
            any = true

            if (b == '\n'.code) {
                if (buffer.isNotEmpty() && buffer[buffer.length - 1] == '\r') {
                    buffer.deleteCharAt(buffer.length - 1)
                }
                return buffer.toString()
            }

            buffer.append(b.toChar())
        }
    }

    // Reads exactly `length` raw bytes - used for the request body, so a
    // binary .torrent upload comes through byte-for-byte rather than
    // through any char-based decoding.
    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var readSoFar = 0

        while (readSoFar < length) {
            val n = input.read(buffer, readSoFar, length - readSoFar)
            if (n == -1) break
            readSoFar += n
        }

        return if (readSoFar == length) buffer else buffer.copyOf(readSoFar)
    }

    private fun isAuthorized(context: Context, authHeader: String?): Boolean {
        val prefs = webUiPrefs(context)
        val savedUser = prefs.getString("username", "") ?: ""
        val savedPass = prefs.getString("password", "") ?: ""

        // Nothing saved yet = nothing gets in, rather than leaving the
        // door open until the user has actually chosen credentials.
        if (savedUser.isBlank() || savedPass.isBlank()) return false

        if (authHeader == null || !authHeader.startsWith("Basic ")) return false

        val decoded = try {
            String(
                Base64.decode(authHeader.removePrefix("Basic ").trim(), Base64.DEFAULT),
                StandardCharsets.UTF_8
            )
        } catch (_: Throwable) {
            return false
        }

        val separator = decoded.indexOf(":")
        if (separator < 0) return false

        val user = decoded.substring(0, separator)
        val pass = decoded.substring(separator + 1)

        return user == savedUser && pass == savedPass
    }

    // ---------------------------------------------------------- status JSON

    // Parses the same " • "-separated line getDetailedStatus() builds for
    // the app's own torrent list (see torrentor.cpp), rather than adding
    // a second native function - one source of truth for what a torrent
    // row says.
    // Mirrors MainActivity.unitMultiplier/extractTotalSizeBytes exactly -
    // used for the Web UI's own "Size (Largest/Smallest First)" sort,
    // same as the app's.
    private fun unitMultiplier(unit: String): Double {
        return when (unit.uppercase()) {
            "B" -> 1.0
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024.0
            "GB" -> 1024.0 * 1024.0 * 1024.0
            "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
    }

    private fun extractTotalSizeBytes(sizeField: String): Long {
        val match = Regex(
            """[\d.]+\s*(?:B|KB|MB|GB|TB)\s*/\s*([\d.]+)\s*(B|KB|MB|GB|TB)"""
        ).find(sizeField) ?: return 0L

        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        return (value * unitMultiplier(match.groupValues[2])).toLong()
    }

    // Mirrors MainActivity.getTorrentAddedTimestamp exactly - same
    // "torrent_dates" prefs, same normalizeHash key - for the Web UI's
    // own "Date Added" sort.
    private fun getTorrentAddedTimestamp(context: Context, hash: String): Long {
        val key = normalizeHash(hash)
        if (key.isBlank()) return 0L
        return context.getSharedPreferences("torrent_dates", Context.MODE_PRIVATE)
            .getLong("added_$key", 0L)
    }

    private fun buildStatusJson(context: Context): String {
        val raw = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        val torrents = StringBuilder("[")
        var first = true

        // Self-cleaning: a hash removed by cancelling the magnet flow
        // (or by any other means) stops existing among the torrents
        // below and gets dropped from pendingMagnetHashes at the end of
        // this function - no action site has to remember to clean it up
        // itself, and a later re-add of the exact same magnet is never
        // stuck hidden by a stale leftover entry.
        val stillPendingHashes = mutableSetOf<String>()

        if (raw.isNotBlank() && raw != "No torrents" && raw != "No active torrents") {
            var index = 1

            for (line in raw.lines()) {
                if (line.isBlank()) {
                    index++
                    continue
                }

                val fields = line.split(" • ")
                val name = fields.getOrNull(0) ?: "Torrent $index"
                val state = fields.getOrNull(1) ?: ""
                val percent = fields.getOrNull(2)?.removeSuffix("%")?.toIntOrNull() ?: 0
                val downSpeed = fields.getOrNull(3)?.removePrefix("↓ ") ?: ""
                val upSpeed = fields.getOrNull(4)?.removePrefix("↑ ") ?: ""
                val size = fields.getOrNull(5)?.removePrefix("Size ") ?: ""
                val seeds = fields.getOrNull(6)?.removePrefix("Seeds ") ?: ""
                val peers = fields.getOrNull(7)?.removePrefix("Peers ") ?: ""
                val eta = fields.getOrNull(8) ?: ""

                if (pendingMagnetHashes.isNotEmpty()) {
                    val hash = normalizeHash(
                        try {
                            TorrentNative.getTorrentHash(index)
                        } catch (_: Throwable) {
                            ""
                        }
                    )

                    if (pendingMagnetHashes.contains(hash)) {
                        stillPendingHashes.add(hash)
                        index++
                        continue
                    }
                }

                if (!first) torrents.append(",")
                first = false

                // totalBytes backs the Web UI's own "Size" sort - same
                // idea as MainActivity.extractTotalSizeBytes: parse the
                // total (second) number out of the "x / y" size text.
                val totalBytes = run {
                    val match = Regex("([\\d.]+)\\s*(B|KB|MB|GB|TB)\\s*/\\s*([\\d.]+)\\s*(B|KB|MB|GB|TB)")
                        .find(size)
                    if (match == null) {
                        0L
                    } else {
                        val amount = match.groupValues[3].toDoubleOrNull() ?: 0.0
                        val unit = match.groupValues[4]
                        val multiplier = when (unit) {
                            "B" -> 1L
                            "KB" -> 1024L
                            "MB" -> 1024L * 1024L
                            "GB" -> 1024L * 1024L * 1024L
                            "TB" -> 1024L * 1024L * 1024L * 1024L
                            else -> 1L
                        }
                        (amount * multiplier).toLong()
                    }
                }

                // addedTimestamp backs the Web UI's own "Date Added"
                // sort - same "torrent_dates" prefs (by hash) as
                // MainActivity.getTorrentAddedTimestamp.
                val addedTimestamp = run {
                    val hash = try {
                        TorrentNative.getTorrentHash(index)
                    } catch (_: Throwable) {
                        ""
                    }
                    val key = normalizeHash(hash)
                    if (key.isBlank()) {
                        0L
                    } else {
                        context.getSharedPreferences("torrent_dates", Context.MODE_PRIVATE)
                            .getLong("added_$key", 0L)
                    }
                }

                torrents.append("{")
                torrents.append("\"index\":").append(index).append(",")
                torrents.append("\"name\":").append(jsonString(name)).append(",")
                torrents.append("\"state\":").append(jsonString(state)).append(",")
                torrents.append("\"percent\":").append(percent).append(",")
                torrents.append("\"downSpeed\":").append(jsonString(downSpeed)).append(",")
                torrents.append("\"upSpeed\":").append(jsonString(upSpeed)).append(",")
                torrents.append("\"size\":").append(jsonString(size)).append(",")
                torrents.append("\"seeds\":").append(jsonString(seeds)).append(",")
                torrents.append("\"peers\":").append(jsonString(peers)).append(",")
                torrents.append("\"eta\":").append(jsonString(eta)).append(",")
                torrents.append("\"addedTimestamp\":").append(addedTimestamp).append(",")
                torrents.append("\"totalBytes\":").append(totalBytes)
                torrents.append("}")

                index++
            }
        }

        if (pendingMagnetHashes.isNotEmpty()) {
            pendingMagnetHashes.retainAll(stillPendingHashes)
        }

        torrents.append("]")
        return "{\"torrents\":$torrents}"
    }

    private fun jsonString(value: String): String {
        val escaped = StringBuilder()

        for (ch in value) {
            when (ch) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\r' -> {}
                else -> if (ch.code < 0x20) escaped.append(' ') else escaped.append(ch)
            }
        }

        return "\"$escaped\""
    }

    // ---------------------------------------------------------- actions

    // Feature: "Global Statistics" - mirrors MainActivity's
    // buildQBittorrentGlobalStatisticsText/formatGiB exactly: reads the
    // same "global_stats" prefs counters TorrentService accumulates
    // (all_time_download/all_time_upload), same GiB formatting, same
    // ratio calculation (0.0 when nothing's been downloaded yet).
    private fun handleGlobalStatistics(context: Context, output: OutputStream) {
        val prefs = context.getSharedPreferences("global_stats", Context.MODE_PRIVATE)

        val allTimeDownload = prefs.getLong("all_time_download", 0L)
        val allTimeUpload = prefs.getLong("all_time_upload", 0L)

        val ratio = if (allTimeDownload > 0L) {
            allTimeUpload.toDouble() / allTimeDownload.toDouble()
        } else {
            0.0
        }

        fun formatGiB(bytes: Long): String {
            val gib = bytes.toDouble() / 1024.0 / 1024.0 / 1024.0
            return String.format("%.2f GiB", gib)
        }

        val text = StringBuilder()
            .append("User statistics\n\n")
            .append("All-time upload:      ").append(formatGiB(allTimeUpload)).append("\n")
            .append("All-time download:    ").append(formatGiB(allTimeDownload)).append("\n")
            .append("All-time share ratio: ").append(String.format("%.2f", ratio))
            .toString()

        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text)}}")
    }

    // Feature: "Execution Log" - same AppLog store the app's own
    // Execution Log screen reads (AppLog.init is idempotent, so this is
    // safe to call from here too), filtered by level the same way
    // (shownLevels), formatted with the same AppLog.formatLine. levels
    // is a comma-separated list like "info,warning,error"; missing or
    // blank means all three, matching the screen's all-checked default.
    private fun handleExecutionLog(context: Context, levelsParam: String?, output: OutputStream) {
        AppLog.init(context.applicationContext)
        AppLog.pullEngineLog()

        val requested = (levelsParam ?: "info,warning,error")
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

        val levels = AppLog.Level.values().filter {
            requested.isEmpty() || requested.contains(it.name.lowercase())
        }.toSet()

        val text = AppLog.exportText(levels)
        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text)}}")
    }

    private fun handleExecutionLogClear(output: OutputStream) {
        AppLog.clear()
        AppLog.info("Execution log cleared")
        sendJson(output, "{\"ok\":true}")
    }

    // Feature: Online Search - runs every enabled provider (same
    // registry/enabled-prefs as the app's own Online Search screen)
    // through OnlineSearchManager.search, blocking this connection's own
    // thread until onAllDone fires (each provider already runs on its
    // own thread with its own timeout inside that function, so this
    // never blocks longer than that - the extra margin below is just a
    // safety net against a bug in that logic, not the normal case).
    private fun handleOnlineSearch(context: Context, body: String, output: OutputStream) {
        val query = extractJsonStringField(body, "query") ?: ""

        if (query.isBlank()) {
            sendJson(output, "{\"ok\":true,\"results\":[]}")
            return
        }

        val latch = CountDownLatch(1)
        var results: List<TorrentSearchResult> = emptyList()

        OnlineSearchManager.search(
            context,
            query,
            onProviderDone = {},
            onAllDone = { found ->
                results = found
                latch.countDown()
            }
        )

        val timeoutSeconds = try {
            OnlineSearchManager.loadTimeoutSeconds(context)
        } catch (_: Throwable) {
            12
        }

        latch.await((timeoutSeconds + 15).toLong(), TimeUnit.SECONDS)

        val json = StringBuilder("[")
        var first = true

        for (r in results) {
            if (!first) json.append(",")
            first = false

            json.append("{")
            json.append("\"name\":").append(jsonString(r.name)).append(",")
            json.append("\"sourceName\":").append(jsonString(r.sourceName)).append(",")
            json.append("\"sizeBytes\":").append(r.sizeBytes).append(",")
            json.append("\"sizeText\":").append(jsonString(r.sizeText)).append(",")
            json.append("\"seeders\":").append(r.seeders).append(",")
            json.append("\"leechers\":").append(r.leechers).append(",")
            json.append("\"publicationDate\":").append(jsonString(r.publicationDate)).append(",")
            json.append("\"category\":").append(jsonString(r.category)).append(",")
            json.append("\"infoHash\":").append(jsonString(r.infoHash)).append(",")
            json.append("\"magnetUri\":").append(jsonString(r.magnetUri)).append(",")
            json.append("\"torrentUrl\":").append(jsonString(r.torrentUrl)).append(",")
            json.append("\"resultPageUrl\":").append(jsonString(r.resultPageUrl))
            json.append("}")
        }

        json.append("]")
        sendJson(output, "{\"ok\":true,\"results\":${json}}")
    }

    // Feature: resolves one online-search result to either a magnet
    // link or a downloaded .torrent file (OnlineSearchAdder.
    // resolveForSelection - the same resolution the app's own "Add"
    // button does), WITHOUT adding it yet, so the browser can route it
    // through the normal magnet/file-selection flow and let the user
    // pick files before anything is actually added.
    private fun handleOnlineSearchResolve(context: Context, body: String, output: OutputStream) {
        val result = TorrentSearchResult(
            name = extractJsonStringField(body, "name") ?: "",
            sourceName = extractJsonStringField(body, "sourceName") ?: "",
            magnetUri = extractJsonStringField(body, "magnetUri") ?: "",
            torrentUrl = extractJsonStringField(body, "torrentUrl") ?: "",
            resultPageUrl = extractJsonStringField(body, "resultPageUrl") ?: ""
        )

        val resolved = try {
            OnlineSearchAdder.resolveForSelection(context, result)
        } catch (e: Throwable) {
            sendJson(output, "{\"ok\":false,\"error\":${jsonString(e.message ?: "Could not resolve")}}")
            return
        }

        val magnet = resolved.magnet
        val torrentPath = resolved.torrentPath

        if (!magnet.isNullOrBlank()) {
            sendJson(output, "{\"ok\":true,\"kind\":\"magnet\",\"magnet\":${jsonString(magnet)}}")
            return
        }

        if (!torrentPath.isNullOrBlank()) {
            val fileData = try {
                TorrentNative.getTorrentFiles(torrentPath)
            } catch (e: Throwable) {
                sendJson(output, "{\"ok\":false,\"error\":\"could not read resolved torrent file\"}")
                return
            }

            if (fileData.isBlank() || fileData.startsWith("ERROR")) {
                sendJson(output, "{\"ok\":false,\"error\":\"not a valid .torrent file\"}")
                return
            }

            sendJson(
                output,
                "{\"ok\":true,\"kind\":\"torrent\",\"path\":${jsonString(torrentPath)},\"files\":${buildFilesJsonArray(fileData)}}"
            )
            return
        }

        sendJson(output, "{\"ok\":false,\"error\":\"This result has no magnet link or .torrent file\"}")
    }

    // ---------------------------------------------------------------------
    // Feature: RSS Feeds - same RssManager the app's own RSS screen reads
    // from and writes to (shared SharedPreferences), so feeds/items/auto-
    // download settings and the auto-refresh interval stay in sync with
    // the app no matter which one changed them last.

    private fun handleRssFeeds(context: Context, output: OutputStream) {
        val feeds = RssManager.loadFeeds(context)
        val json = StringBuilder("[")

        feeds.forEachIndexed { i, feed ->
            if (i > 0) json.append(",")
            val items = RssManager.loadItems(context, feed)
            json.append("{")
            json.append("\"feed\":").append(jsonString(feed)).append(",")
            json.append("\"title\":").append(jsonString(RssManager.feedTitle(context, feed))).append(",")
            json.append("\"lastRefresh\":").append(RssManager.lastFeedRefreshMillis(context, feed)).append(",")
            json.append("\"autoDownload\":").append(RssManager.isAutoDownload(context, feed)).append(",")
            json.append("\"itemCount\":").append(items.size)
            json.append("}")
        }
        json.append("]")

        val minutes = RssManager.loadIntervalMinutes(context)
        sendJson(
            output,
            "{\"ok\":true,\"feeds\":$json,\"refreshing\":${RssManager.isRefreshing()}," +
                "\"lastRefresh\":${RssManager.lastRefreshMillis(context)}," +
                "\"intervalMinutes\":$minutes," +
                "\"intervalLabel\":${jsonString(RssManager.intervalLabel(minutes))}}"
        )
    }

    private fun handleRssAddFeed(context: Context, body: String, output: OutputStream) {
        val url = (extractJsonStringField(body, "url") ?: "").trim()

        if (url.isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"Enter a feed URL\"}")
            return
        }

        val feeds = RssManager.loadFeeds(context)

        if (feeds.contains(url)) {
            sendJson(output, "{\"ok\":false,\"error\":\"That feed is already added\"}")
            return
        }

        RssManager.saveFeeds(context, feeds + url)
        sendJson(output, "{\"ok\":true}")
    }

    private fun handleRssRemoveFeed(context: Context, body: String, output: OutputStream) {
        val feed = extractJsonStringField(body, "feed") ?: ""

        if (feed.isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing feed\"}")
            return
        }

        RssManager.saveFeeds(context, RssManager.loadFeeds(context).filter { it != feed })
        RssManager.removeFeedData(context, feed)
        sendJson(output, "{\"ok\":true}")
    }

    private fun handleRssRefresh(context: Context, body: String, output: OutputStream) {
        val feed = (extractJsonStringField(body, "feed") ?: "").trim()

        val result = if (feed.isBlank()) {
            RssManager.refreshAll(context, true)
        } else {
            RssManager.refreshFeed(context, feed, true)
        }

        if (result == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"A refresh is already running\"}")
            return
        }

        sendJson(
            output,
            "{\"ok\":true,\"feedsOk\":${result.feedsOk},\"feedsFailed\":${result.feedsFailed}," +
                "\"newItems\":${result.newItems}}"
        )
    }

    private fun handleRssSetAuto(context: Context, body: String, output: OutputStream) {
        val feed = extractJsonStringField(body, "feed") ?: ""
        // Sent as the string "true"/"false", not a JSON boolean - same
        // convention the rest of this file uses (e.g. confirm_magnet's
        // "index") so extractJsonStringField (which only matches quoted
        // string values) can read it.
        val enabled = extractJsonStringField(body, "enabled") == "true"

        if (feed.isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing feed\"}")
            return
        }

        RssManager.setAutoDownload(context, feed, enabled)
        sendJson(output, "{\"ok\":true}")
    }

    private fun handleRssItems(context: Context, feed: String?, output: OutputStream) {
        if (feed.isNullOrBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing feed\"}")
            return
        }

        val items = RssManager.loadItems(context, feed)
        val added = RssManager.addedKeys(context, feed)
        val json = StringBuilder("[")

        items.forEachIndexed { i, item ->
            if (i > 0) json.append(",")
            json.append("{")
            json.append("\"title\":").append(jsonString(item.title)).append(",")
            json.append("\"link\":").append(jsonString(item.link)).append(",")
            json.append("\"sizeText\":").append(jsonString(item.sizeText)).append(",")
            json.append("\"page\":").append(jsonString(item.page)).append(",")
            json.append("\"webPage\":").append(jsonString(RssManager.itemWebPage(item))).append(",")
            json.append("\"added\":").append(RssManager.isAdded(added, item))
            json.append("}")
        }
        json.append("]")

        sendJson(output, "{\"ok\":true,\"items\":$json}")
    }

    private fun rssItemFromBody(body: String): RssManager.RssItem {
        return RssManager.RssItem(
            title = extractJsonStringField(body, "title") ?: "",
            link = extractJsonStringField(body, "link") ?: "",
            sizeText = extractJsonStringField(body, "sizeText") ?: "",
            page = extractJsonStringField(body, "page") ?: ""
        )
    }

    // Mirrors handleOnlineSearchResolve above - resolves an RSS item to a
    // magnet link or downloaded .torrent file WITHOUT adding it or marking
    // it seen/added yet (RssManager.resolveItemForSelection), so the Web UI
    // can route it into the same magnet/file-selection flow as everything
    // else; markItemAdded below is only called once the user actually
    // commits to downloading it.
    private fun handleRssResolveItem(context: Context, body: String, output: OutputStream) {
        val item = rssItemFromBody(body)

        val resolved = try {
            RssManager.resolveItemForSelection(context, item)
        } catch (e: Throwable) {
            sendJson(output, "{\"ok\":false,\"error\":${jsonString(e.message ?: "Could not resolve")}}")
            return
        }

        val magnet = resolved.magnet
        val torrentPath = resolved.torrentPath

        if (!magnet.isNullOrBlank()) {
            sendJson(output, "{\"ok\":true,\"kind\":\"magnet\",\"magnet\":${jsonString(magnet)}}")
            return
        }

        if (!torrentPath.isNullOrBlank()) {
            val fileData = try {
                TorrentNative.getTorrentFiles(torrentPath)
            } catch (e: Throwable) {
                sendJson(output, "{\"ok\":false,\"error\":\"could not read resolved torrent file\"}")
                return
            }

            if (fileData.isBlank() || fileData.startsWith("ERROR")) {
                sendJson(output, "{\"ok\":false,\"error\":\"not a valid .torrent file\"}")
                return
            }

            sendJson(
                output,
                "{\"ok\":true,\"kind\":\"torrent\",\"path\":${jsonString(torrentPath)}," +
                    "\"files\":${buildFilesJsonArray(fileData)}}"
            )
            return
        }

        sendJson(output, "{\"ok\":false,\"error\":\"This item has no magnet link or .torrent file\"}")
    }

    private fun handleRssMarkAdded(context: Context, body: String, output: OutputStream) {
        val feed = extractJsonStringField(body, "feed") ?: ""

        if (feed.isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing feed\"}")
            return
        }

        RssManager.markItemAdded(context, feed, rssItemFromBody(body))
        sendJson(output, "{\"ok\":true}")
    }

    private fun handleRssGetInterval(context: Context, output: OutputStream) {
        val minutes = RssManager.loadIntervalMinutes(context)
        sendJson(
            output,
            "{\"ok\":true,\"minutes\":$minutes,\"label\":${jsonString(RssManager.intervalLabel(minutes))}}"
        )
    }

    private fun handleRssSetInterval(context: Context, body: String, output: OutputStream) {
        val minutes = extractJsonStringField(body, "minutes")?.toIntOrNull()

        if (minutes == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"bad interval\"}")
            return
        }

        RssManager.saveIntervalMinutes(context, minutes)
        sendJson(output, "{\"ok\":true,\"label\":${jsonString(RssManager.intervalLabel(minutes))}}")
    }

    // ---------------------------------------------------------------------
    // Feature: Save Folder - same "save_folder_settings" (global default,
    // already read by getGlobalSaveFolder() above) and "torrent_save_
    // folders" (per-torrent override) prefs the app's own Save Folder
    // screen and per-torrent "Change Save Folder" button use, same
    // per-torrent key shape (normalizeHash matches normalizeHashForDateKey).
    // A per-torrent change always goes through moveTorrentStorage(), same
    // as the app - never a plain prefs-only change on an existing torrent.

    private fun torrentSaveFolderPrefs(context: Context) =
        context.getSharedPreferences("torrent_save_folders", Context.MODE_PRIVATE)

    private fun getSavedSaveFolderForHash(context: Context, hash: String): String? {
        val key = normalizeHash(hash)
        if (key.isBlank()) return null
        return torrentSaveFolderPrefs(context).getString(key, null)
    }

    private fun handleGetSaveFolderApi(context: Context, output: OutputStream) {
        sendJson(output, "{\"ok\":true,\"path\":${jsonString(getGlobalSaveFolder(context))}}")
    }

    private fun handleSetSaveFolder(context: Context, body: String, output: OutputStream) {
        val path = (extractJsonStringField(body, "path") ?: "").trim()

        if (path.isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"Enter a folder path\"}")
            return
        }

        context.getSharedPreferences("save_folder_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("global_save_path", path)
            .apply()

        sendJson(output, "{\"ok\":true}")
    }

    private fun handleGetTorrentSaveFolder(context: Context, torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"bad torrent index\"}", status = "400 Bad Request")
            return
        }

        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        val path = getSavedSaveFolderForHash(context, hash) ?: getGlobalSaveFolder(context)
        sendJson(output, "{\"ok\":true,\"path\":${jsonString(path)}}")
    }

    private fun handleSetTorrentSaveFolder(context: Context, body: String, output: OutputStream) {
        val torrentIndex = extractJsonStringField(body, "index")?.toIntOrNull()
        val path = (extractJsonStringField(body, "path") ?: "").trim()

        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"bad torrent index\"}", status = "400 Bad Request")
            return
        }

        if (path.isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"Enter a folder path\"}")
            return
        }

        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        if (normalizeHash(hash).isBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"Torrent hash not available yet\"}")
            return
        }

        val moved = try {
            TorrentNative.moveTorrentStorage(hash, path)
        } catch (_: Throwable) {
            false
        }

        if (moved) {
            torrentSaveFolderPrefs(context).edit().putString(normalizeHash(hash), path).apply()
            sendJson(output, "{\"ok\":true}")
        } else {
            sendJson(output, "{\"ok\":false,\"error\":\"Could not change save folder\"}")
        }
    }

    // ---------------------------------------------------------------------
    // Feature: a graphical folder picker for the Web UI's Save Folder
    // panels - same plain java.io.File browsing MainActivity's own
    // showFolderPickerDialog does (no SAF/DocumentFile involved there
    // either), just driven from a web page instead of an AlertDialog:
    // list of subfolders to tap into, Up, and New Folder.

    private fun handleBrowseFolder(context: Context, pathParam: String?, output: OutputStream) {
        var dir = File(pathParam ?: "")
        if (!dir.isDirectory) {
            dir = File(getGlobalSaveFolder(context)).let {
                if (it.isDirectory) it else File("/storage/emulated/0")
            }
        }

        val subfolders = try {
            dir.listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name.lowercase() }
                ?.map { it.name }
                ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

        val json = StringBuilder("[")
        subfolders.forEachIndexed { i, name ->
            if (i > 0) json.append(",")
            json.append(jsonString(name))
        }
        json.append("]")

        sendJson(
            output,
            "{\"ok\":true,\"path\":${jsonString(dir.absolutePath)}," +
                "\"parent\":${dir.parentFile?.absolutePath?.let { jsonString(it) } ?: "null"}," +
                "\"subfolders\":$json}"
        )
    }

    private fun handleCreateFolder(context: Context, body: String, output: OutputStream) {
        val parentPath = extractJsonStringField(body, "parentPath") ?: ""
        val name = (extractJsonStringField(body, "name") ?: "").trim()

        if (name.isBlank() || name.contains("/") || name.contains("\\")) {
            sendJson(output, "{\"ok\":false,\"error\":\"Enter a valid folder name\"}")
            return
        }

        val parent = File(parentPath)
        if (!parent.isDirectory) {
            sendJson(output, "{\"ok\":false,\"error\":\"Parent folder no longer exists\"}")
            return
        }

        val newFolder = File(parent, name)

        if (newFolder.exists() && !newFolder.isDirectory) {
            sendJson(output, "{\"ok\":false,\"error\":\"A file with that name already exists\"}")
            return
        }

        if (!newFolder.exists() && !newFolder.mkdirs()) {
            sendJson(output, "{\"ok\":false,\"error\":\"Could not create that folder\"}")
            return
        }

        sendJson(output, "{\"ok\":true,\"path\":${jsonString(newFolder.absolutePath)}}")
    }

    private fun handleAllAction(context: Context, action: String, output: OutputStream) {
        val intent = Intent(context, TorrentService::class.java)
        intent.putExtra("ACTION", action)
        startTorrentService(context, intent)
        sendJson(output, "{\"ok\":true}")
    }

    private fun handleTorrentAction(
        context: Context,
        action: String,
        torrentIndex: Int?,
        deleteFiles: Boolean,
        output: OutputStream
    ) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"bad torrent index\"}", status = "400 Bad Request")
            return
        }

        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        val magnet = try {
            TorrentNative.getTorrentMagnet(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        val intent = Intent(context, TorrentService::class.java)
        intent.putExtra("ACTION", action)
        intent.putExtra("TORRENT_INDEX", torrentIndex)
        intent.putExtra("TORRENT_HASH", hash)
        intent.putExtra("TORRENT_MAGNET", magnet)

        if (action == "REMOVE_TORRENT") {
            intent.putExtra("DELETE_FILES", deleteFiles)
        }

        startTorrentService(context, intent)
        AppLog.info("Web UI: $action on torrent $torrentIndex")
        sendJson(output, "{\"ok\":true}")
    }

    // Adds a magnet link only, every file selected - the same as the
    // app's own quick "Add Magnet / Hash" box on the main screen.
    private fun handleAdd(context: Context, body: String, output: OutputStream) {
        val raw = extractJsonStringField(body, "magnet")?.trim()

        if (raw.isNullOrBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing magnet\"}", status = "400 Bad Request")
            return
        }

        val magnet = if (raw.startsWith("magnet:", ignoreCase = true)) {
            raw
        } else {
            "magnet:?xt=urn:btih:$raw"
        }

        val intent = Intent(context, TorrentService::class.java)
        intent.putExtra("MAGNET", magnet)
        startTorrentService(context, intent)

        AppLog.info("Web UI: magnet added")
        sendJson(output, "{\"ok\":true}")
    }

    // Feature: upload a .torrent file from the browser. The raw bytes are
    // saved to a temp file under the app's own files dir, then read back
    // with the same native call (getTorrentFiles) the app's own file-
    // selection screen uses, so the browser can show a tick-box list and
    // the user picks which files to actually download before anything is
    // added - this does NOT add the torrent yet, see handleAddTorrentPath.
    private fun handleUploadTorrent(context: Context, bytes: ByteArray, output: OutputStream) {
        if (bytes.isEmpty()) {
            sendJson(output, "{\"ok\":false,\"error\":\"empty upload\"}", status = "400 Bad Request")
            return
        }

        val dir = File(context.filesDir, "webui_uploads")
        dir.mkdirs()

        val file = File(dir, "webui_${System.currentTimeMillis()}.torrent")

        try {
            file.writeBytes(bytes)
        } catch (e: Throwable) {
            sendServerError(output, "could not save upload: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        val fileData = try {
            TorrentNative.getTorrentFiles(file.absolutePath)
        } catch (e: Throwable) {
            sendServerError(output, "could not read torrent: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        if (fileData.isBlank() || fileData.startsWith("ERROR")) {
            sendJson(output, "{\"ok\":false,\"error\":\"not a valid .torrent file\"}", status = "400 Bad Request")
            return
        }

        AppLog.info("Web UI: .torrent uploaded (${file.name})")

        sendJson(
            output,
            "{\"ok\":true,\"path\":${jsonString(file.absolutePath)},\"files\":${buildFilesJsonArray(fileData)}}"
        )
    }

    // Feature: file selection for a magnet link. Fetches metadata only
    // (paused, same as MainActivity.showMagnetMetadataScreen) so the
    // browser can poll handleFilesList until the file list is ready and
    // let the user pick which ones to download - handleConfirmMagnet
    // does the actual download start. This does NOT persist or resume
    // anything yet, so cancelling just means removing this torrent (the
    // existing /api/remove/{index} route already does that).
    private fun handleAddMagnetMetadata(context: Context, body: String, output: OutputStream) {
        val raw = extractJsonStringField(body, "magnet")?.trim()

        if (raw.isNullOrBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing magnet\"}", status = "400 Bad Request")
            return
        }

        val magnet = if (raw.startsWith("magnet:", ignoreCase = true)) {
            raw
        } else {
            "magnet:?xt=urn:btih:$raw"
        }

        val requestedHash = normalizeHash(extractHashFromMagnetInput(magnet))

        // Bug fix: a magnet from a flaky source (RSS, Online Search) can
        // sit forever with its metadata fetch never finishing - slow/dead
        // peers, no seeders yet, etc. That half-added torrent is still
        // hidden behind pendingMagnetHashes (see addMagnetPaused below),
        // so hasTorrentHash() sees it as real and every later attempt to
        // add the SAME item was refused as "Torrent already added" -
        // forever, with no way to retry, which is what "torrents refuse
        // to add" from RSS/Online Search traced back to. If this exact
        // hash is still only pending (never confirmed), reconnect to
        // that SAME paused torrent instead of refusing the add, so the
        // Web UI can resume waiting on it.
        if (requestedHash.isNotBlank() && pendingMagnetHashes.contains(requestedHash)) {
            val existingIndex = findTorrentIndexByHash(requestedHash)

            if (existingIndex != null) {
                AppLog.info("Web UI: reconnecting to a pending magnet fetch (torrent $existingIndex)")
                sendJson(output, "{\"ok\":true,\"index\":$existingIndex,\"magnet\":${jsonString(magnet)}}")
                return
            }

            // The hash was marked pending but no such torrent exists any
            // more (removed from elsewhere, or a transient lookup
            // failure) - the flag is stale, so drop it and add fresh
            // below rather than refusing forever.
            pendingMagnetHashes.remove(requestedHash)
        }

        // Same check MainActivity.showMagnetMetadataScreen does before
        // it ever calls addMagnetPaused - pulls the hash straight out of
        // the magnet text itself (no torrent handle needed yet) and
        // checks it against every torrent already added. Without this,
        // "re-adding" a torrent that's already downloading opens a
        // SECOND handle for the same hash, which addMagnetPaused pauses
        // - so the real, already-running download stops the instant its
        // paused twin is created, and starts fetching metadata it
        // already has as if from scratch.
        if (isTorrentAlreadyAddedByHash(requestedHash)) {
            sendJson(output, "{\"ok\":false,\"alreadyAdded\":true,\"error\":\"Torrent already added\"}", status = "409 Conflict")
            return
        }

        val torrentIndex = try {
            TorrentNative.addMagnetPaused(magnet, getGlobalSaveFolder(context))
        } catch (e: Throwable) {
            -1
        }

        if (torrentIndex <= 0) {
            sendJson(output, "{\"ok\":false,\"error\":\"could not open magnet\"}", status = "400 Bad Request")
            return
        }

        // A magnet link's info-hash is known immediately (it's part of
        // the magnet URI itself), so this is available well before
        // metadata/file-list is - that's exactly why it's safe to hide
        // this torrent by hash starting right now.
        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        val key = normalizeHash(hash)
        if (key.isNotBlank()) {
            pendingMagnetHashes.add(key)
        }

        AppLog.info("Web UI: fetching magnet metadata (torrent $torrentIndex)")
        sendJson(output, "{\"ok\":true,\"index\":$torrentIndex,\"magnet\":${jsonString(magnet)}}")
    }

    // Shared by /api/magnet_files (while metadata is still being
    // fetched) and /api/torrent_files (an already fully-added torrent,
    // for browsing/playing/downloading its files) - both just need "is
    // the file list ready, and if so what is it" for one torrent index.
    private fun handleFilesList(context: Context, torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val fileData = try {
            TorrentNative.getTorrentFilesByIndex(torrentIndex)
        } catch (e: Throwable) {
            ""
        }

        if (fileData.isBlank() ||
            fileData == "Metadata not ready" ||
            fileData == "Invalid torrent" ||
            fileData == "No files"
        ) {
            sendJson(output, "{\"ok\":true,\"ready\":false,\"files\":[]}")
            return
        }

        sendJson(output, "{\"ok\":true,\"ready\":true,\"files\":${buildFilesJsonArrayWithProgress(context, torrentIndex, fileData)}}")
    }

    // Feature: per-peer list (IP, client software, TCP/uTP, progress,
    // speeds) for one torrent - MainActivity's own Peers screen in text
    // form. TorrentNative.getTorrentPeers already returns a ready-to-
    // display block ("Invalid torrent"/"No peers" included), so this is
    // just wrapped as JSON rather than reparsed.
    private fun handleTorrentPeers(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val text = try {
            TorrentNative.getTorrentPeers(torrentIndex)
        } catch (e: Throwable) {
            "Could not load peers"
        }

        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text)}}")
    }

    // Feature: "Trackers" tab - mirrors MainActivity's trackersButton
    // exactly: try getTorrentTrackerStatus first (the detailed per-
    // tracker status block), and only if that throws, fall back to the
    // plain URL list (getTorrentTrackers) under a "Trackers\n\n" header;
    // if even that throws, the same "Could not load tracker status".
    private fun handleTorrentTrackers(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val text = try {
            TorrentNative.getTorrentTrackerStatus(torrentIndex)
        } catch (e: Throwable) {
            try {
                "Trackers\n\n" + TorrentNative.getTorrentTrackers(torrentIndex)
            } catch (e2: Throwable) {
                "Could not load tracker status"
            }
        }

        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text)}}")
    }

    // Feature: "Web Seeds" tab - mirrors MainActivity's webSeedsButton
    // exactly: getTorrentWebSeeds under a "Web Seeds\n\n" header.
    private fun handleTorrentWebSeeds(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val webSeeds = try {
            TorrentNative.getTorrentWebSeeds(torrentIndex)
        } catch (e: Throwable) {
            "Could not load web seeds"
        }

        sendJson(output, "{\"ok\":true,\"text\":${jsonString("Web Seeds\n\n" + webSeeds)}}")
    }

    // Feature: "Statistics" tab - mirrors MainActivity's statisticsButton
    // exactly: TorrentNative.getTorrentStatistics as-is, no extra header.
    private fun handleTorrentStatistics(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val stats = try {
            TorrentNative.getTorrentStatistics(torrentIndex)
        } catch (e: Throwable) {
            "Could not load statistics"
        }

        sendJson(output, "{\"ok\":true,\"text\":${jsonString(stats)}}")
    }

    // Feature: "Comment" tab - mirrors MainActivity's commentButton
    // exactly: "Torrent Comment\n\n" + getTorrentComment.
    private fun handleTorrentComment(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val comment = try {
            TorrentNative.getTorrentComment(torrentIndex)
        } catch (e: Throwable) {
            "Could not load torrent comment"
        }

        sendJson(output, "{\"ok\":true,\"text\":${jsonString("Torrent Comment\n\n" + comment)}}")
    }

    // Feature: "Pieces" tab - mirrors MainActivity.showPiecesDetails
    // exactly: getTorrentPieces, plus getTorrentPieceSize appended when
    // it isn't blank.
    private fun handleTorrentPieces(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val pieceInfo = try {
            TorrentNative.getTorrentPieces(torrentIndex)
        } catch (e: Throwable) {
            "Could not load pieces"
        }

        val pieceSize = try {
            TorrentNative.getTorrentPieceSize(torrentIndex)
        } catch (e: Throwable) {
            ""
        }

        val text = if (pieceSize.isBlank()) pieceInfo else "$pieceInfo\n\n$pieceSize"
        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text)}}")
    }

    // Feature: "Swarm Health" tab - mirrors MainActivity's swarmButton
    // exactly: TorrentNative.getTorrentSwarmHealth as-is (it already
    // carries its own "Swarm Health" heading).
    private fun handleTorrentSwarmHealth(torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val text = try {
            TorrentNative.getTorrentSwarmHealth(torrentIndex)
        } catch (e: Throwable) {
            "Could not load swarm health"
        }

        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text)}}")
    }

    // Feature: "General" tab - mirrors MainActivity.showGeneralDetails
    // field-for-field: same native getters, same "torrent_dates" prefs
    // lookup for Added/Completed (by hash, same normalizeHash this file
    // already uses elsewhere - never list index), and the same status-
    // line parsing (split on " • ") for the fields getDetailedStatus
    // carries inline rather than through their own getter.
    private fun handleTorrentGeneral(context: Context, torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        val statusLine = run {
            val status = try {
                TorrentNative.getDetailedStatus()
            } catch (e: Throwable) {
                ""
            }
            val lines = status.lines().filter { it.isNotBlank() }
            if (torrentIndex - 1 in lines.indices) lines[torrentIndex - 1] else ""
        }

        fun fieldAfterName(fieldIndex: Int): String =
            statusLine.split(" • ").getOrNull(fieldIndex) ?: "--"

        fun fieldByPrefix(prefix: String): String =
            statusLine.split(" • ").firstOrNull { it.trim().startsWith(prefix) } ?: "--"

        fun fieldByWord(word: String): String =
            statusLine.split(" • ").firstOrNull { it.trim().startsWith(word) }
                ?.removePrefix(word)?.trim() ?: "--"

        fun eta(): String =
            statusLine.split(" • ").firstOrNull { it.trim().startsWith("ETA:") }
                ?.removePrefix("ETA:")?.trim() ?: "--"

        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (e: Throwable) {
            "Hash not ready"
        }

        fun formatTorrentDate(time: Long): String {
            if (time <= 0L) return "Unknown"
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
            } catch (e: Throwable) {
                "Unknown"
            }
        }

        fun datePrefsValue(prefix: String): String {
            val key = normalizeHash(hash)
            if (key.isBlank()) return if (prefix == "completed") "Not completed" else "Unknown"
            val time = context.getSharedPreferences("torrent_dates", Context.MODE_PRIVATE)
                .getLong("${prefix}_$key", 0L)
            if (prefix == "completed" && time <= 0L) return "Not completed"
            return formatTorrentDate(time)
        }

        val addedDate = datePrefsValue("added")
        val completedDate = datePrefsValue("completed")

        val trackerHost = try {
            TorrentNative.getTorrentTrackerHost(torrentIndex)
        } catch (e: Throwable) {
            "Unknown tracker"
        }

        val totalSize = try {
            TorrentNative.getTorrentTotalSize(torrentIndex)
        } catch (e: Throwable) {
            "Size not ready"
        }

        val availability = try {
            TorrentNative.getTorrentAvailability(torrentIndex)
        } catch (e: Throwable) {
            "Availability unknown"
        }

        val swarmHealth = try {
            TorrentNative.getTorrentSwarmHealth(torrentIndex)
        } catch (e: Throwable) {
            "Swarm health unavailable"
        }

        val webSeedCount = try {
            TorrentNative.getTorrentWebSeedCount(torrentIndex)
        } catch (e: Throwable) {
            "0"
        }

        val creatorRaw = try {
            TorrentNative.getTorrentCreator(torrentIndex)
        } catch (e: Throwable) {
            "Unknown"
        }

        val creator = if (creatorRaw.isBlank() || creatorRaw.equals("Unknown", ignoreCase = true)) {
            "Unknown (not in this torrent's metadata - magnet-added torrents usually can't carry this field, and some tools don't write it either)"
        } else {
            creatorRaw
        }

        val createdDateRaw = try {
            TorrentNative.getTorrentCreationDate(torrentIndex)
        } catch (e: Throwable) {
            "Unknown"
        }

        val createdDate = if (createdDateRaw.isBlank() || createdDateRaw.equals("Unknown", ignoreCase = true)) {
            "Unknown (same reason as Creator, above)"
        } else {
            createdDateRaw
        }

        val privateTorrent = try {
            if (TorrentNative.isPrivateTorrent(torrentIndex)) "Yes" else "No"
        } catch (e: Throwable) {
            "Unknown"
        }

        val source = try {
            TorrentNative.getTorrentSource(torrentIndex)
        } catch (e: Throwable) {
            "Unknown"
        }

        val encoding = try {
            TorrentNative.getTorrentEncoding(torrentIndex)
        } catch (e: Throwable) {
            "Unknown"
        }

        val name = statusLine.split(" • ").firstOrNull()?.ifBlank { "Torrent $torrentIndex" } ?: "Torrent $torrentIndex"
        val state = fieldAfterName(1)
        val progress = fieldAfterName(2)
        val download = fieldByPrefix("↓")
        val upload = fieldByPrefix("↑")
        val seeds = fieldByWord("Seeds")
        val peers = fieldByWord("Peers")

        val text = StringBuilder()
        text.append("General\n\n")
        text.append("Name:\n").append(name).append("\n\n")
        text.append("Created with: ").append(creator).append("\n")
        text.append("Created on: ").append(createdDate).append("\n\n")
        text.append("Status: ").append(state).append("\n")
        text.append("Progress: ").append(progress).append("\n")
        text.append("Download: ").append(download).append("\n")
        text.append("Upload: ").append(upload).append("\n")
        text.append("Seeds: ").append(seeds).append("\n")
        text.append("Peers: ").append(peers).append("\n")
        text.append("ETA: ").append(eta()).append("\n")
        text.append("Added: ").append(addedDate).append("\n")
        text.append("Completed: ").append(completedDate).append("\n")
        text.append(trackerHost).append("\n")
        text.append("Total size: ").append(totalSize).append("\n")
        text.append("Availability: ").append(availability).append("\n")
        text.append(swarmHealth).append("\n")
        text.append("Web Seeds: ").append(webSeedCount).append("\n")
        text.append("Private: ").append(privateTorrent).append("\n")
        text.append("Source: ").append(source).append("\n")
        text.append("Encoding: ").append(encoding).append("\n")

        sendJson(output, "{\"ok\":true,\"text\":${jsonString(text.toString())}}")
    }

    // Feature: Force Recheck / Force Reannounce - same native calls as
    // MainActivity's own buttons (both fire-and-forget, no return value).
    private fun handleForceAction(kind: String, torrentIndex: Int?, output: OutputStream) {
        if (torrentIndex == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        try {
            if (kind == "recheck") {
                TorrentNative.forceRecheck(torrentIndex)
            } else {
                TorrentNative.forceReannounce(torrentIndex)
            }
        } catch (e: Throwable) {
            sendJson(output, "{\"ok\":false,\"error\":${jsonString(e.message ?: "failed")}}", status = "500 Internal Server Error")
            return
        }

        sendJson(output, "{\"ok\":true}")
    }

    // Reads the saved "selected_<hash>" CSV from the same "file_priorities"
    // prefs file MainActivity.saveFileSelectionForTorrent/
    // getSavedFileSelectionForTorrent use, so a selection made in the app
    // or the Web UI is picked up by both. Null means "nothing saved yet",
    // which (matching MainActivity.showFilesForOpening exactly) means
    // every file defaults to selected.
    private fun getSavedFileSelectionForTorrent(context: Context, torrentIndex: Int): Set<Int>? {
        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        val key = normalizeHash(hash)
        if (key.isBlank()) return null

        val value = context.getSharedPreferences("file_priorities", Context.MODE_PRIVATE)
            .getString("selected_$key", null) ?: return null

        return value.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    // Same shape as buildFilesJsonArray, plus each file's own download
    // percent (TorrentNative.getTorrentFileProgress), its Seeds/Peers
    // swarm counts (TorrentNative.getTorrentFileSwarm - same figures
    // MainActivity's Files tab shows inline per file), and whether it's
    // currently selected to download (for the Web UI's own "Priority"
    // tab checkboxes). A magnet still mid file-selection (not yet
    // confirmed - see pendingMagnetHashes) has no real progress/swarm
    // data yet, so this falls back to 0 for every file rather than
    // failing the whole list; that's also what getTorrentFileProgress/
    // getTorrentFileSwarm themselves return for anything not ready yet.
    private fun buildFilesJsonArrayWithProgress(context: Context, torrentIndex: Int, fileData: String): String {
        val progressByIndex = mutableMapOf<Int, Int>()

        val progressData = try {
            TorrentNative.getTorrentFileProgress(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        for (line in progressData.split("\n")) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 5)
            if (parts.size < 5) continue

            val fileIndex = parts[0].toIntOrNull() ?: continue
            val percent = parts[4].toIntOrNull() ?: continue

            progressByIndex[fileIndex] = percent
        }

        data class Swarm(val seeds: Int, val peers: Int, val totalSeeds: Int, val totalPeers: Int)
        val swarmByIndex = mutableMapOf<Int, Swarm>()

        val swarmData = try {
            TorrentNative.getTorrentFileSwarm(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        for (line in swarmData.split("\n")) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 6)
            if (parts.size < 6) continue

            val fileIndex = parts[0].toIntOrNull() ?: continue
            val seeds = parts[1].toIntOrNull() ?: 0
            val peers = parts[2].toIntOrNull() ?: 0
            val totalSeeds = parts[3].toIntOrNull() ?: 0
            val totalPeers = parts[4].toIntOrNull() ?: 0

            swarmByIndex[fileIndex] = Swarm(seeds, peers, totalSeeds, totalPeers)
        }

        val savedSelection = getSavedFileSelectionForTorrent(context, torrentIndex)

        val filesJson = StringBuilder("[")
        var first = true

        for (line in fileData.split("\n")) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val size = parts[2].toLongOrNull() ?: 0L
            val percent = progressByIndex[index] ?: 0
            val swarm = swarmByIndex[index]
            val selected = savedSelection?.contains(index) ?: true

            if (!first) filesJson.append(",")
            first = false

            filesJson.append("{\"index\":").append(index)
                .append(",\"name\":").append(jsonString(name))
                .append(",\"size\":").append(size)
                .append(",\"percent\":").append(percent)
                .append(",\"seeds\":").append(swarm?.seeds ?: 0)
                .append(",\"peers\":").append(swarm?.peers ?: 0)
                .append(",\"totalSeeds\":").append(swarm?.totalSeeds ?: 0)
                .append(",\"totalPeers\":").append(swarm?.totalPeers ?: 0)
                .append(",\"selected\":").append(selected)
                .append("}")
        }

        filesJson.append("]")
        return filesJson.toString()
    }

    // Feature: lets the user change which files download on a torrent
    // that's already been added - not just at add-time. Mirrors
    // MainActivity.applyFilePrioritySelectionNow exactly: sets the file
    // priorities natively, then saves the selection to the same
    // "file_priorities" prefs file (by hash, never list index - see the
    // pendingMagnetHashes comment above for why) so it survives and is
    // shared with the app's own Files tab.
    private fun handleSetFilePriorities(context: Context, body: String, output: OutputStream) {
        val index = extractJsonStringField(body, "index")?.toIntOrNull()
        val selected = extractJsonStringField(body, "selected") ?: ""

        if (index == null) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index\"}", status = "400 Bad Request")
            return
        }

        try {
            TorrentNative.setTorrentFilePriorities(index, selected)
        } catch (e: Throwable) {
            sendJson(output, "{\"ok\":false,\"error\":${jsonString(e.message ?: "Could not apply file selection")}}", status = "500 Internal Server Error")
            return
        }

        val hash = try {
            TorrentNative.getTorrentHash(index)
        } catch (_: Throwable) {
            ""
        }

        val key = normalizeHash(hash)
        if (key.isNotBlank()) {
            context.getSharedPreferences("file_priorities", Context.MODE_PRIVATE)
                .edit()
                .putString("selected_$key", selected)
                .commit()
        }

        sendJson(output, "{\"ok\":true}")
    }

    // Feature: confirms a magnet's file selection and actually starts
    // the download - the same two steps MainActivity's "Download
    // Selected"/"Download All" buttons do for a magnet: set file
    // priorities (only when a subset was picked - leaving every file at
    // its default priority is what "download all" means here, same as
    // the .torrent-file flow's SELECTED_INDEXES being omitted) and
    // persist the magnet (SAVE_MAGNET_ONLY, exactly what
    // MainActivity.saveMagnetOnly() sends) plus the selection (written
    // to the same "file_priorities" prefs file
    // saveFileSelectionForTorrent() uses) so this torrent survives an
    // app restart like any other, before resuming it.
    private fun handleConfirmMagnet(context: Context, body: String, output: OutputStream) {
        val index = extractJsonStringField(body, "index")?.toIntOrNull()
        val magnet = extractJsonStringField(body, "magnet")
        val selected = extractJsonStringField(body, "selected")

        if (index == null || magnet.isNullOrBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing index or magnet\"}", status = "400 Bad Request")
            return
        }

        val hash = try {
            TorrentNative.getTorrentHash(index)
        } catch (_: Throwable) {
            ""
        }

        val key = normalizeHash(hash)

        // Confirmed - this torrent is real now, so it's allowed to show
        // up in the list from here on.
        if (key.isNotBlank()) {
            pendingMagnetHashes.remove(key)
        }

        if (!selected.isNullOrBlank()) {
            try {
                TorrentNative.setTorrentFilePriorities(index, selected)
            } catch (_: Throwable) {
            }

            if (key.isNotBlank()) {
                context.getSharedPreferences("file_priorities", Context.MODE_PRIVATE)
                    .edit()
                    .putString("selected_$key", selected)
                    .commit()
            }
        }

        val saveIntent = Intent(context, TorrentService::class.java)
        saveIntent.putExtra("ACTION", "SAVE_MAGNET_ONLY")
        saveIntent.putExtra("MAGNET", magnet)
        startTorrentService(context, saveIntent)

        try {
            TorrentNative.resumeTorrent(index)
        } catch (_: Throwable) {
        }

        AppLog.info("Web UI: magnet download confirmed (torrent $index)")
        sendJson(output, "{\"ok\":true}")
    }

    // Parses "index/fileIndex" (what's left of the URL after /stream/ or
    // /download/) and hands off to handleFileServe.
    private fun handleStreamOrDownload(
        pathSuffix: String,
        rangeHeader: String?,
        attachment: Boolean,
        output: OutputStream
    ) {
        val segments = pathSuffix.split("/")
        val torrentIndex = segments.getOrNull(0)?.toIntOrNull()
        val fileIndex = segments.getOrNull(1)?.toIntOrNull()

        if (torrentIndex == null || fileIndex == null) {
            sendNotFound(output)
            return
        }

        handleFileServe(torrentIndex, fileIndex, rangeHeader, attachment, output)
    }

    // Feature: serves one file's actual bytes straight off disk, with
    // HTTP byte-range support (what lets a browser's <video>/<audio>
    // element seek instead of only ever starting from byte 0, and what
    // lets a stalled download resume where it left off). The file's
    // real location is save_path (read live from the handle, see
    // getTorrentSavePath in torrentor.cpp) plus its own relative path
    // from getTorrentFilesByIndex - never a path the browser supplies
    // itself, so this can't be used to read arbitrary files on the
    // device. A file that hasn't finished downloading yet (or doesn't
    // exist on disk at all) is reported as 404 rather than serving
    // whatever partial/garbage bytes happen to be there.
    private fun handleFileServe(
        torrentIndex: Int,
        fileIndex: Int,
        rangeHeader: String?,
        attachment: Boolean,
        output: OutputStream
    ) {
        val savePath = try {
            TorrentNative.getTorrentSavePath(torrentIndex)
        } catch (e: Throwable) {
            ""
        }

        if (savePath.isBlank()) {
            sendNotFound(output)
            return
        }

        val fileData = try {
            TorrentNative.getTorrentFilesByIndex(torrentIndex)
        } catch (e: Throwable) {
            ""
        }

        var relativePath: String? = null

        for (line in fileData.split("\n")) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            if (parts[0].toIntOrNull() == fileIndex) {
                relativePath = parts[1]
                break
            }
        }

        if (relativePath == null) {
            sendNotFound(output)
            return
        }

        val file = File(savePath, relativePath)

        if (!file.exists() || !file.isFile) {
            sendJson(output, "{\"ok\":false,\"error\":\"file not downloaded yet\"}", status = "404 Not Found")
            return
        }

        val totalLength = file.length()
        var start = 0L
        var end = totalLength - 1
        var partial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
            val rangeParts = rangeHeader.removePrefix("bytes=").split("-", limit = 2)
            val rangeStart = rangeParts.getOrNull(0)?.toLongOrNull()
            val rangeEnd = rangeParts.getOrNull(1)?.toLongOrNull()

            if (rangeStart != null) {
                start = rangeStart
                end = rangeEnd ?: (totalLength - 1)
                partial = true
            } else if (rangeEnd != null) {
                // "bytes=-500" means "the last 500 bytes".
                start = (totalLength - rangeEnd).coerceAtLeast(0)
                end = totalLength - 1
                partial = true
            }
        }

        if (end > totalLength - 1) end = totalLength - 1
        if (start < 0) start = 0

        if (totalLength > 0 && start > end) {
            val header = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */$totalLength\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            return
        }

        val length = if (totalLength == 0L) 0L else end - start + 1
        val disposition = if (attachment) "attachment" else "inline"
        val safeFileName = file.name.replace("\"", "'")

        val headerBuilder = StringBuilder()
        headerBuilder.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        headerBuilder.append("Content-Type: ${guessContentType(file.name)}\r\n")
        headerBuilder.append("Accept-Ranges: bytes\r\n")
        headerBuilder.append("Content-Disposition: $disposition; filename=\"$safeFileName\"\r\n")
        headerBuilder.append("Content-Length: $length\r\n")

        if (partial) {
            headerBuilder.append("Content-Range: bytes $start-$end/$totalLength\r\n")
        }

        headerBuilder.append("Connection: close\r\n\r\n")
        output.write(headerBuilder.toString().toByteArray(StandardCharsets.UTF_8))

        if (length > 0) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(65536)
                var remaining = length

                while (remaining > 0) {
                    val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                    val n = raf.read(buffer, 0, toRead)
                    if (n == -1) break
                    output.write(buffer, 0, n)
                    remaining -= n
                }
            }
        }

        output.flush()
    }

    private fun guessContentType(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "txt", "nfo", "srt", "vtt", "log", "md" -> "text/plain; charset=utf-8"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    // The default download folder, the same SharedPreferences
    // MainActivity.getGlobalSaveFolder() reads - read directly here
    // rather than through MainActivity, since a Web UI request may
    // arrive with no Activity around at all.
    private fun getGlobalSaveFolder(context: Context): String {
        return context.getSharedPreferences("save_folder_settings", Context.MODE_PRIVATE)
            .getString("global_save_path", "/storage/emulated/0/Download")
            ?: "/storage/emulated/0/Download"
    }

    // Mirrors MainActivity.normalizeHashForDateKey() - same per-torrent
    // SharedPreferences key shape, used here so a Web UI file selection
    // for a magnet is found the same way an in-app one would be.
    private fun normalizeHash(hash: String): String {
        return hash.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    // Mirrors MainActivity.extractHashFromMagnetInput() exactly - pulls
    // the info-hash straight out of magnet text, no torrent handle
    // involved, so it can be checked against existing torrents before
    // anything is added.
    private fun extractHashFromMagnetInput(value: String): String {
        val text = value.trim()

        val btihIndex = text.indexOf("btih:", ignoreCase = true)
        if (btihIndex >= 0) {
            val start = btihIndex + "btih:".length
            val end = text.indexOf("&", start)

            return if (end >= 0) {
                text.substring(start, end).trim().lowercase()
            } else {
                text.substring(start).trim().lowercase()
            }
        }

        return text
            .removePrefix("magnet:?xt=urn:")
            .removePrefix("btih:")
            .trim()
            .lowercase()
    }

    // Finds a torrent's index by its info-hash, used by
    // handleAddMagnetMetadata to reconnect a repeated add to the SAME
    // already-paused torrent instead of refusing it outright. Same
    // per-index getTorrentHash() walk buildStatusJson's loop does,
    // bounded by the same line count getDetailedStatus() gives it.
    private fun findTorrentIndexByHash(hash: String): Int? {
        if (hash.isBlank()) return null

        val raw = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        if (raw.isBlank() || raw == "No torrents" || raw == "No active torrents") {
            return null
        }

        for (index in 1..raw.lines().size) {
            val candidate = try {
                normalizeHash(TorrentNative.getTorrentHash(index))
            } catch (_: Throwable) {
                ""
            }

            if (candidate == hash) return index
        }

        return null
    }

    // Mirrors MainActivity.isTorrentAlreadyAddedByHash().
    private fun isTorrentAlreadyAddedByHash(hash: String): Boolean {
        if (hash.isBlank()) {
            return false
        }

        return try {
            TorrentNative.hasTorrentHash(hash)
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildFilesJsonArray(fileData: String): String {
        val filesJson = StringBuilder("[")
        var first = true

        for (line in fileData.split("\n")) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val size = parts[2].toLongOrNull() ?: 0L

            if (!first) filesJson.append(",")
            first = false

            filesJson.append("{\"index\":").append(index)
                .append(",\"name\":").append(jsonString(name))
                .append(",\"size\":").append(size)
                .append("}")
        }

        filesJson.append("]")
        return filesJson.toString()
    }

    private fun parseQueryParam(query: String, key: String): String? {
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue

            val parts = pair.split("=", limit = 2)
            if (parts.getOrNull(0) == key) {
                val raw = parts.getOrNull(1) ?: return ""

                return try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (_: Throwable) {
                    raw
                }
            }
        }

        return null
    }

    // Feature: actually adds the .torrent a prior /api/upload_torrent
    // call saved, with either every file (selected blank/absent) or just
    // the ticked ones - the same TORRENT_PATH/SELECTED_INDEXES Intent the
    // app's own "Download Selected"/"Download All" buttons send.
    private fun handleAddTorrentPath(context: Context, body: String, output: OutputStream) {
        val path = extractJsonStringField(body, "path")
        val selected = extractJsonStringField(body, "selected")

        if (path.isNullOrBlank()) {
            sendJson(output, "{\"ok\":false,\"error\":\"missing path\"}", status = "400 Bad Request")
            return
        }

        val intent = Intent(context, TorrentService::class.java)
        intent.putExtra("TORRENT_PATH", path)

        if (!selected.isNullOrBlank()) {
            intent.putExtra("SELECTED_INDEXES", selected)
        }

        startTorrentService(context, intent)

        AppLog.info("Web UI: .torrent added from upload")
        sendJson(output, "{\"ok\":true}")
    }

    // Minimal, dependency-free extraction of one top-level string field
    // from a small JSON body like {"magnet":"..."} - this server never
    // needs anything more than a couple of flat fields, so pulling in a
    // real JSON parser would be overkill.
    private fun extractJsonStringField(json: String, field: String): String? {
        val pattern = Regex("\"" + Regex.escape(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val match = pattern.find(json) ?: return null

        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\\\", "\\")
    }

    private fun startTorrentService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // ---------------------------------------------------------- HTTP responses

    private fun sendHtml(output: OutputStream, html: String) {
        writeResponse(output, "200 OK", "text/html; charset=utf-8", html.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendJson(output: OutputStream, json: String, status: String = "200 OK") {
        writeResponse(output, status, "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendNotFound(output: OutputStream) {
        writeResponse(output, "404 Not Found", "text/plain; charset=utf-8", "Not found".toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendServerError(output: OutputStream, message: String) {
        val json = "{\"ok\":false,\"error\":${jsonString(message)}}"
        writeResponse(output, "500 Internal Server Error", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendUnauthorized(output: OutputStream) {
        val bytes = "Authentication required".toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 401 Unauthorized\r\n" +
                "WWW-Authenticate: Basic realm=\"TorrentOr\"\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeResponse(output: OutputStream, status: String, contentType: String, bytes: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    // ---------------------------------------------------------- page

    private val INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TorrentOr</title>
<style>
  /* Modern dark theme: neutral graphite surfaces (same family as the speed
     graph panel), teal as the main accent and purple as the secondary one
     (the same two colors the download/upload graph uses), soft borders and
     shadows instead of flat blocks, rounded cards, and gentle hover/focus
     transitions. Every color is a custom property, so the whole palette
     can still be swapped in one place. Style only - no markup or script
     depends on anything in here. */
  :root {
    color-scheme: dark;
    --bg: #0D1016;
    --bg-glow: rgba(79, 209, 197, 0.07);
    --surface: #171B23;
    --surface-alt: #11151C;
    --border: rgba(255, 255, 255, 0.07);
    --border-strong: rgba(255, 255, 255, 0.14);
    --accent: #4FD1C5;
    --accent-soft: rgba(79, 209, 197, 0.14);
    --accent-soft-hover: rgba(79, 209, 197, 0.24);
    --accent-2: #B794F6;
    --accent-dim: rgba(255, 255, 255, 0.07);
    --danger: #F2777A;
    --danger-soft: rgba(242, 119, 122, 0.14);
    --danger-soft-hover: rgba(242, 119, 122, 0.24);
    --text: #E6EAF0;
    --text-dim: rgba(230, 234, 240, 0.62);
    --fill: #4FD1C5;
    --track: rgba(255, 255, 255, 0.07);
    --radius: 16px;
    --shadow: 0 6px 24px rgba(0, 0, 0, 0.35);
  }
  * { box-sizing: border-box; }
  html { -webkit-text-size-adjust: 100%; }
  body {
    margin: 0 auto;
    max-width: 1100px;
    font-family: Inter, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    font-size: 15px;
    line-height: 1.45;
    background:
      radial-gradient(900px 420px at 85% -10%, var(--bg-glow), transparent 70%),
      var(--bg);
    background-attachment: fixed;
    color: var(--text);
    padding: 20px 16px 40px;
    -webkit-font-smoothing: antialiased;
  }
  h1 {
    font-size: 26px;
    font-weight: 700;
    letter-spacing: -0.02em;
    margin: 0 0 18px;
    background: linear-gradient(90deg, var(--accent), var(--accent-2));
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
    display: inline-block;
  }
  .row { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
  input[type=text], input[type=number], input[type=password], select, textarea {
    flex: 1;
    min-width: 200px;
    padding: 11px 14px;
    border-radius: 12px;
    border: 1px solid var(--border);
    background: var(--surface-alt);
    color: var(--text);
    font-size: 14px;
    font-family: inherit;
    outline: none;
    transition: border-color 0.15s, box-shadow 0.15s;
  }
  select { flex: 0 1 auto; min-width: 0; cursor: pointer; }
  input[type=text]:focus, input[type=number]:focus, input[type=password]:focus,
  select:focus, textarea:focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-soft);
  }
  input::placeholder { color: var(--text-dim); }
  input[type=file] { color: var(--text); flex: 1; min-width: 200px; }
  input[type=checkbox] { accent-color: var(--accent); }
  button {
    padding: 10px 15px;
    border-radius: 12px;
    border: 1px solid transparent;
    background: var(--accent-soft);
    color: var(--accent);
    font-size: 14px;
    font-weight: 600;
    font-family: inherit;
    cursor: pointer;
    transition: background 0.15s, transform 0.1s, border-color 0.15s;
  }
  button:hover { background: var(--accent-soft-hover); }
  button:active { transform: scale(0.97); }
  button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  button.secondary {
    background: var(--accent-dim);
    color: var(--text);
    border-color: var(--border);
  }
  button.secondary:hover { background: rgba(255, 255, 255, 0.12); border-color: var(--border-strong); }
  button.danger { background: var(--danger-soft); color: var(--danger); }
  button.danger:hover { background: var(--danger-soft-hover); }
  button:disabled { opacity: 0.45; cursor: not-allowed; }
  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 16px;
    margin-bottom: 14px;
    box-shadow: var(--shadow);
    transition: border-color 0.15s;
  }
  .card:hover { border-color: var(--border-strong); }
  .name { font-weight: 600; font-size: 15px; margin-bottom: 6px; word-break: break-word; color: var(--text); }
  .meta { font-size: 13px; color: var(--text-dim); margin-bottom: 6px; }
  .bar {
    height: 7px;
    border-radius: 999px;
    background: var(--track);
    overflow: hidden;
    margin: 10px 0 12px;
  }
  .fill {
    height: 100%;
    border-radius: 999px;
    background: linear-gradient(90deg, var(--accent), var(--accent-2));
    transition: width 0.4s ease;
  }
  .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
  .actions button { padding: 8px 12px; font-size: 13px; }
  .empty { color: var(--text-dim); padding: 32px 0; text-align: center; }
  .status { font-size: 12px; color: var(--text-dim); margin-top: 24px; text-align: center; }
  #uploadPanel { display: none; }
  #uploadFileList label { display: block; font-size: 14px; padding: 4px 0; }
  pre { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
  ::-webkit-scrollbar { width: 10px; height: 10px; }
  ::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.14); border-radius: 999px; }
  ::-webkit-scrollbar-track { background: transparent; }

  /* Feature: every secondary screen (Files, the Add Torrent file
     picker, and each of the feature panels below) opens as its own
     full-screen "window" over the main torrent list instead of being
     inserted inline into the page, which used to push the list further
     and further down as more panels were opened. Nothing here changes
     how a panel is shown/hidden (still plain style.display toggling,
     same JS as before) - it only changes what "shown" looks like. */
  .overlayPanel {
    position: fixed;
    inset: 0;
    background:
      radial-gradient(900px 420px at 85% -10%, var(--bg-glow), transparent 70%),
      var(--bg);
    z-index: 30;
    overflow: auto;
    padding: 20px 16px;
    margin: 0;
    border-radius: 0;
    border: none;
    box-shadow: none;
  }
  .overlayHeader {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
  }
  .overlayHeader .name { margin: 0; font-size: 18px; }
</style>
</head>
<body>
  <h1>TorrentOr</h1>

  <!-- Feature: the same modern download/upload speed graph as the app's
       main screen (SpeedGraphView) - rounded dark panel, grid, two
       smooth gradient-filled lines - fed by sampling /api/status every
       second (summing every torrent's speed) rather than a native
       history API, since the app's own graph works the same way. -->
  <div id="speedSummary" class="meta" style="margin-bottom:4px;"></div>
  <canvas id="speedGraphCanvas" width="600" height="110" style="width:100%;height:110px;display:block;margin-bottom:12px;"></canvas>

  <div class="row">
    <input type="text" id="magnetInput" placeholder="Paste magnet link or hash">
    <button id="addBtn">Add</button>
  </div>

  <div class="row">
    <input type="file" id="torrentFileInput" accept=".torrent">
    <button id="uploadBtn">Upload .torrent</button>
  </div>

  <div class="overlayPanel" id="uploadPanel">
    <div class="name" id="uploadPanelTitle">Select files</div>
    <div class="actions">
      <button class="secondary" onclick="setAllFileChecks(true)">Select All</button>
      <button class="secondary" onclick="setAllFileChecks(false)">Select None</button>
    </div>
    <div id="uploadFileList"></div>
    <div class="actions">
      <button onclick="submitUpload(true)">Download Selected</button>
      <button onclick="submitUpload(false)">Download All</button>
      <button class="secondary" onclick="cancelUpload()">Cancel</button>
    </div>
  </div>

  <div class="row">
    <button id="pauseAllBtn" class="secondary">Pause All</button>
    <button id="resumeAllBtn" class="secondary">Resume All</button>
    <button id="selectModeBtn" class="secondary">Select Torrents</button>
    <button id="globalStatsBtn" class="secondary">Global Statistics</button>
    <button id="executionLogBtn" class="secondary">Execution Log</button>
    <button id="onlineSearchBtn" class="secondary">Online Search</button>
    <button id="rssBtn" class="secondary">RSS Feeds</button>
    <button id="saveFolderBtn" class="secondary">Save Folder</button>
  </div>

  <!-- Feature: "Save Folder" - same "save_folder_settings" prefs the
       app's own Save Folder screen reads/writes. Only the global
       default lives here; a per-torrent override is set from that
       torrent's General tab (Change Save Folder), same split as the
       app. Hidden until the Save Folder button is pressed. -->
  <div class="overlayPanel" id="saveFolderPanel" style="display:none;">
    <div class="overlayHeader">
      <div class="name">Save Folder</div>
      <button class="secondary" onclick="closeSaveFolder()">Close</button>
    </div>
    <div class="meta">New torrents are saved here by default, unless a different folder is picked for that one torrent.</div>
    <div class="meta" id="saveFolderCurrent">Loading...</div>
    <div class="actions">
      <button id="saveFolderApplyBtn">Change Default Folder</button>
    </div>
  </div>

  <!-- Feature: a graphical folder picker, same plain-folder browsing as
       the app's own "Choose Folder" dialog (Up, tap a subfolder to go
       in, New Folder, Use This Folder) - shared by the global Save
       Folder panel above and the per-torrent "Change Save Folder"
       control on a torrent's General tab. Hidden until opened. -->
  <div id="folderPickerOverlay" style="display:none;position:fixed;inset:0;background:#000a;z-index:50;align-items:center;justify-content:center;">
    <div class="card" style="max-width:480px;width:92%;max-height:80vh;display:flex;flex-direction:column;">
      <div class="name">Choose Folder</div>
      <div class="row">
        <button class="secondary" id="folderPickerBackBtn">&larr;</button>
        <button class="secondary" id="folderPickerForwardBtn">&rarr;</button>
      </div>
      <div class="meta" id="folderPickerPath" style="word-break:break-all;"></div>
      <div id="folderPickerList" style="overflow:auto;flex:1;margin:8px 0;"></div>
      <div class="row">
        <input type="text" id="folderPickerNewNameInput" placeholder="New folder name" style="display:none;">
        <button class="secondary" id="folderPickerNewFolderBtn">New Folder</button>
      </div>
      <div class="actions">
        <button id="folderPickerUseBtn">Use This Folder</button>
        <button class="secondary" id="folderPickerCancelBtn">Cancel</button>
      </div>
    </div>
  </div>

  <!-- Feature: "RSS Feeds" - same RssManager the app's own RSS screen
       uses (feed list, cached items, auto-download, auto-refresh
       interval). Hidden until the RSS Feeds button is pressed. -->
  <div class="overlayPanel" id="rssPanel" style="display:none;">
    <div class="overlayHeader">
      <div class="name">RSS Feeds</div>
      <button class="secondary" onclick="closeRss()">Close</button>
    </div>
    <div class="row">
      <select id="rssIntervalSelect"></select>
      <button class="secondary" id="rssRefreshAllBtn">Refresh All</button>
    </div>
    <div class="meta" id="rssStatus"></div>
    <div class="row">
      <input type="text" id="rssFeedUrlInput" placeholder="RSS feed URL">
      <button id="rssAddFeedBtn">Add Feed</button>
    </div>
    <div id="rssFeedList"></div>
  </div>

  <!-- Feature: search/filter/sort the torrents already in TorrentOr -
       same idea as the app's own search box + Filter row + Sort button
       on the main screen (searchQuery/activeFilter/sortMode), applied
       client-side against the same /api/status fields the cards
       themselves are built from. This is separate from Online Search
       below, same distinction the app's own hint text makes. -->
  <div class="row">
    <input type="text" id="torrentSearchInput" placeholder="Search torrents already added">
    <button class="secondary" id="clearTorrentSearchBtn">Clear</button>
  </div>
  <div class="row" style="flex-wrap:wrap;">
    <button class="secondary" data-filter="All" onclick="setActiveFilter('All')">All</button>
    <button class="secondary" data-filter="Downloading" onclick="setActiveFilter('Downloading')">Downloading</button>
    <button class="secondary" data-filter="Seeding" onclick="setActiveFilter('Seeding')">Seeding</button>
    <button class="secondary" data-filter="Paused" onclick="setActiveFilter('Paused')">Paused</button>
    <button class="secondary" data-filter="Completed" onclick="setActiveFilter('Completed')">Completed</button>
  </div>
  <div class="row">
    <select id="sortModeSelect">
      <option value="None">Sort: None</option>
      <option value="Name (A-Z)">Sort: Name (A-Z)</option>
      <option value="Name (Z-A)">Sort: Name (Z-A)</option>
      <option value="Size (Largest First)">Sort: Size (Largest First)</option>
      <option value="Size (Smallest First)">Sort: Size (Smallest First)</option>
      <option value="Date Added (Newest First)">Sort: Date Added (Newest First)</option>
      <option value="Date Added (Oldest First)">Sort: Date Added (Oldest First)</option>
      <option value="Progress (Highest First)">Sort: Progress (Highest First)</option>
      <option value="Progress (Lowest First)">Sort: Progress (Lowest First)</option>
      <option value="Status (A-Z)">Sort: Status (A-Z)</option>
      <option value="Status (Z-A)">Sort: Status (Z-A)</option>
    </select>
  </div>

  <!-- Feature: "Execution Log" - same AppLog entries (INFO/WARNING/
       ERROR) the app's own Execution Log screen shows. Hidden until
       the Execution Log button is pressed. -->
  <div class="overlayPanel" id="executionLogPanel" style="display:none;">
    <div class="overlayHeader">
      <div class="name">Execution Log</div>
      <button class="secondary" onclick="closeExecutionLog()">Close</button>
    </div>
    <div class="row">
      <label class="meta"><input type="checkbox" id="logLevelInfo" checked> INFO</label>
      <label class="meta"><input type="checkbox" id="logLevelWarning" checked> WARNING</label>
      <label class="meta"><input type="checkbox" id="logLevelError" checked> ERROR</label>
      <label class="meta"><input type="checkbox" id="logAutoScroll" checked> Auto-scroll</label>
    </div>
    <pre id="executionLogText" style="white-space:pre-wrap;max-height:360px;overflow:auto;background:#0003;padding:8px;border-radius:6px;">Loading...</pre>
    <div class="actions">
      <button class="secondary" onclick="clearExecutionLog()">Clear Log</button>
      <button class="secondary" onclick="copyExecutionLog()">Copy Log</button>
    </div>
  </div>

  <!-- Feature: Online Search - same multi-provider search as the app's
       own Online Search screen (OnlineSearchManager), separate from the
       "already added" search above. Hidden until the Online Search
       button is pressed. -->
  <div class="overlayPanel" id="onlineSearchPanel" style="display:none;">
    <div class="overlayHeader">
      <div class="name">Online Search</div>
      <button class="secondary" onclick="closeOnlineSearch()">Close</button>
    </div>
    <div class="meta">Searches multiple providers. This is separate from the search box above, which only searches torrents already in TorrentOr.</div>
    <div class="row">
      <input type="text" id="onlineSearchInput" placeholder="Search online, e.g. ubuntu 24.04">
      <button id="onlineSearchGoBtn">Search</button>
    </div>
    <div class="row">
      <select id="onlineSearchSortSelect">
        <option value="Seeders">Sort: Seeders</option>
        <option value="Name">Sort: Name</option>
        <option value="Size">Sort: Size</option>
        <option value="Leechers">Sort: Leechers</option>
        <option value="Age">Sort: Age</option>
      </select>
      <select id="onlineSearchProviderSelect">
        <option value="All">Provider: All</option>
      </select>
    </div>
    <div class="meta" id="onlineSearchStatus">Enter a search and tap Search.</div>
    <div id="onlineSearchResults"></div>
  </div>

  <!-- Feature: multi-select batch actions - same five operations as the
       app's own Select Torrents mode (Force Recheck/Reannounce/Pause/
       Resume/Remove Selected), looping the same single-torrent calls
       the app itself loops over selectedTorrents. Hidden until Select
       Torrents is turned on. -->
  <div class="row" id="selectActionsRow" style="display:none;flex-wrap:wrap;">
    <button class="secondary" onclick="selectAllVisibleTorrents()">Select All</button>
    <button class="secondary" onclick="selectNoneTorrents()">Select None</button>
    <button class="secondary" onclick="batchAction('recheck')">Force Recheck Selected</button>
    <button class="secondary" onclick="batchAction('reannounce')">Force Reannounce Selected</button>
    <button class="secondary" onclick="batchAction('pause')">Pause Selected</button>
    <button class="secondary" onclick="batchAction('resume')">Resume Selected</button>
    <button class="danger" onclick="batchRemove(false)">Remove Selected</button>
    <button class="danger" onclick="batchRemove(true)">Remove Selected + Files</button>
  </div>

  <!-- Feature: "Global Statistics" - same all-time upload/download/
       share ratio the app's own Statistics screen shows (same
       "global_stats" prefs, written by TorrentService). Hidden until
       the Global Statistics button is pressed. -->
  <div class="overlayPanel" id="globalStatsPanel" style="display:none;">
    <div class="overlayHeader">
      <div class="name">Global Statistics</div>
      <button class="secondary" onclick="closeGlobalStats()">Close</button>
    </div>
    <pre id="globalStatsText" style="white-space:pre-wrap;background:#0003;padding:8px;border-radius:6px;">Loading...</pre>
  </div>

  <div id="list"></div>

  <!-- Deliberately OUTSIDE #list: #list is fully rebuilt by render()
       every 1 second, which would destroy this panel (and interrupt any
       playing video/audio) once a second if it lived inside a torrent's
       card. Only one torrent's files are shown here at a time. -->
  <div class="overlayPanel" id="filesPanel" style="display:none;"></div>

  <div class="status" id="statusLine">Loading...</div>

<script>
async function api(path, options) {
  const res = await fetch(path, options);
  if (res.status === 401) {
    document.getElementById('statusLine').textContent = 'Login required - reload the page.';
    throw new Error('unauthorized');
  }
  return res;
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text == null ? '' : String(text);
  return div.innerHTML;
}

function formatSize(bytes) {
  bytes = Number(bytes) || 0;
  if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(2) + ' GB';
  if (bytes >= 1048576) return (bytes / 1048576).toFixed(2) + ' MB';
  if (bytes >= 1024) return (bytes / 1024).toFixed(2) + ' KB';
  return bytes + ' B';
}

// Phone back button support: pressing back while the upload/select
// panel or the files browser is open should close that panel, not leave
// the page. Opening either panel pushes one history entry; closing it
// (by any means - Cancel, a successful submit, or the Close button)
// pops that same entry with history.back() so the stack doesn't grow a
// dead entry per open/close cycle. suppressNextPopstate distinguishes
// "we popped it ourselves by calling history.back() after an in-page
// close" from "the user actually pressed the phone's back button",
// since both fire the same popstate event.
let panelHistoryPushed = false;
let suppressNextPopstate = false;

function anyPanelOpen() {
  const uploadOpen = document.getElementById('uploadPanel').style.display === 'block';
  const filesOpen = document.getElementById('filesPanel').style.display === 'block';
  return uploadOpen || filesOpen;
}

function notePanelOpened() {
  if (panelHistoryPushed) return;
  panelHistoryPushed = true;
  try { history.pushState({ webuiPanel: true }, ''); } catch (e) {}
}

function notePanelsMaybeClosed() {
  if (!panelHistoryPushed || anyPanelOpen()) return;
  panelHistoryPushed = false;
  suppressNextPopstate = true;
  try { history.back(); } catch (e) {}
}

window.addEventListener('popstate', function() {
  if (suppressNextPopstate) {
    suppressNextPopstate = false;
    return;
  }

  // The phone's actual back button, with a panel open - close it here
  // instead of letting the browser navigate away from the page.
  panelHistoryPushed = false;

  if (document.getElementById('uploadPanel').style.display === 'block') {
    cancelUpload();
  }

  if (openFilesIndex !== null) {
    toggleFiles(openFilesIndex);
  }
});

async function refresh() {
  try {
    const res = await api('/api/status');
    const data = await res.json();
    const torrents = data.torrents || [];
    render(torrents);
    document.getElementById('statusLine').textContent =
      torrents.length + ' torrent(s) - updated ' + new Date().toLocaleTimeString();

    sampleGlobalSpeed(torrents);

    if (openFilesIndex !== null && openFilesTab === 'swarmhealth') {
      sampleSwarmHealth(torrents, openFilesIndex);
    }

    if (globalStatsOpen) loadGlobalStatistics();
    if (executionLogOpen) loadExecutionLog();
  } catch (e) {
    // Already reported above, or a transient network hiccup - leave the
    // list as it was rather than flashing it empty.
  }

  refreshFilesPanelProgress();
}

// ---------------------------------------------------------------------
// Feature: modern speed graph (main screen) - same design as the app's
// own SpeedGraphView: rounded dark panel, light grid, two smooth
// gradient-filled lines (teal download / orange upload). The app builds
// its history by sampling getDetailedStatus every second and summing
// each torrent's own speed (extractTotalSpeedsFromStatus/
// speedUnitToKb) - there's no native "speed history" API, so this does
// the exact same thing against /api/status instead.
// ---------------------------------------------------------------------

let downloadSpeedHistory = [];
let uploadSpeedHistory = [];
const maxSpeedHistoryPoints = 30; // matches the app's own history length

function speedUnitToKb(value, unit) {
  switch (unit) {
    case 'B': return value / 1024;
    case 'KB': return value;
    case 'MB': return value * 1024;
    case 'GB': return value * 1024 * 1024;
    case 'TB': return value * 1024 * 1024 * 1024;
    default: return 0;
  }
}

function parseSpeedToKb(speedStr) {
  const match = /([\d.]+)\s*(B|KB|MB|GB|TB)\/s/.exec(speedStr || '');
  if (!match) return 0;
  return speedUnitToKb(parseFloat(match[1]), match[2]);
}

function sampleGlobalSpeed(torrents) {
  let totalDown = 0;
  let totalUp = 0;

  torrents.forEach(function(t) {
    totalDown += parseSpeedToKb(t.downSpeed);
    totalUp += parseSpeedToKb(t.upSpeed);
  });

  downloadSpeedHistory.push(Math.max(0, Math.round(totalDown)));
  uploadSpeedHistory.push(Math.max(0, Math.round(totalUp)));

  while (downloadSpeedHistory.length > maxSpeedHistoryPoints) downloadSpeedHistory.shift();
  while (uploadSpeedHistory.length > maxSpeedHistoryPoints) uploadSpeedHistory.shift();

  const summaryEl = document.getElementById('speedSummary');
  if (summaryEl) {
    const latestDown = downloadSpeedHistory[downloadSpeedHistory.length - 1] || 0;
    const latestUp = uploadSpeedHistory[uploadSpeedHistory.length - 1] || 0;
    summaryEl.innerHTML =
      '<span style="color:#4FD1C5;">&darr; ' + latestDown + ' KB/s</span>' +
      '&nbsp;&nbsp;&nbsp;<span style="color:#B794F6;">&uarr; ' + latestUp + ' KB/s</span>';
  }

  drawLineAreaGraph('speedGraphCanvas', downloadSpeedHistory, uploadSpeedHistory);
}

// Shared drawing routine for both the main speed graph and the per-
// torrent Swarm Health graph - a direct Canvas port of the app's
// SpeedGraphView.onDraw: rounded dark panel (#1C1F26), a light grid
// (#2A2E37), and two gradient-filled lines, the second series drawn
// first so the (usually larger) first series' line/fill sits on top.
function drawLineAreaGraph(canvasId, seriesA, seriesB) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;

  // Match the canvas's drawing buffer to its displayed CSS size so the
  // graph stays crisp regardless of device pixel density/layout width.
  const displayWidth = canvas.clientWidth || canvas.width;
  const displayHeight = canvas.clientHeight || canvas.height;
  if (canvas.width !== displayWidth) canvas.width = displayWidth;
  if (canvas.height !== displayHeight) canvas.height = displayHeight;

  const ctx = canvas.getContext('2d');
  const w = canvas.width;
  const h = canvas.height;
  if (!ctx || w <= 0 || h <= 0) return;

  ctx.clearRect(0, 0, w, h);

  const cornerRadius = 12;
  ctx.fillStyle = '#1C1F26';
  roundRectPath(ctx, 0, 0, w, h, cornerRadius);
  ctx.fill();

  ctx.strokeStyle = '#2A2E37';
  ctx.lineWidth = 1;
  const gridLines = 4;
  for (let i = 0; i <= gridLines; i++) {
    const y = Math.round((h * i) / gridLines) + 0.5;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(w, y);
    ctx.stroke();
  }

  if (seriesA.length < 2 && seriesB.length < 2) return;

  const maxValue = Math.max(1, ...seriesA, ...seriesB);

  // seriesB (upload / peers) drawn first, seriesA (download / seeds) on top.
  drawGraphSeries(ctx, seriesB, maxValue, w, h, '#B794F6');
  drawGraphSeries(ctx, seriesA, maxValue, w, h, '#4FD1C5');
}

function roundRectPath(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

function drawGraphSeries(ctx, values, maxValue, w, h, colorHex) {
  if (values.length < 2) return;

  const topPadding = h * 0.12;
  const usableHeight = h - topPadding;
  const stepX = w / Math.max(1, values.length - 1);

  function yFor(value) {
    const ratio = value / maxValue;
    return h - ratio * usableHeight;
  }

  const r = parseInt(colorHex.substr(1, 2), 16);
  const g = parseInt(colorHex.substr(3, 2), 16);
  const b = parseInt(colorHex.substr(5, 2), 16);

  const gradient = ctx.createLinearGradient(0, 0, 0, h);
  gradient.addColorStop(0, 'rgba(' + r + ',' + g + ',' + b + ',0.5)');
  gradient.addColorStop(1, 'rgba(' + r + ',' + g + ',' + b + ',0)');

  ctx.beginPath();
  ctx.moveTo(0, h);
  values.forEach(function(value, index) {
    ctx.lineTo(index * stepX, yFor(value));
  });
  ctx.lineTo((values.length - 1) * stepX, h);
  ctx.closePath();
  ctx.fillStyle = gradient;
  ctx.fill();

  ctx.beginPath();
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.lineWidth = 3;
  ctx.strokeStyle = colorHex;
  values.forEach(function(value, index) {
    const x = index * stepX;
    const y = yFor(value);
    if (index === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
}

// Feature: Swarm Health graph (per-torrent Seeds/Peers over the last
// ~30 seconds) - the app's swarm health screen is text-only, so this is
// new, drawn with the exact same style as the speed graph above for a
// consistent look. Sampled from the same /api/status torrents array
// refresh() already has, rather than a separate fetch.
let swarmHealthHistory = { seeds: [], peers: [] };

function sampleSwarmHealth(torrents, torrentIndex) {
  const t = torrents.find(function(x) { return x.index === torrentIndex; });
  const seeds = t ? (parseInt(t.seeds, 10) || 0) : 0;
  const peers = t ? (parseInt(t.peers, 10) || 0) : 0;

  swarmHealthHistory.seeds.push(seeds);
  swarmHealthHistory.peers.push(peers);

  while (swarmHealthHistory.seeds.length > maxSpeedHistoryPoints) swarmHealthHistory.seeds.shift();
  while (swarmHealthHistory.peers.length > maxSpeedHistoryPoints) swarmHealthHistory.peers.shift();

  drawSwarmHealthGraph();
}

function drawSwarmHealthGraph() {
  drawLineAreaGraph('swarmHealthCanvas', swarmHealthHistory.seeds, swarmHealthHistory.peers);
}

// Keeps the open file browser's per-file percent/progress bars moving
// every second, without touching anything else in that panel - updates
// two elements per row in place rather than calling toggleFiles() again,
// which would re-render the whole panel and cut off a playing video/
// audio element or an open text/image viewer sitting in that row.
async function refreshFilesPanelProgress() {
  if (openFilesIndex === null) return;

  if (openFilesTab === 'swarmhealth') {
    // The seeds/peers sample itself is taken in refresh() (it already
    // has the /api/status torrents array) - this just keeps the text
    // block below the graph current.
    loadTextTab(openFilesIndex, openFilesTab);
    return;
  }

  if (openFilesTab === 'general' || openFilesTab === 'peers' || openFilesTab === 'pieces' ||
      openFilesTab === 'trackers' || openFilesTab === 'webseeds' ||
      openFilesTab === 'statistics' || openFilesTab === 'comment') {
    loadTextTab(openFilesIndex, openFilesTab);
    return;
  }

  try {
    const res = await api('/api/torrent_files?index=' + openFilesIndex);
    const data = await res.json();
    if (openFilesIndex === null || !data.ok || !data.ready) return;

    lastFilesList = data.files || [];

    lastFilesList.forEach(function(f) {
      const rowId = 'frow_' + openFilesIndex + '_' + f.index;
      const percent = Number(f.percent) || 0;

      const textEl = document.getElementById('ptext_' + rowId);
      const barEl = document.getElementById('pbar_' + rowId);

      if (textEl) textEl.textContent = percent + '%';
      if (barEl) barEl.style.width = percent + '%';

      // Priority tab's own Seeds/Peers text, if that tab happens to be
      // open right now - checkboxes are never touched here, only this
      // text, so an in-progress selection the user hasn't applied yet
      // is never disturbed by this 1-second poll.
      const swarmEl = document.getElementById('pswarm_' + rowId);
      if (swarmEl) {
        swarmEl.textContent = 'Seeds ' + (Number(f.seeds) || 0) + ' (' + (Number(f.totalSeeds) || 0) + ')' +
          '  Peers ' + (Number(f.peers) || 0) + ' (' + (Number(f.totalPeers) || 0) + ')';
      }
    });
  } catch (e) {
    // Transient hiccup - leave the panel as it was, same as the main list.
  }
}

// Feature: multi-select ("Select Torrents" mode) - same idea as the
// app's own selectMode/selectedTorrents: a checkbox per card while the
// mode is on, and a row of batch buttons (Force Recheck/Reannounce/
// Pause/Resume/Remove Selected) that loop the same single-torrent
// calls the app itself loops over selectedTorrents.
let selectMode = false;
let selectedTorrents = new Set();
let lastTorrents = [];

// Feature: search/filter/sort the torrents already in TorrentOr - same
// idea as the app's own activeFilter/searchQuery/sortMode, applied here
// against the same fields the cards are built from (state/percent/name/
// addedTimestamp), mirroring torrentMatchesFilter()/sortTorrentLines().
let activeFilter = 'All';
let torrentSearchQuery = '';
let sortMode = 'None';

function setActiveFilter(filter) {
  activeFilter = filter;
  document.querySelectorAll('[data-filter]').forEach(function(btn) {
    btn.style.outline = (btn.dataset.filter === filter) ? '2px solid #4FD1C5' : 'none';
  });
  render(lastTorrents);
}

// Mirrors torrentMatchesFilter(line): text search first, then status.
function torrentMatchesFilter(t) {
  const query = torrentSearchQuery.trim().toLowerCase();
  if (query && t.name.toLowerCase().indexOf(query) === -1) return false;

  const state = (t.state || '').toLowerCase();
  const percent = t.percent || 0;

  switch (activeFilter) {
    case 'Downloading': return state.indexOf('downloading') !== -1;
    case 'Seeding': return state.indexOf('seeding') !== -1;
    case 'Paused': return state.indexOf('paused') !== -1;
    case 'Completed': return percent >= 100 || state.indexOf('seeding') !== -1;
    default: return true;
  }
}

// Mirrors sortTorrentLines(): sorts a copy, never mutates t.index, so
// every action button still targets the real native index regardless
// of on-screen order. JS's Array.sort is stable in all current engines,
// same guarantee the app gets from Kotlin's sortedBy/sortedByDescending.
function sortTorrents(torrents) {
  const list = torrents.slice();

  function byName(a, b) { return a.name.toLowerCase().localeCompare(b.name.toLowerCase()); }
  function bySize(a, b) { return (a.totalBytes || 0) - (b.totalBytes || 0); }
  function byAdded(a, b) { return (a.addedTimestamp || 0) - (b.addedTimestamp || 0); }
  function byProgress(a, b) { return (a.percent || 0) - (b.percent || 0); }
  function byStatus(a, b) { return (a.state || '').toLowerCase().localeCompare((b.state || '').toLowerCase()); }

  switch (sortMode) {
    case 'Name (A-Z)': return list.sort(byName);
    case 'Name (Z-A)': return list.sort(function(a, b) { return byName(b, a); });
    case 'Size (Largest First)': return list.sort(function(a, b) { return bySize(b, a); });
    case 'Size (Smallest First)': return list.sort(bySize);
    case 'Date Added (Newest First)': return list.sort(function(a, b) { return byAdded(b, a); });
    case 'Date Added (Oldest First)': return list.sort(byAdded);
    case 'Progress (Highest First)': return list.sort(function(a, b) { return byProgress(b, a); });
    case 'Progress (Lowest First)': return list.sort(byProgress);
    case 'Status (A-Z)': return list.sort(byStatus);
    case 'Status (Z-A)': return list.sort(function(a, b) { return byStatus(b, a); });
    default: return list;
  }
}

document.getElementById('torrentSearchInput').addEventListener('input', function(e) {
  torrentSearchQuery = e.target.value;
  render(lastTorrents);
});
document.getElementById('clearTorrentSearchBtn').addEventListener('click', function() {
  torrentSearchQuery = '';
  document.getElementById('torrentSearchInput').value = '';
  render(lastTorrents);
});
document.getElementById('sortModeSelect').addEventListener('change', function(e) {
  sortMode = e.target.value;
  render(lastTorrents);
});

function render(torrents) {
  lastTorrents = torrents;
  const list = document.getElementById('list');
  const visible = sortTorrents(torrents.filter(torrentMatchesFilter));

  if (torrents.length === 0) {
    list.innerHTML = '<div class="empty">No torrents yet</div>';
    return;
  }
  if (visible.length === 0) {
    list.innerHTML = '<div class="empty">No torrents match the current filter/search</div>';
    return;
  }

  list.innerHTML = visible.map(function(t) {
    const checkbox = selectMode
      ? '<label class="meta"><input type="checkbox" class="selectCheck" data-index="' + t.index + '"' +
          (selectedTorrents.has(t.index) ? ' checked' : '') +
          ' onchange="toggleTorrentSelected(' + t.index + ', this.checked)"> Select</label>'
      : '';

    return '<div class="card">' +
      checkbox +
      '<div class="name">' + escapeHtml(t.name) + '</div>' +
      '<div class="meta">' + escapeHtml(t.state) + ' &middot; ' + t.percent + '% &middot; &darr; ' +
        escapeHtml(t.downSpeed) + ' &middot; &uarr; ' + escapeHtml(t.upSpeed) + ' &middot; ' + escapeHtml(t.size) + '</div>' +
      '<div class="meta">Seeds ' + escapeHtml(t.seeds) + ' &middot; Peers ' + escapeHtml(t.peers) +
        ' &middot; ' + escapeHtml(t.eta) + '</div>' +
      '<div class="bar"><div class="fill" style="width:' + t.percent + '%"></div></div>' +
      '<div class="actions">' +
        '<button onclick="doAction(\'pause\', ' + t.index + ')">Pause</button>' +
        '<button onclick="doAction(\'resume\', ' + t.index + ')">Resume</button>' +
        '<button class="secondary" onclick="toggleFiles(' + t.index + ')">Files</button>' +
        // Feature: Force Recheck / Force Reannounce right on this card,
        // matching the app's own main torrent card - reuses the exact
        // same server endpoints (/api/force_recheck/<index> and
        // /api/force_reannounce/<index>) the bulk "Select Torrents"
        // actions already call, just for this one torrent's index
        // instead of looping over a selected set.
        '<button class="secondary" onclick="doAction(\'force_recheck\', ' + t.index + ')">Force Recheck</button>' +
        '<button class="secondary" onclick="doAction(\'force_reannounce\', ' + t.index + ')">Force Reannounce</button>' +
        '<button class="danger" onclick="doRemove(' + t.index + ', false)">Remove</button>' +
        '<button class="danger" onclick="doRemove(' + t.index + ', true)">Remove + Files</button>' +
      '</div>' +
    '</div>';
  }).join('');
}

async function doAction(action, index) {
  await api('/api/' + action + '/' + index, { method: 'POST' });
  refresh();
}

async function doRemove(index, deleteFiles) {
  const message = deleteFiles
    ? 'Remove this torrent AND delete its downloaded files?'
    : 'Remove this torrent? Downloaded files are kept.';
  if (!confirm(message)) return;
  await api('/api/remove/' + index + (deleteFiles ? '?files=1' : ''), { method: 'POST' });
  refresh();
}

function toggleTorrentSelected(index, checked) {
  if (checked) selectedTorrents.add(index); else selectedTorrents.delete(index);
}

// Feature: Select All / Select None, same as the app's own Select All /
// Select None buttons. "Select All" only selects torrents actually
// visible under the active filter/search right now - same list render()
// builds cards from - not every torrent in the app.
function selectAllVisibleTorrents() {
  if (!selectMode) return;

  const visible = sortTorrents(lastTorrents.filter(torrentMatchesFilter));
  selectedTorrents = new Set(visible.map(function(t) { return t.index; }));
  render(lastTorrents);
}

function selectNoneTorrents() {
  if (!selectMode) return;

  selectedTorrents.clear();
  render(lastTorrents);
}

document.getElementById('selectModeBtn').addEventListener('click', function() {
  selectMode = !selectMode;
  selectedTorrents.clear();
  document.getElementById('selectModeBtn').textContent = selectMode ? 'Exit Select Mode' : 'Select Torrents';
  document.getElementById('selectActionsRow').style.display = selectMode ? 'flex' : 'none';
  render(lastTorrents);
});

// Force Recheck / Force Reannounce / Pause / Resume Selected - same
// sequential loop over the selected indexes (ascending order; unlike
// Remove, none of these change the list, so order doesn't matter) the
// app itself does, just calling the Web UI's own single-torrent
// endpoints instead of the native functions directly.
async function batchAction(kind) {
  if (selectedTorrents.size === 0) { alert('No torrents selected'); return; }

  const endpoints = {
    recheck: '/api/force_recheck/',
    reannounce: '/api/force_reannounce/',
    pause: '/api/pause/',
    resume: '/api/resume/'
  };
  const endpoint = endpoints[kind];
  if (!endpoint) return;

  const indexes = Array.from(selectedTorrents).sort(function(a, b) { return a - b; });

  for (const index of indexes) {
    try { await api(endpoint + index, { method: 'POST' }); } catch (e) {}
  }

  selectedTorrents.clear();
  selectMode = false;
  document.getElementById('selectModeBtn').textContent = 'Select Torrents';
  document.getElementById('selectActionsRow').style.display = 'none';
  refresh();
}

// Remove Selected - same "Cancel / Remove Torrent Only / Remove +
// Files" choice as the app's AlertDialog, and the same descending-
// index removal order the app uses (sortedDescending), since removing
// a lower index first would shift every index above it still waiting
// to be removed in this same loop.
async function batchRemove(deleteFiles) {
  if (selectedTorrents.size === 0) { alert('No torrents selected'); return; }

  const count = selectedTorrents.size;
  const message = deleteFiles
    ? 'Remove ' + count + ' selected torrent(s) AND delete their downloaded files?'
    : 'Remove ' + count + ' selected torrent(s)? Downloaded files are kept.';
  if (!confirm(message)) return;

  const indexes = Array.from(selectedTorrents).sort(function(a, b) { return b - a; });

  for (const index of indexes) {
    try {
      await api('/api/remove/' + index + (deleteFiles ? '?files=1' : ''), { method: 'POST' });
    } catch (e) {}
  }

  selectedTorrents.clear();
  selectMode = false;
  document.getElementById('selectModeBtn').textContent = 'Select Torrents';
  document.getElementById('selectActionsRow').style.display = 'none';
  refresh();
}

// Each of Global Statistics/Execution Log/Online Search/Save Folder/RSS
// Feeds is now a full-screen overlay (see .overlayPanel), so opening
// one closes any other that's already open first - otherwise two could
// end up stacked with no way to see or close the one underneath.
function closeAllFeaturePanels() {
  closeGlobalStats();
  closeExecutionLog();
  closeOnlineSearch();
  closeSaveFolder();
  closeRss();

  // Also close the torrent Files/details overlay, same reason - it is
  // itself a full-screen overlay (see .overlayPanel) that would
  // otherwise sit stacked underneath whichever of the above opens next.
  // The file-selection (Add) overlay is left alone here: it is an
  // active add-in-progress the user may not want silently cancelled,
  // and opening a feature panel over it, while unusual, does not
  // corrupt anything.
  if (openFilesIndex !== null) {
    openFilesIndex = null;
    const panel = document.getElementById('filesPanel');
    panel.style.display = 'none';
    panel.innerHTML = '';
    notePanelsMaybeClosed();
  }
}

// Feature: "Global Statistics" panel - same all-time upload/download/
// share ratio text as the app's own Statistics screen, refreshed every
// second alongside everything else while the panel is open.
let globalStatsOpen = false;

document.getElementById('globalStatsBtn').addEventListener('click', function() {
  if (globalStatsOpen) closeGlobalStats(); else openGlobalStats();
});

function openGlobalStats() {
  closeAllFeaturePanels();
  globalStatsOpen = true;
  document.getElementById('globalStatsPanel').style.display = 'block';
  loadGlobalStatistics();
}

function closeGlobalStats() {
  globalStatsOpen = false;
  document.getElementById('globalStatsPanel').style.display = 'none';
}

async function loadGlobalStatistics() {
  if (!globalStatsOpen) return;
  try {
    const res = await api('/api/global_statistics');
    const data = await res.json();
    const el = document.getElementById('globalStatsText');
    if (el) el.textContent = (data.ok ? data.text : 'Could not load statistics') || 'No statistics yet';
  } catch (e) {
    const el = document.getElementById('globalStatsText');
    if (el) el.textContent = 'Could not load statistics';
  }
}

// ---------------------------------------------------------------------
// Feature: "Execution Log" - same AppLog entries (INFO/WARNING/ERROR)
// the app's own Execution Log screen shows, with the same level
// checkboxes, Clear Log and Copy Log. Refreshed every second alongside
// everything else while the panel is open, same as Global Statistics.
let executionLogOpen = false;

document.getElementById('executionLogBtn').addEventListener('click', function() {
  if (executionLogOpen) closeExecutionLog(); else openExecutionLog();
});

function openExecutionLog() {
  closeAllFeaturePanels();
  executionLogOpen = true;
  document.getElementById('executionLogPanel').style.display = 'block';
  loadExecutionLog();
}

function closeExecutionLog() {
  executionLogOpen = false;
  document.getElementById('executionLogPanel').style.display = 'none';
}

['logLevelInfo', 'logLevelWarning', 'logLevelError'].forEach(function(id) {
  document.getElementById(id).addEventListener('change', function() {
    if (executionLogOpen) loadExecutionLog();
  });
});

async function loadExecutionLog() {
  if (!executionLogOpen) return;

  const levels = [];
  if (document.getElementById('logLevelInfo').checked) levels.push('info');
  if (document.getElementById('logLevelWarning').checked) levels.push('warning');
  if (document.getElementById('logLevelError').checked) levels.push('error');

  const el = document.getElementById('executionLogText');
  try {
    const res = await api('/api/execution_log?levels=' + encodeURIComponent(levels.join(',')));
    const data = await res.json();
    if (el) {
      el.textContent = (data.ok ? data.text : '') || 'No log entries yet';
      if (document.getElementById('logAutoScroll').checked) el.scrollTop = el.scrollHeight;
    }
  } catch (e) {
    if (el) el.textContent = 'Could not load execution log';
  }
}

async function clearExecutionLog() {
  if (!confirm('Clear the execution log?')) return;
  try { await api('/api/execution_log/clear', { method: 'POST' }); } catch (e) {}
  loadExecutionLog();
}

async function copyExecutionLog() {
  const el = document.getElementById('executionLogText');
  const text = el ? el.textContent : '';
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    alert('Could not copy - your browser may not allow clipboard access here');
  }
}

// ---------------------------------------------------------------------
// Feature: "Online Search" - same multi-provider search as the app's
// own Online Search screen (OnlineSearchManager), with the same sort
// modes, provider filter, and Add flow that resolves a result (without
// adding it yet) and routes it into the SAME magnet/file-selection
// flow as every other add path in this Web UI.
let onlineSearchResults = [];
let onlineSearchOpen = false;

document.getElementById('onlineSearchBtn').addEventListener('click', function() {
  if (onlineSearchOpen) closeOnlineSearch(); else openOnlineSearch();
});

function openOnlineSearch() {
  closeAllFeaturePanels();
  onlineSearchOpen = true;
  document.getElementById('onlineSearchPanel').style.display = 'block';
}

function closeOnlineSearch() {
  onlineSearchOpen = false;
  document.getElementById('onlineSearchPanel').style.display = 'none';
}

document.getElementById('onlineSearchGoBtn').addEventListener('click', runOnlineSearch);
document.getElementById('onlineSearchInput').addEventListener('keydown', function(e) {
  if (e.key === 'Enter') runOnlineSearch();
});
document.getElementById('onlineSearchSortSelect').addEventListener('change', renderOnlineSearchResults);
document.getElementById('onlineSearchProviderSelect').addEventListener('change', renderOnlineSearchResults);

async function runOnlineSearch() {
  const query = document.getElementById('onlineSearchInput').value.trim();
  const statusEl = document.getElementById('onlineSearchStatus');
  if (!query) { statusEl.textContent = 'Enter a search and tap Search.'; return; }

  statusEl.textContent = 'Searching...';
  document.getElementById('onlineSearchResults').innerHTML = '';

  try {
    const res = await api('/api/online_search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: query })
    });
    const data = await res.json();
    onlineSearchResults = (data.ok ? data.results : []) || [];

    const providerSelect = document.getElementById('onlineSearchProviderSelect');
    const current = providerSelect.value;
    const providers = Array.from(new Set(onlineSearchResults.map(function(r) { return r.sourceName; }))).sort();
    providerSelect.innerHTML = '<option value="All">Provider: All</option>' +
      providers.map(function(p) { return '<option value="' + escapeHtml(p) + '">Provider: ' + escapeHtml(p) + '</option>'; }).join('');
    providerSelect.value = providers.indexOf(current) !== -1 ? current : 'All';

    statusEl.textContent = onlineSearchResults.length + ' result(s) for "' + query + '"';
    renderOnlineSearchResults();
  } catch (e) {
    statusEl.textContent = 'Search failed';
  }
}

// Mirrors the app's applySortAndFilter(): provider filter first, then
// one of Name/Size/Leechers/Age/Seeders (default Seeders).
function renderOnlineSearchResults() {
  const providerFilter = document.getElementById('onlineSearchProviderSelect').value;
  const sort = document.getElementById('onlineSearchSortSelect').value;

  let list = onlineSearchResults.slice();
  if (providerFilter !== 'All') list = list.filter(function(r) { return r.sourceName === providerFilter; });

  switch (sort) {
    case 'Name':
      list.sort(function(a, b) { return a.name.toLowerCase().localeCompare(b.name.toLowerCase()); });
      break;
    case 'Size':
      list.sort(function(a, b) { return (b.sizeBytes || 0) - (a.sizeBytes || 0); });
      break;
    case 'Leechers':
      list.sort(function(a, b) { return (b.leechers || -1) - (a.leechers || -1); });
      break;
    case 'Age':
      list.sort(function(a, b) { return (b.publicationDate || '').localeCompare(a.publicationDate || ''); });
      break;
    default: // Seeders
      list.sort(function(a, b) { return (b.seeders || -1) - (a.seeders || -1); });
  }

  const container = document.getElementById('onlineSearchResults');
  if (list.length === 0) {
    container.innerHTML = '<div class="empty">No results</div>';
    return;
  }

  container.innerHTML = list.map(function(r, i) {
    const bits = [];
    if (r.sizeText) bits.push(escapeHtml(r.sizeText));
    if (r.seeders >= 0) bits.push('Seeds ' + r.seeders);
    if (r.leechers >= 0) bits.push('Peers ' + r.leechers);
    if (r.sourceName) bits.push(escapeHtml(r.sourceName));
    if (r.publicationDate) bits.push(escapeHtml(r.publicationDate));

    const canAdd = !!(r.magnetUri || r.torrentUrl || r.resultPageUrl);

    return '<div class="card">' +
      '<div class="name">' + escapeHtml(r.name) + '</div>' +
      '<div class="meta">' + bits.join(' &middot; ') + '</div>' +
      '<div class="actions">' +
        '<button' + (canAdd ? '' : ' disabled') + ' onclick="onlineSearchAdd(' + i + ')">Add</button>' +
        (r.resultPageUrl ? '<button class="secondary" onclick="openOnlineSearchResultPage(' + i + ')">Open Page</button>' : '') +
      '</div>' +
    '</div>';
  }).join('');

  // Stash the filtered/sorted list so onlineSearchAdd(i) can look up the
  // same result the user is looking at, regardless of provider filter
  // or sort order.
  window.__onlineSearchVisible = list;
}

// Looked up by index rather than inlining the URL into an onclick
// attribute - the URL can itself contain a double quote (or just needs
// JSON.stringify, which emits one), which would otherwise terminate
// the onclick="..." attribute early and corrupt that card's HTML (and
// push every button after it out of place too).
function openOnlineSearchResultPage(i) {
  const result = (window.__onlineSearchVisible || [])[i];
  if (!result || !result.resultPageUrl) return;
  window.open(result.resultPageUrl, '_blank');
}

async function onlineSearchAdd(i) {
  const result = (window.__onlineSearchVisible || [])[i];
  if (!result) return;

  try {
    const res = await api('/api/online_search_resolve', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: result.name,
        sourceName: result.sourceName,
        magnetUri: result.magnetUri,
        torrentUrl: result.torrentUrl,
        resultPageUrl: result.resultPageUrl
      })
    });
    const data = await res.json();
    if (!data.ok) {
      alert('Could not add: ' + (data.error || 'unknown error'));
      return;
    }

    if (data.kind === 'magnet') {
      await addMagnetByValue(data.magnet);
    } else if (data.kind === 'torrent') {
      openUploadFilesForPath(data.path, data.files || []);
    }
  } catch (e) {
    alert('Could not add result');
  }
}

// ---------------------------------------------------------------------
// Feature: "Save Folder" - same "save_folder_settings" prefs the app's
// own Save Folder screen reads/writes. Changing it only affects new
// torrents, same as the app (a per-torrent override is set from that
// torrent's General tab instead - see changeTorrentSaveFolder below).
let saveFolderOpen = false;
let saveFolderCurrentPath = '';

document.getElementById('saveFolderBtn').addEventListener('click', function() {
  if (saveFolderOpen) closeSaveFolder(); else openSaveFolder();
});

function openSaveFolder() {
  closeAllFeaturePanels();
  saveFolderOpen = true;
  document.getElementById('saveFolderPanel').style.display = 'block';
  loadSaveFolder();
}

function closeSaveFolder() {
  saveFolderOpen = false;
  document.getElementById('saveFolderPanel').style.display = 'none';
}

async function loadSaveFolder() {
  const el = document.getElementById('saveFolderCurrent');
  try {
    const res = await api('/api/save_folder');
    const data = await res.json();
    saveFolderCurrentPath = data.ok ? data.path : '';
    el.textContent = 'Current default folder: ' + saveFolderCurrentPath;
  } catch (e) {
    el.textContent = 'Could not load the current folder';
  }
}

document.getElementById('saveFolderApplyBtn').addEventListener('click', function() {
  openFolderPicker(saveFolderCurrentPath, async function(path) {
    try {
      const res = await api('/api/save_folder', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: path })
      });
      const data = await res.json();
      if (!data.ok) { alert('Could not update: ' + (data.error || 'unknown error')); return; }
      alert('Default save folder updated. This only affects new torrents.');
      loadSaveFolder();
    } catch (e) {
      alert('Could not update the save folder');
    }
  });
});

// ---------------------------------------------------------------------
// Feature: a graphical folder picker - same plain folder browsing as
// the app's own "Choose Folder" dialog (Up row, tap a subfolder to go
// in, Back/Forward history, New Folder), shared by the global Save
// Folder panel above and the per-torrent "Change Save Folder" control.
let folderPickerCurrentPath = '';
let folderPickerOnPicked = null;
let folderPickerBackStack = [];
let folderPickerForwardStack = [];
// Rows are looked up by index rather than inlining the folder path into
// the onclick="..." attribute - a path can contain characters (and
// JSON.stringify always adds its own quotes) that would terminate the
// attribute early and corrupt the picker's HTML, the same bug fixed in
// Online Search's "Open Page" button and the RSS item list.
let folderPickerRowPaths = [];

function openFolderPicker(startPath, onPicked) {
  folderPickerOnPicked = onPicked;
  folderPickerBackStack = [];
  folderPickerForwardStack = [];
  document.getElementById('folderPickerOverlay').style.display = 'flex';
  document.getElementById('folderPickerNewNameInput').style.display = 'none';
  navigateFolderPicker(startPath || '');
}

function closeFolderPicker() {
  document.getElementById('folderPickerOverlay').style.display = 'none';
  folderPickerOnPicked = null;
}

async function navigateFolderPicker(path) {
  try {
    const res = await api('/api/browse_folder?path=' + encodeURIComponent(path));
    const data = await res.json();
    if (!data.ok) return;

    folderPickerCurrentPath = data.path;
    document.getElementById('folderPickerPath').textContent = data.path;

    const list = document.getElementById('folderPickerList');
    const rows = [];
    folderPickerRowPaths = [];

    if (data.parent) {
      folderPickerRowPaths.push(data.parent);
      rows.push('<button class="secondary" style="display:block;width:100%;margin-bottom:4px;" onclick="folderPickerGoToIndex(' + (folderPickerRowPaths.length - 1) + ')">.. (Up)</button>');
    }

    if ((data.subfolders || []).length === 0) {
      rows.push('<div class="meta">No subfolders here</div>');
    } else {
      data.subfolders.forEach(function(name) {
        const childPath = data.path.replace(/\/+$/, '') + '/' + name;
        folderPickerRowPaths.push(childPath);
        rows.push('<button class="secondary" style="display:block;width:100%;margin-bottom:4px;text-align:left;" onclick="folderPickerGoToIndex(' + (folderPickerRowPaths.length - 1) + ')">' + escapeHtml(name) + '</button>');
      });
    }

    list.innerHTML = rows.join('');

    document.getElementById('folderPickerBackBtn').disabled = folderPickerBackStack.length === 0;
    document.getElementById('folderPickerForwardBtn').disabled = folderPickerForwardStack.length === 0;
  } catch (e) {}
}

function folderPickerGoToIndex(i) {
  const path = folderPickerRowPaths[i];
  if (path === undefined) return;
  folderPickerBackStack.push(folderPickerCurrentPath);
  folderPickerForwardStack = [];
  navigateFolderPicker(path);
}

// Still used by the New Folder flow, which has the freshly-created
// path straight from the server response rather than from a rendered
// row, so there is no HTML attribute involved to corrupt.
function folderPickerGoTo(path) {
  folderPickerBackStack.push(folderPickerCurrentPath);
  folderPickerForwardStack = [];
  navigateFolderPicker(path);
}

document.getElementById('folderPickerBackBtn').addEventListener('click', function() {
  if (folderPickerBackStack.length === 0) return;
  folderPickerForwardStack.push(folderPickerCurrentPath);
  navigateFolderPicker(folderPickerBackStack.pop());
});

document.getElementById('folderPickerForwardBtn').addEventListener('click', function() {
  if (folderPickerForwardStack.length === 0) return;
  folderPickerBackStack.push(folderPickerCurrentPath);
  navigateFolderPicker(folderPickerForwardStack.pop());
});

document.getElementById('folderPickerNewFolderBtn').addEventListener('click', async function() {
  const input = document.getElementById('folderPickerNewNameInput');

  if (input.style.display === 'none') {
    input.style.display = '';
    input.value = '';
    input.focus();
    return;
  }

  const name = input.value.trim();
  if (!name) return;

  try {
    const res = await api('/api/create_folder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentPath: folderPickerCurrentPath, name: name })
    });
    const data = await res.json();
    if (!data.ok) { alert('Could not create folder: ' + (data.error || 'unknown error')); return; }
    input.style.display = 'none';
    folderPickerGoTo(data.path);
  } catch (e) {
    alert('Could not create folder');
  }
});

document.getElementById('folderPickerUseBtn').addEventListener('click', function() {
  const onPicked = folderPickerOnPicked;
  const path = folderPickerCurrentPath;
  closeFolderPicker();
  if (onPicked) onPicked(path);
});

document.getElementById('folderPickerCancelBtn').addEventListener('click', closeFolderPicker);

// ---------------------------------------------------------------------
// Feature: RSS Feeds - same RssManager the app's own RSS screen uses
// (feed list, cached items, auto-download, auto-refresh interval), so
// anything done here shows up in the app too and vice versa.
let rssOpen = false;
let rssFeeds = [];
let rssOpenItemsFeed = null;
let rssItemsByFeed = {};

const RSS_INTERVAL_OPTIONS = [0, 5, 15, 30, 60, 180, 360, 720, 1440];
const RSS_INTERVAL_LABELS = {
  0: 'Off', 5: 'Every 5 minutes', 15: 'Every 15 minutes', 30: 'Every 30 minutes',
  60: 'Every hour', 180: 'Every 3 hours', 360: 'Every 6 hours', 720: 'Every 12 hours', 1440: 'Every day'
};

(function initRssIntervalSelect() {
  const select = document.getElementById('rssIntervalSelect');
  select.innerHTML = RSS_INTERVAL_OPTIONS.map(function(m) {
    return '<option value="' + m + '">' + RSS_INTERVAL_LABELS[m] + '</option>';
  }).join('');
})();

document.getElementById('rssBtn').addEventListener('click', function() {
  if (rssOpen) closeRss(); else openRss();
});

function openRss() {
  closeAllFeaturePanels();
  rssOpen = true;
  document.getElementById('rssPanel').style.display = 'block';
  loadRssFeeds();
}

function closeRss() {
  rssOpen = false;
  document.getElementById('rssPanel').style.display = 'none';
}

document.getElementById('rssIntervalSelect').addEventListener('change', async function(e) {
  try {
    await api('/api/rss/interval', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ minutes: String(e.target.value) })
    });
  } catch (e) {}
});

document.getElementById('rssRefreshAllBtn').addEventListener('click', async function() {
  document.getElementById('rssStatus').textContent = 'Refreshing...';
  try {
    const res = await api('/api/rss/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feed: '' })
    });
    const data = await res.json();
    document.getElementById('rssStatus').textContent = data.ok
      ? (data.feedsOk + ' feed(s) ok, ' + data.feedsFailed + ' failed, ' + data.newItems + ' new item(s)')
      : (data.error || 'Refresh failed');
  } catch (e) {
    document.getElementById('rssStatus').textContent = 'Refresh failed';
  }
  loadRssFeeds();
});

document.getElementById('rssAddFeedBtn').addEventListener('click', async function() {
  const input = document.getElementById('rssFeedUrlInput');
  const url = input.value.trim();
  if (!url) return;

  try {
    const res = await api('/api/rss/add_feed', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: url })
    });
    const data = await res.json();
    if (!data.ok) { alert('Could not add feed: ' + (data.error || 'unknown error')); return; }
    input.value = '';
    loadRssFeeds();
  } catch (e) {
    alert('Could not add feed');
  }
});

async function loadRssFeeds() {
  if (!rssOpen) return;
  try {
    const res = await api('/api/rss/feeds');
    const data = await res.json();
    if (!data.ok) return;

    rssFeeds = data.feeds || [];
    document.getElementById('rssIntervalSelect').value = String(data.intervalMinutes);
    document.getElementById('rssStatus').textContent = data.refreshing
      ? 'Refreshing...'
      : (rssFeeds.length === 0 ? 'No feeds yet' : '');

    renderRssFeeds();
  } catch (e) {}
}

function renderRssFeeds() {
  const container = document.getElementById('rssFeedList');

  container.innerHTML = rssFeeds.map(function(f, i) {
    const last = f.lastRefresh ? new Date(f.lastRefresh).toLocaleString() : 'never';
    const itemsBlock = (rssOpenItemsFeed === f.feed) ? renderRssItems(i) : '';

    return '<div class="card">' +
      '<div class="name">' + escapeHtml(f.title) + '</div>' +
      '<div class="meta">' + f.itemCount + ' item(s) &middot; last refreshed ' + escapeHtml(last) + '</div>' +
      '<div class="actions">' +
        '<button class="secondary" onclick="rssRefreshFeed(' + i + ')">Refresh</button>' +
        '<button class="secondary" onclick="rssToggleItems(' + i + ')">' + (rssOpenItemsFeed === f.feed ? 'Hide Items' : 'View Items') + '</button>' +
        '<label class="meta"><input type="checkbox" ' + (f.autoDownload ? 'checked' : '') +
          ' onchange="rssSetAuto(' + i + ', this.checked)"> Auto-download</label>' +
        '<button class="danger" onclick="rssRemoveFeed(' + i + ')">Remove</button>' +
      '</div>' +
      itemsBlock +
    '</div>';
  }).join('');
}

// Takes the feed's INDEX in rssFeeds, not the feed URL itself - the URL
// can contain characters (and JSON.stringify always adds surrounding
// double quotes) that would terminate the onclick="..." attribute early
// and corrupt the card's HTML, the same bug fixed in Online Search's
// "Open Page" button. A plain number in the attribute has no such risk.
function renderRssItems(feedIndex) {
  const feed = rssFeeds[feedIndex].feed;
  const items = rssItemsByFeed[feed];
  if (!items) return '<div class="meta">Loading items...</div>';
  if (items.length === 0) return '<div class="meta">No items yet</div>';

  return '<div class="actions" style="margin-top:4px;"><button class="secondary" onclick="rssAddAll(' +
      feedIndex + ')">Add All</button></div>' +
    items.map(function(item, i) {
      const bits = [];
      if (item.sizeText) bits.push(escapeHtml(item.sizeText));

      return '<div class="card">' +
        '<div class="name">' + escapeHtml(item.title) + (item.added ? ' (Added)' : '') + '</div>' +
        (bits.length ? '<div class="meta">' + bits.join(' &middot; ') + '</div>' : '') +
        '<div class="actions">' +
          '<button onclick="rssAddItem(' + feedIndex + ', ' + i + ')">Add</button>' +
          (item.webPage ? '<button class="secondary" onclick="rssOpenItemPage(' + feedIndex + ', ' + i + ')">Open Page</button>' : '') +
        '</div>' +
      '</div>';
    }).join('');
}

async function rssToggleItems(i) {
  const feed = rssFeeds[i].feed;

  if (rssOpenItemsFeed === feed) {
    rssOpenItemsFeed = null;
    renderRssFeeds();
    return;
  }

  rssOpenItemsFeed = feed;
  delete rssItemsByFeed[feed];
  renderRssFeeds();

  try {
    const res = await api('/api/rss/items?feed=' + encodeURIComponent(feed));
    const data = await res.json();
    rssItemsByFeed[feed] = data.ok ? (data.items || []) : [];
  } catch (e) {
    rssItemsByFeed[feed] = [];
  }

  if (rssOpenItemsFeed === feed) renderRssFeeds();
}

async function rssRefreshFeed(i) {
  const feed = rssFeeds[i].feed;
  try {
    await api('/api/rss/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feed: feed })
    });
  } catch (e) {}

  if (rssOpenItemsFeed === feed) delete rssItemsByFeed[feed];
  loadRssFeeds();
  if (rssOpenItemsFeed === feed) rssToggleItemsReload(feed);
}

async function rssToggleItemsReload(feed) {
  try {
    const res = await api('/api/rss/items?feed=' + encodeURIComponent(feed));
    const data = await res.json();
    rssItemsByFeed[feed] = data.ok ? (data.items || []) : [];
  } catch (e) {}
  renderRssFeeds();
}

async function rssSetAuto(i, checked) {
  const feed = rssFeeds[i].feed;
  try {
    await api('/api/rss/set_auto', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feed: feed, enabled: checked ? 'true' : 'false' })
    });
  } catch (e) {}
  loadRssFeeds();
}

async function rssRemoveFeed(i) {
  const feed = rssFeeds[i].feed;
  if (!confirm('Remove this feed? Its cached items and settings are removed too.')) return;

  try {
    await api('/api/rss/remove_feed', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feed: feed })
    });
  } catch (e) {}

  if (rssOpenItemsFeed === feed) rssOpenItemsFeed = null;
  delete rssItemsByFeed[feed];
  loadRssFeeds();
}

// Resolves an RSS item (without adding it yet) and routes it into the
// SAME magnet/file-selection flow as the manual Add box and Online
// Search Add button, exactly like RssManager.resolveItemForSelection is
// used in the app - nothing is handed to the engine until the user
// commits to downloading it, at which point rssMarkAdded below is
// called so "Added" reflects real intent, same as the app.
async function rssAddItem(feedIndex, i) {
  const feed = rssFeeds[feedIndex].feed;
  const item = (rssItemsByFeed[feed] || [])[i];
  if (!item) return;

  try {
    const res = await api('/api/rss/resolve_item', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feed: feed, title: item.title, link: item.link, sizeText: item.sizeText, page: item.page })
    });
    const data = await res.json();
    if (!data.ok) { alert('Could not add: ' + (data.error || 'unknown error')); return; }

    // Only marked "Added" once the user actually submits the file-
    // selection panel (pendingSubmitCallback, consumed by submitUpload),
    // not the moment the panel opens - mirrors the app only calling
    // RssManager.markItemAdded from inside that screen's own success path.
    pendingSubmitCallback = function() { rssMarkAdded(feed, item); };

    if (data.kind === 'magnet') {
      const added = await addMagnetByValue(data.magnet);
      if (!added) pendingSubmitCallback = null;
    } else if (data.kind === 'torrent') {
      openUploadFilesForPath(data.path, data.files || []);
    }
  } catch (e) {
    alert('Could not add item');
  }
}

async function rssMarkAdded(feed, item) {
  try {
    await api('/api/rss/mark_added', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ feed: feed, title: item.title, link: item.link, sizeText: item.sizeText, page: item.page })
    });
  } catch (e) {}
  rssToggleItemsReload(feed);
}

async function rssAddAll(feedIndex) {
  const feed = rssFeeds[feedIndex].feed;
  const items = (rssItemsByFeed[feed] || []).filter(function(it) { return !it.added; });
  if (items.length === 0) { alert('Nothing new to add'); return; }
  if (!confirm('Add all ' + items.length + ' item(s) of this feed?')) return;

  for (const item of items) {
    try {
      const res = await api('/api/rss/resolve_item', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ feed: feed, title: item.title, link: item.link, sizeText: item.sizeText, page: item.page })
      });
      const data = await res.json();
      if (data.ok && data.kind === 'magnet') {
        await addMagnetByValue(data.magnet);
        await submitUpload(false);
        await rssMarkAdded(feed, item);
      } else if (data.ok && data.kind === 'torrent') {
        openUploadFilesForPath(data.path, data.files || []);
        await submitUpload(false);
        await rssMarkAdded(feed, item);
      }
    } catch (e) {}
  }
}

function rssOpenItemPage(feedIndex, i) {
  const feed = rssFeeds[feedIndex].feed;
  const item = (rssItemsByFeed[feed] || [])[i];
  if (!item || !item.webPage) return;
  window.open(item.webPage, '_blank');
}

document.getElementById('addBtn').addEventListener('click', async function() {
  const input = document.getElementById('magnetInput');
  const magnet = input.value.trim();
  if (!magnet) return;
  const ok = await addMagnetByValue(magnet);
  if (ok) input.value = '';
});

// Shared by the manual "Add" box above and the Online Search "Add"
// button below - both just need to hand a magnet URI to the SAME
// metadata-fetch + file-selection flow (add_magnet_metadata, then
// pollMagnetFiles/renderUploadFiles), never add directly.
async function addMagnetByValue(magnet) {
  if (!magnet) return false;

  try {
    const res = await api('/api/add_magnet_metadata', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet: magnet })
    });
    const data = await res.json();
    if (!data.ok) {
      alert(data.alreadyAdded ? 'Torrent already added' : 'Could not add: ' + (data.error || 'unknown error'));
      return false;
    }

    pendingMode = 'magnet';
    pendingMagnetIndex = data.index;
    pendingMagnetValue = data.magnet;

    document.getElementById('uploadPanelTitle').textContent = 'Fetching magnet metadata...';
    document.getElementById('uploadFileList').innerHTML = '';
    document.getElementById('uploadPanel').style.display = 'block';
    notePanelOpened();

    pollMagnetFiles();
    return true;
  } catch (e) {
    return false;
  }
}

// Shared by the file-upload box above and the Online Search "Add"
// button below for a result that resolves to a .torrent file instead
// of a magnet - both already have a server-side path + file list, so
// this just opens the same file-selection panel they use.
function openUploadFilesForPath(path, files) {
  pendingMode = 'torrent';
  pendingUploadPath = path;
  document.getElementById('uploadPanelTitle').textContent = 'Select files';
  renderUploadFiles(files || []);
  document.getElementById('uploadPanel').style.display = 'block';
  notePanelOpened();
}

let pendingMode = null; // 'torrent' | 'magnet'
let pendingUploadPath = null;
let pendingMagnetIndex = null;
let pendingMagnetValue = null;
let magnetPollTimer = null;

function pollMagnetFiles() {
  api('/api/magnet_files?index=' + pendingMagnetIndex)
    .then(function(res) { return res.json(); })
    .then(function(data) {
      if (pendingMode !== 'magnet') return; // cancelled while this was in flight

      if (data.ok && data.ready) {
        document.getElementById('uploadPanelTitle').textContent = 'Select files';
        renderUploadFiles(data.files || []);
      } else {
        magnetPollTimer = setTimeout(pollMagnetFiles, 2000);
      }
    })
    .catch(function() {
      magnetPollTimer = setTimeout(pollMagnetFiles, 2000);
    });
}

document.getElementById('uploadBtn').addEventListener('click', async function() {
  const input = document.getElementById('torrentFileInput');
  const file = input.files && input.files[0];
  if (!file) { alert('Choose a .torrent file first'); return; }

  try {
    const bytes = await file.arrayBuffer();
    const res = await api('/api/upload_torrent', {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: bytes
    });
    const data = await res.json();

    if (!data.ok) {
      alert('Could not read torrent: ' + (data.error || 'unknown error'));
      return;
    }

    pendingMode = 'torrent';
    pendingUploadPath = data.path;
    document.getElementById('uploadPanelTitle').textContent = 'Select files';
    renderUploadFiles(data.files || []);
    document.getElementById('uploadPanel').style.display = 'block';
    notePanelOpened();
  } catch (e) {
    alert('Upload failed');
  }
});

function renderUploadFiles(files) {
  const list = document.getElementById('uploadFileList');
  list.innerHTML = files.map(function(f) {
    return '<label><input type="checkbox" class="fileCheck" data-index="' + f.index +
      '" checked> ' + fileTypeIcon(f.name) + ' ' + escapeHtml(f.name) + ' (' + formatSize(f.size) + ')</label>';
  }).join('');
}

function setAllFileChecks(checked) {
  document.querySelectorAll('.fileCheck').forEach(function(c) {
    c.checked = checked;
  });
}

async function submitUpload(selectedOnly) {
  if (pendingMode === 'torrent' && !pendingUploadPath) return;
  if (pendingMode === 'magnet' && !pendingMagnetIndex) return;

  let selected = '';

  if (selectedOnly) {
    const checks = document.querySelectorAll('.fileCheck:checked');
    const indexes = Array.from(checks).map(function(c) { return c.dataset.index; });
    if (indexes.length === 0) { alert('Select at least one file'); return; }
    selected = indexes.join(',');
  }

  if (pendingMode === 'magnet') {
    await api('/api/confirm_magnet', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ index: String(pendingMagnetIndex), magnet: pendingMagnetValue, selected: selected })
    });
  } else {
    await api('/api/add_torrent_path', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path: pendingUploadPath, selected: selected })
    });
  }

  // Lets a caller (RSS's Add button) know the user actually committed to
  // downloading this, as opposed to just opening the file-selection
  // panel - mirrors the app only calling RssManager.markItemAdded from
  // inside the file-selection screen's own success callback, not the
  // moment the screen opens.
  const callback = pendingSubmitCallback;
  pendingSubmitCallback = null;

  resetUploadState();
  refresh();
  if (callback) callback();
}

let pendingSubmitCallback = null;

// Silent cleanup after a successful submit - unlike cancelUpload(), this
// never removes a paused magnet torrent, since submitUpload() either
// already confirmed it or it was never a magnet to begin with.
function resetUploadState() {
  if (magnetPollTimer) { clearTimeout(magnetPollTimer); magnetPollTimer = null; }
  pendingMode = null;
  pendingUploadPath = null;
  pendingMagnetIndex = null;
  pendingMagnetValue = null;
  document.getElementById('uploadPanel').style.display = 'none';
  document.getElementById('torrentFileInput').value = '';
  notePanelsMaybeClosed();
}

async function cancelUpload() {
  // For a magnet, this torrent was never shown as a card (the server
  // hides it by hash while it's still pending confirmation - see
  // pendingMagnetHashes), so cancelling actually removes it: nothing
  // was ever visibly "added" for the user to see, and nothing should be
  // left behind either.
  if (pendingMode === 'magnet' && pendingMagnetIndex) {
    try { await api('/api/remove/' + pendingMagnetIndex, { method: 'POST' }); } catch (e) {}
  }
  pendingSubmitCallback = null;
  resetUploadState();
  refresh();
}

let openFilesIndex = null;
let openFilesTab = 'files'; // 'files' | 'priority' | 'general' | 'peers' | 'swarmhealth' | 'pieces' | 'trackers' | 'webseeds' | 'statistics' | 'comment'
let lastFilesList = [];

async function toggleFiles(index) {
  const panel = document.getElementById('filesPanel');

  if (openFilesIndex === index) {
    openFilesIndex = null;
    panel.style.display = 'none';
    panel.innerHTML = '';
    notePanelsMaybeClosed();
    return;
  }

  closeAllFeaturePanels();

  openFilesIndex = index;
  openFilesTab = 'files';
  lastFilesList = [];
  panel.style.display = 'block';
  panel.innerHTML = '<div class="name">Files</div><div class="empty">Loading...</div>';
  notePanelOpened();

  try {
    const res = await api('/api/torrent_files?index=' + index);
    const data = await res.json();

    if (openFilesIndex !== index) return; // closed/switched while this was in flight

    if (!data.ok || !data.ready) {
      panel.innerHTML = '<div class="name">Files</div>' +
        '<div class="empty">Not ready yet - still fetching metadata.</div>' +
        '<div class="actions"><button class="secondary" onclick="toggleFiles(' + index + ')">Close</button></div>';
      return;
    }

    lastFilesList = data.files || [];
    renderFilesPanel();
  } catch (e) {
    panel.innerHTML = '<div class="name">Files</div><div class="empty">Could not load files.</div>';
  }
}

// Feature: tab switcher inside the file browser - "Files" (browse/play/
// view/download, unchanged) and "Priority" (choose which files should
// download, even after this torrent was already added, plus each file's
// Seeds/Peers - the Web UI equivalent of MainActivity's "Apply File
// Selection" button and its per-file swarm line). Switching tabs always
// re-renders the panel, which closes any open video/audio/text/image
// viewer - same as closing the whole panel would.
function switchFilesTab(tab) {
  openFilesTab = tab;
  renderFilesPanel();
}

function renderFilesPanel() {
  const panel = document.getElementById('filesPanel');
  const index = openFilesIndex;
  if (index === null) return;

  const tabs = '<div class="actions" style="margin-bottom:8px;flex-wrap:wrap;">' +
    '<button class="' + (openFilesTab === 'general' ? '' : 'secondary') + '" onclick="switchFilesTab(\'general\')">General</button>' +
    '<button class="' + (openFilesTab === 'files' ? '' : 'secondary') + '" onclick="switchFilesTab(\'files\')">Files</button>' +
    '<button class="' + (openFilesTab === 'priority' ? '' : 'secondary') + '" onclick="switchFilesTab(\'priority\')">Priority &amp; Peers</button>' +
    '<button class="' + (openFilesTab === 'peers' ? '' : 'secondary') + '" onclick="switchFilesTab(\'peers\')">Peers</button>' +
    '<button class="' + (openFilesTab === 'swarmhealth' ? '' : 'secondary') + '" onclick="switchFilesTab(\'swarmhealth\')">Swarm Health</button>' +
    '<button class="' + (openFilesTab === 'pieces' ? '' : 'secondary') + '" onclick="switchFilesTab(\'pieces\')">Pieces</button>' +
    '<button class="' + (openFilesTab === 'trackers' ? '' : 'secondary') + '" onclick="switchFilesTab(\'trackers\')">Trackers</button>' +
    '<button class="' + (openFilesTab === 'webseeds' ? '' : 'secondary') + '" onclick="switchFilesTab(\'webseeds\')">Web Seeds</button>' +
    '<button class="' + (openFilesTab === 'statistics' ? '' : 'secondary') + '" onclick="switchFilesTab(\'statistics\')">Statistics</button>' +
    '<button class="' + (openFilesTab === 'comment' ? '' : 'secondary') + '" onclick="switchFilesTab(\'comment\')">Comment</button>' +
  '</div>';

  // Plain-text tabs - same shape as the app's own General/Peers/Swarm/
  // Pieces/Trackers/Web Seeds/Statistics/Comment screens: one endpoint
  // per tab, result shown as-is in a <pre>.
  const textTabEndpoints = {
    general: '/api/torrent_general',
    peers: '/api/torrent_peers',
    swarmhealth: '/api/torrent_swarmhealth',
    pieces: '/api/torrent_pieces',
    trackers: '/api/torrent_trackers',
    webseeds: '/api/torrent_webseeds',
    statistics: '/api/torrent_statistics',
    comment: '/api/torrent_comment'
  };

  let body;
  if (openFilesTab === 'priority') {
    const rows = lastFilesList.map(function(f) { return renderPriorityRow(index, f); }).join('');
    body = rows +
      '<div class="actions" style="margin-top:8px;">' +
        '<button class="secondary" onclick="setAllPriorityChecks(true)">Select All</button>' +
        '<button class="secondary" onclick="setAllPriorityChecks(false)">Select None</button>' +
        '<button onclick="applyFilePriorities(' + index + ')">Apply</button>' +
      '</div>';
  } else if (openFilesTab === 'swarmhealth') {
    // Feature: a small live Seeds/Peers graph above the swarm health
    // text - the app has no such graph (swarm health is text-only
    // there), built in the same visual style as the app's own
    // download/upload SpeedGraphView (rounded dark panel, grid, two
    // smooth gradient-filled lines) so it reads as part of the same app.
    body = '<canvas id="swarmHealthCanvas" width="600" height="140" style="width:100%;height:140px;display:block;margin-bottom:8px;"></canvas>' +
      '<pre id="tabText" style="white-space:pre-wrap;max-height:300px;overflow:auto;background:#0003;padding:8px;border-radius:6px;">Loading...</pre>';
  } else if (textTabEndpoints[openFilesTab]) {
    body = '<pre id="tabText" style="white-space:pre-wrap;max-height:360px;overflow:auto;background:#0003;padding:8px;border-radius:6px;">Loading...</pre>';
  } else {
    body = lastFilesList.map(function(f) { return renderFileRow(index, f); }).join('');
  }

  const forceButtons = (openFilesTab === 'general')
    ? '<div class="actions" style="margin-top:8px;">' +
        '<button class="secondary" onclick="forceAction(' + index + ', \'recheck\')">Force Recheck</button>' +
        '<button class="secondary" onclick="forceAction(' + index + ', \'reannounce\')">Force Reannounce</button>' +
      '</div>'
    : '';

  // Feature: per-torrent "Change Save Folder" - same moveTorrentStorage()
  // call the app's own General/Details screen button makes, since this
  // torrent may already have data on disk (never a plain prefs-only
  // change). Lives on the General tab, same place the app puts it.
  const saveFolderRow = (openFilesTab === 'general')
    ? '<div class="meta" style="margin-top:8px;" id="torrentSaveFolderText">Loading current folder...</div>' +
      '<div class="actions">' +
        '<button class="secondary" onclick="changeTorrentSaveFolder(' + index + ')">Change Save Folder</button>' +
      '</div>'
    : '';

  panel.innerHTML = '<div class="name">Files</div>' + tabs + body + forceButtons + saveFolderRow +
    '<div class="actions"><button class="secondary" onclick="toggleFiles(' + index + ')">Close</button></div>';

  if (openFilesTab === 'swarmhealth') {
    swarmHealthHistory = { seeds: [], peers: [] };
    drawSwarmHealthGraph();
  }

  if (openFilesTab === 'general') loadTorrentSaveFolder(index);

  if (textTabEndpoints[openFilesTab]) loadTextTab(index, openFilesTab);
}

// Feature: Force Recheck / Force Reannounce - same native calls as
// MainActivity's own buttons. Fire-and-forget (no result to show beyond
// the General tab's own Status field changing), so this just re-loads
// General afterwards, same as the app calling showGeneralTab().
async function forceAction(index, kind) {
  try {
    await api('/api/force_' + kind + '/' + index, { method: 'POST' });
  } catch (e) {}

  if (openFilesIndex === index) {
    openFilesTab = 'general';
    renderFilesPanel();
  }
}

// Feature: per-torrent "Change Save Folder" - shows the folder this
// torrent is currently saved to (its own override if one was set,
// otherwise the global default - same as getSavedSaveFolderForHash() ?:
// getGlobalSaveFolder() in the app) and moves it on request, picked
// graphically through the same folder browser as the global Save
// Folder panel (openFolderPicker).
let torrentSaveFolderCurrentPath = '';

async function loadTorrentSaveFolder(index) {
  const el = document.getElementById('torrentSaveFolderText');
  if (!el) return;
  try {
    const res = await api('/api/torrent_save_folder?index=' + index);
    const data = await res.json();
    if (data.ok) {
      torrentSaveFolderCurrentPath = data.path;
      el.textContent = 'Save Folder: ' + data.path;
    }
  } catch (e) {}
}

function changeTorrentSaveFolder(index) {
  openFolderPicker(torrentSaveFolderCurrentPath, function(path) {
    applyTorrentSaveFolder(index, path);
  });
}

async function applyTorrentSaveFolder(index, path) {
  try {
    const res = await api('/api/torrent_save_folder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ index: String(index), path: path })
    });
    const data = await res.json();
    if (!data.ok) { alert('Could not change save folder: ' + (data.error || 'unknown error')); return; }
    torrentSaveFolderCurrentPath = path;
    const el = document.getElementById('torrentSaveFolderText');
    if (el) el.textContent = 'Save Folder: ' + path;
    alert('Moving files to ' + path + ' - this may take a moment for large torrents. Check the Execution Log for when it\'s done.');
  } catch (e) {
    alert('Could not change save folder');
  }
}

// Fetches one of the plain-text tabs (Peers/Trackers/Web Seeds) and
// shows it as-is in the #tabText <pre> - same text MainActivity's own
// equivalent screens show, not reparsed into a table.
async function loadTextTab(index, tab) {
  const endpoints = {
    general: '/api/torrent_general',
    peers: '/api/torrent_peers',
    swarmhealth: '/api/torrent_swarmhealth',
    pieces: '/api/torrent_pieces',
    trackers: '/api/torrent_trackers',
    webseeds: '/api/torrent_webseeds',
    statistics: '/api/torrent_statistics',
    comment: '/api/torrent_comment'
  };
  const endpoint = endpoints[tab];
  if (!endpoint) return;

  try {
    const res = await api(endpoint + '?index=' + index);
    const data = await res.json();
    const el = document.getElementById('tabText');
    if (!el || openFilesIndex !== index || openFilesTab !== tab) return;
    el.textContent = (data.ok ? data.text : 'Could not load') || 'Nothing to show';
  } catch (e) {
    const el = document.getElementById('tabText');
    if (el && openFilesIndex === index && openFilesTab === tab) el.textContent = 'Could not load';
  }
}

function setAllPriorityChecks(checked) {
  document.querySelectorAll('.priorityCheck').forEach(function(c) {
    c.checked = checked;
  });
}

async function applyFilePriorities(torrentIndex) {
  const checks = document.querySelectorAll('.priorityCheck');
  const checkedIndexes = Array.from(checks)
    .filter(function(c) { return c.checked; })
    .map(function(c) { return c.dataset.index; });

  if (checkedIndexes.length === 0 &&
      !confirm('This will pause downloading all files in this torrent. Continue?')) {
    return;
  }

  try {
    await api('/api/set_file_priorities', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ index: String(torrentIndex), selected: checkedIndexes.join(',') })
    });
  } catch (e) {}

  // Re-fetch so progress/percent reflects the new selection right away,
  // then stay on the Priority tab showing the just-applied state.
  try {
    const res = await api('/api/torrent_files?index=' + torrentIndex);
    const data = await res.json();
    if (openFilesIndex === torrentIndex && data.ok && data.ready) {
      lastFilesList = data.files || [];
    }
  } catch (e) {}

  if (openFilesIndex === torrentIndex) renderFilesPanel();
  refresh();
}

function fileKind(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  if (['mp4', 'm4v', 'webm', 'mkv', 'avi', 'mov'].indexOf(ext) !== -1) return 'video';
  if (['mp3', 'm4a', 'flac', 'wav', 'ogg', 'oga'].indexOf(ext) !== -1) return 'audio';
  if (['txt', 'nfo', 'srt', 'vtt', 'log', 'md'].indexOf(ext) !== -1) return 'text';
  if (['jpg', 'jpeg', 'png', 'gif', 'webp'].indexOf(ext) !== -1) return 'image';
  return 'other';
}

// Same per-extension icon set as the app's own Files tab/file-selection
// screens (MainActivity.fileTypeIcon) - a plain emoji prefix, not an
// image, consistent with how the app itself does it.
function fileTypeIcon(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();

  if (['mp4', 'mkv', 'avi', 'mov', 'webm', 'm4v', 'wmv', 'flv', 'ts', 'mpg', 'mpeg', '3gp'].indexOf(ext) !== -1) {
    return '\u{1F3AC}'; // 🎬
  }
  if (['mp3', 'flac', 'wav', 'aac', 'm4a', 'ogg', 'wma', 'opus'].indexOf(ext) !== -1) {
    return '\u{1F3B5}'; // 🎵
  }
  if (['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'heic'].indexOf(ext) !== -1) {
    return '\u{1F5BC}\u{FE0F}'; // 🖼️
  }
  if (['txt', 'srt', 'ass', 'ssa', 'nfo', 'log', 'json', 'xml', 'md', 'csv', 'pdf', 'doc', 'docx', 'rtf'].indexOf(ext) !== -1) {
    return '\u{1F4C4}'; // 📄
  }
  if (['exe', 'msi', 'apk', 'bat', 'sh', 'jar', 'deb', 'dmg', 'run'].indexOf(ext) !== -1) {
    return '\u{2699}\u{FE0F}'; // ⚙️
  }
  if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz'].indexOf(ext) !== -1) {
    return '\u{1F4E6}'; // 📦
  }

  return '\u{1F4C1}'; // 📁
}

function renderFileRow(torrentIndex, f) {
  const rowId = 'frow_' + torrentIndex + '_' + f.index;
  const streamUrl = '/stream/' + torrentIndex + '/' + f.index;
  const downloadUrl = '/download/' + torrentIndex + '/' + f.index;
  const kind = fileKind(f.name);

  let actionBtn = '';
  if (kind === 'video' || kind === 'audio') {
    actionBtn = '<button onclick="playMedia(\'' + rowId + '\', \'' + streamUrl + '\', \'' + kind + '\')">Play</button>';
  } else if (kind === 'text') {
    actionBtn = '<button onclick="viewText(\'' + rowId + '\', \'' + streamUrl + '\')">View</button>';
  } else if (kind === 'image') {
    actionBtn = '<button onclick="viewImage(\'' + rowId + '\', \'' + streamUrl + '\')">View</button>';
  }

  const percent = Number(f.percent) || 0;

  // ptext_/pbar_ get updated in place every second (see
  // refreshFilesPanelProgress) rather than by re-rendering the whole
  // row - a full re-render would wipe out whatever's sitting in the
  // #rowId viewer slot below (a playing <video>/<audio>, an open text
  // view), the same reason the files panel itself lives outside the
  // auto-refreshed torrent list.
  return '<div class="card" style="margin:8px 0;" data-file-index="' + f.index + '">' +
    '<div class="meta">' + fileTypeIcon(f.name) + ' ' + escapeHtml(f.name) + ' (' + formatSize(f.size) + ')</div>' +
    '<div class="meta" id="ptext_' + rowId + '">' + percent + '%</div>' +
    '<div class="bar"><div class="fill" id="pbar_' + rowId + '" style="width:' + percent + '%"></div></div>' +
    '<div class="actions">' + actionBtn +
      '<button class="secondary" onclick="window.location.href=\'' + downloadUrl + '\'">Download</button>' +
    '</div>' +
    '<div id="' + rowId + '"></div>' +
  '</div>';
}

// Priority tab row: a checkbox (checked = will download, unchecked =
// skip - same binary choice MainActivity.setTorrentFilePriorities
// supports, there's no tiered Low/Normal/High at the native level) plus
// this file's Seeds/Peers, same figures the app's own Files tab shows
// inline. Defaults to f.selected, which the server derives from the
// saved "file_priorities" prefs (or "everything selected" if nothing
// was ever saved for this torrent) - never from list index.
function renderPriorityRow(torrentIndex, f) {
  const rowId = 'frow_' + torrentIndex + '_' + f.index;
  const checked = f.selected !== false;

  return '<div class="card" style="margin:8px 0;" data-file-index="' + f.index + '">' +
    '<label class="meta">' +
      '<input type="checkbox" class="priorityCheck" data-index="' + f.index + '"' + (checked ? ' checked' : '') + '> ' +
      fileTypeIcon(f.name) + ' ' + escapeHtml(f.name) + ' (' + formatSize(f.size) + ')' +
    '</label>' +
    '<div class="meta" id="pswarm_' + rowId + '">' +
      'Seeds ' + (Number(f.seeds) || 0) + ' (' + (Number(f.totalSeeds) || 0) + ')' +
      '  Peers ' + (Number(f.peers) || 0) + ' (' + (Number(f.totalPeers) || 0) + ')' +
    '</div>' +
  '</div>';
}

// Closes whichever viewer (video/audio/text/image) is currently open in
// this file row. Setting innerHTML = '' also removes any <video>/<audio>
// element in there, which stops playback - no separate pause step needed.
function closeViewer(rowId) {
  const el = document.getElementById(rowId);
  if (el) el.innerHTML = '';
}

function hideButton(rowId) {
  return '<div class="actions"><button class="secondary" onclick="closeViewer(\'' + rowId + '\')">Hide</button></div>';
}

function playMedia(rowId, url, kind) {
  const el = document.getElementById(rowId);
  if (kind === 'video') {
    el.innerHTML = '<video controls autoplay style="width:100%;max-height:360px;" src="' + url + '"></video>' + hideButton(rowId);
  } else {
    el.innerHTML = '<audio controls autoplay style="width:100%;" src="' + url + '"></audio>' + hideButton(rowId);
  }
}

async function viewText(rowId, url) {
  const el = document.getElementById(rowId);
  el.innerHTML = '<div class="empty">Loading...</div>';
  try {
    const res = await fetch(url);
    const text = await res.text();
    el.innerHTML = '<pre style="white-space:pre-wrap;max-height:360px;overflow:auto;background:#0003;padding:8px;border-radius:6px;">' +
      escapeHtml(text) + '</pre>' + hideButton(rowId);
  } catch (e) {
    el.innerHTML = '<div class="empty">Could not load file.</div>' + hideButton(rowId);
  }
}

function viewImage(rowId, url) {
  const el = document.getElementById(rowId);
  el.innerHTML = '<img src="' + url + '" style="max-width:100%;max-height:360px;">' + hideButton(rowId);
}

document.getElementById('pauseAllBtn').addEventListener('click', async function() {
  await api('/api/pause_all', { method: 'POST' });
  refresh();
});

document.getElementById('resumeAllBtn').addEventListener('click', async function() {
  await api('/api/resume_all', { method: 'POST' });
  refresh();
});

refresh();
setInterval(refresh, 1000);
</script>
</body>
</html>
""".trimIndent()
}
