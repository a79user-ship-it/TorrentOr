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
import java.util.Date
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
                    sendJson(output, buildStatusJson())

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
                    handleFilesList(parseQueryParam(query, "index")?.toIntOrNull(), output)

                method == "POST" && path == "/api/confirm_magnet" ->
                    handleConfirmMagnet(context, String(bodyBytes, StandardCharsets.UTF_8), output)

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
    private fun buildStatusJson(): String {
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
                torrents.append("\"eta\":").append(jsonString(eta))
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

        // Same check MainActivity.showMagnetMetadataScreen does before
        // it ever calls addMagnetPaused - pulls the hash straight out of
        // the magnet text itself (no torrent handle needed yet) and
        // checks it against every torrent already added. Without this,
        // "re-adding" a torrent that's already downloading opens a
        // SECOND handle for the same hash, which addMagnetPaused pauses
        // - so the real, already-running download stops the instant its
        // paused twin is created, and starts fetching metadata it
        // already has as if from scratch.
        if (isTorrentAlreadyAddedByHash(extractHashFromMagnetInput(magnet))) {
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
    private fun handleFilesList(torrentIndex: Int?, output: OutputStream) {
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

        sendJson(output, "{\"ok\":true,\"ready\":true,\"files\":${buildFilesJsonArrayWithProgress(torrentIndex, fileData)}}")
    }

    // Same shape as buildFilesJsonArray, plus each file's own download
    // percent (TorrentNative.getTorrentFileProgress) - for the file
    // browser's per-file progress bars. A magnet still mid file-
    // selection (not yet confirmed - see pendingMagnetHashes) has no
    // real progress yet, so this falls back to 0 for every file rather
    // than failing the whole list; that's also what getTorrentFileProgress
    // itself returns for anything it can't read right now.
    private fun buildFilesJsonArrayWithProgress(torrentIndex: Int, fileData: String): String {
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

            if (!first) filesJson.append(",")
            first = false

            filesJson.append("{\"index\":").append(index)
                .append(",\"name\":").append(jsonString(name))
                .append(",\"size\":").append(size)
                .append(",\"percent\":").append(percent)
                .append("}")
        }

        filesJson.append("]")
        return filesJson.toString()
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
  /* Dark navy "eye comfort" theme: a deep, low-brightness blue-black
     backdrop instead of a bright/saturated blue, softer off-white text
     instead of pure white, and muted (not neon) accent colors - easier
     to look at for long stretches, especially at night. Colors are kept
     as custom properties so the whole palette can be swapped in one
     place later if wanted. */
  :root {
    color-scheme: dark;
    --bg: #0A1420;
    --surface: #132339;
    --surface-alt: #0E1C2C;
    --accent: #3D6FA5;
    --accent-dim: #1C3350;
    --danger: #8C3A3A;
    --text: #DCE6F0;
    --text-dim: rgba(220, 230, 240, 0.72);
    --fill: #4F86B8;
    --track: rgba(0, 0, 0, 0.35);
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    font-family: -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif;
    background: var(--bg);
    color: var(--text);
    padding: 16px;
  }
  h1 { font-size: 22px; margin: 0 0 16px; }
  .row { display: flex; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; align-items: center; }
  input[type=text] {
    flex: 1;
    min-width: 200px;
    padding: 10px;
    border-radius: 8px;
    border: 1px solid var(--accent-dim);
    background: var(--surface-alt);
    color: var(--text);
    font-size: 14px;
  }
  input[type=file] { color: var(--text); flex: 1; min-width: 200px; }
  button {
    padding: 10px 14px;
    border-radius: 8px;
    border: none;
    background: var(--accent);
    color: var(--text);
    font-size: 14px;
    cursor: pointer;
  }
  button:active { opacity: 0.8; }
  button.danger { background: var(--danger); }
  button.secondary { background: var(--accent-dim); }
  .card {
    background: var(--surface);
    border-radius: 14px;
    padding: 14px;
    margin-bottom: 12px;
  }
  .name { font-weight: 600; font-size: 15px; margin-bottom: 4px; word-break: break-word; color: var(--text); }
  .meta { font-size: 13px; color: var(--text-dim); margin-bottom: 6px; }
  .bar {
    height: 8px;
    border-radius: 4px;
    background: var(--track);
    overflow: hidden;
    margin-bottom: 10px;
  }
  .fill { height: 100%; background: var(--fill); }
  .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
  .empty { color: var(--text-dim); padding: 24px 0; text-align: center; }
  .status { font-size: 12px; color: var(--text-dim); margin-top: 24px; text-align: center; }
  #uploadPanel { display: none; }
  #uploadFileList label { display: block; font-size: 14px; padding: 4px 0; }
</style>
</head>
<body>
  <h1>TorrentOr</h1>

  <div class="row">
    <input type="text" id="magnetInput" placeholder="Paste magnet link or hash">
    <button id="addBtn">Add</button>
  </div>

  <div class="row">
    <input type="file" id="torrentFileInput" accept=".torrent">
    <button id="uploadBtn">Upload .torrent</button>
  </div>

  <div class="card" id="uploadPanel">
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
  </div>

  <div id="list"></div>

  <!-- Deliberately OUTSIDE #list: #list is fully rebuilt by render()
       every 1 second, which would destroy this panel (and interrupt any
       playing video/audio) once a second if it lived inside a torrent's
       card. Only one torrent's files are shown here at a time. -->
  <div class="card" id="filesPanel" style="display:none;"></div>

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
  } catch (e) {
    // Already reported above, or a transient network hiccup - leave the
    // list as it was rather than flashing it empty.
  }

  refreshFilesPanelProgress();
}

