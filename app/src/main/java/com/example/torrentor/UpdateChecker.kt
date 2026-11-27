package com.example.torrentor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Looks at the newest GitHub Release and, if it is newer than the installed
// version, offers Download Now (opens the release page) / Later / Skip this
// version. Runs at startup (silently; at most 3 pop-ups a day) and from the menu
// ("Check for updates", always answers).
object UpdateChecker {

    private const val REPO = "a79user-ship-it/TorrentOr"
    private const val PREFS = "update_check"
    // The automatic check looks at GitHub at most once an hour, and the
    // dialog is shown at most MAX_POPUPS_PER_DAY times per calendar day.
    private const val CHECK_GAP_MS = 60L * 60L * 1000L
    private const val MAX_POPUPS_PER_DAY = 3

    private class Result(
        val ok: Boolean,
        val notFound: Boolean,
        val tag: String,
        val url: String,
        val notes: String
    )

    fun check(activity: Activity, manual: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        if (!manual) {
            val last = prefs.getLong("last_check", 0L)
            if (System.currentTimeMillis() - last < CHECK_GAP_MS) return
        }

        val app = activity.applicationContext
        val installed = installedVersion(app)

        Thread {
            val r = fetchLatest()

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                if (!r.ok) {
                    if (manual) {
                        Toast.makeText(
                            activity,
                            if (r.notFound) "No releases are published yet."
                            else "Couldn't check for updates. Check your internet connection.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@runOnUiThread
                }

                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()

                val latest = versionNumbers(r.tag)
                val newer = latest.isNotEmpty() && compare(latest, versionNumbers(installed)) > 0

                if (!newer) {
                    if (manual) {
                        Toast.makeText(
                            activity,
                            "You're up to date with version (v$installed).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@runOnUiThread
                }

                // "Skip this version" only silences the automatic check.
                if (!manual && prefs.getString("skipped_version", "") == r.tag) {
                    return@runOnUiThread
                }

                // Automatic checks: no more than 3 pop-ups per calendar day.
                if (!manual) {
                    val today = java.text.SimpleDateFormat(
                        "yyyy-MM-dd", java.util.Locale.US
                    ).format(java.util.Date())
                    if (prefs.getString("popup_day", "") != today) {
                        prefs.edit()
                            .putString("popup_day", today)
                            .putInt("popup_count", 0)
                            .apply()
                    }
                    val count = prefs.getInt("popup_count", 0)
                    if (count >= MAX_POPUPS_PER_DAY) return@runOnUiThread
                    prefs.edit().putInt("popup_count", count + 1).apply()
                }

                // Automatic check: post the notification together with the
                // pop-up, so it follows the same limit (3 times a day).
                if (!manual) {
                    postUpdateNotification(activity, r.tag)
                }

                showDialog(activity, prefs, r, installed)
            }
        }.start()
    }

    // "TorrentOr vX is available" - tapping it opens the app and shows the
    // update dialog. Needs notifications to be allowed; otherwise it is
    // simply skipped (the dialog still shows).
    private fun postUpdateNotification(context: Context, tag: String) {
        try {
            val nm = androidx.core.app.NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val channel = android.app.NotificationChannel(
                    "updates",
                    "App updates",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                (context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager).createNotificationChannel(channel)
            }

            val open = android.app.PendingIntent.getActivity(
                context,
                7,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("check_update", true)
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val version = tag.removePrefix("v").removePrefix("V")
            val n = androidx.core.app.NotificationCompat.Builder(context, "updates")
                .setSmallIcon(R.drawable.ic_stat_torrentor)
                .setColor(0xFF4FA3E8.toInt())
                .setContentTitle("TorrentOr update available")
                .setContentText("Version v$version is ready to download. Tap to see what's new.")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()

            nm.notify(7001, n)
        } catch (_: Throwable) {
        }
    }

    private fun showDialog(
        activity: Activity,
        prefs: android.content.SharedPreferences,
        r: Result,
        installed: String
    ) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val grey = android.graphics.Color.rgb(150, 150, 150)
        val blue = android.graphics.Color.rgb(47, 111, 224)
        val version = r.tag.removePrefix("v").removePrefix("V")

        val panel = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.rgb(22, 22, 22))
            setPadding(dp(24), dp(26), dp(24), dp(14))
        }

        panel.addView(android.widget.TextView(activity).apply {
            text = "TorrentOr"
            setTextColor(grey)
            textSize = 26f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
        })

        panel.addView(
            android.widget.TextView(activity).apply {
                text = "Dear user, this app has a new version v" + version +
                        " please update it now for the best experience. " +
                        "Thanks for understanding."
                setTextColor(grey)
                textSize = 20f
                gravity = android.view.Gravity.CENTER
                setLineSpacing(0f, 1.15f)
            },
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(26) }
        )

        // "What's new": the release notes as plain text, scrollable and
        // capped in height so the buttons always stay on screen.
        val notesText = plainNotes(r.notes).ifEmpty { "No release notes." }
        run {
            panel.addView(
                android.widget.TextView(activity).apply {
                    text = "WHAT'S NEW"
                    setTextColor(android.graphics.Color.rgb(76, 217, 100)) // green
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = android.view.Gravity.START
                },
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24) }
            )

