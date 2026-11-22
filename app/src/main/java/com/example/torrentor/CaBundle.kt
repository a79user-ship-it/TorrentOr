package com.example.torrentor

import android.content.Context
import android.system.Os
import java.io.File

/**
 * HTTPS trackers need OpenSSL to know which certificate authorities to trust.
 * OpenSSL has no certificate store on Android, so this builds ONE bundle file
 * from Android's own trusted certificates and tells OpenSSL where it is
 * (SSL_CERT_FILE). It must run before the libtorrent session is created.
 */
object CaBundle {
    private val CERT_DIRS = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts"
    )

    fun prepare(context: Context) {
        try {
            val out = File(context.filesDir, "cacert.pem")
            val srcDir = CERT_DIRS.map { File(it) }
                .firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }

            if (srcDir != null) {
                val stale = !out.exists() ||
                        out.length() < 1024L ||
                        srcDir.lastModified() > out.lastModified()
                if (stale) {
                    build(srcDir, out)
                }
            }

            if (out.exists() && out.length() > 0L) {
                Os.setenv("SSL_CERT_FILE", out.absolutePath, true)
            }
            // Also keep the folder lookup as a second source.
            Os.setenv("SSL_CERT_DIR", CERT_DIRS.joinToString(":"), true)
        } catch (e: Throwable) {
            AppLog.error(
                "Could not prepare the HTTPS certificate bundle: " +
                        e.javaClass.simpleName + " " + (e.message ?: "")
            )
        }
    }

    private fun build(srcDir: File, out: File) {
        val begin = "-----BEGIN CERTIFICATE-----"
        val end = "-----END CERTIFICATE-----"
        val tmp = File(out.parentFile, "cacert.pem.tmp")
        var count = 0

        tmp.bufferedWriter().use { w ->
            val files = srcDir.listFiles()?.sortedBy { it.name } ?: emptyList()
            for (f in files) {
                if (!f.isFile) continue
                val text = try {
                    f.readText()
                } catch (_: Throwable) {
                    continue
                }
                val s = text.indexOf(begin)
                val e = text.indexOf(end)
                if (s >= 0 && e > s) {
                    w.write(text.substring(s, e + end.length))
                    w.write("\n")
                    count++
                }
            }
        }

        if (count > 0) {
            if (out.exists()) out.delete()
            tmp.renameTo(out)
            AppLog.info("HTTPS certificate bundle built ($count certificates)")
        } else {
            tmp.delete()
        }
    }
}
