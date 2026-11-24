package com.example.torrentor

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

// Tiny loopback-only HTTP server so an external video player on the phone can
// stream a torrent file that is still downloading. Bound to 127.0.0.1 and
// protected by a random per-launch token, so nothing off-device can reach it.
object StreamServer {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val token = UUID.randomUUID().toString().replace("-", "")

    @Synchronized
    fun urlFor(torrentIndex: Int, fileIndex: Int, fileName: String): String? {
        if (server == null || server?.isClosed == true) {
            try {
                server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
                val s = server!!
                Thread {
                    while (!s.isClosed) {
                        try {
                            val sock = s.accept()
                            pool.execute { handle(sock) }
                        } catch (e: Exception) {
                            break
                        }
                    }
                }.apply { isDaemon = true; name = "StreamServer" }.start()
            } catch (e: Exception) {
                return null
            }
        }
        val port = server?.localPort ?: return null
        val safe = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/s/$token/$torrentIndex/$fileIndex/$safe"
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val input = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("range:")) range = line.substring(6).trim()
            }

            val parts = requestLine.split(" ")
            val segs = (parts.getOrNull(1) ?: "").split("/")
            // ["", "s", token, torrentIdx, fileIdx, name]
            val output = sock.getOutputStream()
            if (parts.getOrNull(0) != "GET" || segs.size < 5 || segs[1] != "s" || segs[2] != token) {
                output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            val t = segs[3].toIntOrNull()
            val f = segs[4].toIntOrNull()
            if (t == null || f == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }
            sock.soTimeout = 0
            StreamSupport.serve(t, f, range, output)
        } catch (e: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