            val scroll = object : android.widget.ScrollView(activity) {
                override fun onMeasure(w: Int, h: Int) {
                    val cap = android.view.View.MeasureSpec.makeMeasureSpec(
                        dp(160), android.view.View.MeasureSpec.AT_MOST
                    )
                    super.onMeasure(w, cap)
                }
            }.apply {
                isVerticalScrollBarEnabled = true
                addView(
                    android.widget.TextView(activity).apply {
                        text = notesText
                        setTextColor(android.graphics.Color.rgb(255, 193, 7)) // amber
                        textSize = 16f
                        setLineSpacing(0f, 1.1f)
                    }
                )
            }
            panel.addView(
                scroll,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }

        // DOWNLOAD NOW, right-aligned and blue, as the main action.
        panel.addView(
            android.widget.TextView(activity).apply {
                text = "DOWNLOAD NOW"
                setTextColor(blue)
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.END
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener {
                    dialog.dismiss()
                    try {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(r.url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                        Toast.makeText(activity, "Couldn't open the download page.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        )

        // Smaller grey options under it: Skip this version (left), Later (right).
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        row.addView(
            android.widget.TextView(activity).apply {
                text = "SKIP THIS VERSION"
                setTextColor(grey)
                textSize = 14f
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener {
                    prefs.edit().putString("skipped_version", r.tag).apply()
                    dialog.dismiss()
                }
            },
            android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        row.addView(
            android.widget.TextView(activity).apply {
                text = "LATER"
                setTextColor(grey)
                textSize = 14f
                gravity = android.view.Gravity.END
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { dialog.dismiss() }
            },
            android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        panel.addView(row)

        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    // Release notes are Markdown; show them as clean plain lines.
    private fun plainNotes(md: String): String {
        val out = ArrayList<String>()
        for (raw in md.replace("\r", "").lines()) {
            var line = raw.trim()
            if (line.startsWith("```") || line == "---" || line == "***") continue
            line = line.trimStart('#').trim()                       // headings
            line = line.replace(Regex("""^[*+-]\s+"""), "\u2022 ")      // bullets
            line = line.replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1") // links
            line = line.replace("**", "").replace("__", "").replace("`", "")
            out.add(line)
        }
        return out.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun fetchLatest(): Result {
        val fail = Result(false, false, "", "", "")
        return try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TorrentOr")

            val code = conn.responseCode
            if (code == 404) {
                conn.disconnect()
                return Result(false, true, "", "", "")
            }
            if (code != 200) {
                conn.disconnect()
                return fail
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val j = JSONObject(body)
            val tag = j.optString("tag_name", "").ifEmpty { j.optString("name", "") }
            val url = j.optString("html_url", "https://github.com/$REPO/releases/latest")
            Result(tag.isNotEmpty(), false, tag, url, j.optString("body", ""))
        } catch (_: Throwable) {
            fail
        }
    }

    private fun installedVersion(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (_: Throwable) {
            "0"
        }
    }

    // "TorrentOr 3.99", "v4.01" -> [3, 99], [4, 1]
    private fun versionNumbers(text: String): List<Int> {
        val m = Regex("""\d+(?:\.\d+)*""").find(text) ?: return emptyList()
        return m.value.split(".").map { it.toIntOrNull() ?: 0 }
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