// Keeps the open file browser's per-file percent/progress bars moving
// every second, without touching anything else in that panel - updates
// two elements per row in place rather than calling toggleFiles() again,
// which would re-render the whole panel and cut off a playing video/
// audio element or an open text/image viewer sitting in that row.
async function refreshFilesPanelProgress() {
  if (openFilesIndex === null) return;

  try {
    const res = await api('/api/torrent_files?index=' + openFilesIndex);
    const data = await res.json();
    if (openFilesIndex === null || !data.ok || !data.ready) return;

    (data.files || []).forEach(function(f) {
      const rowId = 'frow_' + openFilesIndex + '_' + f.index;
      const percent = Number(f.percent) || 0;

      const textEl = document.getElementById('ptext_' + rowId);
      const barEl = document.getElementById('pbar_' + rowId);

      if (textEl) textEl.textContent = percent + '%';
      if (barEl) barEl.style.width = percent + '%';
    });
  } catch (e) {
    // Transient hiccup - leave the panel as it was, same as the main list.
  }
}

function render(torrents) {
  const list = document.getElementById('list');

  if (torrents.length === 0) {
    list.innerHTML = '<div class="empty">No torrents yet</div>';
    return;
  }

  list.innerHTML = torrents.map(function(t) {
    return '<div class="card">' +
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

document.getElementById('addBtn').addEventListener('click', async function() {
  const input = document.getElementById('magnetInput');
  const magnet = input.value.trim();
  if (!magnet) return;

  try {
    const res = await api('/api/add_magnet_metadata', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ magnet: magnet })
    });
    const data = await res.json();
    if (!data.ok) {
      alert(data.alreadyAdded ? 'Torrent already added' : 'Could not add: ' + (data.error || 'unknown error'));
      return;
    }

    input.value = '';
    pendingMode = 'magnet';
    pendingMagnetIndex = data.index;
    pendingMagnetValue = data.magnet;

    document.getElementById('uploadPanelTitle').textContent = 'Fetching magnet metadata...';
    document.getElementById('uploadFileList').innerHTML = '';
    document.getElementById('uploadPanel').style.display = 'block';
    notePanelOpened();

    pollMagnetFiles();
  } catch (e) {}
});

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

  resetUploadState();
  refresh();
}

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
  resetUploadState();
  refresh();
}

let openFilesIndex = null;

async function toggleFiles(index) {
  const panel = document.getElementById('filesPanel');

  if (openFilesIndex === index) {
    openFilesIndex = null;
    panel.style.display = 'none';
    panel.innerHTML = '';
    notePanelsMaybeClosed();
    return;
  }

  openFilesIndex = index;
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

    const files = data.files || [];
    const rows = files.map(function(f) { return renderFileRow(index, f); }).join('');

    panel.innerHTML = '<div class="name">Files</div>' + rows +
      '<div class="actions"><button class="secondary" onclick="toggleFiles(' + index + ')">Close</button></div>';
  } catch (e) {
    panel.innerHTML = '<div class="name">Files</div><div class="empty">Could not load files.</div>';
  }
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
