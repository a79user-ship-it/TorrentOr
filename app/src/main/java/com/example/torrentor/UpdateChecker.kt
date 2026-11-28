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
            if (System.currentTimeMillis() - last < CHECK_GAP_MS) {
                // No pop-up due yet, but quietly warm the answer so a later
                // tap on "Check for updates" appears instantly.
                if (cachedResult(prefs) == null) {
                    Thread { saveCache(prefs, fetchLatest()) }.start()
                }
                return
            }
        }

        val app = activity.applicationContext
        val installed = installedVersion(app)

        // Reuse a recent answer (from the startup check or an earlier tap) so
        // the pop-up appears instantly; otherwise show a short "Checking..."
        // message right away while GitHub is being asked.
        val cached = cachedResult(prefs)
        if (manual && cached == null) {
            Toast.makeText(activity, "Checking for updates\u2026", Toast.LENGTH_SHORT).show()
        }

        Thread {
            val r = cached ?: fetchLatest().also { saveCache(prefs, it) }

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
                        showUpToDateDialog(activity, installed)
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

    // ---- Modern pop-up styling shared by both dialogs ----

    private val AMBER = android.graphics.Color.rgb(255, 193, 7)
    private val GREEN = android.graphics.Color.parseColor("#8BC34A") // lime green, same as the speed graph
    private val MUTED = android.graphics.Color.parseColor("#7B8494")

    private fun px(activity: Activity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    private fun card(activity: Activity): android.widget.LinearLayout {
        return android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#11151C"))
                cornerRadius = px(activity, 26).toFloat()
                setStroke(px(activity, 2), GREEN)
            }
            setPadding(px(activity, 22), px(activity, 24), px(activity, 22), px(activity, 16))
        }
    }

    private fun gradientTitle(activity: Activity): android.widget.TextView {
        return android.widget.TextView(activity).apply {
            text = "TorrentOr"
            textSize = 27f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#4FD1C5"))
            post {
                val w = paint.measureText(text.toString())
                paint.shader = android.graphics.LinearGradient(
                    0f, 0f, w, 0f,
                    android.graphics.Color.parseColor("#4FD1C5"),
                    android.graphics.Color.parseColor("#B794F6"),
                    android.graphics.Shader.TileMode.CLAMP
                )
                invalidate()
            }
        }
    }

    // "TorrentOr" gradient title with the app's planet logo on its right.
    private fun titleRow(activity: Activity): android.widget.LinearLayout {
        return android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(gradientTitle(activity).apply { textSize = 30f })
            addView(
                android.widget.ImageView(activity).apply {
                    setImageResource(R.drawable.notif_planet)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                },
                android.widget.LinearLayout.LayoutParams(px(activity, 48), px(activity, 48)).apply {
                    leftMargin = px(activity, 12)
                }
            )
        }
    }

    private fun iconCircle(activity: Activity, content: android.view.View): android.widget.FrameLayout {
        val size = px(activity, 64)
        return android.widget.FrameLayout(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#16222B"))
                setStroke(px(activity, 1), android.graphics.Color.parseColor("#264A52"))
            }
            addView(
                content,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = px(activity, 10)
            }
        }
    }

    private fun pillButton(activity: Activity, label: String, onClick: () -> Unit): android.widget.TextView {
        val radius = px(activity, 26).toFloat()
        val fill = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                android.graphics.Color.parseColor("#A06CF0"),
                android.graphics.Color.parseColor("#7C4DDB")
            )
        ).apply { cornerRadius = radius }
        val mask = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = radius
        }
        return android.widget.TextView(activity).apply {
            text = label
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.05f
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(70, 255, 255, 255)),
                fill, mask
            )
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, px(activity, 50)
            )
        }
    }

    private fun showCard(activity: Activity, dialog: android.app.Dialog, card: android.view.View) {
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
            setDimAmount(0.6f)
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    // Manual "Check for updates" with nothing newer: a small modern card
    // closed with OK.
    private fun showUpToDateDialog(activity: Activity, installed: String) {
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val panel = card(activity)
        panel.addView(iconCircle(activity, android.widget.ImageView(activity).apply {
            setImageResource(R.drawable.ic_autorefresh)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(px(activity, 14), px(activity, 14), px(activity, 14), px(activity, 14))
        }))
        panel.addView(titleRow(activity))
        panel.addView(android.widget.TextView(activity).apply {
            text = "You're up to date with version (v$installed)."
            setTextColor(AMBER)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, px(activity, 10), 0, px(activity, 20))
        })
        panel.addView(pillButton(activity, "OK") { dialog.dismiss() })
        showCard(activity, dialog, panel)
    }

    private fun showDialog(
        activity: Activity,
        prefs: android.content.SharedPreferences,
        r: Result,
        installed: String
    ) {
        val version = r.tag.removePrefix("v").removePrefix("V")
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)

        val panel = card(activity)

        panel.addView(iconCircle(activity, android.widget.ImageView(activity).apply {
            setImageResource(R.drawable.ic_autorefresh)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(px(activity, 14), px(activity, 14), px(activity, 14), px(activity, 14))
        }))
        panel.addView(titleRow(activity))

        panel.addView(android.widget.TextView(activity).apply {
            text = "Dear user, this app has a new version v" + version +
                " please update it now for the best experience. Thanks for understanding."
            setTextColor(AMBER)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, px(activity, 10), 0, px(activity, 14))
        })

        panel.addView(android.widget.TextView(activity).apply {
            text = "WHAT'S NEW"
            setTextColor(GREEN)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setPadding(px(activity, 4), 0, 0, px(activity, 6))
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        val notesText = plainNotes(r.notes).ifEmpty { "No release notes." }
        val scroll = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            addView(android.widget.TextView(activity).apply {
                text = notesText
                setTextColor(AMBER)
                textSize = 14f
                setLineSpacing(0f, 1.15f)
            })
        }
        val notesBox = android.widget.FrameLayout(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#0D1016"))
                cornerRadius = px(activity, 16).toFloat()
                setStroke(px(activity, 1), android.graphics.Color.parseColor("#1D232D"))
            }
            setPadding(px(activity, 14), px(activity, 12), px(activity, 14), px(activity, 12))
            addView(scroll)
        }
        // Cap the notes height so the buttons always stay on screen.
        val cap = px(activity, 160)
        scroll.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (scroll.height > cap) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = cap }
                }
            }
        })
        panel.addView(
            notesBox,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scrollHint = android.widget.TextView(activity).apply {
            text = "SCROLL TO BOTTOM TO SEE FULL RELEASE NOTES"
            setTextColor(AMBER)
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(0, px(activity, 8), 0, 0)
            visibility = android.view.View.GONE
        }
        panel.addView(scrollHint)
        fun updateScrollHint() {
            val child = scroll.getChildAt(0) ?: return
            val canScroll = child.height > scroll.height
            val atBottom = scroll.scrollY + scroll.height >= child.height - px(activity, 4)
            scrollHint.visibility =
                if (canScroll && !atBottom) android.view.View.VISIBLE else android.view.View.GONE
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener { updateScrollHint() }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollHint() }

        panel.addView(
            pillButton(activity, "DOWNLOAD NOW") {
                dialog.dismiss()
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(r.url))
                    )
                } catch (_: Throwable) {
                    Toast.makeText(activity, "Couldn't open the download page.", Toast.LENGTH_SHORT).show()
                }
            }.also {
                (it.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = px(activity, 16)
            }
        )

        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, px(activity, 6), 0, 0)
        }
        fun smallLink(label: String, onClick: () -> Unit) = android.widget.TextView(activity).apply {
            text = label
            setTextColor(MUTED)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            gravity = android.view.Gravity.CENTER
            setPadding(0, px(activity, 10), 0, px(activity, 10))
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(smallLink("SKIP THIS VERSION") {
            prefs.edit().putString("skipped_version", r.tag).apply()
            dialog.dismiss()
        })
        row.addView(smallLink("LATER") { dialog.dismiss() })
        panel.addView(row)

        showCard(activity, dialog, panel)
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

    private const val CACHE_MS = 30L * 60L * 1000L

    private fun cachedResult(prefs: android.content.SharedPreferences): Result? {
        val t = prefs.getLong("cache_time", 0L)
        val tag = prefs.getString("cache_tag", "") ?: ""
        if (tag.isEmpty() || System.currentTimeMillis() - t !in 0 until CACHE_MS) return null
        return Result(
            true, false, tag,
            prefs.getString("cache_url", "") ?: "",
            prefs.getString("cache_notes", "") ?: ""
        )
    }

    private fun saveCache(prefs: android.content.SharedPreferences, r: Result) {
        if (!r.ok) return
        prefs.edit()
            .putLong("cache_time", System.currentTimeMillis())
            .putString("cache_tag", r.tag)
            .putString("cache_url", r.url)
            .putString("cache_notes", r.notes)
            .apply()
    }

    private fun fetchLatest(): Result {
        val fail = Result(false, false, "", "", "")
        return try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
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
