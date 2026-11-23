package com.example.torrentor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class MainActivity : AppCompatActivity() {

    private lateinit var listLayout: LinearLayout
    private lateinit var speedGraphText: TextView
    // Modern replacement for the old block-character sparkline that used to
    // live inside speedGraphText - speedGraphText now just holds the small
    // colored "↓/↑ KB/s" readout above this drawn chart.
    private lateinit var speedGraphView: SpeedGraphView
    private val downloadSpeedHistory = mutableListOf<Int>()
    private val uploadSpeedHistory = mutableListOf<Int>()
    private val maxSpeedHistoryPoints = 30
    private val handler = Handler(Looper.getMainLooper())
    private val interval = 3000L

    // The torrent cards refresh every second. Most detail tabs stay at 3 s.
    private val listInterval = 1000L
    private var uiTick = 0

    // The list is rebuilt on every refresh. That must not happen while a
    // finger is on the screen, or a tap or a scroll gets lost.
    private var fingerDown = false
    private var fingerDownSince = 0L

    // No refreshing while the app is not on screen.
    private var uiVisible = true
    // Feature 5: Custom Save Folder - this is now only the built-in
    // fallback default, used the first time the app ever runs (before any
    // prefs file exists) and whenever a prefs read genuinely comes back
    // empty. See getGlobalSaveFolder() / getSavedSaveFolderForHash() for
    // the real, user-editable values everywhere else in this file uses.
    private val savePath = "/storage/emulated/0/Download"

    private var selectMode = false
    private val selectedTorrents = mutableSetOf<Int>()
    private var skipMagnetSelection = false
    // Bug fix: this and sortMode below used to only live in memory, so the
    // chosen filter/sort reset to "All"/"None" every time the app was
    // closed and reopened (a fresh Activity re-initializes these fields to
    // their declared defaults). Both are now persisted - see
    // torrentListViewPrefs() - and reloaded once in onCreate().
    private var activeFilter = "All"
    private var searchQuery = ""

    // Bug fix: the phone's own Back button (hardware key or gesture) used
    // to just close the app on every screen, because nothing in this
    // Activity ever handled it - only the in-app "Back" buttons called
    // showMainScreen(). This flag tracks whether the torrent list (the
    // screen showMainScreen() builds) is what's currently on screen. It's
    // flipped to false by the setContentView() override below every time
    // any other screen is shown, then flipped back to true by
    // showMainScreen() itself right after its own setContentView() call.
    private var isOnMainScreen = false

    // Which field the visible torrent list is ordered by. This only
    // changes display order; it never changes the native list index used
    // for Pause/Resume/Remove actions, since those are resolved from the
    // original (unsorted) position captured before sorting.
    private var sortMode = "None"

    private val TORRENT_SORT_MODES = arrayOf(
        "None",
        TorrentQueue.SORT_MODE,
        "Name (A-Z)",
        "Name (Z-A)",
        "Size (Largest First)",
        "Size (Smallest First)",
        "Date Added (Newest First)",
        "Date Added (Oldest First)",
        "Progress (Highest First)",
        "Progress (Lowest First)",
        "Status (A-Z)",
        "Status (Z-A)"
    )

    // Online Search remembers what you typed, the results found, and the
    // sort/filter choice, so leaving the screen (e.g. to Provider Settings)
    // and coming back doesn't lose them. Cleared only when you search again.
    private var onlineSearchQueryText = ""
    private var onlineSearchResults: List<TorrentSearchResult> = emptyList()
    private var onlineSearchHandle: OnlineSearchManager.SearchHandle? = null
    private var onlineSearchStatus = "Enter a search and tap Search."

    private var currentDetailsTab = ""
    private var currentDetailsTorrentIndex = -1
    private var currentDetailsContentText: TextView? = null
    private var currentDetailsFileListLayout: LinearLayout? = null

    // Files tab state. The tick boxes the user has changed but not applied yet
    // live here so the 3-second refresh can never wipe them.
    private var filesBuiltForIndex = -1
    private var filesPendingSelection: MutableSet<Int>? = null
    private val filesProgressViews = mutableMapOf<Int, Pair<TextView, ProgressBar>>()

    // Feature: per-file download speed + ETA. libtorrent only reports one
    // combined download rate for the whole torrent (used for the
    // torrent-level ETA on the General tab), never a separate rate per
    // file, so each file's own rate is estimated here instead - from how
    // many bytes it gained between one refresh tick and the next
    // (updateActiveDetailsTab() ticks every 3 seconds - see its comment).
    // Keyed by file index; cleared alongside filesProgressViews so stale
    // numbers from a previously viewed torrent are never mixed in.
    private val filesPrevProgressSnapshot = mutableMapOf<Int, Pair<Long, Long>>()

    private var networkFeaturesRefreshRunnable: Runnable? = null
    private var isNetworkFeaturesScreenActive = false

    private var connectionRefreshRunnable: Runnable? = null
    private var isConnectionScreenActive = false

    private var storageRefreshRunnable: Runnable? = null
    private var isStorageScreenActive = false

    private var globalStatsRefreshRunnable: Runnable? = null
    private var isGlobalStatsScreenActive = false

    // RSS screen and Execution Log screen refresh loops.
    private var rssUiRefreshRunnable: Runnable? = null
    private var isRssScreenActive = false

    private var logUiRefreshRunnable: Runnable? = null
    private var isLogScreenActive = false

    private fun bgColor(): Int {
        val theme = getSharedPreferences("prefs", MODE_PRIVATE)
            .getString("theme_color", "blue")

        return when (theme) {
            "red" -> Color.parseColor("#3A0D0D")
            "orange" -> Color.parseColor("#3B1F00")
            "black" -> Color.parseColor("#1A1A1A")
            "purple" -> Color.parseColor("#2E0854")
            "green" -> Color.parseColor("#0B3D0B")
            "amoled" -> Color.parseColor("#000000")
            "rose" -> Color.parseColor("#3D0A1E")
            "ash" -> Color.parseColor("#2A2A28")
            "darkblue" -> Color.parseColor("#081028")
            "deepblue" -> Color.parseColor("#0A2558")
            else -> Color.parseColor("#0D47A1")
        }
    }

    private fun cardColor(): Int {
        val theme = getSharedPreferences("prefs", MODE_PRIVATE)
            .getString("theme_color", "blue")

        return when (theme) {
            "red" -> Color.parseColor("#7B1E1E")
            "orange" -> Color.parseColor("#FF8C00")
            "black" -> Color.parseColor("#333333")
            "purple" -> Color.parseColor("#7B1FA2")
            "green" -> Color.parseColor("#2E7D32")
            "amoled" -> Color.parseColor("#121212")
            "rose" -> Color.parseColor("#C2185B")
            "ash" -> Color.parseColor("#6E6E66")
            "darkblue" -> Color.parseColor("#16305C")
            "deepblue" -> Color.parseColor("#1553A6")
            else -> Color.parseColor("#42A5F5")
        }
    }

    // Feature: modern look, Flud-style rounded elevated cards - a plain
    // flat setBackgroundColor(cardColor()) rectangle replaced with this
    // rounded drawable everywhere the app lists cards (torrents, search
    // results, RSS feed items), while still honoring the user's chosen
    // card color theme from cardColor() above.
    private fun roundedCardBackground(): android.graphics.drawable.GradientDrawable {
        // Every card (torrents, search results, RSS feeds...) gets a thin
        // outline in a lighter shade of the theme's card color.
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(cardColor())
            cornerRadius = 22f
            setStroke(
                (1f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                outlineColor()
            )
        }
    }

    private fun torrentCardBackground(): android.graphics.drawable.GradientDrawable {
        return roundedCardBackground()
    }

    // Pressed flash for the icon-style TextViews on the blue top bars (back
    // arrows, the selection bar items): a light overlay shows while pressed.
    private fun applyBarPressFlash(view: android.view.View) {
        val pressed = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(Color.argb(110, 255, 255, 255))
        }
        val normal = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        view.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    // Every theme: buttons are filled with the theme's card color and have
    // a thin lighter outline (the Deep Blue look), instead of the default
    // faint teal tint. Used for the main screen's buttons, the buttons inside
    // each torrent card, the details tabs and the buttons on every screen.
    private fun isDeepBlueTheme(): Boolean {
        return getSharedPreferences("prefs", MODE_PRIVATE)
            .getString("theme_color", "blue") == "deepblue"
    }

    // Outline and pressed colors for the current theme. Deep Blue keeps its
    // hand-picked blues; the other themes use a lighter shade of their own
    // card color, so every theme gets the same outlined look.
    private fun outlineColor(): Int {
        return if (isDeepBlueTheme()) {
            Color.parseColor("#3F7FD8")
        } else {
            androidx.core.graphics.ColorUtils.blendARGB(cardColor(), Color.WHITE, 0.40f)
        }
    }

    private fun pressedFillColor(): Int {
        return if (isDeepBlueTheme()) {
            Color.parseColor("#2E74D6")
        } else {
            androidx.core.graphics.ColorUtils.blendARGB(cardColor(), Color.WHITE, 0.25f)
        }
    }

    private fun pressedOutlineColor(): Int {
        return if (isDeepBlueTheme()) {
            Color.parseColor("#7FB2F0")
        } else {
            androidx.core.graphics.ColorUtils.blendARGB(cardColor(), Color.WHITE, 0.65f)
        }
    }

    private fun deepBlueButtons(onCard: Boolean, vararg buttons: Button) {
        val density = resources.displayMetrics.density
        val strokeWidth = (1f * density).toInt().coerceAtLeast(1)
        val outline = outlineColor()

        // The teal button text is unreadable on bright cards (orange, light
        // blue, rose...), so those themes get white text instead.
        val teal = Color.parseColor("#4FD1C5")
        val whiteText = androidx.core.graphics.ColorUtils.calculateContrast(teal, cardColor()) < 3.0

        for (button in buttons) {
            val normal = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor())
                cornerRadius = 8f * density
                setStroke(strokeWidth, outline)
            }

            // Lighter blue while the finger is down, so every press is
            // visibly acknowledged (Pause, Resume, Force Recheck...).
            val pressed = android.graphics.drawable.GradientDrawable().apply {
                setColor(pressedFillColor())
                cornerRadius = 8f * density
                setStroke(strokeWidth, pressedOutlineColor())
            }

            val states = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressed)
                addState(intArrayOf(), normal)
            }

            if (whiteText) {
                button.setTextColor(Color.WHITE)
            }

            button.background = DeepBlueRipple(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#664FD1C5")),
                states
            )
        }
    }

    // Every theme: every plain button on every screen (Settings, Web UI,
    // RSS, Notifications, Online Search, Execution Log...) gets the same
    // look as the main-screen buttons. Buttons are built in code and
    // rebuilt often, so this runs after each layout pass and only touches
    // buttons that still have the default look. Check boxes, radio buttons
    // and anything with its own custom color are left alone.
    private fun restyleDeepBlueTree(view: android.view.View) {
        if (view is Button && view !is CompoundButton) {
            val bg = view.background
            if (bg !is DeepBlueRipple && bg is android.graphics.drawable.RippleDrawable) {
                deepBlueButtons(true, view)
            }
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                restyleDeepBlueTree(view.getChildAt(i))
            }
        }
    }

    private fun installDeepBlueButtonWatcher() {
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            restyleDeepBlueTree(window.decorView)
            addMissingButtonIcons(window.decorView)
        }
    }

    // Colored icon for buttons that were built without one (Refresh, Apply,
    // Save, Enable/Disable DHT, Copy Address...), picked from the button's
    // text. Buttons that already have an icon, and ones whose text changes
    // meaning (Add/Added, Turn On/Off), are left alone.
    private fun iconNameForButtonText(text: String): String? {
        return when {
            text == "Refresh" -> "refresh"
            text.startsWith("Apply") -> "check"
            text == "Save" || text == "Create Backup" -> "save"
            text == "Restore from Backup" -> "recheck"
            text == "Cancel" || text == "Clear Search" -> "selectnone"
            text == "Add all" || text == "Add Custom Provider" -> "plus"
            text.startsWith("Download All") || text == "Download Selected" -> "download"
            text.startsWith("Auto-download") -> "download"
            text == "Enable DHT" || text == "Enable PEX" || text == "Enable LSD" -> "check"
            text == "Disable DHT" || text == "Disable PEX" || text == "Disable LSD" -> "selectnone"
            text == "Copy Address" || text == "Copy Tailscale Address" -> "copy"
            text == "Change Default Folder" -> "edit"
            text == "Create Folder Here" -> "folder"
            text == "Open" -> "files"
            text == "Open Site" -> "connection"
            text == "History" -> "clock"
            text == "Remove Feed" -> "trash"
            text == "Test" -> "speed"
            text == ".. (Up)" -> "back"
            else -> null
        }
    }

    private fun addMissingButtonIcons(view: android.view.View) {
        if (view is Button && view !is CompoundButton) {
            if (view.compoundDrawablesRelative[0] == null && view.compoundDrawables[0] == null) {
                val name = iconNameForButtonText(view.text?.toString() ?: "")

                if (name != null) {
                    TabIcons.apply(view, name, 18, 6)
                }
            }
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                addMissingButtonIcons(view.getChildAt(i))
            }
        }
    }

    private fun applyDeepBlueButtons(root: LinearLayout) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)

            if (child is Button) {
                deepBlueButtons(false, child)
            } else if (child is HorizontalScrollView) {
                val row = child.getChildAt(0) as? LinearLayout ?: continue

                for (j in 0 until row.childCount) {
                    (row.getChildAt(j) as? Button)?.let { deepBlueButtons(false, it) }
                }
            }
        }
    }

    private fun currentThemeName(): String {
        val theme = getSharedPreferences("prefs", MODE_PRIVATE)
            .getString("theme_color", "blue")

        return when (theme) {
            "red" -> "Red"
            "orange" -> "Orange"
            "black" -> "Black"
            "purple" -> "Purple"
            "green" -> "Green"
            "amoled" -> "AMOLED"
            "rose" -> "Rose"
            "ash" -> "Ash"
            "darkblue" -> "Dark Blue"
            "deepblue" -> "Deep Blue"
            else -> "Light Blue"
        }
    }

    private fun switchTheme() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val current = prefs.getString("theme_color", "blue")

        val next = when (current) {
            "blue" -> "red"
            "red" -> "orange"
            "orange" -> "black"
            "black" -> "purple"
            "purple" -> "green"
            "green" -> "rose"
            "rose" -> "amoled"
            "amoled" -> "ash"
            "ash" -> "darkblue"
            "darkblue" -> "deepblue"
            else -> "blue"
        }

        prefs.edit()
            .putString("theme_color", next)
            .apply()

        showMainScreen()
    }

    // Backup & Restore (v2.08)
    private var backupStatusView: TextView? = null

    // Where the main list was scrolled to, so coming back from a torrent's
    // details (or after Sort / Filter rebuilds the screen) lands in the same
    // place instead of at the top.
    private var mainScrollView: ScrollView? = null
    private var savedMainScrollY = 0

    // After choosing a Filter or Sort the list is rebuilt; instead of
    // restoring the old scroll position (which can leave the results off
    // the bottom of the screen) the page scrolls so the "Filter: ..." row
    // is at the top, with the matching torrents right beneath it.
    private var scrollToFilterNext = false
    private var filterTitleView: TextView? = null

    private fun rememberMainScroll() {
        if (isOnMainScreen) {
            mainScrollView?.let { savedMainScrollY = it.scrollY }
        }
    }

    // Sticky action bar shown at the top of the main screen while
    // torrents are being selected (long-press a torrent to start).
    private var selectionBar: LinearLayout? = null
    private var mainTitleBar: LinearLayout? = null
    private var selectionCountView: TextView? = null

    private fun updateSelectionBar() {
        selectionBar?.visibility = if (selectMode) android.view.View.VISIBLE else android.view.View.GONE
        // The title bar and the selection bar share the same spot: the
        // selection bar replaces the title while selecting.
        mainTitleBar?.visibility = if (selectMode) android.view.View.GONE else android.view.View.VISIBLE
        selectionCountView?.text = "${selectedTorrents.size}"
    }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) runBackupTo(uri)
        }

    private val openBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) previewAndRestore(uri)
        }

    private val pickTorrent =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                Toast.makeText(this, "No file selected", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            try {
                val filePath = copyTorrentToFiles(uri.toString())
                showTorrentFileSelection(filePath)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Could not read torrent file", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installDeepBlueButtonWatcher()

        AppLog.init(applicationContext)
        AppLog.info("TorrentOr opened")

        // HTTPS tracker certificates (must be ready before the session starts).
        CaBundle.prepare(applicationContext)

        // Bug fix: restore the torrent list's Filter and Sort choices from
        // last time, instead of leaving them at the "All"/"None" defaults
        // the fields above are declared with (see torrentListViewPrefs()).
        val listViewPrefs = torrentListViewPrefs()
        activeFilter = listViewPrefs.getString("filter", "All") ?: "All"
        sortMode = listViewPrefs.getString("sort", "None") ?: "None"

        // Start TorrentService every time the app opens.
        // This reloads saved torrents if Android killed the native session/service.
        val serviceIntent = Intent(this, TorrentService::class.java)
        startTorrentService(serviceIntent)

        // Bug fix: route the phone's own Back button (hardware key or
        // gesture) the same way every in-app "Back" button already
        // goes - to the main torrent list - instead of letting it fall
        // through to the default behavior of closing the app. On the
        // main screen itself, Back keeps its normal behavior (exit).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isOnMainScreen && selectMode) {
                    // Back closes selection first, like the X in the bar.
                    selectMode = false
                    selectedTorrents.clear()
                    showMainScreen()
                } else if (isOnMainScreen) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    showMainScreen()
                }
            }
        })

        showMainScreen()
        startUiUpdates()

        // Bug fix: if Android kills the app process in the background
        // (low memory) while the task that originally opened a magnet/
        // torrent-file link is still in Recents, reopening the app later
        // makes Android recreate this Activity using that SAME original
        // ACTION_VIEW intent - the system never learns about the
        // setIntent() call below that cleared it the first time, because
        // that only updates this in-memory Activity instance, not the
        // system's own record of the task's launching intent. The result
        // was the file-selection/metadata screen reappearing for a
        // torrent that was already added, as if the link had just been
        // tapped again - worse on a device where the app gets killed in
        // the background more aggressively. savedInstanceState is
        // Android's own, reliable signal for exactly this situation: it
        // is null only on a genuinely fresh launch (a real tap on the
        // link, or a first-ever open), and non-null whenever this is a
        // recreation after the process died - so only act on the intent
        // in the first case.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        ensureStorageAccess()
        ensureNotificationAccess()
    }

    // Bug fix (see isOnMainScreen above): every screen in this app shows
    // itself via setContentView(), so this is the one place that can tell
    // which screen just became visible without touching all ~17 call
    // sites individually. Defaults to "not main"; showMainScreen() flips
    // it back to true right after calling this.
    override fun setContentView(view: android.view.View?) {
        // Every screen except the main list and Torrent details (which
        // carry their own bar, tagged "has_bar") gets a back arrow pinned
        // at the top-left. It presses that screen's own "Back" button (so
        // each screen still goes where its Back always went) and that
        // button is hidden, so nobody has to scroll to the bottom to leave.
        rememberMainScroll()

        if (view == null || view.tag == "has_bar") {
            super.setContentView(view)
            isOnMainScreen = false
            return
        }

        val backButton = findBackButton(view)
        // A real Back button is hidden (the arrow replaces it); the Cancel
        // buttons of the file-selection screens stay, the arrow just does
        // the same thing.
        if (backButton != null && backButton.tag != "back_button") {
            backButton.visibility = android.view.View.GONE
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(13, 71, 161))
            setPadding(8, 8, 8, 8)
            elevation = 12f

            addView(TextView(this@MainActivity).apply {
                text = "\u2190"
                textSize = 26f
                setTextColor(Color.WHITE)
                setPadding(28, 12, 28, 12)
                applyBarPressFlash(this)
                setOnClickListener {
                    if (backButton != null) {
                        backButton.performClick()
                    } else {
                        showMainScreen()
                    }
                }
            })

            addView(TextView(this@MainActivity).apply {
                text = "Back"
                textSize = 18f
                setTextColor(Color.WHITE)
            })
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            addView(topBar)
            addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        super.setContentView(wrapper)
        isOnMainScreen = false
    }

    // The screen's own bottom "Back" button, if it has one.
    private fun findBackButton(view: android.view.View): Button? {
        if (view is Button) {
            val t = view.text?.toString() ?: ""
            if (t == "Back" || t.startsWith("Back to") || view.tag == "back_button") return view
        }

        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findBackButton(view.getChildAt(i))
                if (found != null) return found
            }
        }

        return null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        uiVisible = true
        updateTorrentList()
    }

    // The main screen's "Paste magnet link or hash" box, while that screen
    // is showing. Used to fill it from the clipboard (see below).
    private var mainMagnetInput: EditText? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        // Android only lets an app read the clipboard while it has focus,
        // so this is the moment to look - right after coming back from the
        // browser where the magnet link was copied.
        if (hasFocus) {
            fillMagnetFromClipboard()
        }
    }

    // The "Paste from Clipboard" button: always reads the clipboard right now
    // and puts a magnet link (or bare 40-character info-hash) into the box,
    // replacing whatever was there. Unlike the automatic paste it has no
    // "only once" or "box must be empty" rules.
    private fun pasteMagnetFromClipboardNow(input: EditText) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip

            if (clip == null || clip.itemCount == 0) {
                Toast.makeText(this, "The clipboard is empty", Toast.LENGTH_SHORT).show()
                return
            }

            val raw = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()

            val magnet = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value
            val value = when {
                magnet != null -> magnet
                Regex("[0-9a-fA-F]{40}").matches(raw) -> raw
                else -> null
            }

            if (value == null) {
                Toast.makeText(
                    this,
                    "No magnet link or hash found on the clipboard",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }

            // Remember this copy as already used, so the automatic paste does
            // not offer the same link again after it has been added.
            val copiedAt = if (android.os.Build.VERSION.SDK_INT >= 26) {
                clip.description.timestamp
            } else {
                0L
            }
            getSharedPreferences("clipboard_magnet", MODE_PRIVATE)
                .edit()
                .putString("last_offered", "$copiedAt|$value")
                .apply()

            input.setText(value)
            input.setSelection(input.text.length)

            Toast.makeText(this, "Magnet link pasted", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(this, "Could not read the clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    // Like qBittorrent / tTorrent: if the clipboard holds a magnet link (or
    // a bare 40-character info-hash) and the main magnet box is empty, put
    // it in the box. Each copy is only offered once, so clearing the box
    // does not make it come straight back - but copying the same link
    // again does fill it again.
    private fun fillMagnetFromClipboard() {
        val input = mainMagnetInput ?: return

        if (!input.isAttachedToWindow || input.text.isNotEmpty()) return

        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip ?: return

            if (clip.itemCount == 0) return

            val raw = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()

            if (raw.isEmpty() || raw.length > 20000) return

            val magnet = Regex("magnet:\\?[^\\s]+", RegexOption.IGNORE_CASE).find(raw)?.value
            val value = when {
                magnet != null -> magnet
                Regex("[0-9a-fA-F]{40}").matches(raw) -> raw
                else -> return
            }

            // Text + the time it was copied. Copying the same link again
            // gives it a new time, so it fills again; leaving the app and
            // coming back WITHOUT copying anything new does not.
            val copiedAt = if (android.os.Build.VERSION.SDK_INT >= 26) {
                clip.description.timestamp
            } else {
                0L
            }
            val key = "$copiedAt|$value"

            val prefs = getSharedPreferences("clipboard_magnet", MODE_PRIVATE)

            if (prefs.getString("last_offered", "") == key) return

            prefs.edit().putString("last_offered", key).apply()

            input.setText(value)
            input.setSelection(input.text.length)

            Toast.makeText(this, "Magnet link pasted from clipboard", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // Clipboard not readable right now - nothing to do.
        }
    }

    override fun onStop() {
        uiVisible = false
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                fingerDown = true
                fingerDownSince = System.currentTimeMillis()
            }

            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        stopRssUiRefresh()
        stopLogUiRefresh()
        super.onDestroy()
    }

    private var askedStorageAccess = false

    // The native engine writes downloads straight into the Download folder.
    // Without storage access every torrent stops with a "permission denied"
    // error and shows as Paused. Android 11+ needs "All files access",
    // older versions need the normal storage permission.
    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) return

            if (askedStorageAccess) return

            askedStorageAccess = true
            AppLog.warning("Storage access (all files) is not granted, downloads may fail")

            AlertDialog.Builder(this)
                .setTitle("Allow storage access")
                .setMessage(
                    "TorrentOr saves downloads to your Download folder. " +
                            "On the next screen, turn on \"Allow access to manage all files\" " +
                            "for TorrentOr, then come back to the app."
                )
                .setCancelable(false)
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Throwable) {
                        try {
                            startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        } catch (e2: Throwable) {
                        }
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        } else {
            val readGranted =
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            val writeGranted =
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!readGranted || !writeGranted) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    1001
                )
            }
        }
    }

    // Android 13+ (API 33) requires this runtime permission before any
    // notification can be shown at all - without it, the "download
    // complete" alert from TorrentService is silently skipped by the
    // system. Older versions don't need it (granted automatically).
    private fun ensureNotificationAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1002
            )
        }
    }

    // FIX: Consume the intent immediately with setIntent() before processing it.
    // This prevents the dialog from appearing again if the activity is recreated
    // (e.g. after cancelling), because onCreate will then find a plain blank intent
    // with no ACTION_VIEW data.
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // Tap on a torrent notification: open that torrent's details.
        val openHash = intent.getStringExtra("OPEN_TORRENT_HASH")

        if (!openHash.isNullOrBlank()) {
            intent.removeExtra("OPEN_TORRENT_HASH")
            setIntent(Intent(this, MainActivity::class.java))
            openTorrentDetailsByHash(openHash)
            return
        }

        if (intent.action != Intent.ACTION_VIEW) return

        val data = intent.data ?: return
        val uriText = data.toString()

        // Consume the intent before doing anything else
        setIntent(Intent(this, MainActivity::class.java))

        if (uriText.startsWith("magnet:")) {
            val hash = extractHashFromMagnetInput(uriText)

            if (isTorrentAlreadyAddedByHash(hash)) {
                showAlreadyAddedMessage(hash, uriText)
                return
            }

            showMagnetMetadataScreen(uriText)
            return
        }

        try {
            val filePath = copyTorrentToFiles(uriText)

            // Bug fix: unlike the magnet branch above, this path never
            // checked whether the .torrent file's hash was already added
            // before showing the file-selection screen. That's the real
            // reason "already added" torrents kept re-showing file
            // selection on reopen - the savedInstanceState guard in
            // onCreate() only stops a stale intent from being processed
            // again after the process was simply killed in the background,
            // but it can't tell that case apart from a genuinely fresh tap
            // on a .torrent file when Android relaunches the app after an
            // APK update (no saved instance state either way) - so it's
            // the already-added check itself that was missing here, not
            // the guard around it.
            val hash = try {
                TorrentNative.getTorrentFileHash(filePath)
            } catch (_: Throwable) {
                ""
            }

            if (isTorrentAlreadyAddedByHash(hash)) {
                showAlreadyAddedMessage(hash, filePath)
                return
            }

            showTorrentFileSelection(filePath)

            Toast.makeText(this, "Torrent file opened in TorrentOr", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not open torrent file", Toast.LENGTH_SHORT).show()
        }
    }


    private fun sendTorrentAction(
        action: String,
        torrentIndex: Int,
        deleteFiles: Boolean = false
    ) {
        val intent = Intent(this, TorrentService::class.java)
        intent.putExtra("ACTION", action)
        intent.putExtra("TORRENT_INDEX", torrentIndex)
        intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
        intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))

        if (action == "REMOVE_TORRENT") {
            intent.putExtra("DELETE_FILES", deleteFiles)
        }

        startTorrentService(intent)
    }

    private fun showMainScreen() {
        rememberMainScroll()

        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // No top padding: the pinned title bar above now covers that
            // strip, so there is no dark gap between the bar and the list.
            setPadding(32, 0, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(Color.WHITE)
        }

        val themeButton = Button(this).apply {
            text = "Switch Theme: ${currentThemeName()}"
            setOnClickListener { switchTheme() }
        }

        val selectModeButton = Button(this).apply {
            text = if (selectMode) "Exit Select Mode" else "Select Torrents"
            setOnClickListener {
                selectMode = !selectMode
                selectedTorrents.clear()
                showMainScreen()
            }
        }

        // Feature: Select All / Select None, so bulk actions (Force Recheck
        // Selected, Force Reannounce Selected, and the others below) don't
        // require tapping every torrent's checkbox one at a time. "Select
        // All" only selects torrents currently visible under the active
        // filter/search, matching what updateTorrentList() itself shows.
        val selectAllButton = Button(this).apply {
            text = "Select All"
            isEnabled = selectMode

            setOnClickListener {
                if (!selectMode) return@setOnClickListener

                selectedTorrents.clear()
                selectedTorrents.addAll(getVisibleTorrentIndexes())
                updateTorrentList()
            }
        }

        val selectNoneButton = Button(this).apply {
            text = "Select None"
            isEnabled = selectMode

            setOnClickListener {
                if (!selectMode) return@setOnClickListener

                selectedTorrents.clear()
                updateTorrentList()
            }
        }

        val forceRecheckSelected = Button(this).apply {
            text = "Force Recheck Selected"
            isEnabled = selectMode

            setOnClickListener {
                if (selectedTorrents.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No torrents selected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                for (index in selectedTorrents.sorted()) {
                    TorrentNative.forceRecheck(index)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Force recheck started for ${selectedTorrents.size} torrent(s)",
                    Toast.LENGTH_SHORT
                ).show()

                selectedTorrents.clear()
                selectMode = false
                showMainScreen()
            }

        }

        val forceReannounceSelected = Button(this).apply {
            text = "Force Reannounce Selected"
            isEnabled = selectMode

            setOnClickListener {
                if (selectedTorrents.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No torrents selected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                for (index in selectedTorrents.sorted()) {
                    TorrentNative.forceReannounce(index)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Reannounce sent for ${selectedTorrents.size} torrent(s)",
                    Toast.LENGTH_SHORT
                ).show()

                selectedTorrents.clear()
                selectMode = false
                showMainScreen()
            }
        }

        val pauseSelected = Button(this).apply {
            text = "Pause Selected"
            isEnabled = selectMode

            setOnClickListener {
                if (selectedTorrents.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No torrents selected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                for (index in selectedTorrents.sorted()) {
                    sendTorrentAction("PAUSE_TORRENT", index)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Paused ${selectedTorrents.size} torrent(s)",
                    Toast.LENGTH_SHORT
                ).show()

                selectedTorrents.clear()
                selectMode = false
                showMainScreen()
            }
        }

        val resumeSelected = Button(this).apply {
            text = "Resume Selected"
            isEnabled = selectMode

            setOnClickListener {
                if (selectedTorrents.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No torrents selected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                for (index in selectedTorrents.sorted()) {
                    sendTorrentAction("RESUME_TORRENT", index)
                }

                Toast.makeText(
                    this@MainActivity,
                    "Resumed ${selectedTorrents.size} torrent(s)",
                    Toast.LENGTH_SHORT
                ).show()

                selectedTorrents.clear()
                selectMode = false
                showMainScreen()
            }
        }

        val removeSelected = Button(this).apply {
            text = "Remove Selected"
            isEnabled = selectMode

            setOnClickListener {
                if (selectedTorrents.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No torrents selected", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val count = selectedTorrents.size

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Remove Selected Torrents?")
                    .setMessage("Choose how you want to remove $count selected torrent(s).")
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Remove Torrent Only") { _, _ ->
                        for (index in selectedTorrents.sortedDescending()) {
                            sendTorrentAction(
                                action = "REMOVE_TORRENT",
                                torrentIndex = index,
                                deleteFiles = false
                            )
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "Removed $count torrent(s)",
                            Toast.LENGTH_SHORT
                        ).show()

                        selectedTorrents.clear()
                        selectMode = false
                        showMainScreen()
                    }
                    .setPositiveButton("Remove + Files") { _, _ ->
                        for (index in selectedTorrents.sortedDescending()) {
                            sendTorrentAction(
                                action = "REMOVE_TORRENT",
                                torrentIndex = index,
                                deleteFiles = true
                            )
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "Removed $count torrent(s) and files",
                            Toast.LENGTH_SHORT
                        ).show()

                        selectedTorrents.clear()
                        selectMode = false
                        showMainScreen()
                    }
                    .show()
            }
        }

        val magnetInput = EditText(this).apply {
            hint = "Paste magnet link or hash"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
        }
        mainMagnetInput = magnetInput

        // Reads the clipboard when tapped, so it does not depend on the
        // automatic paste noticing the copy when the app regains focus.
        val pasteMagnet = Button(this).apply {
            text = "Paste from Clipboard"
            setOnClickListener { pasteMagnetFromClipboardNow(magnetInput) }
        }

        val addMagnet = Button(this).apply {
            text = "Add Magnet / Hash"
            setOnClickListener {
                var magnet = magnetInput.text.toString().trim()

                if (magnet.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Enter magnet or hash", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!magnet.startsWith("magnet:")) {
                    magnet = "magnet:?xt=urn:btih:$magnet"
                }

                val hash = extractHashFromMagnetInput(magnet)

                if (isTorrentAlreadyAddedByHash(hash)) {
                    showAlreadyAddedMessage(hash, magnet)
                    return@setOnClickListener
                }

                magnetInput.setText("")
                showMagnetMetadataScreen(magnet)
            }
        }

        val selectFile = Button(this).apply {
            text = "Select Torrent File"
            setOnClickListener { pickTorrent.launch("*/*") }
        }

        val searchBox = EditText(this).apply {
            hint = "Search torrents..."
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setText(searchQuery)
            setSelection(text.length)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                    // Not needed
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    searchQuery = s?.toString() ?: ""
                    updateTorrentList()
                }

                override fun afterTextChanged(s: Editable?) {
                    // Not needed
                }
            })
        }

        val clearSearch = Button(this).apply {
            text = "Clear Search"
            setOnClickListener {
                searchQuery = ""
                searchBox.setText("")
                updateTorrentList()
            }
        }

        speedGraphText = TextView(this).apply {
            text = buildSpeedSummaryText()
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 16, 16, 8)
            setBackgroundColor(Color.rgb(20, 20, 20))
        }

        speedGraphView = SpeedGraphView(this).apply {
            // The view paints its own rounded dark panel in onDraw(), so no
            // background/padding here - that would show as a mismatched
            // square edge behind the rounded corners. Bottom margin only,
            // to separate it from whatever comes next in the layout.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                360
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            setData(downloadSpeedHistory, uploadSpeedHistory)
        }

        val pauseAll = Button(this).apply {
            text = "Pause All"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("ACTION", "PAUSE_ALL")
                startTorrentService(intent)
            }
        }

        val resumeAll = Button(this).apply {
            text = "Resume All"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("ACTION", "RESUME_ALL")
                startTorrentService(intent)
            }
        }

        val globalStatistics = Button(this).apply {
            text = "Statistics"
            setOnClickListener {
                showGlobalStatisticsScreen()
            }
        }

        val portForwardingStatus = Button(this).apply {
            text = "Port Forwarding Status"
            setOnClickListener {
                showPortForwardingStatusScreen()
            }
        }

        val networkFeatures = Button(this).apply {
            text = "Network Features"
            setOnClickListener {
                showNetworkFeaturesScreen()
            }
        }

        val connectionSettingsButton = Button(this).apply {
            text = "Connection"
            setOnClickListener {
                showConnectionSettingsScreen()
            }
        }

        val speedLimitsButton = Button(this).apply {
            text = "Speed Limits"
            setOnClickListener {
                showSpeedLimitsScreen()
            }
        }


        val storageSpaceButton = Button(this).apply {
            text = "Storage Space"
            setOnClickListener {
                showStorageSpaceScreen()
            }
        }

        val saveFolderButton = Button(this).apply {
            text = "Save Folder"
            setOnClickListener {
                showSaveFolderScreen()
            }
        }

        val rssButton = Button(this).apply {
            text = "RSS Feeds"
            setOnClickListener {
                showRssScreen()
            }
        }

        val executionLogButton = Button(this).apply {
            text = "Execution Log"
            setOnClickListener {
                showExecutionLogScreen()
            }
        }

        val notificationSettingsButton = Button(this).apply {
            text = "Notifications"
            setOnClickListener {
                showNotificationSettingsScreen()
            }
        }

        val backupRestoreButton = Button(this).apply {
            text = "Backup & Restore"
            setOnClickListener {
                showBackupRestoreScreen()
            }
        }

        val onlineSearchButton = Button(this).apply {
            text = "Online Search"
            setOnClickListener {
                showOnlineSearchScreen()
            }
        }

        val webUiButton = Button(this).apply {
            text = "Web UI"
            setOnClickListener {
                showWebUiScreen()
            }
        }

        val filterTitle = TextView(this).apply {
            text = "Filter: $activeFilter"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }
        filterTitleView = filterTitle

        val filterRowOne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val filterRowTwo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun makeFilterButton(name: String): Button {
            return Button(this).apply {
                text = if (activeFilter == name) "[$name]" else name
                TabIcons.apply(
                    this,
                    when (name) {
                        "Downloading" -> "download"
                        "Seeding" -> "upload"
                        "Paused" -> "pause"
                        "Completed" -> "check"
                        else -> "list"
                    },
                    18,
                    6
                )
                setOnClickListener {
                    activeFilter = name
                    torrentListViewPrefs().edit().putString("filter", name).apply()
                    selectedTorrents.clear()
                    selectMode = false
                    scrollToFilterNext = true
                    showMainScreen()
                }
            }
        }

        filterRowOne.addView(makeFilterButton("All"))
        filterRowOne.addView(makeFilterButton("Downloading"))
        filterRowOne.addView(makeFilterButton("Seeding"))

        filterRowTwo.addView(makeFilterButton("Paused"))
        filterRowTwo.addView(makeFilterButton("Completed"))

        val sortButton = Button(this).apply {
            text = "Sort: $sortMode"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sort torrents by")
                    .setItems(TORRENT_SORT_MODES) { _, which ->
                        sortMode = TORRENT_SORT_MODES[which]
                        torrentListViewPrefs().edit().putString("sort", sortMode).apply()
                        scrollToFilterNext = true
                        showMainScreen()
                    }
                    .show()
            }
        }

        listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Icons next to the button names (flat single-colour, see TabIcons.kt)
        TabIcons.apply(themeButton, "theme")
        TabIcons.apply(selectAllButton, "selectall")
        TabIcons.apply(selectNoneButton, "selectnone")
        TabIcons.apply(forceRecheckSelected, "recheck")
        TabIcons.apply(forceReannounceSelected, "reannounce")
        TabIcons.apply(pauseSelected, "pause")
        TabIcons.apply(resumeSelected, "play")
        TabIcons.apply(removeSelected, "trash")
        TabIcons.apply(sortButton, "sort")
        TabIcons.apply(selectModeButton, "select")
        TabIcons.apply(pasteMagnet, "copy")
        TabIcons.applyBoth(addMagnet, "magnet", "hash")
        TabIcons.apply(selectFile, "addfile")
        TabIcons.apply(pauseAll, "pause")
        TabIcons.apply(resumeAll, "play")
        TabIcons.apply(globalStatistics, "statistics")
        TabIcons.apply(portForwardingStatus, "portfwd")
        TabIcons.apply(networkFeatures, "network")
        TabIcons.apply(connectionSettingsButton, "connection")
        TabIcons.apply(speedLimitsButton, "speed")
        TabIcons.apply(storageSpaceButton, "storage")
        TabIcons.apply(saveFolderButton, "folder")
        TabIcons.apply(rssButton, "rss")
        TabIcons.apply(executionLogButton, "log")
        TabIcons.apply(notificationSettingsButton, "comment")
        TabIcons.apply(backupRestoreButton, "save")
        TabIcons.apply(onlineSearchButton, "search")
        TabIcons.apply(webUiButton, "webui")

        root.addView(themeButton)
        root.addView(selectModeButton)
        root.addView(selectAllButton)
        root.addView(selectNoneButton)
        root.addView(forceRecheckSelected)
        root.addView(forceReannounceSelected)
        root.addView(pauseSelected)
        root.addView(resumeSelected)
        root.addView(removeSelected)
        root.addView(magnetInput)
        root.addView(pasteMagnet)
        root.addView(addMagnet)
        root.addView(selectFile)
        root.addView(searchBox)
        root.addView(clearSearch)
        root.addView(speedGraphText)
        root.addView(speedGraphView)
        root.addView(pauseAll)
        root.addView(resumeAll)
        root.addView(globalStatistics)
        root.addView(portForwardingStatus)
        root.addView(networkFeatures)
        root.addView(connectionSettingsButton)
        root.addView(speedLimitsButton)
        root.addView(storageSpaceButton)
        root.addView(saveFolderButton)
        root.addView(rssButton)
        root.addView(executionLogButton)
        root.addView(notificationSettingsButton)
        root.addView(backupRestoreButton)
        root.addView(onlineSearchButton)
        root.addView(webUiButton)
        root.addView(filterTitle)
        root.addView(horizontalScrollFor(filterRowOne))
        root.addView(horizontalScrollFor(filterRowTwo))
        root.addView(sortButton)
        root.addView(listLayout)

        applyDeepBlueButtons(root)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        // Sticky selection bar (stays on screen while scrolling the list):
        // close, count, select all, remove, resume and a menu with the rest
        // - same actions as the Select Torrents buttons further down, plus
        // the queue position moves.
        fun barItem(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(34, 30, 34, 30)
            applyBarPressFlash(this)
            setOnClickListener { onClick() }
        }

        val countView = TextView(this).apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 0, 0)
        }
        selectionCountView = countView

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(13, 71, 161))
            setPadding(12, 14, 12, 14)
            elevation = 12f

            addView(barItem("\u2715") {
                selectMode = false
                selectedTorrents.clear()
                showMainScreen()
            })

            addView(
                countView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            addView(barItem("\u2610") { selectNoneButton.performClick() })
            addView(barItem("\u2611") { selectAllButton.performClick() })
            addView(barItem("\uD83D\uDDD1") { removeSelected.performClick() })
            addView(barItem("\u25B6") { resumeSelected.performClick() })

            val menuAnchor = barItem("\u22EE") {}
            menuAnchor.setOnClickListener {
                showIconMenu(
                    menuAnchor,
                    listOf(
                        IconMenuItem("Pause", "pause", true) { pauseSelected.performClick() },
                        IconMenuItem("Force Recheck", "recheck", true) { forceRecheckSelected.performClick() },
                        IconMenuItem("Reannounce", "reannounce", true) { forceReannounceSelected.performClick() },
                        IconMenuItem("Share Magnet Link", "share", true) { shareSelectedMagnets() },
                        IconMenuItem("Move...", "move", false) {},
                        IconMenuItem("to Top", "totop", true) { moveSelectedInQueue("top") },
                        IconMenuItem("Up", "up", true) { moveSelectedInQueue("up") },
                        IconMenuItem("Down", "down", true) { moveSelectedInQueue("down") },
                        IconMenuItem("to Bottom", "tobottom", true) { moveSelectedInQueue("bottom") }
                    )
                )
            }
            addView(menuAnchor)
        }
        selectionBar = bar

        // Pinned title bar in the same deep blue as the other top bars; the
        // title no longer scrolls away with the list.
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(13, 71, 161))
            setPadding(32, 40, 96, 40)
            elevation = 12f

            // App name, with the live total speeds under it in the same
            // "[D: 0 B/s, U: 0 B/s]" style as qBittorrent's window title.
            val nameColumn = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)

                val speedView = TextView(this@MainActivity).apply {
                    textSize = 14f
                    maxLines = 1
                    gravity = android.view.Gravity.END
                    text = "[D: 0 B/s, U: 0 B/s]"
                    setTextColor(Color.WHITE)
                    setPadding(0, 4, 0, 0)
                }
                mainSpeedText = speedView
                addView(
                    speedView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 48, 0) }
                )
            }

            addView(
                nameColumn,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            // Three-dot menu on the right (Shutdown).
            val menuDots = TextView(this@MainActivity).apply {
                text = "\u22EE"
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setPadding(28, 8, 28, 8)
                setOnClickListener { showMainOverflowMenu(this) }
            }
            addView(menuDots)
        }
        mainTitleBar = titleBar

        val container = LinearLayout(this).apply {
            tag = "has_bar"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            addView(titleBar)
            addView(bar)
            addView(
                outerScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            // Thin status strip pinned to the bottom: DHT / PEX / LSD
            // state dots and the total seeds and peers. Tap it to open
            // Network Features.
            addView(
                android.view.View(this@MainActivity).apply {
                    setBackgroundColor(outlineColor())
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    maxOf(1, (1f * resources.displayMetrics.density).toInt())
                )
            )
            addView(
                TextView(this@MainActivity).apply {
                    textSize = 12.5f
                    maxLines = 1
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(16, 22, 16, 22)
                    setBackgroundColor(
                        androidx.core.graphics.ColorUtils.blendARGB(bgColor(), Color.BLACK, 0.35f)
                    )
                    setOnClickListener { showNetworkFeaturesScreen() }
                    mainBottomBar = this
                    updateMainBottomBarText()
                }
            )
        }

        setContentView(container)
        isOnMainScreen = true
        mainScrollView = outerScroll
        updateSelectionBar()
        updateTorrentList()

        val toFilter = scrollToFilterNext
        scrollToFilterNext = false
        val restoreY = savedMainScrollY
        val filterRow = filterTitleView

        if (toFilter && filterRow != null) {
            outerScroll.post { outerScroll.scrollTo(0, filterRow.top) }
        } else if (restoreY > 0) {
            outerScroll.post { outerScroll.scrollTo(0, restoreY) }
        }
    }

    // ------------------------------------------------------------------ RSS

    // The list of feeds. A tap on a feed opens that feed in its own screen.
    // -------------------------------------------------------------- Online Search

    private fun showOnlineSearchScreen() {
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        // read the remembered state instead of starting blank each time
        var allResults: List<TorrentSearchResult> = onlineSearchResults
        var sortMode = SearchFilters.loadSortMode(this)
        var filters = SearchFilters.load(this)
        var searching = onlineSearchHandle != null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        val title = TextView(this).apply {
            text = "Online Search"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        val hintText = TextView(this).apply {
            text = "Searches multiple providers. This is separate from the search box on the main screen, which only searches torrents already in TorrentOr."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 12)
        }

        val queryBox = AutoCompleteTextView(this).apply {
            threshold = 1
            setAdapter(
                ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    SearchHistory.suggestions(this@MainActivity)
                )
            )
            hint = "Search online, e.g. ubuntu 24.04"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setText(onlineSearchQueryText)
            setSelection(text.length)

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onlineSearchQueryText = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val statusText = TextView(this).apply {
            text = onlineSearchStatus
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        }

        val resultsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // (defined below, referenced by both the sort/filter buttons and search())
        var redraw: () -> Unit = {}

        fun visibleProviderNames(): List<String> {
            return OnlineSearchManager.allProviders(this).map { it.name }.distinct()
        }

        fun applySortAndFilter(): List<TorrentSearchResult> {
            val filtered = allResults.filter { filters.matches(it) }

            return SearchFilters.sort(
                filtered,
                sortMode,
                queryBox.text.toString(),
                OnlineSearchManager.favoriteNames(this)
            )
        }

        fun resultLabel(result: TorrentSearchResult): String {
            val parts = mutableListOf<String>()

            if (result.sizeText.isNotBlank()) {
                parts.add(result.sizeText)
            } else if (result.sizeBytes > 0) {
                parts.add("${result.sizeBytes / (1024 * 1024)} MB")
            }

            if (result.seeders >= 0) parts.add("Seeds: ${result.seeders}")
            if (result.leechers >= 0) parts.add("Leechers: ${result.leechers}")

            val source = if (result.alsoFoundOn.isEmpty()) {
                "Source: ${result.sourceName}"
            } else {
                "Source: ${result.sourceName} (also: ${result.alsoFoundOn.joinToString(", ")})"
            }
            parts.add(source)

            if (result.publicationDate.isNotBlank()) {
                parts.add(result.publicationDate)
            }

            val hash = result.normalizedHash()
            val hashLine = if (hash.isNotEmpty()) "\nHash: $hash" else ""

            return result.name + "\n" + parts.joinToString("  •  ") + hashLine
        }

        fun rebuildResultsList() {
            resultsLayout.removeAllViews()

            val shown = applySortAndFilter()

            if (shown.isEmpty()) {
                val emptyText = TextView(this).apply {
                    text = when {
                        searching -> "Searching..."
                        allResults.isNotEmpty() -> "No results match the current filters."
                        else -> "No results."
                    }
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 12, 0, 12)
                }
                resultsLayout.addView(emptyText)
                return
            }

            for (result in shown) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                    background = roundedCardBackground()

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 12
                    }
                }

                val label = TextView(this).apply {
                    text = resultLabel(result)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, 8)
                }

                val buttonRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val canAdd = result.magnetUri.isNotBlank() ||
                        result.torrentUrl.isNotBlank() ||
                        result.resultPageUrl.isNotBlank()

                val addButton = Button(this).apply {
                    text = when {
                        result.magnetUri.isNotBlank() || result.torrentUrl.isNotBlank() -> "Add Magnet"
                        result.resultPageUrl.isNotBlank() -> "Add (via page)"
                        else -> "Add"
                    }

                    isEnabled = canAdd

                    setOnClickListener {
                        isEnabled = false
                        val originalText = text
                        text = "..."

                        // Resolves the result (magnet link, or downloads
                        // the .torrent file) on a background thread first,
                        // then hands it to the same Add Magnet/Add Torrent
                        // File screens used everywhere else in the app, so
                        // the user picks files/folder before anything is
                        // added - it is only actually added once they
                        // commit there, not the moment this button is
                        // tapped.
                        Thread {
                            var resolved: OnlineSearchAdder.ResolvedItem? = null
                            var errorReason: String? = null

                            try {
                                resolved = OnlineSearchAdder.resolveForSelection(
                                    this@MainActivity,
                                    result
                                )
                            } catch (e: Throwable) {
                                errorReason = e.message ?: e.javaClass.simpleName
                            }

                            runOnUiThread {
                                isEnabled = true

                                val magnet = resolved?.magnet
                                val torrentPath = resolved?.torrentPath

                                when {
                                    magnet != null -> {
                                        text = originalText
                                        showMagnetMetadataScreen(magnet) {
                                            text = "Added"
                                        }
                                    }
                                    torrentPath != null -> {
                                        text = originalText
                                        showTorrentFileSelection(torrentPath) {
                                            text = "Added"
                                        }
                                    }
                                    else -> {
                                        text = originalText
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Could not add: ${errorReason ?: "unknown error"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }.start()
                    }
                }

                val openSiteButton = Button(this).apply {
                    text = "Open Site"

                    val pageUrl = result.resultPageUrl.trim()
                    val canOpen = pageUrl.startsWith("http://", ignoreCase = true) ||
                            pageUrl.startsWith("https://", ignoreCase = true)

                    isEnabled = canOpen

                    if (canOpen) {
                        setOnClickListener {
                            try {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
                                startActivity(browserIntent)
                            } catch (_: Throwable) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "No app can open this link",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                buttonRow.addView(addButton)
                buttonRow.addView(openSiteButton)

                card.addView(label)
                card.addView(buttonRow)
                resultsLayout.addView(card)
            }
        }

        redraw = { rebuildResultsList() }

        // (assigned below; lets runSearch/cancel keep the Cancel button in sync)
        var cancelButtonRef: Button? = null

        fun runSearch() {
            val query = queryBox.text.toString().trim()

            if (query.isBlank()) {
                statusText.text = "Enter something to search for."
                onlineSearchStatus = statusText.text.toString()
                return
            }

            // A new search replaces any search still running.
            onlineSearchHandle?.cancel()
            onlineSearchHandle = null

            SearchHistory.add(this, query)
            queryBox.setAdapter(
                ArrayAdapter(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    SearchHistory.suggestions(this)
                )
            )
            queryBox.dismissDropDown()

            searching = true
            allResults = emptyList()
            redraw()

            val providerNames = OnlineSearchManager.allProviders(this)
                .filter { OnlineSearchManager.isProviderEnabled(this, it.name) }
                .map { it.name }

            if (providerNames.isEmpty()) {
                searching = false
                statusText.text = "No providers are enabled. Use Provider Settings below."
                onlineSearchStatus = statusText.text.toString()
                redraw()
                return
            }

            val doneNames = mutableSetOf<String>()
            statusText.text = "Searching ${providerNames.size} provider(s)..."
            onlineSearchStatus = statusText.text.toString()
            onlineSearchResults = emptyList()
            cancelButtonRef?.isEnabled = true

            val newHandle = OnlineSearchManager.search(
                this,
                query,
                onProviderDone = { outcome ->
                    runOnUiThread {
                        doneNames.add(outcome.providerName)

                        statusText.text = if (outcome.error == null) {
                            "${outcome.providerName}: ${outcome.resultCount} result(s), " +
                                    "${outcome.elapsedMs} ms   " +
                                    "(${doneNames.size}/${providerNames.size} providers done)"
                        } else {
                            "${outcome.providerName}: ${outcome.error}   " +
                                    "(${doneNames.size}/${providerNames.size} providers done)"
                        }
                        onlineSearchStatus = statusText.text.toString()
                    }
                },
                onAllDone = { combined ->
                    runOnUiThread {
                        searching = false
                        onlineSearchHandle = null
                        cancelButtonRef?.isEnabled = false
                        allResults = combined

                        statusText.text = if (combined.isEmpty()) {
                            "No results from any provider."
                        } else {
                            "${combined.size} result(s) from ${providerNames.size} provider(s)."
                        }
                        onlineSearchStatus = statusText.text.toString()
                        onlineSearchResults = allResults

                        redraw()
                    }
                },
                onUpdate = { partial ->
                    runOnUiThread {
                        allResults = partial
                        onlineSearchResults = partial
                        redraw()
                    }
                }
            )

            onlineSearchHandle = newHandle
        }

        fun cancelSearch() {
            val handle = onlineSearchHandle ?: return
            handle.cancel()
            onlineSearchHandle = null
            searching = false
            cancelButtonRef?.isEnabled = false
            statusText.text = "Search cancelled. ${allResults.size} result(s) found so far."
            onlineSearchStatus = statusText.text.toString()
            redraw()
        }

        val searchButton = Button(this).apply {
            text = "Search"
            setOnClickListener { runSearch() }
        }

        queryBox.setOnEditorActionListener { _, _, _ ->
            runSearch()
            true
        }

        val sortButton = Button(this).apply {
            text = "Sort: $sortMode"
            setOnClickListener {
                val options = SearchFilters.SORT_MODES.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Sort by")
                    .setItems(options) { _, which ->
                        sortMode = options[which]
                        SearchFilters.saveSortMode(this@MainActivity, sortMode)
                        text = "Sort: $sortMode"
                        redraw()
                    }
                    .show()
            }
        }

        fun filterButtonText(): String {
            return if (filters.isActive()) "Filters (on)" else "Filters"
        }

        val filterButton = Button(this).apply {
            text = filterButtonText()
            setOnClickListener {
                showSearchFiltersDialog(filters, allResults) { newFilters ->
                    filters = newFilters
                    SearchFilters.save(this@MainActivity, filters)
                    text = filterButtonText()
                    redraw()
                }
            }
        }

        val historyButton = Button(this).apply {
            text = "History"
            setOnClickListener {
                showSearchHistoryDialog { picked ->
                    queryBox.setText(picked)
                    queryBox.setSelection(queryBox.text.length)
                    runSearch()
                }
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            isEnabled = searching
            setOnClickListener { cancelSearch() }
        }
        cancelButtonRef = cancelButton

        TabIcons.apply(searchButton, "search", 18, 6)
        TabIcons.apply(sortButton, "sort", 18, 6)
        TabIcons.apply(filterButton, "filter", 18, 6)

        val settingsButton = Button(this).apply {
            text = "Provider Settings"
            setOnClickListener {
                showOnlineSearchProviderSettings()
            }
        }
        TabIcons.apply(settingsButton, "settings", 18, 6)

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(hintText)
        root.addView(queryBox)
        root.addView(
            horizontalScrollFor(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(searchButton)
                    addView(cancelButton)
                    addView(sortButton)
                    addView(filterButton)
                    addView(historyButton)
                    addView(settingsButton)
                }
            )
        )
        root.addView(statusText)
        root.addView(resultsLayout)
        root.addView(backButton)

        setContentView(outerScroll)
    }

    // Advanced filters for Online Search. Empty/blank number = no limit.
    // Results with an unknown value for a field are never hidden by that
    // field's limit.
    private fun showSearchFiltersDialog(
        current: SearchFilters,
        results: List<TorrentSearchResult>,
        onApply: (SearchFilters) -> Unit
    ) {
        val builder = AlertDialog.Builder(this)
        val ctx = builder.context

        var providers = current.providers
        var category = current.category
        var fileType = current.fileType

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }

        fun numberField(label: String, value: Long): EditText {
            col.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setPadding(0, 12, 0, 0)
            })

            return EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                hint = "No limit"
                setText(if (value > 0L) value.toString() else "")
                col.addView(this)
            }
        }

        val minSeedersInput = numberField("Minimum seeders", current.minSeeders.toLong())
        val maxLeechersInput = numberField("Maximum leechers", current.maxLeechers.toLong())
        val minSizeInput = numberField("Minimum size (MB)", current.minSizeMb)
        val maxSizeInput = numberField("Maximum size (MB)", current.maxSizeMb)
        val minHealthInput = numberField(
            "Minimum availability (seeders + leechers)",
            current.minHealth.toLong()
        )

        fun providerText() = if (providers.isEmpty()) "Providers: all" else "Providers: ${providers.size} selected"
        fun categoryText() = "Category: " + category.ifBlank { "any" }
        fun typeText() = "File type: " + fileType.ifBlank { "any" }

        val providerButton = Button(ctx).apply {
            text = providerText()
            setOnClickListener {
                val names = OnlineSearchManager.allProviders(this@MainActivity).map { it.name }.distinct()
                val labels = names.map { n ->
                    val star = if (OnlineSearchManager.isFavorite(this@MainActivity, n)) "★ " else ""
                    val bad = if (OnlineSearchManager.loadStats(this@MainActivity, n).unavailable) " (unavailable)" else ""
                    star + n + bad
                }.toTypedArray()
                val checked = BooleanArray(names.size) { names[it] in providers }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Show results from (none checked = all)")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("OK") { _, _ ->
                        providers = names.filterIndexed { i, _ -> checked[i] }.toSet()
                        text = providerText()
                    }
                    .setNeutralButton("Favorites only") { _, _ ->
                        providers = OnlineSearchManager.favoriteNames(this@MainActivity)
                        text = providerText()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        val categoryButton = Button(ctx).apply {
            text = categoryText()
            setOnClickListener {
                val cats = results
                    .map { it.category.trim().ifBlank { SearchFilters.UNCATEGORIZED } }
                    .distinct().sorted()
                val options = (listOf("Any") + cats).toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Category")
                    .setItems(options) { _, which ->
                        category = if (which == 0) "" else options[which]
                        text = categoryText()
                    }
                    .show()
            }
        }

        val typeButton = Button(ctx).apply {
            text = typeText()
            setOnClickListener {
                val options = (listOf("Any") + SearchFilters.FILE_TYPES).toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("File type (guessed from name)")
                    .setItems(options) { _, which ->
                        fileType = if (which == 0) "" else options[which]
                        text = typeText()
                    }
                    .show()
            }
        }

        col.addView(providerButton)
        col.addView(categoryButton)
        col.addView(typeButton)

        fun num(e: EditText): Long = e.text.toString().trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        builder.setTitle("Search filters")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Apply") { _, _ ->
                onApply(
                    SearchFilters(
                        minSeeders = num(minSeedersInput).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        maxLeechers = num(maxLeechersInput).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        minSizeMb = num(minSizeInput),
                        maxSizeMb = num(maxSizeInput),
                        minHealth = num(minHealthInput).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        providers = providers,
                        category = category,
                        fileType = fileType
                    )
                )
            }
            .setNeutralButton("Reset") { _, _ -> onApply(SearchFilters()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Past Online Search queries: tap one to search it again, X removes
    // one, Clear all removes everything.
    private fun showSearchHistoryDialog(onPick: (String) -> Unit) {
        val history = SearchHistory.load(this)

        if (history.isEmpty()) {
            Toast.makeText(this, "No search history yet", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = AlertDialog.Builder(this)
        val ctx = builder.context
        var dialog: AlertDialog? = null

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }

        for (q in history) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val label = TextView(ctx).apply {
                text = q
                textSize = 16f
                setPadding(8, 20, 8, 20)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    dialog?.dismiss()
                    onPick(q)
                }
            }

            val removeButton = Button(ctx).apply {
                text = "✕"
                setOnClickListener {
                    SearchHistory.remove(this@MainActivity, q)
                    col.removeView(row)

                    if (col.childCount == 0) dialog?.dismiss()
                }
            }

            row.addView(label)
            row.addView(removeButton)
            col.addView(row)
        }

        builder.setTitle("Search history")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setNeutralButton("Clear all") { _, _ ->
                SearchHistory.clear(this)
                Toast.makeText(this, "Search history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)

        dialog = builder.show()
    }

    private fun showOnlineSearchProviderSettings() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Search Provider Settings"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }

        // Collects every provider checkbox (built-in and custom) so
        // Select All / Select None below can flip them all at once. Each
        // checkbox already persists through its own setOnCheckedChangeListener,
        // which still fires when isChecked is set programmatically, so
        // these two buttons don't need their own prefs-writing logic.
        val allProviderCheckboxes = mutableListOf<CheckBox>()

        val selectAllButton = Button(this).apply {
            text = "Select All"
            setOnClickListener {
                for (box in allProviderCheckboxes) box.isChecked = true
            }
        }

        val selectNoneButton = Button(this).apply {
            text = "Select None"
            setOnClickListener {
                for (box in allProviderCheckboxes) box.isChecked = false
            }
        }

        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(selectAllButton)
                addView(selectNoneButton)
            }
        )

        // One card per provider: enable checkbox, last-known health
        // (response time, last success, last error), a favorite toggle and
        // a Test button. Custom providers also get Remove.
        // Re-run every second while this screen is showing, so "last
        // success: 5 min ago" and results of searches/tests keep up in real time.
        val statusRefreshers = mutableListOf<() -> Unit>()

        fun statusLine(name: String): String {
            val st = OnlineSearchManager.loadStats(this, name)

            if (st.lastAttemptMs == 0L) return "Not tested yet"

            fun ago(ms: Long): String {
                if (ms <= 0L) return "never"
                val sec = (System.currentTimeMillis() - ms) / 1000L
                return when {
                    sec < 60 -> "just now"
                    sec < 3600 -> "${sec / 60} min ago"
                    sec < 86400 -> "${sec / 3600} h ago"
                    else -> "${sec / 86400} d ago"
                }
            }

            val head = if (st.unavailable) "UNAVAILABLE: ${st.lastError}" else "Working, ${st.lastResponseMs} ms"
            return head + "\nLast success: " + ago(st.lastSuccessMs) +
                    (if (st.unavailable) "   (response ${st.lastResponseMs} ms)" else "")
        }

        fun addProviderCard(name: String, isCustom: Boolean) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                background = roundedCardBackground()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
            }

            val box = CheckBox(this).apply {
                text = name
                setTextColor(Color.WHITE)
                isChecked = OnlineSearchManager.isProviderEnabled(this@MainActivity, name)

                setOnCheckedChangeListener { _, checked ->
                    OnlineSearchManager.setProviderEnabled(this@MainActivity, name, checked)
                }
            }

            val initial = OnlineSearchManager.loadStats(this, name)

            val status = TextView(this).apply {
                text = statusLine(name)
                textSize = 12f
                setTextColor(if (initial.unavailable) Color.rgb(255, 120, 120) else Color.LTGRAY)
                setPadding(8, 0, 0, 6)
            }

            statusRefreshers.add {
                val st = OnlineSearchManager.loadStats(this@MainActivity, name)
                status.text = statusLine(name)
                status.setTextColor(if (st.unavailable) Color.rgb(255, 120, 120) else Color.LTGRAY)
            }

            val favButton = Button(this).apply {
                text = if (OnlineSearchManager.isFavorite(this@MainActivity, name)) "★ Favorite" else "☆ Favorite"
                setOnClickListener {
                    val now = !OnlineSearchManager.isFavorite(this@MainActivity, name)
                    OnlineSearchManager.setFavorite(this@MainActivity, name, now)
                    text = if (now) "★ Favorite" else "☆ Favorite"
                }
            }

            val testButton = Button(this).apply {
                text = "Test"
                setOnClickListener {
                    isEnabled = false
                    text = "Testing..."
                    status.text = "Testing..."

                    OnlineSearchManager.testProvider(this@MainActivity, name) { _ ->
                        runOnUiThread {
                            isEnabled = true
                            text = "Test"
                            val st = OnlineSearchManager.loadStats(this@MainActivity, name)
                            status.text = statusLine(name)
                            status.setTextColor(
                                if (st.unavailable) Color.rgb(255, 120, 120) else Color.LTGRAY
                            )
                        }
                    }
                }
            }

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(favButton)
                addView(testButton)

                if (isCustom) {
                    addView(Button(this@MainActivity).apply {
                        text = "Remove"
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Remove search provider?")
                                .setMessage("Remove \"$name\"? This cannot be undone.")
                                .setPositiveButton("Remove") { _, _ ->
                                    OnlineSearchManager.removeCustomProvider(this@MainActivity, name)
                                    showOnlineSearchProviderSettings()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    })
                }
            }

            allProviderCheckboxes.add(box)
            card.addView(box)
            card.addView(status)
            card.addView(horizontalScrollFor(buttons))
            root.addView(card)
        }

        // Favorites first, then the rest in registry order.
        val builtinNames = OnlineSearchManager.BUILTIN_PROVIDERS.map { it.name }
        val orderedBuiltin = builtinNames.sortedBy {
            if (OnlineSearchManager.isFavorite(this, it)) 0 else 1
        }

        for (name in orderedBuiltin) addProviderCard(name, false)

        val customProvidersTitle = TextView(this).apply {
            text = "Custom Providers"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 4)
        }

        root.addView(customProvidersTitle)

        val customProviders = OnlineSearchManager.loadCustomProviders(this@MainActivity)

        if (customProviders.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "None added yet."
                textSize = 13f
                setTextColor(Color.LTGRAY)
                setPadding(0, 0, 0, 8)
            })
        } else {
            val orderedCustom = customProviders.sortedBy {
                if (OnlineSearchManager.isFavorite(this, it.name)) 0 else 1
            }

            for (provider in orderedCustom) addProviderCard(provider.name, true)
        }

        val refreshHandler = Handler(Looper.getMainLooper())

        refreshHandler.postDelayed(object : Runnable {
            override fun run() {
                // Stops by itself once another screen replaces this one.
                if (!root.isAttachedToWindow) return

                for (refresh in statusRefreshers) {
                    try {
                        refresh()
                    } catch (_: Throwable) {
                    }
                }

                refreshHandler.postDelayed(this, 1000L)
            }
        }, 1000L)

        val suggestBox = CheckBox(this).apply {
            text = "Show search suggestions from history"
            setTextColor(Color.WHITE)
            isChecked = SearchHistory.suggestionsEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                SearchHistory.setSuggestionsEnabled(this@MainActivity, checked)
            }
        }

        root.addView(suggestBox)

        val addCustomProviderButton = Button(this).apply {
            text = "Add Custom Provider"
            setOnClickListener {
                showAddCustomProviderScreen()
            }
        }

        root.addView(addCustomProviderButton)

        val timeoutLabel = TextView(this).apply {
            text = "Timeout per provider (seconds)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val timeoutInput = EditText(this).apply {
            setText(OnlineSearchManager.loadTimeoutSeconds(this@MainActivity).toString())
            setTextColor(Color.WHITE)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val seconds = timeoutInput.text.toString().toIntOrNull()

                if (seconds != null && seconds in 3..60) {
                    OnlineSearchManager.saveTimeoutSeconds(this@MainActivity, seconds)
                    Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Enter a number between 3 and 60",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                showOnlineSearchScreen()
            }
        }

        root.addView(title)
        root.addView(timeoutLabel)
        root.addView(timeoutInput)
        root.addView(saveButton)
        root.addView(backButton)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // Lets the user point TorrentOr at any torrent site that has its own
    // public JSON search API, without needing a hand-written provider
    // class (see CustomSearchProvider.kt). Re-saving with the same Name
    // overwrites the existing one, so this screen doubles as "Edit".
    private fun showAddCustomProviderScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Add Custom Provider"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        val hintBlock = TextView(this).apply {
            text = "For a torrent site with its own public JSON search API. " +
                    "Fill in the field names that API's JSON results use - " +
                    "only Name, Search URL and Title field are required. " +
                    "Saving again with the same Name replaces it."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 16)
        }

        fun labeledInput(label: String, hintText: String, prefill: String = ""): EditText {
            root.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, 12, 0, 2)
            })

            return EditText(this).apply {
                hint = hintText
                setText(prefill)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                root.addView(this)
            }
        }

        val nameInput = labeledInput("Name", "e.g. My Tracker")

        val urlInput = labeledInput(
            "Search URL",
            "Must contain {query}, e.g. https://example.com/api/search?q={query}"
        )

        val resultsPathInput = labeledInput(
            "Results array path (optional)",
            "e.g. results - blank if the response is itself a list"
        )

        val titleFieldInput = labeledInput("Title field", "title", "title")
        val infohashFieldInput = labeledInput("Info hash field (optional)", "infohash")
        val magnetFieldInput = labeledInput(
            "Magnet field (optional)",
            "magnet - leave blank to build it from the info hash field"
        )
        val sizeFieldInput = labeledInput("Size in bytes field (optional)", "size")
        val seedersFieldInput = labeledInput("Seeders field (optional)", "seeders")
        val leechersFieldInput = labeledInput("Leechers field (optional)", "leechers")
        val categoryFieldInput = labeledInput("Category field (optional)", "category")
        val dateFieldInput = labeledInput("Date field (optional)", "createdAt")
        val idFieldInput = labeledInput("Result id field (optional)", "id")

        val detailUrlInput = labeledInput(
            "Detail page URL template (optional)",
            "Needs {id}, e.g. https://example.com/torrent/{id}"
        )

        fun buildConfig(): CustomProviderConfig {
            return CustomProviderConfig(
                name = nameInput.text.toString().trim(),
                urlTemplate = urlInput.text.toString().trim(),
                resultsPath = resultsPathInput.text.toString().trim(),
                titleField = titleFieldInput.text.toString().trim().ifBlank { "title" },
                infohashField = infohashFieldInput.text.toString().trim(),
                magnetField = magnetFieldInput.text.toString().trim(),
                sizeField = sizeFieldInput.text.toString().trim(),
                seedersField = seedersFieldInput.text.toString().trim(),
                leechersField = leechersFieldInput.text.toString().trim(),
                categoryField = categoryFieldInput.text.toString().trim(),
                dateField = dateFieldInput.text.toString().trim(),
                idField = idFieldInput.text.toString().trim(),
                detailUrlTemplate = detailUrlInput.text.toString().trim()
            )
        }

        val testButton = Button(this).apply {
            text = "Test"
            setOnClickListener {
                val config = buildConfig()

                if (config.name.isBlank() || !config.urlTemplate.contains("{query}")) {
                    Toast.makeText(
                        this@MainActivity,
                        "Name and a Search URL containing {query} are required",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                isEnabled = false
                val originalText = text
                text = "Testing..."

                Thread {
                    val resultText = try {
                        val count = CustomSearchProvider(config).search("test").size
                        "Test search for \"test\" returned $count result(s)"
                    } catch (e: Throwable) {
                        "Test failed: ${e.message ?: e.javaClass.simpleName}"
                    }

                    runOnUiThread {
                        isEnabled = true
                        text = originalText
                        Toast.makeText(this@MainActivity, resultText, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }

        val saveButton = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val config = buildConfig()

                if (config.name.isBlank()) {
                    Toast.makeText(this@MainActivity, "Enter a name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!config.urlTemplate.contains("{query}")) {
                    Toast.makeText(
                        this@MainActivity,
                        "Search URL must contain {query}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                OnlineSearchManager.addCustomProvider(this@MainActivity, config)
                Toast.makeText(this@MainActivity, "Saved \"${config.name}\"", Toast.LENGTH_SHORT).show()
                showOnlineSearchProviderSettings()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                showOnlineSearchProviderSettings()
            }
        }

        root.addView(title)
        root.addView(hintBlock)
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 20, 0, 0)
                addView(testButton)
                addView(saveButton)
                addView(backButton)
            }
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showRssScreen() {
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "RSS Feeds"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val statusText = TextView(this).apply {
            text = "Add a feed URL to start."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        }

        val autoRefreshText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 12)
        }

        val feedsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Changes when any feed was refreshed, so the list is redrawn then.
        var shownSignature = ""

        // (the list and the refresh functions need each other)
        var redrawList: () -> Unit = {}

        fun currentSignature(): String {
            return RssManager.lastRefreshMillis(this).toString() + "|" +
                    loadRssFeeds().joinToString("|") {
                        RssManager.lastFeedRefreshMillis(this, it).toString()
                    }
        }

        fun updateAutoRefreshText() {
            val minutes = RssManager.loadIntervalMinutes(this)
            val last = RssManager.lastRefreshMillis(this)
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            val lastText = if (last == 0L) "never" else format.format(Date(last))

            val nextText = when {
                minutes <= 0 -> "off"
                last == 0L -> "soon"
                else -> format.format(Date(last + minutes * 60_000L))
            }

            autoRefreshText.text =
                "Auto refresh: ${RssManager.intervalLabel(minutes)}\n" +
                        "Last refresh: $lastText\n" +
                        "Next refresh: $nextText"
        }

        // Refreshes one feed (the Refresh button of a feed card).
        fun startFeedRefresh(feed: String) {
            if (RssManager.isRefreshing()) {
                statusText.text = "A refresh is already running..."
                return
            }

            statusText.text = "Refreshing ${RssManager.feedTitle(this, feed)}..."

            Thread {
                val result = try {
                    RssManager.refreshFeed(applicationContext, feed, true)
                } catch (_: Throwable) {
                    null
                }

                runOnUiThread {
                    statusText.text = when {
                        result == null -> "Refresh could not run right now."
                        result.feedsFailed > 0 ->
                            "Could not refresh this feed. See the Execution Log."
                        result.newItems > 0 ->
                            "Refreshed: ${result.newItems} new item(s)."
                        else -> "Refreshed, no new items."
                    }

                    redrawList()
                    updateAutoRefreshText()
                }
            }.start()
        }

        fun refreshUi() {
            feedsLayout.removeAllViews()

            val feeds = loadRssFeeds()

            if (feeds.isEmpty()) {
                statusText.text = "No feeds yet. Tap Add Feed to add one."
            } else if (!RssManager.isRefreshing()) {
                statusText.text = "${feeds.size} feed(s). Tap a feed to open it."
            }

            val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

            for (feed in feeds) {
                val items = RssManager.loadItems(this, feed)
                val autoOn = RssManager.isAutoDownload(this, feed)
                val last = RssManager.lastFeedRefreshMillis(this, feed)

                val updatedText = if (last == 0L) {
                    "not refreshed yet"
                } else {
                    "updated ${timeFormat.format(Date(last))}"
                }

                val feedCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16, 16, 16, 16)
                    background = roundedCardBackground()

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 16
                    }

                    // a tap anywhere on the card opens the feed
                    setOnClickListener {
                        showRssFeedScreen(feed)
                    }
                }

                val nameText = TextView(this).apply {
                    text = RssManager.feedTitle(this@MainActivity, feed)
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val urlText = TextView(this).apply {
                    text = feed
                    textSize = 12f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 2, 0, 4)
                }

                val infoText = TextView(this).apply {
                    text = "${items.size} item(s)  •  " +
                            "Auto-download: ${if (autoOn) "On" else "Off"}  •  $updatedText"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, 8)
                }

                val buttonRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }

                val openButton = Button(this).apply {
                    text = "Open"
                    setOnClickListener {
                        showRssFeedScreen(feed)
                    }
                }

                val refreshFeedButton = Button(this).apply {
                    text = "Refresh"
                    setOnClickListener {
                        startFeedRefresh(feed)
                    }
                }

                buttonRow.addView(openButton)
                buttonRow.addView(refreshFeedButton)

                feedCard.addView(nameText)
                feedCard.addView(urlText)
                feedCard.addView(infoText)
                feedCard.addView(buttonRow)

                feedsLayout.addView(feedCard)
            }

            shownSignature = currentSignature()
        }

        redrawList = { refreshUi() }

        // Refreshes all feeds on a background thread, then redraws the list.
        fun startRefresh(manual: Boolean) {
            if (loadRssFeeds().isEmpty()) {
                statusText.text = "No feeds yet. Tap Add Feed to add one."
                return
            }

            if (RssManager.isRefreshing()) {
                statusText.text = "A refresh is already running..."
                return
            }

            statusText.text = "Refreshing feeds..."

            Thread {
                val result = try {
                    RssManager.refreshAll(applicationContext, manual)
                } catch (_: Throwable) {
                    null
                }

                runOnUiThread {
                    statusText.text = when {
                        result == null -> "Refresh could not run right now."
                        result.feedsFailed > 0 ->
                            "Refresh finished: ${result.feedsOk} ok, ${result.feedsFailed} failed. See Execution Log."
                        result.newItems > 0 ->
                            "Refresh complete: ${result.newItems} new item(s)."
                        else -> "Refresh complete."
                    }

                    refreshUi()
                    updateAutoRefreshText()
                }
            }.start()
        }

        val addFeedButton = Button(this).apply {
            text = "Add Feed"
            setOnClickListener {
                showAddFeedDialog {
                    refreshUi()
                    startRefresh(true)
                }
            }
        }

        val refreshButton = Button(this).apply {
            text = "Refresh all"
            setOnClickListener {
                startRefresh(true)
            }
        }

        val intervalButton = Button(this).apply {
            text = "Auto refresh: " +
                    RssManager.intervalLabel(RssManager.loadIntervalMinutes(this@MainActivity))

            setOnClickListener {
                val options = RssManager.INTERVAL_OPTIONS
                val labels = options.map { RssManager.intervalLabel(it) }.toTypedArray()

                val currentIndex = options
                    .indexOf(RssManager.loadIntervalMinutes(this@MainActivity))
                    .let { if (it < 0) 0 else it }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Refresh feeds")
                    .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                        val minutes = options[which]

                        RssManager.saveIntervalMinutes(this@MainActivity, minutes)
                        AppLog.info(
                            "RSS: auto refresh set to ${RssManager.intervalLabel(minutes)}"
                        )

                        text = "Auto refresh: ${RssManager.intervalLabel(minutes)}"
                        updateAutoRefreshText()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        TabIcons.apply(addFeedButton, "addfeed", 18, 6)
        TabIcons.apply(refreshButton, "refresh", 18, 6)
        TabIcons.apply(intervalButton, "clock", 18, 6)

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                stopRssUiRefresh()
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(autoRefreshText)
        root.addView(horizontalScrollFor(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(addFeedButton)
                addView(refreshButton)
                addView(intervalButton)
            }
        ))
        root.addView(feedsLayout)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)

        refreshUi()
        updateAutoRefreshText()

        // Redraw when a background refresh has fetched something new.
        isRssScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isRssScreenActive) {
                    return
                }

                try {
                    if (currentSignature() != shownSignature) {
                        refreshUi()
                    }

                    updateAutoRefreshText()
                } catch (_: Throwable) {
                    // keep the screen alive
                }

                handler.postDelayed(this, 5000L)
            }
        }

        rssUiRefreshRunnable = runnable
        handler.postDelayed(runnable, 5000L)

        // Opening the screen refreshes feeds that have not been fetched lately.
        val last = RssManager.lastRefreshMillis(this)

        if (
            loadRssFeeds().isNotEmpty() &&
            (last == 0L || System.currentTimeMillis() - last > 2 * 60_000L)
        ) {
            startRefresh(false)
        }
    }

    // One feed in its own screen: all its items, each with an Add button,
    // and the buttons for this feed (Refresh, Add all, Auto-download, Remove).
    private fun showRssFeedScreen(feed: String) {
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        val title = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        val urlText = TextView(this).apply {
            text = feed
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 2, 0, 4)
        }

        val hintText = TextView(this).apply {
            text = "Tip: press and hold an item to open it on the torrent website."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 8)
        }

        val statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 4, 0, 8)
        }

        val itemsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Every Add button gets the same width (88 dp), so they line up.
        val addButtonWidth = (88 * resources.displayMetrics.density).toInt()

        var busy = false
        var shownStamp = -1L

        // (the buttons and the list need each other)
        var redraw: () -> Unit = {}

        fun itemLabel(item: RssManager.RssItem, added: Boolean): String {
            val meta = mutableListOf<String>()

            if (item.sizeText.isNotBlank()) {
                meta.add(item.sizeText)
            }

            if (added) {
                meta.add("✔ Added")
            }

            return if (meta.isEmpty()) {
                item.title
            } else {
                item.title + "\n" + meta.joinToString("  •  ")
            }
        }

        fun idleStatus(count: Int): String {
            val last = RssManager.lastFeedRefreshMillis(this, feed)

            val updated = if (last == 0L) {
                "not refreshed yet"
            } else {
                "updated " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    .format(Date(last))
            }

            val auto = if (RssManager.isAutoDownload(this, feed)) "On" else "Off"

            return "$count item(s)  •  Auto-download: $auto  •  $updated"
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
        }

        val addAllButton = Button(this).apply {
            text = "Add all"
        }

        val autoDownloadButton = Button(this).apply {
            text = "Auto-download: Off"
        }

        val removeFeedButton = Button(this).apply {
            text = "Remove Feed"
        }

        fun startRefresh(manual: Boolean) {
            if (RssManager.isRefreshing()) {
                statusText.text = "A refresh is already running..."
                return
            }

            busy = true
            statusText.text = "Refreshing..."

            Thread {
                val result = try {
                    RssManager.refreshFeed(applicationContext, feed, manual)
                } catch (_: Throwable) {
                    null
                }

                runOnUiThread {
                    busy = false
                    redraw()

                    statusText.text = when {
                        result == null -> "Refresh could not run right now."
                        result.feedsFailed > 0 ->
                            "Could not refresh this feed. See the Execution Log."
                        result.newItems > 0 ->
                            "Refreshed: ${result.newItems} new item(s)."
                        else -> "Refreshed, no new items."
                    }
                }
            }.start()
        }

        fun refreshUi() {
            val keepScroll = outerScroll.scrollY

            itemsLayout.removeAllViews()

            val items = RssManager.loadItems(this, feed)
            val added = RssManager.addedKeys(this, feed)
            val autoOn = RssManager.isAutoDownload(this, feed)

            title.text = RssManager.feedTitle(this, feed)

            if (!busy) {
                statusText.text = idleStatus(items.size)
            }

            addAllButton.text = "Add all (${items.size})"
            addAllButton.isEnabled = items.isNotEmpty()
            autoDownloadButton.text = if (autoOn) "Auto-download: On" else "Auto-download: Off"

            if (items.isEmpty()) {
                val emptyText = TextView(this).apply {
                    text = "No items yet. Tap Refresh."
                    textSize = 14f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, 12, 0, 12)
                }

                itemsLayout.addView(emptyText)
            }

            // Every item of the feed gets its own Add button.
            for (item in items) {
                val isAdded = RssManager.isAdded(added, item)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(12, 10, 12, 10)
                    background = roundedCardBackground()

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8
                    }
                }

                // The title takes the space that is left and wraps.
                val label = TextView(this).apply {
                    text = itemLabel(item, isAdded)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 8, 0)

                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val addButton = Button(this).apply {
                    text = if (isAdded) "Added" else "Add"

                    layoutParams = LinearLayout.LayoutParams(
                        addButtonWidth,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    setOnClickListener {
                        isEnabled = false
                        text = "..."

                        // Resolves the item (magnet link, or downloads the
                        // .torrent file) on a background thread first, then
                        // hands it to the same Add Magnet/Add Torrent File
                        // screens used everywhere else in the app, so the
                        // user picks files/folder before anything is added -
                        // it is only actually added once they commit there,
                        // not the moment this button is tapped.
                        Thread {
                            var resolved: RssManager.ResolvedItem? = null
                            var errorReason: String? = null

                            try {
                                resolved = RssManager.resolveItemForSelection(
                                    this@MainActivity,
                                    item
                                )
                            } catch (e: Throwable) {
                                errorReason = e.message ?: e.javaClass.simpleName
                            }

                            runOnUiThread {
                                isEnabled = true

                                val magnet = resolved?.magnet
                                val torrentPath = resolved?.torrentPath

                                when {
                                    magnet != null -> {
                                        text = "Add"
                                        showMagnetMetadataScreen(magnet) {
                                            RssManager.markItemAdded(this@MainActivity, feed, item)
                                        }
                                    }
                                    torrentPath != null -> {
                                        text = "Add"
                                        showTorrentFileSelection(torrentPath) {
                                            RssManager.markItemAdded(this@MainActivity, feed, item)
                                        }
                                    }
                                    else -> {
                                        text = "Retry"
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Could not add: ${errorReason ?: "unknown error"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }.start()
                    }
                }

                // press and hold: open the item on the torrent website
                row.setOnLongClickListener {
                    showRssItemMenu(item)
                    true
                }

                label.setOnLongClickListener {
                    showRssItemMenu(item)
                    true
                }

                row.addView(label)
                row.addView(addButton)
                itemsLayout.addView(row)
            }

            shownStamp = RssManager.lastFeedRefreshMillis(this, feed)

            // keep the place in the list when it is redrawn
            outerScroll.post {
                outerScroll.scrollTo(0, keepScroll)
            }
        }

        redraw = { refreshUi() }

        refreshButton.setOnClickListener {
            startRefresh(true)
        }

        addAllButton.setOnClickListener {
            confirmAddAllFromFeed(
                feed,
                RssManager.loadItems(this, feed),
                statusText
            ) {
                refreshUi()
            }
        }

        autoDownloadButton.setOnClickListener {
            toggleAutoDownload(feed, RssManager.isAutoDownload(this, feed)) {
                refreshUi()
            }
        }

        removeFeedButton.setOnClickListener {
            // Ask first, a removed feed and its saved items are gone.
            AlertDialog.Builder(this)
                .setTitle("Remove RSS feed?")
                .setMessage("This feed and its saved items will be removed:\n\n$feed")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    removeRssFeed(feed)
                    RssManager.removeFeedData(this, feed)
                    AppLog.info("RSS: feed removed: $feed")
                    Toast.makeText(this, "Feed removed", Toast.LENGTH_SHORT).show()

                    stopRssUiRefresh()
                    showRssScreen()
                }
                .show()
        }

        val backButton = Button(this).apply {
            text = "Back to feeds"
            setOnClickListener {
                stopRssUiRefresh()
                showRssScreen()
            }
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshButton)
            addView(addAllButton)
            addView(autoDownloadButton)
            addView(removeFeedButton)
        }

        root.addView(title)
        root.addView(urlText)
        root.addView(hintText)
        root.addView(statusText)
        root.addView(horizontalScrollFor(actionRow))
        root.addView(itemsLayout)
        root.addView(backButton)

        setContentView(outerScroll)

        refreshUi()

        // Redraw when a background refresh has fetched something new.
        isRssScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isRssScreenActive) {
                    return
                }

                try {
                    val stamp = RssManager.lastFeedRefreshMillis(this@MainActivity, feed)

                    if (stamp != shownStamp && !busy) {
                        refreshUi()
                    }
                } catch (_: Throwable) {
                    // keep the screen alive
                }

                handler.postDelayed(this, 5000L)
            }
        }

        rssUiRefreshRunnable = runnable
        handler.postDelayed(runnable, 5000L)

        // Opening the feed refreshes it when it is empty or was not read lately.
        val last = RssManager.lastFeedRefreshMillis(this, feed)

        if (
            RssManager.loadItems(this, feed).isEmpty() ||
            System.currentTimeMillis() - last > 2 * 60_000L
        ) {
            startRefresh(false)
        }
    }

    // Press and hold on a feed item: open its page on the torrent website,
    // or copy its link.
    private fun showRssItemMenu(item: RssManager.RssItem) {
        val webPage = RssManager.itemWebPage(item)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (webPage.isNotBlank()) {
            labels.add("Open on the torrent website")
            actions.add { openWebPage(webPage) }
        }

        val copyText = item.link.ifBlank { webPage }

        if (copyText.isNotBlank()) {
            labels.add(
                if (copyText.startsWith("magnet:", ignoreCase = true)) {
                    "Copy magnet link"
                } else {
                    "Copy link"
                }
            )

            actions.add {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(ClipData.newPlainText("TorrentOr link", copyText))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, "This item has no web page or link", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which]()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Opens a web address in the browser. Only http and https are allowed,
    // because the address comes from a feed and is not to be trusted.
    private fun openWebPage(url: String) {
        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            null
        }

        val scheme = uri?.scheme?.lowercase()

        if (uri == null || (scheme != "http" && scheme != "https")) {
            Toast.makeText(this, "This is not a web address", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Throwable) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRssUiRefresh() {
        isRssScreenActive = false

        rssUiRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        rssUiRefreshRunnable = null
    }

    private fun showAddFeedDialog(onAdded: () -> Unit) {
        val input = EditText(this).apply {
            hint = "https://example.com/feed.xml"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
        }

        AlertDialog.Builder(this)
            .setTitle("Add RSS Feed")
            .setMessage("Enter the feed URL. Every item of the feed gets an Add button.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()

                if (url.isEmpty()) {
                    Toast.makeText(this, "Enter a feed URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                addRssFeed(url)
                AppLog.info("RSS: feed added: $url")
                Toast.makeText(this, "Feed added", Toast.LENGTH_SHORT).show()
                onAdded()
            }
            .show()
    }

    private fun loadRssFeeds(): List<String> {
        return RssManager.loadFeeds(this)
    }

    private fun saveRssFeeds(feeds: List<String>) {
        RssManager.saveFeeds(this, feeds)
    }

    private fun addRssFeed(url: String) {
        val feeds = loadRssFeeds().toMutableList()

        if (!feeds.contains(url)) {
            feeds.add(url)
        }

        saveRssFeeds(feeds)
    }

    private fun removeRssFeed(url: String) {
        saveRssFeeds(loadRssFeeds().filterNot { it == url })
    }

    // "Add all": every item currently listed for the feed, after a question.
    private fun confirmAddAllFromFeed(
        feed: String,
        items: List<RssManager.RssItem>,
        statusText: TextView,
        onFinished: () -> Unit = {}
    ) {
        AlertDialog.Builder(this)
            .setTitle("Add all items?")
            .setMessage(
                "Add all ${items.size} item(s) of this feed to your downloads?\n\n" +
                        "Items that are already in your list are skipped.\n\n$feed"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add all") { _, _ ->
                statusText.text = "Adding ${items.size} item(s)..."
                AppLog.info("RSS: adding all ${items.size} item(s) of $feed")

                Thread {
                    var added = 0
                    var failed = 0

                    for ((position, item) in items.withIndex()) {
                        val error = RssManager.addItem(applicationContext, feed, item)

                        if (error == null) {
                            added++
                        } else {
                            failed++
                        }

                        if (position % 5 == 4) {
                            runOnUiThread {
                                statusText.text =
                                    "Adding items... ${position + 1}/${items.size}"
                            }
                        }
                    }

                    AppLog.info("RSS: add all finished, $added added, $failed failed")

                    runOnUiThread {
                        statusText.text = if (failed == 0) {
                            "Added $added item(s)."
                        } else {
                            "Added $added item(s), $failed failed. See the Execution Log."
                        }

                        onFinished()
                    }
                }.start()
            }
            .show()
    }

    // Auto-download on/off for one feed. Turning it on asks first, because
    // from then on the app adds torrents by itself.
    private fun toggleAutoDownload(feed: String, currentlyOn: Boolean, onDone: () -> Unit) {
        if (currentlyOn) {
            RssManager.setAutoDownload(this, feed, false)
            AppLog.info("RSS: auto-download turned off for $feed")
            Toast.makeText(this, "Auto-download turned off", Toast.LENGTH_SHORT).show()
            onDone()
            return
        }

        val refreshNote = if (RssManager.loadIntervalMinutes(this) <= 0) {
            "\n\nAuto refresh is Off, so new items are only found when you tap Refresh now."
        } else {
            ""
        }

        AlertDialog.Builder(this)
            .setTitle("Auto-download new items?")
            .setMessage(
                "Every new item that appears in this feed from now on is added to your " +
                        "downloads automatically.\n\n" +
                        "Items already in the feed are not added (use Add all for those)." +
                        refreshNote +
                        "\n\n$feed"
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Turn on") { _, _ ->
                RssManager.setAutoDownload(this, feed, true)
                AppLog.info("RSS: auto-download turned on for $feed")
                Toast.makeText(this, "Auto-download turned on", Toast.LENGTH_SHORT).show()
                onDone()
            }
            .show()
    }

    // Execution Log, in the style of qBittorrent: timestamp, level (INFO /
    // WARNING / ERROR), colours, auto-scroll, Clear Log and Copy Log. The
    // entries are stored by AppLog, so they are still here after the app is
    // closed and opened again.
    private fun showExecutionLogScreen() {
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        AppLog.init(applicationContext)

        // Bug fix: the INFO / WARNING / ERROR / Auto-scroll ticks used to
        // start out all ticked every time this screen was opened, so
        // anything unticked was forgotten as soon as the screen (or the
        // app) was closed. They are now saved the moment they change and
        // restored here - a box you unticked stays unticked.
        val logPrefs = getSharedPreferences("execution_log_settings", MODE_PRIVATE)

        val shownLevels = mutableSetOf<AppLog.Level>()

        for (level in listOf(AppLog.Level.INFO, AppLog.Level.WARNING, AppLog.Level.ERROR)) {
            if (logPrefs.getBoolean("show_" + level.name.lowercase(), true)) {
                shownLevels.add(level)
            }
        }

        var autoScroll = logPrefs.getBoolean("auto_scroll", true)
        var lastShownId = 0L

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Execution Log"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        val logText = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            // Long-press to select and drag handles to extend the
            // selection, then use the system "Copy" action - lets you
            // grab one line (or a few) instead of the whole log.
            setTextIsSelectable(true)
        }

        val logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101418"))
            addView(logText)
        }

        val autoScrollBox = CheckBox(this).apply {
            text = "Auto-scroll"
            setTextColor(Color.WHITE)
            isChecked = autoScroll
        }

        fun levelColor(level: AppLog.Level): Int {
            return when (level) {
                AppLog.Level.INFO -> Color.parseColor("#D0D8E0")
                AppLog.Level.WARNING -> Color.parseColor("#FFB74D")
                AppLog.Level.ERROR -> Color.parseColor("#FF5252")
            }
        }

        fun appendEntries(entries: List<AppLog.Entry>) {
            if (entries.isEmpty()) return

            val builder = android.text.SpannableStringBuilder()

            for (entry in entries) {
                val start = builder.length
                builder.append(AppLog.formatLine(entry))

                builder.setSpan(
                    android.text.style.ForegroundColorSpan(levelColor(entry.level)),
                    start,
                    builder.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                builder.append("\n")
            }

            logText.append(builder)
        }

        // Draws the whole log again (first draw, filter change, Clear Log).
        fun rebuild() {
            val snapshot = AppLog.snapshot()

            lastShownId = snapshot.lastOrNull()?.id ?: 0L

            logText.text = ""
            appendEntries(snapshot.filter { shownLevels.contains(it.level) })
        }

        // Adds the entries written since the last check.
        fun poll() {
            AppLog.pullEngineLog()

            val fresh = AppLog.snapshot().filter { it.id > lastShownId }

            if (fresh.isEmpty()) return

            lastShownId = fresh.last().id

            // a screen left open for hours keeps growing, start over when huge
            if (logText.text.length > 400_000) {
                rebuild()
                return
            }

            appendEntries(fresh.filter { shownLevels.contains(it.level) })
        }

        // Auto-scroll: after every layout jump to the newest line.
        logScroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (autoScroll) {
                logScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        autoScrollBox.setOnCheckedChangeListener { _, checked ->
            autoScroll = checked
            logPrefs.edit().putBoolean("auto_scroll", checked).apply()

            if (checked) {
                logScroll.post {
                    logScroll.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }

        fun makeLevelBox(label: String, level: AppLog.Level): CheckBox {
            return CheckBox(this).apply {
                text = label
                setTextColor(levelColor(level))
                isChecked = shownLevels.contains(level)

                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        shownLevels.add(level)
                    } else {
                        shownLevels.remove(level)
                    }

                    logPrefs.edit()
                        .putBoolean("show_" + level.name.lowercase(), checked)
                        .apply()

                    rebuild()
                }
            }
        }

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        filterRow.addView(makeLevelBox("INFO", AppLog.Level.INFO))
        filterRow.addView(makeLevelBox("WARNING", AppLog.Level.WARNING))
        filterRow.addView(makeLevelBox("ERROR", AppLog.Level.ERROR))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun rowButton(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener { onClick() }
            }
        }

        buttonRow.addView(
            rowButton("Clear Log") {
                AppLog.clear()
                AppLog.info("Execution log cleared")
                rebuild()
            }
        )

        buttonRow.addView(
            rowButton("Copy Log") {
                val lines = AppLog.snapshot()
                    .filter { shownLevels.contains(it.level) }
                    .map { AppLog.formatLine(it) }

                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "TorrentOr execution log",
                        lines.joinToString("\n")
                    )
                )

                Toast.makeText(
                    this,
                    "Copied ${lines.size} log line(s)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        buttonRow.addView(
            rowButton("Back") {
                stopLogUiRefresh()
                showMainScreen()
            }
        )

        root.addView(title)
        root.addView(filterRow)
        root.addView(autoScrollBox)
        root.addView(buttonRow)

        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        AppLog.pullEngineLog()
        rebuild()

        // New lines appear while the screen is open.
        isLogScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isLogScreenActive) {
                    return
                }

                try {
                    poll()
                } catch (_: Throwable) {
                    // keep the screen alive
                }

                handler.postDelayed(this, 1000L)
            }
        }

        logUiRefreshRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopLogUiRefresh() {
        isLogScreenActive = false

        logUiRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        logUiRefreshRunnable = null
    }

    // Remembers the global DHT / PEX / LSD switch so it survives a restart
    // (TorrentService sends an "off" choice back to the engine on start).
    private fun saveNetworkFeature(name: String, enabled: Boolean) {
        getSharedPreferences("network_features", MODE_PRIVATE)
            .edit()
            .putBoolean(name, enabled)
            .apply()
    }

    private fun showNetworkFeaturesScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()

        TorrentNative.startSession(getGlobalSaveFolder())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Network Features"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val statusText = TextView(this).apply {
            text = buildNetworkFeaturesColoredText()
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }

        // Colours the Enable / Disable buttons from the saved on/off choice
        // (set once the buttons exist, further down).
        var updateFeatureColors: () -> Unit = {}

        fun refreshStatus() {
            statusText.text = buildNetworkFeaturesColoredText()
            updateFeatureColors()
        }

        startNetworkFeaturesAutoRefresh(statusText)

        val dhtRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enableDht = Button(this).apply {
            text = "Enable DHT"
            setOnClickListener {
                saveNetworkFeature("dht", true)
                TorrentNative.setDhtEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "DHT enabled", Toast.LENGTH_SHORT).show()
            }
        }

        val disableDht = Button(this).apply {
            text = "Disable DHT"
            setOnClickListener {
                saveNetworkFeature("dht", false)
                TorrentNative.setDhtEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "DHT disabled", Toast.LENGTH_SHORT).show()
            }
        }

        TabIcons.apply(enableDht, "dht")
        TabIcons.apply(disableDht, "poweroff")

        dhtRow.addView(enableDht)
        dhtRow.addView(disableDht)

        val pexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enablePex = Button(this).apply {
            text = "Enable PEX"
            setOnClickListener {
                saveNetworkFeature("pex", true)
                TorrentNative.setPexEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "PEX enabled", Toast.LENGTH_SHORT).show()
            }
        }

        val disablePex = Button(this).apply {
            text = "Disable PEX"
            setOnClickListener {
                saveNetworkFeature("pex", false)
                TorrentNative.setPexEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "PEX disabled", Toast.LENGTH_SHORT).show()
            }
        }

        TabIcons.apply(enablePex, "pex")
        TabIcons.apply(disablePex, "poweroff")

        pexRow.addView(enablePex)
        pexRow.addView(disablePex)

        val lsdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enableLsd = Button(this).apply {
            text = "Enable LSD"
            setOnClickListener {
                saveNetworkFeature("lsd", true)
                TorrentNative.setLsdEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "LSD enabled", Toast.LENGTH_SHORT).show()
            }
        }

        val disableLsd = Button(this).apply {
            text = "Disable LSD"
            setOnClickListener {
                saveNetworkFeature("lsd", false)
                TorrentNative.setLsdEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "LSD disabled", Toast.LENGTH_SHORT).show()
            }
        }

        TabIcons.apply(enableLsd, "lsd")
        TabIcons.apply(disableLsd, "poweroff")

        lsdRow.addView(enableLsd)
        lsdRow.addView(disableLsd)

        // Contact every tracker at once instead of only the first working tier.
        val announceAllRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enableAnnounceAll = Button(this).apply {
            text = "Announce to all trackers: On"
            setOnClickListener {
                saveNetworkFeature("announce_all", true)
                TorrentNative.setAnnounceToAllEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "Announcing to all trackers", Toast.LENGTH_SHORT).show()
            }
        }

        val disableAnnounceAll = Button(this).apply {
            text = "Off"
            setOnClickListener {
                saveNetworkFeature("announce_all", false)
                TorrentNative.setAnnounceToAllEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "Announcing to the first working tier only", Toast.LENGTH_SHORT).show()
            }
        }

        TabIcons.apply(enableAnnounceAll, "announce")
        TabIcons.apply(disableAnnounceAll, "poweroff")

        announceAllRow.addView(enableAnnounceAll)
        announceAllRow.addView(disableAnnounceAll)

        // DHT / PEX / LSD / Announce-to-all buttons: when a feature is ON its Enable button's
        // whole text turns green, when it is OFF its Disable button's whole
        // text turns red; the other button keeps the normal theme colour.
        val featureButtons = listOf(
            Triple("dht", enableDht, disableDht),
            Triple("pex", enablePex, disablePex),
            Triple("lsd", enableLsd, disableLsd),
            Triple("announce_all", enableAnnounceAll, disableAnnounceAll)
        )
        val normalButtonText = enableDht.textColors
        updateFeatureColors = {
            val prefs = getSharedPreferences("network_features", MODE_PRIVATE)
            for ((key, enableBtn, disableBtn) in featureButtons) {
                val on = prefs.getBoolean(key, true)
                if (on) {
                    enableBtn.setTextColor(Color.parseColor("#00E676"))
                    disableBtn.setTextColor(normalButtonText)
                } else {
                    enableBtn.setTextColor(normalButtonText)
                    disableBtn.setTextColor(Color.parseColor("#FF1744"))
                }
            }
        }
        updateFeatureColors()

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                refreshStatus()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                stopNetworkFeaturesAutoRefresh()
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(dhtRow)
        root.addView(pexRow)
        root.addView(lsdRow)
        root.addView(announceAllRow)
        root.addView(buildEncryptionSection())
        root.addView(refreshButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    // Protocol encryption (like qBittorrent's "Encryption mode"), shown on the
    // Network Features screen. It hides BitTorrent traffic from ISPs that
    // throttle it; it does not hide your IP address or make you anonymous.
    private fun buildEncryptionSection(): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 8)
        }

        val heading = TextView(this).apply {
            text = "Protocol encryption"
            textSize = 18f
            setTextColor(Color.WHITE)
        }

        TabIcons.apply(heading, "encryption", 26, 10, true)

        val info = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 8)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val modeButtons = mutableListOf<Button>()
        var normalModeTextColors: android.content.res.ColorStateList? = null

        fun describe(mode: Int): String {
            return when (mode) {
                1 -> "Require: only encrypted peer connections are used. Peers without encryption are refused, so you may find fewer peers."
                2 -> "Disable: peer connections are never encrypted."
                else -> "Allow (default): connections are encrypted when the other peer supports it, otherwise they stay plain."
            }
        }

        fun refreshInfo() {
            val mode = EngineExtras.loadEncryptionMode(this)

            info.text =
                "Current mode: ${EngineExtras.encryptionLabel(mode)}\n" +
                        describe(mode) +
                        "\nA change applies to new connections."

            // The chosen mode's whole text is coloured: Allow green,
            // Require amber, Disable red. The other two keep the theme colour.
            val selectedColors = listOf("#00E676", "#FFB300", "#FF1744")
            for ((index, button) in modeButtons.withIndex()) {
                if (normalModeTextColors == null) normalModeTextColors = button.textColors
                button.text = if (index == mode) {
                    "[${EngineExtras.encryptionLabel(index)}]"
                } else {
                    EngineExtras.encryptionLabel(index)
                }
                if (index == mode) {
                    button.setTextColor(Color.parseColor(selectedColors[index.coerceIn(0, 2)]))
                } else if (index == 0) {
                    // Allow when not chosen: back to the normal theme colour
                    normalModeTextColors?.let { button.setTextColor(it) }
                } else if (index == 2) {
                    // Disable when not chosen: a deeper red, like its icon
                    button.setTextColor(Color.parseColor("#E53935"))
                } else {
                    // Require when not chosen: a deeper blue, like its icon
                    button.setTextColor(Color.parseColor("#2D8CFF"))
                }
            }
        }

        for (mode in 0..2) {
            val button = Button(this).apply {
                text = EngineExtras.encryptionLabel(mode)

                setOnClickListener {
                    EngineExtras.saveEncryptionMode(this@MainActivity, mode)

                    try {
                        EngineExtras.setEncryptionMode(mode)
                    } catch (e: Throwable) {
                        AppLog.error(
                            "Could not change the encryption mode: " +
                                    e.javaClass.simpleName + " " + (e.message ?: "")
                        )
                    }

                    refreshInfo()

                    Toast.makeText(
                        this@MainActivity,
                        "Encryption: ${EngineExtras.encryptionLabel(mode)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // Allow = green check, Require = checklist, Disable = red X
            TabIcons.apply(
                button,
                when (mode) {
                    0 -> "check"
                    1 -> "checklist"
                    else -> "selectnone"
                },
                18,
                6
            )

            modeButtons.add(button)
            row.addView(button)
        }

        section.addView(heading)
        section.addView(info)
        section.addView(row)

        refreshInfo()

        return section
    }

    private fun buildNetworkFeaturesText(): String {
        TorrentNative.startSession(getGlobalSaveFolder())

        val networkStatus = try {
            TorrentNative.getNetworkFeaturesStatus()
        } catch (e: Throwable) {
            buildString {
                append("Network Features\n\n")
                append("DHT: Unknown\n")
                append("DHT nodes: --\n")
                append("DHT torrents: --\n\n")
                append("PEX: Unknown\n")
                append("PEX peers: --\n\n")
                append("LSD: Unknown")
            }
        }

        return StringBuilder()
            .append(networkStatus)
            .append("\n\n")
            .append("DHT helps find peers without trackers.\n")
            .append("PEX lets peers exchange more peer addresses.\n")
            .append("LSD finds peers on the same local network.")
            .toString()
    }

    private fun buildNetworkFeaturesColoredText(): android.text.SpannableStringBuilder {
        TorrentNative.startSession(getGlobalSaveFolder())

        val networkStatus = try {
            buildString {
                append("Network Features\n\n")
                append(TorrentNative.getDhtStatus())
                append("\n\n")
                append(TorrentNative.getPexStatus())
                append("\n\n")
                append(TorrentNative.getLsdStatus())
                append("\n\n")
                append(TorrentNative.getAnnounceToAllStatus())
            }
        } catch (e: Throwable) {
            buildString {
                append("Network Features\n\n")
                append("DHT: Unknown\n")
                append("DHT nodes: --\n")
                append("DHT torrents: --\n\n")
                append("PEX: Unknown\n")
                append("PEX peers: --\n\n")
                append("LSD: Unknown")
            }
        }

        val builder = android.text.SpannableStringBuilder()
        val lines = networkStatus.lines()

        for (line in lines) {
            if (line.isBlank()) {
                builder.append("\n")
            } else {
                appendColoredFeatureLine(builder, line)
            }
        }

        builder.append("\n")
        builder.append("Green = enabled / active / working\n")
        builder.append("Red = disabled / inactive / failed\n")
        builder.append("Yellow = waiting / unknown / zero\n\n")
        builder.append("DHT nodes shows how many DHT contacts libtorrent knows.\n")
        builder.append("DHT torrents shows if DHT is active for torrents.\n")
        builder.append("PEX peers shows peer exchange availability/discovery.\n")
        builder.append("LSD shows local network discovery status.\n")
        builder.append("Announce to all trackers contacts every tracker at once, not just the first working tier.")

        return builder
    }

    private fun appendColoredFeatureLine(
        builder: android.text.SpannableStringBuilder,
        line: String
    ) {
        val start = builder.length
        builder.append(line)
        val end = builder.length

        val lower = line.lowercase()

        val color = when {
            lower.contains("disabled") ||
                    lower.contains("inactive") ||
                    lower.contains("failed") ||
                    lower.contains("off") -> {
                Color.parseColor("#FF5252")
            }

            lower.contains("enabled") ||
                    lower.contains("active") ||
                    lower.contains("success") ||
                    lower.contains("working") ||
                    lower.contains("peers: enabled") ||
                    lower.contains("lsd: enabled") -> {
                Color.parseColor("#00E676")
            }

            Regex("nodes:\\s*[1-9]\\d*").containsMatchIn(lower) ||
                    Regex("peers.*:\\s*[1-9]\\d*").containsMatchIn(lower) -> {
                Color.parseColor("#00E676")
            }

            lower.contains("unknown") ||
                    lower.contains("waiting") ||
                    lower.contains("--") ||
                    lower.contains(": 0") -> {
                Color.parseColor("#FFD54F")
            }

            else -> {
                Color.WHITE
            }
        }

        builder.setSpan(
            android.text.style.ForegroundColorSpan(color),
            start,
            end,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        builder.append("\n")
    }

    private fun startNetworkFeaturesAutoRefresh(statusText: TextView) {
        stopNetworkFeaturesAutoRefresh()

        isNetworkFeaturesScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isNetworkFeaturesScreenActive) {
                    return
                }

                try {
                    statusText.text = buildNetworkFeaturesColoredText()
                } catch (_: Throwable) {
                    // Keep the screen alive even if native status is temporarily unavailable.
                }

                handler.postDelayed(this, 1000L)
            }
        }

        networkFeaturesRefreshRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopNetworkFeaturesAutoRefresh() {
        isNetworkFeaturesScreenActive = false

        networkFeaturesRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        networkFeaturesRefreshRunnable = null
    }

    // ------------------------------------------------- Feature 4: Connection

    // Feature 3: Sequential Download & First/Last Piece Priority - same
    // prefs files TorrentService reads from on restore, keyed the same
    // way (normalizeHashForDateKey matches TorrentService's own
    // normalizeHashForKey exactly).
    private fun sequentialDownloadPrefs() =
        getSharedPreferences("sequential_download", MODE_PRIVATE)

    private fun firstLastPriorityPrefs() =
        getSharedPreferences("firstlast_priority", MODE_PRIVATE)

    // Bug fix: backs the torrent list's Filter and Sort choices (see
    // activeFilter/sortMode above), so they survive the app being closed
    // and reopened instead of silently resetting to "All"/"None".
    private fun torrentListViewPrefs() =
        getSharedPreferences("torrent_list_view", MODE_PRIVATE)

    // Feature 1: Speed Control (global limits + per-torrent override,
    // no scheduling) - same prefs files TorrentService reads from.
    // Stored in bytes/second (what the native calls take); the UI
    // itself works in KB/s, converting at the boundary.
    private fun globalSpeedLimitPrefs() =
        getSharedPreferences("global_speed_limits", MODE_PRIVATE)

    private fun torrentSpeedLimitPrefs() =
        getSharedPreferences("torrent_speed_limits", MODE_PRIVATE)

    // Low Disk Space Warning - same prefs file TorrentService reads from
    // for its own periodic check. Warn-only: this never blocks an add.
    private fun storageSettingsPrefs() =
        getSharedPreferences("storage_settings", MODE_PRIVATE)

    private fun getLowSpaceThresholdMb(): Int {
        return storageSettingsPrefs().getInt("low_space_threshold_mb", 500)
    }

    private fun getLowSpaceThresholdBytes(): Long {
        return getLowSpaceThresholdMb().toLong() * 1024L * 1024L
    }

    // ------------------------------------------------- Feature 5: Custom Save Folder
    // Same two prefs files TorrentService reads from: a global default
    // folder (used by any torrent with no override) and a per-torrent
    // override keyed by info-hash, same key format as the other
    // per-torrent prefs above (normalizeHashForDateKey).

    private fun saveFolderPrefs() =
        getSharedPreferences("save_folder_settings", MODE_PRIVATE)

    private fun getGlobalSaveFolder(): String =
        saveFolderPrefs().getString("global_save_path", savePath) ?: savePath

    private fun setGlobalSaveFolder(path: String) {
        saveFolderPrefs().edit().putString("global_save_path", path).apply()
    }

    private fun torrentSaveFolderPrefs() =
        getSharedPreferences("torrent_save_folders", MODE_PRIVATE)

    private fun getSavedSaveFolderForHash(hash: String): String? {
        if (hash.isBlank()) return null
        return torrentSaveFolderPrefs().getString(normalizeHashForDateKey(hash), null)
    }

    private fun saveSaveFolderForHash(hash: String, path: String) {
        if (hash.isBlank() || path.isBlank()) return
        torrentSaveFolderPrefs().edit()
            .putString(normalizeHashForDateKey(hash), path)
            .apply()
    }

    // Gates a "Download..." button's action behind a low-disk-space
    // check. If "Ignore low storage space" is checked, or there's
    // nothing to warn about (including a storage read error - never
    // block an add over that), onContinue runs immediately with no
    // dialog. Otherwise shows a Continue/Cancel dialog; Cancel simply
    // leaves the user on the same screen having done nothing.
    //
    // targetPath defaults to the global save folder, but Feature 5 lets a
    // screen pass the folder the torrent will actually land in (a custom
    // per-torrent folder), so the free-space check reflects where the
    // files are really going.
    private fun confirmLowDiskSpaceThenRun(
        ignoreLowSpace: Boolean,
        targetPath: String = getGlobalSaveFolder(),
        onContinue: () -> Unit
    ) {
        if (ignoreLowSpace) {
            onContinue()
            return
        }

        val free = try {
            StatFs(targetPath).availableBytes
        } catch (_: Throwable) {
            onContinue()
            return
        }

        if (free >= getLowSpaceThresholdBytes()) {
            onContinue()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Low Disk Space")
            .setMessage(
                "Only ${formatSize(free)} free on this device. " +
                        "This download may fail if space runs out. Continue anyway?"
            )
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------- Feature 5: Custom Save Folder
    // Shared folder browser/creator - used by the global Save Folder
    // screen, all three add-time screens, and the per-torrent "Change"
    // button on the Details screen. Lets the user navigate anywhere on
    // device storage (TorrentOr already holds MANAGE_EXTERNAL_STORAGE) and
    // create new folders; it never deletes, renames or moves anything by
    // itself - the caller decides what onPicked does with the chosen path.
    private fun showFolderPickerDialog(startPath: String, onPicked: (String) -> Unit) {
        var currentDir = File(startPath).let {
            if (it.isDirectory) it else File("/storage/emulated/0")
        }

        // Bug fix: this used to dismiss() the whole AlertDialog and build a
        // brand new one on every single "Up"/subfolder tap. Tearing down and
        // recreating a dialog window that fast crashed the app on some
        // devices. Now there is exactly one dialog - navigation just swaps
        // out its content views in place (pathText + folderListLayout),
        // never touching the dialog window itself.
        val pathText = TextView(this).apply {
            setPadding(0, 0, 0, 16)
            setTextIsSelectable(true)
        }

        val folderListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Feature: Back/Forward folder history, browser-style. "Up" only
        // ever moves one level toward the root, so once you've gone up
        // past a folder you picked, there was no way back to it short of
        // walking all the way back down by hand. These two stacks record
        // where you've been so Back/Forward can jump straight there.
        val backStack = mutableListOf<File>()
        val forwardStack = mutableListOf<File>()

        val backButton = Button(this).apply { text = "<" }
        val forwardButton = Button(this).apply { text = ">" }

        fun refreshContents() {
            try {
                pathText.text = currentDir.absolutePath
                folderListLayout.removeAllViews()

                backButton.isEnabled = backStack.isNotEmpty()
                forwardButton.isEnabled = forwardStack.isNotEmpty()

                val parent = currentDir.parentFile
                if (parent != null) {
                    val upRow = Button(this).apply {
                        text = ".. (Up)"
                        setOnClickListener {
                            backStack.add(currentDir)
                            forwardStack.clear()
                            currentDir = parent
                            refreshContents()
                        }
                    }
                    folderListLayout.addView(upRow)
                }

                val subFolders = try {
                    currentDir.listFiles { f -> f.isDirectory }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }

                if (subFolders.isEmpty()) {
                    val empty = TextView(this).apply {
                        text = "No subfolders here"
                        setPadding(0, 8, 0, 8)
                    }
                    folderListLayout.addView(empty)
                }

                for (folder in subFolders) {
                    val row = Button(this).apply {
                        text = folder.name
                        setOnClickListener {
                            backStack.add(currentDir)
                            forwardStack.clear()
                            currentDir = folder
                            refreshContents()
                        }
                    }
                    folderListLayout.addView(row)
                }
            } catch (e: Throwable) {
                // Belt-and-braces: never let a folder-browsing hiccup take
                // the whole app down, just show what we can.
                Toast.makeText(
                    this,
                    "Could not open that folder: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        backButton.setOnClickListener {
            if (backStack.isNotEmpty()) {
                forwardStack.add(currentDir)
                currentDir = backStack.removeAt(backStack.size - 1)
                refreshContents()
            }
        }

        forwardButton.setOnClickListener {
            if (forwardStack.isNotEmpty()) {
                backStack.add(currentDir)
                currentDir = forwardStack.removeAt(forwardStack.size - 1)
                refreshContents()
            }
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
            addView(backButton)
            addView(forwardButton)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val scroll = ScrollView(this).apply {
            addView(folderListLayout)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                800
            )
        }

        root.addView(navRow)
        root.addView(pathText)
        root.addView(scroll)

        refreshContents()

        // Bug fix: AlertDialog dismisses on any button tap by default,
        // which used to close this whole picker the moment "New Folder"
        // was tapped - the user then had to reopen Change Save Folder and
        // navigate all the way back down to find the folder they just
        // created. Overriding the neutral button's listener after show()
        // (instead of passing one straight to setNeutralButton) keeps this
        // dialog open; Use This Folder / Cancel keep their normal
        // dismiss-on-tap behavior, which is what's wanted for those two.
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose Folder")
            .setView(root)
            .setPositiveButton("Use This Folder") { _, _ ->
                onPicked(currentDir.absolutePath)
            }
            .setNeutralButton("New Folder", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            showCreateFolderDialog(currentDir) { newFolder ->
                // Land inside the folder just created, instead of leaving
                // the user to find it by hand.
                backStack.add(currentDir)
                forwardStack.clear()
                currentDir = newFolder
                refreshContents()
            }
        }
    }

    private fun showCreateFolderDialog(parentDir: File, onCreated: (File) -> Unit) {
        val input = EditText(this).apply {
            hint = "Folder name"
        }

        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()

                if (name.isBlank() || name.contains("/") || name.contains("\\")) {
                    Toast.makeText(this, "Enter a valid folder name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newFolder = File(parentDir, name)

                val created = try {
                    newFolder.exists() || newFolder.mkdirs()
                } catch (_: Throwable) {
                    false
                }

                Toast.makeText(
                    this,
                    if (created) "Folder created" else "Could not create folder",
                    Toast.LENGTH_SHORT
                ).show()

                if (created) {
                    onCreated(newFolder)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Used from the add-time file-selection screens (magnet metadata,
    // magnet file selection, .torrent file selection) where the user
    // can opt in to Sequential Download before the download even
    // starts. Always persists first, then tries the live native call -
    // for the .torrent-file-picker screen specifically, the torrent
    // handle doesn't exist yet at this point (TorrentService creates it
    // only after receiving the Intent), so the live call is expected to
    // no-op there; TorrentService re-applies every saved piece setting
    // itself each time it handles any action, so it catches up either way.
    private fun applySequentialDownloadForHash(hash: String, enabled: Boolean) {
        if (hash.isBlank()) return

        sequentialDownloadPrefs().edit()
            .putBoolean(normalizeHashForDateKey(hash), enabled)
            .apply()

        try {
            TorrentNative.setSequentialDownload(hash, enabled)
        } catch (_: Throwable) {
            // Not fatal - see note above.
        }
    }

    private fun connectionPrefs() =
        getSharedPreferences("connection_settings", MODE_PRIVATE)

    // True when the active network is cellular. UPnP/NAT-PMP port mapping
    // cannot work over mobile data (carrier-grade NAT), so the Connection
    // screen shows that plainly instead of letting a real failure alert
    // read like a generic error loop.
    private fun isOnMobileData(): Boolean {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false

            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (_: Throwable) {
            false
        }
    }

    // Same text as buildConnectionStatusText(), with the trailing
    // "Auto-refresh" line drawn in amber so it stands out from the status.
    private fun buildConnectionStatusStyled(): CharSequence {
        val plain = buildConnectionStatusText()
        val spannable = android.text.SpannableString(plain)
        val idx = plain.lastIndexOf("Auto-refresh:")
        if (idx >= 0) {
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#FFB74D")),
                idx,
                plain.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun buildConnectionStatusText(): String {
        val body = buildConnectionStatusBody()
        return body.trimEnd() + "\n\nAuto-refresh: Every 1 second"
    }

    private fun buildConnectionStatusBody(): String {
        val native = try {
            TorrentNative.getConnectionStatus()
        } catch (e: Throwable) {
            Log.e("TorrentOrLT", "getConnectionStatus() threw", e)
            return "Could not load connection status: " +
                    "${e.javaClass.simpleName}: ${e.message}"
        }

        if (!isOnMobileData()) {
            return native
        }

        // Replace whatever UPnP/NAT-PMP lines came back with a single,
        // honest line - trying to map a port over mobile data just loops
        // on the same failure, which would otherwise look like a bug.
        val lines = native.lines()
        val kept = StringBuilder()
        var skipping = false

        for (line in lines) {
            if (line.startsWith("UPnP:") || line.startsWith("NAT-PMP:")) {
                skipping = true
                continue
            }

            if (skipping && (line.isBlank() || line.startsWith("Note:"))) {
                skipping = false
                continue
            }

            if (skipping) {
                continue
            }

            kept.append(line).append("\n")
        }

        kept.append("\nUPnP / NAT-PMP: not available on mobile data")

        return kept.toString()
    }

    // Bug fix support: pulls the real, currently-listening port back out of
    // buildConnectionStatusText()'s "Listening port: X" line, so the port
    // input field can show the live random port instead of the saved fixed
    // one while random-port mode is on. Returns null if that line isn't
    // there yet (native status briefly unavailable) rather than guessing.
    private fun extractLiveListenPort(statusText: String): Int? {
        val line = statusText.lineSequence()
            .firstOrNull { it.startsWith("Listening port:") }
            ?: return null

        return line.removePrefix("Listening port:").trim().toIntOrNull()
    }

    private fun showConnectionSettingsScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()

        TorrentNative.startSession(getGlobalSaveFolder())

        val prefs = connectionPrefs()

        var utpIn = prefs.getBoolean("utp_in", true)
        var utpOut = prefs.getBoolean("utp_out", true)
        var tcpIn = prefs.getBoolean("tcp_in", true)
        var tcpOut = prefs.getBoolean("tcp_out", true)
        var upnpEnabled = prefs.getBoolean("upnp_enabled", true)
        var natpmpEnabled = prefs.getBoolean("natpmp_enabled", true)
        var randomPortOnStart = prefs.getBoolean("random_port_on_start", false)
        val savedPort = prefs.getInt("listen_port", 6881)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Connection"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val statusText = TextView(this).apply {
            text = buildConnectionStatusStyled()
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 16, 0, 16)
        }

        fun refreshStatus() {
            statusText.text = buildConnectionStatusStyled()
        }

        // Applies and persists every toggle together (never just the one
        // that changed), so the saved state and the live session can never
        // drift apart. Native re-validates the uTP/TCP combination too, but
        // checking here first means an invalid tap never has to silently
        // no-op without explanation.
        fun applyAndPersist(label: String) {
            val utpOn = utpIn || utpOut
            val tcpOn = tcpIn || tcpOut

            if (!utpOn && !tcpOn) {
                Toast.makeText(
                    this,
                    "At least one of uTP or TCP must stay enabled",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            prefs.edit()
                .putBoolean("utp_in", utpIn)
                .putBoolean("utp_out", utpOut)
                .putBoolean("tcp_in", tcpIn)
                .putBoolean("tcp_out", tcpOut)
                .putBoolean("upnp_enabled", upnpEnabled)
                .putBoolean("natpmp_enabled", natpmpEnabled)
                .apply()

            try {
                TorrentNative.applyConnectionSettings(
                    utpIn,
                    utpOut,
                    tcpIn,
                    tcpOut,
                    upnpEnabled,
                    natpmpEnabled,
                    prefs.getInt("listen_port", savedPort)
                )
            } catch (e: Throwable) {
                Log.e("TorrentOrLT", "applyConnectionSettings(...) threw", e)
                Toast.makeText(
                    this,
                    "Could not apply connection settings: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Only the UPnP multicast lock depends on crossing a Service
            // boundary (it's a system resource the Service owns so it keeps
            // working whether or not this screen is open) - everything else
            // above already took effect directly through the native call.
            val intent = Intent(this, TorrentService::class.java)
            intent.putExtra("ACTION", "CONNECTION_SETTINGS_CHANGED")
            intent.putExtra("UPNP_ENABLED", upnpEnabled)
            startTorrentService(intent)

            refreshStatus()
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        fun toggleButton(
            onLabel: String,
            offLabel: String,
            getValue: () -> Boolean,
            setValue: (Boolean) -> Unit
        ): Button {
            lateinit var button: Button

            fun relabel() {
                button.text = if (getValue()) onLabel else offLabel
            }

            button = Button(this).apply {
                setOnClickListener {
                    val newValue = !getValue()
                    setValue(newValue)
                    applyAndPersist(if (newValue) "Enabled" else "Disabled")
                    relabel()
                }
            }

            relabel()
            return button
        }

        val utpInButton = toggleButton(
            "uTP Incoming: On", "uTP Incoming: Off",
            { utpIn }, { utpIn = it }
        )

        val utpOutButton = toggleButton(
            "uTP Outgoing: On", "uTP Outgoing: Off",
            { utpOut }, { utpOut = it }
        )

        val tcpInButton = toggleButton(
            "TCP Incoming: On", "TCP Incoming: Off",
            { tcpIn }, { tcpIn = it }
        )

        val tcpOutButton = toggleButton(
            "TCP Outgoing: On", "TCP Outgoing: Off",
            { tcpOut }, { tcpOut = it }
        )

        val upnpButton = toggleButton(
            "UPnP: On", "UPnP: Off",
            { upnpEnabled }, { upnpEnabled = it }
        )

        val natpmpButton = toggleButton(
            "NAT-PMP: On", "NAT-PMP: Off",
            { natpmpEnabled }, { natpmpEnabled = it }
        )

        val utpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        utpRow.addView(utpInButton)
        utpRow.addView(utpOutButton)

        val tcpRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tcpRow.addView(tcpInButton)
        tcpRow.addView(tcpOutButton)

        val portForwardingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portForwardingRow.addView(upnpButton)
        portForwardingRow.addView(natpmpButton)

        // Download network: which kind of connection TorrentOr may transfer
        // on. Saved in the same prefs file as the other connection settings;
        // the Service watches the network and pauses/resumes the whole
        // session (it never touches any torrent's own paused state).
        val networkLabel = TextView(this).apply {
            text = "Download over"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        }

        val networkNote = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
        }

        fun refreshNetworkNote() {
            val blocked = TorrentService.networkBlockedReason
            networkNote.text =
                "When the phone is on a network that isn't allowed, all " +
                        "downloading and seeding pauses and carries on by " +
                        "itself once an allowed network is available. Your own " +
                        "pause/resume choices are kept.\n\n" +
                        (blocked ?: "Status: transferring normally")
        }

        refreshNetworkNote()

        val networkGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        val networkModes = listOf(
            "both" to "Wi-Fi and mobile data",
            "wifi_only" to "Wi-Fi only",
            "mobile_only" to "Mobile data only"
        )

        val currentNetworkMode = prefs.getString("network_mode", "both") ?: "both"

        for ((modeKey, modeLabel) in networkModes) {
            val radio = RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = modeLabel
                setTextColor(Color.WHITE)
                isChecked = (modeKey == currentNetworkMode)
            }
            radio.tag = modeKey
            networkGroup.addView(radio)
        }

        networkGroup.setOnCheckedChangeListener { group, checkedId ->
            val chosen = group.findViewById<RadioButton>(checkedId)?.tag as? String
                ?: return@setOnCheckedChangeListener

            prefs.edit().putString("network_mode", chosen).apply()

            val intent = Intent(this, TorrentService::class.java)
            intent.putExtra("ACTION", "NETWORK_MODE_CHANGED")
            startTorrentService(intent)

            handler.postDelayed({ refreshNetworkNote() }, 600)
        }

        val portLabel = TextView(this).apply {
            text = "Listening port"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        }

        // Bug fix: this field used to always show the saved FIXED port
        // ("listen_port"), even while random-port mode was on - so after
        // turning random mode on, it kept showing the old fixed value
        // (often 6881) forever, making it look like randomizing the port
        // did nothing, when the engine was actually listening on a
        // different port the whole time (visible only in the status text
        // above). Now, while random mode is on, this field shows that same
        // live port instead - parsed from the same "Listening port: X"
        // line buildConnectionStatusText() already produces - and falls
        // back to a plain note if that line isn't available yet.
        val portInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(
                if (randomPortOnStart) {
                    extractLiveListenPort(statusText.text.toString())?.toString()
                        ?: "Random - see status above"
                } else {
                    savedPort.toString()
                }
            )
            setTextColor(Color.WHITE)
            isEnabled = !randomPortOnStart
        }

        val randomPortCheckbox = CheckBox(this).apply {
            text = "Use a random port every time the app starts"
            setTextColor(Color.WHITE)
            isChecked = randomPortOnStart

            setOnCheckedChangeListener { _, isChecked ->
                randomPortOnStart = isChecked
                portInput.isEnabled = !isChecked

                prefs.edit()
                    .putBoolean("random_port_on_start", isChecked)
                    .apply()

                if (isChecked) {
                    portInput.setText(
                        extractLiveListenPort(statusText.text.toString())?.toString()
                            ?: "Random - see status above"
                    )

                    Toast.makeText(
                        this@MainActivity,
                        "A new random port will be used next time TorrentOr starts",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // Bug fix: turning random mode off used to only save
                    // the flag, so the engine kept listening on the random
                    // port until the next restart. It now switches to the
                    // saved fixed port right away. Read fresh from prefs
                    // (not the screen-open snapshot in savedPort).
                    val fixedPort = prefs.getInt("listen_port", 6881)
                    portInput.setText(fixedPort.toString())
                    applyAndPersist("Fixed port $fixedPort applied")
                }
            }
        }

        val applyPortButton = Button(this).apply {
            text = "Apply Port Now"
            setOnClickListener {
                // Bug fix: while random mode is on, the port field shows
                // the live random port (read-only display), so saving it
                // here turned that random port into the "fixed" one.
                if (randomPortOnStart) {
                    Toast.makeText(
                        this@MainActivity,
                        "Turn off the random port option first",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val typed = portInput.text.toString().trim().toIntOrNull()

                if (typed == null || typed <= 0 || typed > 65535) {
                    Toast.makeText(this@MainActivity, "Enter a port between 1 and 65535", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                prefs.edit().putInt("listen_port", typed).apply()
                applyAndPersist("Listening on port $typed")
            }
        }

        val portNote = TextView(this).apply {
            text = "Changing the port or toggling UPnP/NAT-PMP never pauses, " +
                    "resumes or re-adds any torrent."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 16)
        }

        // Keeps the port field in sync with whatever randomPortOnStart is
        // doing right now, same fallback text as the initial setText above.
        fun syncPortInputToLivePort() {
            if (randomPortOnStart) {
                portInput.setText(
                    extractLiveListenPort(statusText.text.toString())?.toString()
                        ?: "Random - see status above"
                )
            }
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                refreshStatus()
                syncPortInputToLivePort()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                stopConnectionAutoRefresh()
                showMainScreen()
            }
        }

        startConnectionAutoRefresh(statusText) {
            syncPortInputToLivePort()
            refreshNetworkNote()
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(utpRow)
        root.addView(tcpRow)
        root.addView(portForwardingRow)
        root.addView(networkLabel)
        root.addView(networkGroup)
        root.addView(networkNote)
        root.addView(portLabel)
        root.addView(portInput)
        root.addView(randomPortCheckbox)
        root.addView(applyPortButton)
        root.addView(portNote)
        root.addView(refreshButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    private fun startConnectionAutoRefresh(statusText: TextView, onTick: (() -> Unit)? = null) {
        stopConnectionAutoRefresh()

        isConnectionScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isConnectionScreenActive) {
                    return
                }

                try {
                    statusText.text = buildConnectionStatusStyled()
                    onTick?.invoke()
                } catch (_: Throwable) {
                    // Keep the screen alive even if native status is temporarily unavailable.
                }

                handler.postDelayed(this, 1000L)
            }
        }

        connectionRefreshRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopConnectionAutoRefresh() {
        isConnectionScreenActive = false

        connectionRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        connectionRefreshRunnable = null
    }

    // Feature 1: Speed Control - global limits only, no scheduling.
    // The UI works in KB/s; everything stored in prefs and sent to
    // native is bytes/second, converted at this boundary.
    // Feature: Web UI - view/control TorrentOr from a browser on the same
    // Wi-Fi/LAN (see WebUiServer.kt). Off by default; this screen is the
    // only place that turns it on, and it requires a username + password
    // before it will start (WebUiServer itself refuses every request
    // until both are set, so there is never an accidentally-open port).
    private fun webUiPrefs() = WebUiServer.webUiPrefs(this)

    // Best-effort local IP, for showing "http://<ip>:<port>" once the
    // server is running - just something to type into another device's
    // browser, not used for anything the server itself depends on.
    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun showWebUiScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopConnectionAutoRefresh()

        val prefs = webUiPrefs()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Web UI"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "View and control TorrentOr from a browser on the same " +
                    "Wi-Fi - add magnets, upload .torrent files and choose " +
                    "which files to download, pause/resume/remove torrents. " +
                    "Off by default; needs a username and password before " +
                    "it will turn on. If you change anything below while " +
                    "it's already on, turn it off and back on to apply it."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val remoteAccessLabel = TextView(this).apply {
            text = "Controlling it from outside your Wi-Fi"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 4)
        }

        val remoteAccessText = TextView(this).apply {
            text = "Use Tailscale (tailscale.com, free) instead of port forwarding - " +
                    "it works even behind CGNAT, where your ISP doesn't actually give " +
                    "you a forwardable public IP at all. Install the Tailscale app on " +
                    "this device and on whichever phone/computer you'll control it " +
                    "from, and sign in to the same Tailscale account on both. They " +
                    "then get a direct, encrypted connection to each other with no " +
                    "public exposure and nothing to configure on your router.\n\n" +
                    "Open the Tailscale app on THIS device to see its Tailscale IP " +
                    "(something like 100.x.x.x), then enter it below so this screen " +
                    "can show you the full remote address, ready to copy.\n\n" +
                    "The HTTPS option below adds another layer of encryption on top " +
                    "of whatever carries the connection (Wi-Fi or Tailscale) and is " +
                    "worth leaving on either way."
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        // Guards against a very easy mistake: "Copy Tailscale Address"
        // copies the FULL built address (scheme:// + ip + :port) to the
        // clipboard, and a later paste into this bare-IP field can
        // easily be that same clipboard content, not a fresh IP. Without
        // this, the address gets rebuilt by wrapping scheme:// / :port
        // around whatever's already here, so each such round-trip adds
        // one more layer ("https://https://ip:8080:8080", and so on).
        // Stripping any existing scheme/port wrapping first makes
        // rebuilding the address idempotent no matter what ends up
        // pasted in. Declared here, before first use below, since
        // Kotlin local functions aren't hoisted across a nested apply{}.
        fun sanitizeTailscaleIp(raw: String): String {
            var value = raw.trim()
            value = value.replace(Regex("^(https?://)+", RegexOption.IGNORE_CASE), "")
            value = value.replace(Regex("(:\\d+)+$"), "")
            return value.trim()
        }

        val tailscaleIpLabel = TextView(this).apply {
            text = "This device's Tailscale IP (optional)"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 4)
        }

        val tailscaleIpInput = EditText(this).apply {
            hint = "e.g. 100.x.x.x"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            // sanitizeTailscaleIp also cleans up anything already saved
            // from before this fix existed, so a previously-corrupted
            // value self-heals the first time this screen is opened.
            setText(sanitizeTailscaleIp(prefs.getString("tailscale_ip", "") ?: ""))
        }

        // Shown only once a Tailscale IP is entered above - its own
        // selectable address + Copy button, the same pattern as the
        // regular (Wi-Fi) address below.
        val tailscaleAddressInput = TextView(this).apply {
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 0)
            visibility = android.view.View.GONE
        }

        val copyTailscaleAddressButton = Button(this).apply {
            text = "Copy Tailscale Address"
            visibility = android.view.View.GONE
        }

        copyTailscaleAddressButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("TorrentOr Web UI (Tailscale)", tailscaleAddressInput.text.toString())
            )
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
        }

        val httpsLabel = TextView(this).apply {
            text = "Use HTTPS"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val httpsCheckbox = CheckBox(this).apply {
            text = "Encrypt the connection (recommended)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("https", true)
        }

        // Saved as soon as it's changed, not only when "Turn On" is next
        // pressed - otherwise unchecking this while the server is already
        // running (or without restarting it right away) never actually
        // got saved, so the old value came right back the next time the
        // server started, including after fully closing and reopening
        // the app.
        httpsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("https", isChecked).apply()
        }

        val httpsHelpText = TextView(this).apply {
            text = "Each device generates its own private certificate the first " +
                    "time this is turned on - the key for it never leaves the " +
                    "device's secure storage. Since it isn't signed by a public " +
                    "authority, your browser will show a one-time \"not secure\"/" +
                    "\"certificate not trusted\" warning the first time it connects - " +
                    "that's expected, not a sign anything's wrong; choose \"Advanced\" " +
                    "> \"Proceed\" (wording varies by browser)."
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 4, 0, 12)
        }

        val statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        }

        // Shows the address as selectable text (tap-and-hold to select/
        // copy) plus an explicit Copy button, so it can be moved to
        // another device without retyping it by hand. A plain TextView,
        // not an EditText - calling setTextIsSelectable(true) on an
        // EditText quietly makes it focusable/editable again regardless
        // of isFocusable = false, which is how a typed-in address (e.g.
        // a Tailscale IP) could get overwritten back to the computed
        // Wi-Fi one on the next refresh; a TextView has no such
        // "selectable also means editable" trap.
        val addressInput = TextView(this).apply {
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            visibility = android.view.View.GONE
        }

        val copyAddressButton = Button(this).apply {
            text = "Copy Address"
            visibility = android.view.View.GONE
        }

        copyAddressButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TorrentOr Web UI", addressInput.text.toString()))
            Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
        }

        fun refreshStatus() {
            if (WebUiServer.isRunning()) {
                val ip = getLocalIpAddress() ?: "<this device's IP>"
                val scheme = if (WebUiServer.isHttps()) "https" else "http"
                val address = "$scheme://$ip:${WebUiServer.currentPort()}"

                statusText.text = if (WebUiServer.isHttps()) {
                    "Status: ON - open this on another device (expect a one-time " +
                            "certificate warning - see below):"
                } else {
                    "Status: ON - open this on another device on the same Wi-Fi:"
                }

                addressInput.setText(address)
                addressInput.visibility = android.view.View.VISIBLE
                copyAddressButton.visibility = android.view.View.VISIBLE

                val tailscaleIp = sanitizeTailscaleIp(tailscaleIpInput.text.toString())

                if (tailscaleIp.isNotBlank()) {
                    tailscaleAddressInput.setText(
                        "$scheme://$tailscaleIp:${WebUiServer.currentPort()}"
                    )
                    tailscaleAddressInput.visibility = android.view.View.VISIBLE
                    copyTailscaleAddressButton.visibility = android.view.View.VISIBLE
                } else {
                    tailscaleAddressInput.visibility = android.view.View.GONE
                    copyTailscaleAddressButton.visibility = android.view.View.GONE
                }
            } else {
                statusText.text = "Status: OFF"
                addressInput.visibility = android.view.View.GONE
                copyAddressButton.visibility = android.view.View.GONE
                tailscaleAddressInput.visibility = android.view.View.GONE
                copyTailscaleAddressButton.visibility = android.view.View.GONE
            }
        }

        tailscaleIpInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("tailscale_ip", sanitizeTailscaleIp(s?.toString() ?: "")).apply()
                refreshStatus()
            }
        })

        val usernameLabel = TextView(this).apply {
            text = "Username"
            setTextColor(Color.WHITE)
            setPadding(0, 8, 0, 4)
        }

        val usernameInput = EditText(this).apply {
            setText(prefs.getString("username", "") ?: "")
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
        }

        val passwordLabel = TextView(this).apply {
            text = "Password"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val passwordInput = EditText(this).apply {
            setText(prefs.getString("password", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
        }

        val portLabel = TextView(this).apply {
            text = "Port"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val portInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("port", 8080).toString())
            setTextColor(Color.WHITE)
        }

        val toggleButton = Button(this).apply {
            setPadding(0, 16, 0, 0)
        }

        fun refreshToggleButton() {
            val running = WebUiServer.isRunning()
            toggleButton.text = if (running) "Turn Off" else "Turn On"
            // Red power icon on Turn Off, green power icon on Turn On.
            TabIcons.apply(toggleButton, if (running) "poweroff" else "poweron")
        }

        toggleButton.setOnClickListener {
            if (WebUiServer.isRunning()) {
                WebUiServer.stop()
                prefs.edit().putBoolean("enabled", false).apply()
                Toast.makeText(this, "Web UI turned off", Toast.LENGTH_SHORT).show()
                refreshStatus()
                refreshToggleButton()
                return@setOnClickListener
            }

            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val port = portInput.text.toString().toIntOrNull()

            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, "Set a username and password first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (port == null || port !in 1024..65535) {
                Toast.makeText(this, "Enter a port between 1024 and 65535", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val useHttps = httpsCheckbox.isChecked

            prefs.edit()
                .putString("username", username)
                .putString("password", password)
                .putInt("port", port)
                .putBoolean("https", useHttps)
                .apply()

            val error = WebUiServer.start(this, port, useHttps)

            if (error != null) {
                Toast.makeText(this, "Could not start: $error", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putBoolean("enabled", true).apply()
                Toast.makeText(this, "Web UI turned on", Toast.LENGTH_SHORT).show()
            }

            refreshStatus()
            refreshToggleButton()
        }

        refreshStatus()
        refreshToggleButton()

        val backButton = Button(this).apply {
            text = "Back"
            setPadding(0, 16, 0, 0)
            setOnClickListener { showMainScreen() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(addressInput)
        root.addView(copyAddressButton)
        root.addView(usernameLabel)
        root.addView(usernameInput)
        root.addView(passwordLabel)
        root.addView(passwordInput)
        root.addView(portLabel)
        root.addView(portInput)
        root.addView(httpsLabel)
        root.addView(httpsCheckbox)
        root.addView(httpsHelpText)
        root.addView(toggleButton)
        root.addView(remoteAccessLabel)
        root.addView(remoteAccessText)
        root.addView(tailscaleIpLabel)
        root.addView(tailscaleIpInput)
        root.addView(tailscaleAddressInput)
        root.addView(copyTailscaleAddressButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    private fun showSpeedLimitsScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopConnectionAutoRefresh()

        TorrentNative.startSession(getGlobalSaveFolder())

        val prefs = globalSpeedLimitPrefs()
        val savedUploadBytes = prefs.getInt("upload_limit", 0)
        val savedDownloadBytes = prefs.getInt("download_limit", 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Speed Limits"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "Global limits only. Leave a field blank or 0 for unlimited."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val uploadLabel = TextView(this).apply {
            text = "Upload limit (KB/s)"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val uploadInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Unlimited"
            setText(
                if (savedUploadBytes > 0) (savedUploadBytes / 1024).toString() else ""
            )
        }

        val downloadLabel = TextView(this).apply {
            text = "Download limit (KB/s)"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val downloadInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Unlimited"
            setText(
                if (savedDownloadBytes > 0) (savedDownloadBytes / 1024).toString() else ""
            )
        }

        val applyButton = Button(this).apply {
            text = "Apply"
            setOnClickListener {
                val uploadKb = uploadInput.text.toString().toIntOrNull() ?: 0
                val downloadKb = downloadInput.text.toString().toIntOrNull() ?: 0

                val uploadBytes = if (uploadKb > 0) uploadKb * 1024 else 0
                val downloadBytes = if (downloadKb > 0) downloadKb * 1024 else 0

                prefs.edit()
                    .putInt("upload_limit", uploadBytes)
                    .putInt("download_limit", downloadBytes)
                    .apply()

                try {
                    TorrentNative.applyGlobalSpeedLimits(uploadBytes, downloadBytes)
                    Toast.makeText(
                        this@MainActivity,
                        "Speed limits applied",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Throwable) {
                    Log.e("TorrentOrLT", "applyGlobalSpeedLimits(...) threw", e)
                    Toast.makeText(
                        this@MainActivity,
                        "Could not apply speed limits: " +
                                "${e.javaClass.simpleName}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener { showMainScreen() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(uploadLabel)
        root.addView(uploadInput)
        root.addView(downloadLabel)
        root.addView(downloadInput)
        root.addView(applyButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    private fun showPortForwardingStatusScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Port Forwarding Status"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val statusText = TextView(this).apply {
            text = try {
                TorrentNative.getPortForwardingStatus()
            } catch (e: Throwable) {
                "Could not load port forwarding status"
            }
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                statusText.text = try {
                    TorrentNative.getPortForwardingStatus()
                } catch (e: Throwable) {
                    "Could not load port forwarding status"
                }
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener { showMainScreen() }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(refreshButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    private fun showGlobalStatisticsScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Global Statistics"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val statsText = TextView(this).apply {
            text = buildQBittorrentGlobalStatisticsText()
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }

        startGlobalStatsAutoRefresh(statsText)

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                statsText.text = buildQBittorrentGlobalStatisticsText()
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener {
                stopGlobalStatsAutoRefresh()
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(statsText)
        root.addView(refreshButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    private fun startGlobalStatsAutoRefresh(statsText: TextView) {
        stopGlobalStatsAutoRefresh()

        isGlobalStatsScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isGlobalStatsScreenActive) {
                    return
                }

                try {
                    statsText.text = buildQBittorrentGlobalStatisticsText()
                } catch (_: Throwable) {
                    // Keep the screen alive even if statistics are temporarily unavailable.
                }

                handler.postDelayed(this, 1000L)
            }
        }

        globalStatsRefreshRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopGlobalStatsAutoRefresh() {
        isGlobalStatsScreenActive = false

        globalStatsRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        globalStatsRefreshRunnable = null
    }

    private fun buildQBittorrentGlobalStatisticsText(): String {
        val prefs = getSharedPreferences("global_stats", MODE_PRIVATE)

        val allTimeDownload = prefs.getLong("all_time_download", 0L)
        val allTimeUpload = prefs.getLong("all_time_upload", 0L)

        val ratio = if (allTimeDownload > 0L) {
            allTimeUpload.toDouble() / allTimeDownload.toDouble()
        } else {
            0.0
        }

        return StringBuilder()
            .append("User statistics\n\n")
            .append("All-time upload:      ")
            .append(formatGiB(allTimeUpload))
            .append("\n")
            .append("All-time download:    ")
            .append(formatGiB(allTimeDownload))
            .append("\n")
            .append("All-time share ratio: ")
            .append(String.format("%.2f", ratio))
            .append("\n\nAuto-refresh: Every 1 second")
            .toString()
    }

    private fun formatGiB(bytes: Long): String {
        val gib = bytes.toDouble() / 1024.0 / 1024.0 / 1024.0
        return String.format("%.2f GiB", gib)
    }


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

    // Bug fix: TorrentNative.hasTorrentHash(...) only looks at the LIVE
    // libtorrent session's currently-loaded torrents. Right at app start
    // (a fresh process after a background kill, or reopening right after
    // an APK update) that check runs here in the Activity while
    // TorrentService.restoreSavedTorrents() is still working through
    // re-adding every previously-saved torrent back into the session on
    // its own thread - so for a brief window, a torrent that is genuinely
    // already added can still report "not added yet" simply because it
    // hasn't been re-loaded into the session at that exact moment. That
    // race is what kept letting the file-selection screen reappear even
    // after the v1.87 fix added the hash check itself. This now also
    // checks the same persisted "torrent_store" entries
    // TorrentService.getSavedEntries()/saveFileEntry()/saveMagnetEntry()
    // write to - which exist the instant a torrent is first added, not
    // only once it's back in the live session - so either signal being
    // true means "already added".
    private fun isTorrentAlreadyAddedByHash(hash: String): Boolean {
        if (hash.isBlank()) {
            return false
        }

        val liveSessionHasIt = try {
            TorrentNative.hasTorrentHash(hash)
        } catch (_: Throwable) {
            false
        }

        return liveSessionHasIt || isHashInPersistedTorrentStore(hash)
    }

    private fun normalizeHashForKey(hash: String): String {
        return hash
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun isHashInPersistedTorrentStore(hash: String): Boolean {
        val target = normalizeHashForKey(hash)
        if (target.isBlank()) {
            return false
        }

        val entries = try {
            getSharedPreferences("torrent_store", MODE_PRIVATE)
                .getString("entries", "")
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (_: Throwable) {
            return false
        }

        for (entry in entries) {
            val parts = entry.split("||", limit = 4)

            val matches = when (parts.getOrNull(0)) {
                "MAGNET" -> {
                    val magnet = parts.getOrNull(1) ?: ""
                    normalizeHashForKey(extractHashFromMagnetInput(magnet)) == target
                }

                "FILE" -> {
                    // Entries saved with the hash already in them (the
                    // common case) are checked directly, with no file I/O.
                    // Older-format entries without a stored hash are
                    // skipped here rather than hashing the file again -
                    // TorrentService's own equivalent check (used once the
                    // session is actually ready) already covers those.
                    parts.size >= 4 && normalizeHashForKey(parts.getOrNull(1) ?: "") == target
                }

                else -> false
            }

            if (matches) {
                return true
            }
        }

        return false
    }

    // Adding something that is already in the list no longer just refuses:
    // when the hash and the new source (a .torrent path or a magnet) are
    // known, any trackers the new source has that the existing torrent
    // lacks are merged into it, like qBittorrent does.
    private fun showAlreadyAddedMessage(hash: String = "", source: String = "") {
        var message = "Torrent already added"

        if (hash.isNotBlank() && source.isNotBlank()) {
            val result = try {
                TorrentNative.mergeTorrentTrackers(hash, source)
            } catch (_: Throwable) {
                ""
            }

            if (result.startsWith("OK|")) {
                val added = result.split("|").getOrNull(1)?.toIntOrNull() ?: 0
                message = if (added > 0) {
                    "Torrent already added - merged $added new tracker" +
                        (if (added == 1) "" else "s")
                } else {
                    "Torrent already added - no new trackers to merge"
                }
            } else if (result == "PRIVATE") {
                message = "Torrent already added (private torrent - trackers not merged)"
            }
        }

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showMagnetMetadataScreen(magnet: String, onAdded: (() -> Unit)? = null) {
        val hash = extractHashFromMagnetInput(magnet)

        if (isTorrentAlreadyAddedByHash(hash)) {
            showAlreadyAddedMessage(hash, magnet)
            return
        }

        skipMagnetSelection = false

        // Feature 5: the handle has to be created now (paused) just to
        // fetch metadata, before the user has had a chance to pick a
        // folder, so it starts in the global default. If they pick a
        // different one below, that's applied with moveTorrentStorage()
        // the moment they pick it - see currentSaveFolder below - so by
        // the time Download runs the torrent is already in the right place.
        val torrentIndex = try {
            TorrentNative.addMagnetPaused(magnet, getGlobalSaveFolder())
        } catch (e: Throwable) {
            e.printStackTrace()
            -1
        }

        if (torrentIndex <= 0) {
            Toast.makeText(this, "Could not open magnet", Toast.LENGTH_SHORT).show()
            return
        }

        var currentSaveFolder = getGlobalSaveFolder()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Fetching magnet metadata..."
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        val statusText = TextView(this).apply {
            text = "Please wait. File list will appear when metadata is ready."
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 24)
        }

        val saveFolderText = TextView(this).apply {
            text = "Save Folder:\n$currentSaveFolder"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 4)
        }

        val changeSaveFolderButton = Button(this).apply {
            text = "Change Save Folder"
            setOnClickListener {
                showFolderPickerDialog(currentSaveFolder) { newPath ->
                    val hash = getSafeTorrentHashForAction(torrentIndex)

                    if (hash.isBlank()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Torrent not ready yet - try again in a moment",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@showFolderPickerDialog
                    }

                    val moved = try {
                        TorrentNative.moveTorrentStorage(hash, newPath)
                    } catch (e: Throwable) {
                        Log.e("TorrentOrLT", "moveTorrentStorage(...) threw", e)
                        false
                    }

                    if (moved) {
                        currentSaveFolder = newPath
                        saveSaveFolderForHash(hash, newPath)
                        saveFolderText.text = "Save Folder:\n$currentSaveFolder"

                        Toast.makeText(
                            this@MainActivity,
                            "Save folder updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not change save folder",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        val sequentialCheckBox = CheckBox(this).apply {
            text = "Sequential Download"
            setTextColor(Color.WHITE)
            isChecked = false
        }

        val ignoreLowSpaceCheckBox = CheckBox(this).apply {
            text = "Ignore low storage space"
            setTextColor(Color.WHITE)
            isChecked = false
        }

        val downloadNowButton = Button(this).apply {
            text = "Download All Without Waiting"
            setOnClickListener {
                confirmLowDiskSpaceThenRun(
                    ignoreLowSpaceCheckBox.isChecked,
                    targetPath = currentSaveFolder
                ) {
                    skipMagnetSelection = true

                    applySequentialDownloadForHash(
                        getSafeTorrentHashForAction(torrentIndex),
                        sequentialCheckBox.isChecked
                    )

                    saveMagnetOnly(magnet)
                    TorrentNative.resumeTorrent(torrentIndex)
                    onAdded?.invoke()

                    Toast.makeText(
                        this@MainActivity,
                        "Download started. Files will begin after metadata is ready.",
                        Toast.LENGTH_SHORT
                    ).show()

                    showMainScreen()
                }
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            tag = "back_button"
            setOnClickListener {
                skipMagnetSelection = true
                TorrentNative.removeTorrent(torrentIndex, false)
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(saveFolderText)
        root.addView(changeSaveFolderButton)
        root.addView(sequentialCheckBox)
        root.addView(ignoreLowSpaceCheckBox)
        root.addView(downloadNowButton)
        root.addView(cancelButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)

        waitForMagnetMetadata(torrentIndex, magnet, onAdded)
    }

    private fun waitForMagnetMetadata(
        torrentIndex: Int,
        magnet: String,
        onAdded: (() -> Unit)? = null
    ) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val files = try {
                    TorrentNative.getTorrentFilesByIndex(torrentIndex)
                } catch (e: Throwable) {
                    "Metadata not ready"
                }

                if (
                    files.isNotBlank() &&
                    files != "Metadata not ready" &&
                    files != "Invalid torrent" &&
                    files != "No files"
                ) {
                    if (skipMagnetSelection) {
                        skipMagnetSelection = false
                        return
                    }

                    showMagnetFileSelection(torrentIndex, magnet, files, onAdded)
                } else {
                    if (!skipMagnetSelection) {
                        handler.postDelayed(this, 2000L)
                    }
                }
            }
        }, 2000L)
    }

    private fun showMagnetFileSelection(
        torrentIndex: Int,
        magnet: String,
        fileData: String,
        onAdded: (() -> Unit)? = null
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Select files from magnet"
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        val fileListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val selected = mutableSetOf<Int>()
        val allIndexes = mutableSetOf<Int>()
        val checkBoxes = mutableListOf<CheckBox>()

        val lines = fileData.split("\n").filter { it.isNotBlank() }

        for (line in lines) {
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val sizeBytes = parts[2].toLongOrNull() ?: 0L

            allIndexes.add(index)
            selected.add(index)

            val checkBox = CheckBox(this).apply {
                text = "${fileTypeIcon(name)} $name (${formatSize(sizeBytes)})"
                setTextColor(Color.WHITE)
                isChecked = true

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(index)
                    else selected.remove(index)
                }
            }

            checkBoxes.add(checkBox)
            fileListLayout.addView(checkBox)
        }

        val scroll = ScrollView(this).apply {
            addView(fileListLayout)
        }

        val selectButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val selectAllButton = Button(this).apply {
            text = "Select All"
            setOnClickListener {
                selected.clear()
                selected.addAll(allIndexes)

                for (checkBox in checkBoxes) {
                    checkBox.isChecked = true
                }
            }
        }

        val selectNoneButton = Button(this).apply {
            text = "Select None"
            setOnClickListener {
                selected.clear()

                for (checkBox in checkBoxes) {
                    checkBox.isChecked = false
                }
            }
        }

        selectButtonsRow.addView(selectAllButton)
        selectButtonsRow.addView(selectNoneButton)

        // Feature 5: this torrent was already added (paused) on the
        // previous screen, so reflect whatever folder it's actually in
        // right now (the default, or whatever the user already changed it
        // to there) rather than assuming the global default.
        val initialHash = getSafeTorrentHashForAction(torrentIndex)
        var currentSaveFolder =
            getSavedSaveFolderForHash(initialHash) ?: getGlobalSaveFolder()

        val saveFolderText = TextView(this).apply {
            text = "Save Folder:\n$currentSaveFolder"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 4)
        }

        val changeSaveFolderButton = Button(this).apply {
            text = "Change Save Folder"
            setOnClickListener {
                showFolderPickerDialog(currentSaveFolder) { newPath ->
                    val hash = getSafeTorrentHashForAction(torrentIndex)

                    if (hash.isBlank()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Torrent not ready yet - try again in a moment",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@showFolderPickerDialog
                    }

                    val moved = try {
                        TorrentNative.moveTorrentStorage(hash, newPath)
                    } catch (e: Throwable) {
                        Log.e("TorrentOrLT", "moveTorrentStorage(...) threw", e)
                        false
                    }

                    if (moved) {
                        currentSaveFolder = newPath
                        saveSaveFolderForHash(hash, newPath)
                        saveFolderText.text = "Save Folder:\n$currentSaveFolder"

                        Toast.makeText(
                            this@MainActivity,
                            "Save folder updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not change save folder",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        val sequentialCheckBox = CheckBox(this).apply {
            text = "Sequential Download"
            setTextColor(Color.WHITE)
            isChecked = false
        }

        val ignoreLowSpaceCheckBox = CheckBox(this).apply {
            text = "Ignore low storage space"
            setTextColor(Color.WHITE)
            isChecked = false
        }

        val downloadSelected = Button(this).apply {
            text = "Download Selected"
            setOnClickListener {
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Select at least one file", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                confirmLowDiskSpaceThenRun(
                    ignoreLowSpaceCheckBox.isChecked,
                    targetPath = currentSaveFolder
                ) {
                    val indexes = selected.sorted().joinToString(",")

                    applySequentialDownloadForHash(
                        getSafeTorrentHashForAction(torrentIndex),
                        sequentialCheckBox.isChecked
                    )

                    saveMagnetOnly(magnet)
                    TorrentNative.setTorrentFilePriorities(torrentIndex, indexes)
                    saveFileSelectionForTorrent(torrentIndex, selected)
                    TorrentNative.resumeTorrent(torrentIndex)
                    onAdded?.invoke()

                    Toast.makeText(
                        this@MainActivity,
                        "Magnet download started",
                        Toast.LENGTH_SHORT
                    ).show()

                    showMainScreen()
                }
            }
        }

        val downloadAll = Button(this).apply {
            text = "Download All"
            setOnClickListener {
                confirmLowDiskSpaceThenRun(
                    ignoreLowSpaceCheckBox.isChecked,
                    targetPath = currentSaveFolder
                ) {
                    val indexes = allIndexes.sorted().joinToString(",")

                    applySequentialDownloadForHash(
                        getSafeTorrentHashForAction(torrentIndex),
                        sequentialCheckBox.isChecked
                    )

                    saveMagnetOnly(magnet)
                    TorrentNative.setTorrentFilePriorities(torrentIndex, indexes)
                    saveFileSelectionForTorrent(torrentIndex, allIndexes)
                    TorrentNative.resumeTorrent(torrentIndex)
                    onAdded?.invoke()

                    Toast.makeText(
                        this@MainActivity,
                        "Magnet download started",
                        Toast.LENGTH_SHORT
                    ).show()

                    showMainScreen()
                }
            }
        }

        val cancel = Button(this).apply {
            text = "Cancel"
            tag = "back_button"
            setOnClickListener {
                TorrentNative.removeTorrent(torrentIndex, false)
                showMainScreen()
            }
        }

        container.addView(title)
        container.addView(selectButtonsRow)
        container.addView(scroll)
        container.addView(saveFolderText)
        container.addView(changeSaveFolderButton)
        container.addView(sequentialCheckBox)
        container.addView(ignoreLowSpaceCheckBox)
        container.addView(downloadSelected)
        container.addView(downloadAll)
        container.addView(cancel)

        val outerScroll = ScrollView(this).apply {
            addView(container)
        }

        setContentView(outerScroll)
    }

    private fun showTorrentFileSelection(filePath: String, onAdded: (() -> Unit)? = null) {
        try {
            val hash = TorrentNative.getTorrentFileHash(filePath)

            if (
                hash.isNotBlank() &&
                isTorrentAlreadyAddedByHash(hash)
            ) {
                showAlreadyAddedMessage(hash, filePath)
                return
            }
        } catch (_: Throwable) {
            // Continue normally if hash check is temporarily unavailable.
        }

        val fileData = try {
            TorrentNative.getTorrentFiles(filePath)
        } catch (e: Throwable) {
            e.printStackTrace()
            Toast.makeText(this, "Could not read file list", Toast.LENGTH_LONG).show()
            return
        }

        if (fileData.startsWith("ERROR") || fileData.isBlank()) {
            Toast.makeText(this, "Could not read torrent file list", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Select files"
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        val fileListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val selected = mutableSetOf<Int>()
        val allIndexes = mutableSetOf<Int>()
        val checkBoxes = mutableListOf<CheckBox>()
        val lines = fileData.split("\n").filter { it.isNotBlank() }

        for (line in lines) {
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val sizeBytes = parts[2].toLongOrNull() ?: 0L

            allIndexes.add(index)

            val checkBox = CheckBox(this).apply {
                text = "${fileTypeIcon(name)} $name (${formatSize(sizeBytes)})"
                setTextColor(Color.WHITE)
                isChecked = true
                selected.add(index)

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(index)
                    else selected.remove(index)
                }
            }

            checkBoxes.add(checkBox)
            fileListLayout.addView(checkBox)
        }

        val scroll = ScrollView(this).apply {
            addView(fileListLayout)
        }

        val selectButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val selectAllButton = Button(this).apply {
            text = "Select All"
            setOnClickListener {
                selected.clear()
                selected.addAll(allIndexes)

                for (checkBox in checkBoxes) {
                    checkBox.isChecked = true
                }
            }
        }

        val selectNoneButton = Button(this).apply {
            text = "Select None"
            setOnClickListener {
                selected.clear()

                for (checkBox in checkBoxes) {
                    checkBox.isChecked = false
                }
            }
        }

        selectButtonsRow.addView(selectAllButton)
        selectButtonsRow.addView(selectNoneButton)

        // Feature 5: no torrent handle exists yet here - it's only created
        // once TorrentService receives the Intent below - so this just
        // picks the folder and sends it along as an Intent extra; the
        // Service applies it at actual add time and persists it.
        var selectedSaveFolder = getGlobalSaveFolder()

        val saveFolderText = TextView(this).apply {
            text = "Save Folder:\n$selectedSaveFolder"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 4)
        }

        val changeSaveFolderButton = Button(this).apply {
            text = "Change Save Folder"
            setOnClickListener {
                showFolderPickerDialog(selectedSaveFolder) { newPath ->
                    selectedSaveFolder = newPath
                    saveFolderText.text = "Save Folder:\n$selectedSaveFolder"
                }
            }
        }

        val sequentialCheckBox = CheckBox(this).apply {
            text = "Sequential Download"
            setTextColor(Color.WHITE)
            isChecked = false
        }

        val ignoreLowSpaceCheckBox = CheckBox(this).apply {
            text = "Ignore low storage space"
            setTextColor(Color.WHITE)
            isChecked = false
        }

        // The torrent handle doesn't exist yet at this point - it's only
        // created once TorrentService receives the Intent below - so this
        // only persists the choice (keyed by the file's own hash); the
        // handle-dependent live apply happens inside TorrentService once
        // it actually adds the torrent (reapplySavedPieceSettings runs on
        // every action it handles).
        fun persistSequentialDownloadChoice(enabled: Boolean) {
            val hash = try {
                TorrentNative.getTorrentFileHash(filePath)
            } catch (_: Throwable) {
                ""
            }

            if (hash.isNotBlank()) {
                sequentialDownloadPrefs().edit()
                    .putBoolean(normalizeHashForDateKey(hash), enabled)
                    .apply()
            }
        }

        val downloadSelected = Button(this).apply {
            text = "Download Selected"
            setOnClickListener {
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Select at least one file", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                confirmLowDiskSpaceThenRun(
                    ignoreLowSpaceCheckBox.isChecked,
                    targetPath = selectedSaveFolder
                ) {
                    val indexes = selected.sorted().joinToString(",")

                    persistSequentialDownloadChoice(sequentialCheckBox.isChecked)

                    val intent = Intent(this@MainActivity, TorrentService::class.java)
                    intent.putExtra("TORRENT_PATH", filePath)
                    intent.putExtra("SELECTED_INDEXES", indexes)
                    intent.putExtra("SAVE_PATH", selectedSaveFolder)

                    startTorrentService(intent)
                    onAdded?.invoke()
                    showMainScreen()
                }
            }
        }

        val downloadAll = Button(this).apply {
            text = "Download All"
            setOnClickListener {
                confirmLowDiskSpaceThenRun(
                    ignoreLowSpaceCheckBox.isChecked,
                    targetPath = selectedSaveFolder
                ) {
                    persistSequentialDownloadChoice(sequentialCheckBox.isChecked)

                    val intent = Intent(this@MainActivity, TorrentService::class.java)
                    intent.putExtra("TORRENT_PATH", filePath)
                    intent.putExtra("SAVE_PATH", selectedSaveFolder)
                    startTorrentService(intent)
                    onAdded?.invoke()
                    showMainScreen()
                }
            }
        }

        // Nothing has been added yet on this screen, so leaving it is a
        // plain Cancel; the top arrow does the same.
        val back = Button(this).apply {
            text = "Cancel"
            tag = "back_button"
            setOnClickListener { showMainScreen() }
        }

        container.addView(title)
        container.addView(selectButtonsRow)
        container.addView(scroll)
        container.addView(saveFolderText)
        container.addView(changeSaveFolderButton)
        container.addView(sequentialCheckBox)
        container.addView(ignoreLowSpaceCheckBox)
        container.addView(downloadSelected)
        container.addView(downloadAll)
        container.addView(back)

        val outerScroll = ScrollView(this).apply {
            addView(container)
        }

        setContentView(outerScroll)
    }


    private fun horizontalScrollFor(row: LinearLayout): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = HorizontalScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(row)
        }
    }

    private fun showTorrentDetails(torrentIndex: Int) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val torrentLine = getTorrentStatusLine(torrentIndex)
        val torrentName = extractTorrentName(torrentLine, torrentIndex)

        val title = TextView(this).apply {
            text = torrentName
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val tabRowOne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val tabRowTwo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val contentText = TextView(this).apply {
            text = "Select a tab"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }

        currentDetailsTorrentIndex = torrentIndex
        currentDetailsContentText = contentText
        currentDetailsTab = "General"

        val fileListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        currentDetailsFileListLayout = fileListLayout

        filesBuiltForIndex = -1
        filesPendingSelection = null
        filesProgressViews.clear()
        filesPrevProgressSnapshot.clear()

        fun clearContent() {
            fileListLayout.removeAllViews()
            contentText.text = ""
        }

        fun showGeneralTab() {
            currentDetailsTab = "General"
            clearContent()
            showGeneralDetails(torrentIndex, contentText)
        }

        val generalButton = Button(this).apply {
            text = "General"
            setOnClickListener { showGeneralTab() }
        }

        val filesButton = Button(this).apply {
            text = "Files"
            setOnClickListener {
                currentDetailsTab = "Files"
                clearContent()
                showFilesForOpening(torrentIndex, fileListLayout, contentText)
            }
        }

        val peersButton = Button(this).apply {
            text = "Peers"
            setOnClickListener {
                currentDetailsTab = "Peers"
                clearContent()
                contentText.text = TorrentNative.getTorrentPeers(torrentIndex)
            }
        }

        val trackersButton = Button(this).apply {
            text = "Trackers"
            setOnClickListener {
                currentDetailsTab = "Trackers"
                clearContent()

                renderTrackersTab(torrentIndex, contentText, fileListLayout)
            }
        }

        val webSeedsButton = Button(this).apply {
            text = "Web Seeds"
            setOnClickListener {
                currentDetailsTab = "Web Seeds"
                clearContent()

                val webSeeds = try {
                    TorrentNative.getTorrentWebSeeds(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load web seeds"
                }

                contentText.text = "Web Seeds\n\n" + webSeeds
            }
        }

        val swarmButton = Button(this).apply {
            text = "Swarm"
            setOnClickListener {
                currentDetailsTab = "Swarm"
                clearContent()

                contentText.text = colorSwarmHealth(try {
                    TorrentNative.getTorrentSwarmHealth(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load swarm health"
                })
            }
        }

        val piecesButton = Button(this).apply {
            text = "Pieces"
            setOnClickListener {
                currentDetailsTab = "Pieces"
                clearContent()
                showPiecesDetails(torrentIndex, contentText)
            }
        }

        val statisticsButton = Button(this).apply {
            text = "Statistics"
            setOnClickListener {
                currentDetailsTab = "Statistics"
                clearContent()

                val stats = try {
                    TorrentNative.getTorrentStatistics(torrentIndex) +
                        "\n\nAuto-refresh: Every 1 second"
                } catch (e: Throwable) {
                    "Could not load statistics"
                }

                contentText.text = highlightTimeLines(stats)
            }
        }

        val commentButton = Button(this).apply {
            text = "Comment"
            setOnClickListener {
                currentDetailsTab = "Comment"
                clearContent()

                val comment = try {
                    TorrentNative.getTorrentComment(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load torrent comment"
                }

                contentText.text =
                    "Torrent Comment\n\n" + comment
            }
        }

        TabIcons.apply(generalButton, "general", 18, 6)
        TabIcons.apply(filesButton, "files", 18, 6)
        TabIcons.apply(peersButton, "peers", 18, 6)
        TabIcons.apply(trackersButton, "trackers", 18, 6)
        TabIcons.apply(webSeedsButton, "webseeds", 18, 6)
        TabIcons.apply(swarmButton, "swarm", 18, 6)
        TabIcons.apply(piecesButton, "pieces", 18, 6)
        TabIcons.apply(statisticsButton, "statistics", 18, 6)
        TabIcons.apply(commentButton, "comment", 18, 6)

        tabRowOne.addView(generalButton)
        tabRowOne.addView(filesButton)
        tabRowOne.addView(peersButton)

        tabRowTwo.addView(trackersButton)
        tabRowTwo.addView(webSeedsButton)
        tabRowTwo.addView(swarmButton)
        tabRowTwo.addView(piecesButton)
        tabRowTwo.addView(statisticsButton)
        tabRowTwo.addView(commentButton)

        deepBlueButtons(
            false,
            generalButton, filesButton, peersButton, trackersButton,
            webSeedsButton, swarmButton, piecesButton, statisticsButton, commentButton
        )

        val actionTitle = TextView(this).apply {
            text = "Actions"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 8)
        }

        val copyMagnet = Button(this).apply {
            text = "Copy Magnet"
            setOnClickListener {
                val magnet = TorrentNative.getTorrentMagnet(torrentIndex)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Magnet", magnet))
                Toast.makeText(this@MainActivity, "Magnet copied", Toast.LENGTH_SHORT).show()
            }
        }

        val shareMagnet = Button(this).apply {
            text = "Share Magnet"
            setOnClickListener {
                val magnet = try {
                    TorrentNative.getTorrentMagnet(torrentIndex)
                } catch (_: Throwable) {
                    ""
                }

                if (magnet.isBlank() || !magnet.startsWith("magnet:")) {
                    Toast.makeText(this@MainActivity, "Magnet not available yet", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, magnet)
                }

                try {
                    startActivity(Intent.createChooser(send, "Share magnet link"))
                } catch (_: Throwable) {
                    Toast.makeText(this@MainActivity, "Could not open the share menu", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val copyHash = Button(this).apply {
            text = "Copy Hash"
            setOnClickListener {
                val hash = TorrentNative.getTorrentHash(torrentIndex)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Hash", hash))
                Toast.makeText(this@MainActivity, "Hash copied", Toast.LENGTH_SHORT).show()
            }
        }

        val pauseButton = Button(this).apply {
            text = "Pause"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("ACTION", "PAUSE_TORRENT")
                intent.putExtra("TORRENT_INDEX", torrentIndex)
                intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
                intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))
                startTorrentService(intent)

                Toast.makeText(this@MainActivity, "Torrent paused", Toast.LENGTH_SHORT).show()
                // Stay on the tab the user is on - just refresh it.
                updateActiveDetailsTab()
            }
        }

        val resumeButton = Button(this).apply {
            text = "Resume"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("ACTION", "RESUME_TORRENT")
                intent.putExtra("TORRENT_INDEX", torrentIndex)
                intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
                intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))
                startTorrentService(intent)

                Toast.makeText(this@MainActivity, "Torrent resumed", Toast.LENGTH_SHORT).show()
                // Stay on the tab the user is on - just refresh it.
                updateActiveDetailsTab()
            }
        }


        // Feature 3: Sequential Download & First/Last Piece Priority.
        // Both call native directly by info-hash (never the list index,
        // which can shift) and persist in SharedPreferences the moment
        // the engine confirms the change actually took effect - never
        // optimistically, so a failed apply (e.g. metadata not ready
        // yet for first/last priority) never gets saved as if it worked.
        fun pieceSettingToggleButton(
            onLabel: String,
            offLabel: String,
            prefs: SharedPreferences,
            getEngine: (String) -> Boolean,
            setEngine: (String, Boolean) -> Boolean,
            enableMessage: String,
            disableMessage: String,
            notReadyMessage: String,
            colorState: Boolean = false
        ): Button {
            lateinit var button: Button

            fun currentHash(): String = getSafeTorrentHashForAction(torrentIndex)

            fun relabel() {
                val hash = currentHash()

                val enabled = if (hash.isBlank()) {
                    false
                } else {
                    try {
                        getEngine(hash)
                    } catch (_: Throwable) {
                        false
                    }
                }

                val label = if (enabled) onLabel else offLabel

                if (!colorState) {
                    button.text = label
                } else {
                    // "DHT: On for this torrent" - everything after the colon
                    // ("On for this torrent") is drawn bold in bright green
                    // for On, bright red for Off.
                    val spannable = android.text.SpannableString(label)
                    val start = label.indexOf(": ") + 2
                    val end = label.length
                    if (start in 2 until end) {
                        val word = label.substring(start).substringBefore(' ')
                        val color = if (word == "On") "#00E676" else "#FF1744"
                        spannable.setSpan(
                            android.text.style.ForegroundColorSpan(Color.parseColor(color)),
                            start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        spannable.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    button.text = spannable
                }
            }

            button = Button(this).apply {
                setOnClickListener {
                    val hash = currentHash()

                    if (hash.isBlank()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Torrent hash not available yet",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }

                    val currentlyEnabled = try {
                        getEngine(hash)
                    } catch (e: Throwable) {
                        Log.e("TorrentOrLT", "piece setting getEngine() threw", e)
                        false
                    }
                    val newValue = !currentlyEnabled

                    val applied = try {
                        setEngine(hash, newValue)
                    } catch (e: Throwable) {
                        Log.e("TorrentOrLT", "piece setting setEngine() threw", e)
                        false
                    }

                    if (applied) {
                        prefs.edit()
                            .putBoolean(normalizeHashForDateKey(hash), newValue)
                            .apply()

                        Toast.makeText(
                            this@MainActivity,
                            if (newValue) enableMessage else disableMessage,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            notReadyMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    relabel()
                }
            }

            relabel()
            return button
        }

        val sequentialButton = pieceSettingToggleButton(
            onLabel = "Sequential Download: On",
            offLabel = "Sequential Download: Off",
            prefs = sequentialDownloadPrefs(),
            getEngine = { h -> TorrentNative.getSequentialDownload(h) },
            setEngine = { h, v -> TorrentNative.setSequentialDownload(h, v) },
            enableMessage = "Sequential download enabled",
            disableMessage = "Sequential download disabled",
            notReadyMessage = "Could not change sequential download"
        )

        // Per-torrent DHT switch. The "enabled" value used by this helper
        // means "DHT turned OFF for this torrent", so the labels read the
        // other way round: On-label shows when it is switched off.
        val dhtButton = pieceSettingToggleButton(
            onLabel = "DHT: Off for this torrent",
            offLabel = "DHT: On for this torrent",
            prefs = getSharedPreferences("torrent_dht_disabled", MODE_PRIVATE),
            getEngine = { h -> TorrentNative.getTorrentDhtDisabled(h) },
            setEngine = { h, v -> TorrentNative.setTorrentDhtDisabled(h, v) },
            enableMessage = "DHT turned off for this torrent (takes effect on its next announce)",
            disableMessage = "DHT turned on for this torrent",
            notReadyMessage = "Could not change DHT for this torrent yet",
            colorState = true
        )

        val pexButton = pieceSettingToggleButton(
            onLabel = "PEX: Off for this torrent",
            offLabel = "PEX: On for this torrent",
            prefs = getSharedPreferences("torrent_pex_disabled", MODE_PRIVATE),
            getEngine = { h -> TorrentNative.getTorrentPexDisabled(h) },
            setEngine = { h, v -> TorrentNative.setTorrentPexDisabled(h, v) },
            enableMessage = "PEX turned off for this torrent",
            disableMessage = "PEX turned on for this torrent",
            notReadyMessage = "Could not change PEX for this torrent yet",
            colorState = true
        )

        val lsdButton = pieceSettingToggleButton(
            onLabel = "LSD: Off for this torrent",
            offLabel = "LSD: On for this torrent",
            prefs = getSharedPreferences("torrent_lsd_disabled", MODE_PRIVATE),
            getEngine = { h -> TorrentNative.getTorrentLsdDisabled(h) },
            setEngine = { h, v -> TorrentNative.setTorrentLsdDisabled(h, v) },
            enableMessage = "LSD turned off for this torrent",
            disableMessage = "LSD turned on for this torrent",
            notReadyMessage = "Could not change LSD for this torrent yet",
            colorState = true
        )

        val firstLastButton = pieceSettingToggleButton(
            onLabel = "First/Last Piece Priority: On",
            offLabel = "First/Last Piece Priority: Off",
            prefs = firstLastPriorityPrefs(),
            getEngine = { h -> TorrentNative.getFirstLastPiecePriority(h) },
            setEngine = { h, v -> TorrentNative.setFirstLastPiecePriority(h, v) },
            enableMessage = "First/last piece priority enabled",
            disableMessage = "First/last piece priority disabled",
            notReadyMessage = "Could not change this yet - the torrent's " +
                    "metadata may still be loading. Try again in a moment."
        )

        // Feature 1: per-torrent speed limit override. 0 or blank means
        // no cap of its own for this torrent - the global limit (Speed
        // Limits screen) still applies on top, same as any other torrent.
        val savedTorrentLimits = try {
            TorrentNative.getTorrentSpeedLimits(getSafeTorrentHashForAction(torrentIndex))
        } catch (_: Throwable) {
            ""
        }

        val savedLimitParts = savedTorrentLimits.split("|")
        val savedTorrentUploadBytes = savedLimitParts.getOrNull(0)?.toIntOrNull() ?: 0
        val savedTorrentDownloadBytes = savedLimitParts.getOrNull(1)?.toIntOrNull() ?: 0

        val speedLimitLabel = TextView(this).apply {
            text = "Per-Torrent Speed Limit (KB/s, 0 or blank = use global)"
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 4)
        }

        val uploadLimitInput = EditText(this).apply {
            hint = "Upload KB/s - unlimited"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(
                if (savedTorrentUploadBytes > 0) (savedTorrentUploadBytes / 1024).toString() else ""
            )
        }

        val downloadLimitInput = EditText(this).apply {
            hint = "Download KB/s - unlimited"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(
                if (savedTorrentDownloadBytes > 0) (savedTorrentDownloadBytes / 1024).toString() else ""
            )
        }

        val applySpeedLimitButton = Button(this).apply {
            text = "Apply Torrent Speed Limit"
            setOnClickListener {
                val hash = getSafeTorrentHashForAction(torrentIndex)

                if (hash.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Torrent hash not available yet",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val uploadKb = uploadLimitInput.text.toString().toIntOrNull() ?: 0
                val downloadKb = downloadLimitInput.text.toString().toIntOrNull() ?: 0

                val uploadBytes = if (uploadKb > 0) uploadKb * 1024 else 0
                val downloadBytes = if (downloadKb > 0) downloadKb * 1024 else 0

                val applied = try {
                    TorrentNative.setTorrentSpeedLimits(hash, uploadBytes, downloadBytes)
                } catch (e: Throwable) {
                    Log.e("TorrentOrLT", "setTorrentSpeedLimits(...) threw", e)
                    false
                }

                if (applied) {
                    torrentSpeedLimitPrefs().edit()
                        .putString(
                            normalizeHashForDateKey(hash),
                            "$uploadBytes|$downloadBytes"
                        )
                        .apply()

                    Toast.makeText(
                        this@MainActivity,
                        "Torrent speed limit applied",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Could not apply torrent speed limit",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val forceReannounce = Button(this).apply {
            text = "Force Reannounce"
            setOnClickListener {
                TorrentNative.forceReannounce(torrentIndex)
                Toast.makeText(this@MainActivity, "Reannounce sent", Toast.LENGTH_SHORT).show()
                updateActiveDetailsTab()
            }
        }

        val forceRecheck = Button(this).apply {
            text = "Force Recheck"
            setOnClickListener {
                TorrentNative.forceRecheck(torrentIndex)
                Toast.makeText(this@MainActivity, "Force recheck started", Toast.LENGTH_SHORT).show()
                updateActiveDetailsTab()
            }
        }

        val openFolderButton = Button(this).apply {
            text = "Open Folder"
            setOnClickListener {
                val hash = getSafeTorrentHashForAction(torrentIndex)
                val folder = getSavedSaveFolderForHash(hash) ?: getGlobalSaveFolder()
                openDownloadFolder(folder)
            }
        }

        // Feature: lets a torrent added by magnet link or bare info-hash be
        // saved as a real .torrent file once its metadata has arrived (the
        // handle has nothing to export before then - that's the normal,
        // expected state for a magnet still resolving, not an error).
        val saveTorrentFileButton = Button(this).apply {
            text = "Save as .torrent File"
            setOnClickListener {
                val hash = getSafeTorrentHashForAction(torrentIndex)

                if (hash.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Torrent hash not available yet",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val destFolder = File(savePath, "Saved Torrent Files")
                destFolder.mkdirs()

                val savedPath = try {
                    TorrentNative.saveTorrentFile(hash, destFolder.absolutePath)
                } catch (e: Throwable) {
                    Log.e("TorrentOrLT", "saveTorrentFile(...) threw", e)
                    ""
                }

                if (savedPath.isNotBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Saved to $savedPath",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Couldn't save yet - metadata hasn't finished " +
                                "downloading for this torrent",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Feature 5: moves this torrent's already-downloaded files to a
        // new folder. Unlike the add-time pickers, this one acts on a
        // torrent that may already have data on disk, so it always goes
        // through moveTorrentStorage() (never a plain prefs-only change).
        val changeSaveFolderButton = Button(this).apply {
            text = "Change Save Folder"
            setOnClickListener {
                val hash = getSafeTorrentHashForAction(torrentIndex)

                if (hash.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Torrent hash not available yet",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                val currentFolder = getSavedSaveFolderForHash(hash) ?: getGlobalSaveFolder()

                showFolderPickerDialog(currentFolder) { newPath ->
                    val moved = try {
                        TorrentNative.moveTorrentStorage(hash, newPath)
                    } catch (e: Throwable) {
                        Log.e("TorrentOrLT", "moveTorrentStorage(...) threw", e)
                        false
                    }

                    if (moved) {
                        saveSaveFolderForHash(hash, newPath)

                        Toast.makeText(
                            this@MainActivity,
                            "Moving files to $newPath - this may take a " +
                                    "moment for large torrents. Check the " +
                                    "Execution Log for when it's done.",
                            Toast.LENGTH_LONG
                        ).show()

                        // Stay on the tab the user is on - just refresh it.
                        updateActiveDetailsTab()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Could not change save folder",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        // Feature: Delete Torrent, right inside the detail screen - same
        // Cancel / Delete Torrent Only / Delete Torrent + Files choice as
        // the list card's existing Remove button (showRemoveDialog), just
        // worded "Delete" and reachable without going back to the list.
        val deleteButton = Button(this).apply {
            text = "Delete Torrent"
            setOnClickListener {
                showDeleteDialogFromDetails(torrentIndex)
            }
        }

        val backButton = Button(this).apply {
            text = "Back"
            setOnClickListener { showMainScreen() }
        }

        root.addView(title)
        root.addView(horizontalScrollFor(tabRowOne))
        root.addView(horizontalScrollFor(tabRowTwo))
        root.addView(contentText)
        root.addView(fileListLayout)
        TabIcons.apply(copyMagnet, "magnet", 18, 6)
        TabIcons.apply(copyHash, "hash", 18, 6)
        TabIcons.apply(shareMagnet, "share", 18, 6)
        TabIcons.apply(pauseButton, "pause", 18, 6)
        TabIcons.apply(resumeButton, "play", 18, 6)
        TabIcons.apply(deleteButton, "trash", 18, 6)
        TabIcons.apply(sequentialButton, "sequential", 18, 6)
        TabIcons.apply(firstLastButton, "flag", 18, 6)
        TabIcons.apply(dhtButton, "network", 18, 6)
        TabIcons.apply(pexButton, "peers", 18, 6)
        TabIcons.apply(lsdButton, "connection", 18, 6)
        TabIcons.apply(applySpeedLimitButton, "speed", 18, 6)
        TabIcons.apply(forceReannounce, "reannounce", 18, 6)
        TabIcons.apply(forceRecheck, "recheck", 18, 6)
        TabIcons.apply(openFolderButton, "folder", 18, 6)
        TabIcons.apply(saveTorrentFileButton, "save", 18, 6)
        TabIcons.apply(changeSaveFolderButton, "edit", 18, 6)
        TabIcons.apply(backButton, "back", 18, 6)

        deepBlueButtons(
            true,
            copyMagnet, copyHash, shareMagnet, pauseButton, resumeButton, deleteButton,
            sequentialButton, firstLastButton, dhtButton, pexButton, lsdButton, applySpeedLimitButton,
            forceReannounce, forceRecheck, openFolderButton,
            saveTorrentFileButton, changeSaveFolderButton
        )

        root.addView(actionTitle)
        root.addView(copyMagnet)
        root.addView(copyHash)
        root.addView(shareMagnet)
        root.addView(pauseButton)
        root.addView(resumeButton)
        root.addView(deleteButton)
        root.addView(sequentialButton)
        root.addView(firstLastButton)
        root.addView(dhtButton)
        root.addView(pexButton)
        root.addView(lsdButton)
        root.addView(speedLimitLabel)
        root.addView(uploadLimitInput)
        root.addView(downloadLimitInput)
        root.addView(applySpeedLimitButton)
        root.addView(forceReannounce)
        root.addView(forceRecheck)
        root.addView(openFolderButton)
        root.addView(saveTorrentFileButton)
        root.addView(changeSaveFolderButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        // Back arrow pinned at the top-left (no scrolling to the bottom to
        // leave this screen). The bottom Back button it replaces is gone.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(13, 71, 161))
            setPadding(8, 32, 8, 32)
            elevation = 12f

            addView(TextView(this@MainActivity).apply {
                text = "\u2190"
                textSize = 30f
                setTextColor(Color.WHITE)
                setPadding(28, 12, 28, 12)
                applyBarPressFlash(this)
                setOnClickListener { showMainScreen() }
            })

            addView(TextView(this@MainActivity).apply {
                text = "Torrent details"
                textSize = 24f
                setTextColor(Color.WHITE)
                setPadding(12, 0, 0, 0)
            })
        }

        val container = LinearLayout(this).apply {
            tag = "has_bar"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
            addView(topBar)
            addView(
                outerScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        setContentView(container)
        showGeneralTab()
    }

    private fun showPiecesDetails(
        torrentIndex: Int,
        contentText: TextView
    ) {
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

        val stateText = try {
            TorrentNative.getTorrentPieceStates(torrentIndex)
        } catch (e: Throwable) {
            ""
        }

        if (stateText.startsWith("OK|")) {
            contentText.text = buildColoredPieceMap(stateText, pieceSize)
            return
        }

        contentText.text =
            if (pieceSize.isBlank()) {
                pieceInfo
            } else {
                "$pieceInfo\n\n$pieceSize"
            }
    }

    // Four-state piece map: downloaded (teal-green), downloading now
    // (amber), missing but wanted (dark grey), skipped because its file is
    // not selected (very faint grey). Same colours as the Web UI.
    private fun buildColoredPieceMap(stateText: String, pieceSize: String): CharSequence {
        val newline = stateText.indexOf('\n')
        val header = stateText.substring(0, if (newline < 0) stateText.length else newline).split("|")
        val states = if (newline < 0) "" else stateText.substring(newline + 1)

        val total = header.getOrNull(1)?.toIntOrNull() ?: states.length
        val completed = header.getOrNull(2)?.toIntOrNull() ?: 0
        val sizeBytes = header.getOrNull(3) ?: "?"
        val percent = if (total > 0) (completed.toLong() * 100L / total).toInt() else 0

        val haveColor = Color.parseColor("#4DA3FF")
        val downloadingColor = Color.parseColor("#4CD964")
        val missingColor = Color.parseColor("#6B7686")
        val skippedColor = Color.parseColor("#2C3442")

        val out = android.text.SpannableStringBuilder()

        out.append("Pieces\n\n")
        out.append("Completed: $completed / $total\n")
        out.append("Progress: $percent%\n")
        out.append("Piece size: ${formatSize(sizeBytes.toLongOrNull() ?: 0L)}\n\n")

        fun legend(color: Int, label: String) {
            val start = out.length
            out.append("\u25A0")
            out.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start,
                out.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.append(" $label    ")
        }

        legend(haveColor, "downloaded")
        legend(downloadingColor, "downloading")
        out.append("\n")
        legend(missingColor, "missing")
        legend(skippedColor, "skipped (file not selected)")
        out.append("\n\n")

        val perLine = 40
        var runStart = out.length
        var runColor = -1

        fun closeRun() {
            if (runColor != -1 && out.length > runStart) {
                out.setSpan(
                    android.text.style.ForegroundColorSpan(runColor),
                    runStart,
                    out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        for (p in states.indices) {
            val color = when (states[p]) {
                '2' -> haveColor
                '1' -> downloadingColor
                'x' -> skippedColor
                else -> missingColor
            }

            if (color != runColor) {
                closeRun()
                runStart = out.length
                runColor = color
            }

            out.append("\u25A0")

            if ((p + 1) % perLine == 0) {
                closeRun()
                out.append("\n")
                runStart = out.length
                runColor = -1
            }
        }

        closeRun()

        if (states.length < total) {
            out.append("\n\nShowing first ${states.length} of $total pieces.")
        }

        return out
    }

    // Auto-refresh all live tabs every 3 seconds.
    // The Files tab is only refreshed in place (progress bars), it is never
    // rebuilt here, because rebuilding throws away the user's tick boxes.
    private fun updateActiveDetailsTab() {
        val contentText = currentDetailsContentText ?: return
        val torrentIndex = currentDetailsTorrentIndex

        if (torrentIndex <= 0) {
            return
        }

        when (currentDetailsTab) {
            "General" -> showGeneralDetails(torrentIndex, contentText)
            "Peers" -> {
                contentText.text = try {
                    TorrentNative.getTorrentPeers(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load peers"
                }
            }
            "Swarm" -> {
                contentText.text = colorSwarmHealth(try {
                    TorrentNative.getTorrentSwarmHealth(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load swarm health"
                })
            }
            "Pieces" -> showPiecesDetails(torrentIndex, contentText)
            "Statistics" -> {
                contentText.text = highlightTimeLines(try {
                    TorrentNative.getTorrentStatistics(torrentIndex) +
                        "\n\nAuto-refresh: Every 1 second"
                } catch (e: Throwable) {
                    "Could not load statistics"
                })
            }
            "Comment" -> {
                contentText.text = try {
                    "Torrent Comment\n\n" + TorrentNative.getTorrentComment(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load torrent comment"
                }
            }
            "Web Seeds" -> {
                contentText.text = try {
                    "Web Seeds\n\n" + TorrentNative.getTorrentWebSeeds(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load web seeds"
                }
            }
            "Trackers" -> {
                renderTrackersTab(torrentIndex, contentText, currentDetailsFileListLayout)
            }
            "Files" -> {
                val fileListLayout = currentDetailsFileListLayout
                if (fileListLayout != null) {
                    if (
                        filesBuiltForIndex == torrentIndex &&
                        fileListLayout.childCount > 0
                    ) {
                        refreshFileProgressInPlace(torrentIndex)
                    } else {
                        // list not built yet (e.g. magnet metadata was not ready)
                        showFilesForOpening(
                            torrentIndex,
                            fileListLayout,
                            contentText
                        )
                    }
                }
            }
        }
    }


    private fun getTorrentAddedDate(hash: String): String {
        val key = normalizeHashForDateKey(hash)
        if (key.isBlank()) return "Unknown"

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)
        val time = prefs.getLong("added_$key", 0L)

        return formatTorrentDate(time)
    }

    private fun getTorrentCompletedDate(hash: String): String {
        val key = normalizeHashForDateKey(hash)
        if (key.isBlank()) return "Not completed"

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)
        val time = prefs.getLong("completed_$key", 0L)

        if (time <= 0L) {
            return "Not completed"
        }

        return formatTorrentDate(time)
    }

    private fun normalizeHashForDateKey(hash: String): String {
        return hash
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun formatTorrentDate(time: Long): String {
        if (time <= 0L) {
            return "Unknown"
        }

        return try {
            val formatter = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            )

            formatter.format(Date(time))
        } catch (e: Throwable) {
            "Unknown"
        }
    }

    private fun showGeneralDetails(
        torrentIndex: Int,
        contentText: TextView
    ) {
        val statusLine = getTorrentStatusLine(torrentIndex)
        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (e: Throwable) {
            "Hash not ready"
        }

        val addedDate = getTorrentAddedDate(hash)
        val completedDate = getTorrentCompletedDate(hash)

        val magnet = try {
            TorrentNative.getTorrentMagnet(torrentIndex)
        } catch (e: Throwable) {
            "Magnet not ready"
        }

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

        val pieceSize = try {
            TorrentNative.getTorrentPieceSize(torrentIndex)
        } catch (e: Throwable) {
            ""
        }

        // The tool that made the .torrent (qBittorrent, Transmission,
        // mktorrent, Deluge, rTorrent, ...), when the torrent itself says so.
        // libtorrent reads this straight from the "created by" field that
        // tool wrote into the .torrent file - TorrentOr doesn't guess it.
        //
        // It is often unavailable for a torrent added by MAGNET LINK: a
        // magnet only carries a hash, and the BitTorrent metadata exchange
        // that follows (BEP 9 / ut_metadata) only fetches the "info"
        // dictionary (file list + piece hashes) - "created by" and
        // "creation date" live outside "info", so they are never part of
        // what gets exchanged over a magnet. A .torrent FILE has both, but
        // even then some tools simply don't write this field.
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
            if (TorrentNative.isPrivateTorrent(torrentIndex)) {
                "Yes"
            } else {
                "No"
            }
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

        val name = extractTorrentName(statusLine, torrentIndex)
        val state = extractFieldAfterName(statusLine, 1)
        val progress = extractFieldAfterName(statusLine, 2)
        val download = extractFieldByPrefix(statusLine, "↓")
        val upload = extractFieldByPrefix(statusLine, "↑")
        val seeds = extractFieldByWord(statusLine, "Seeds")
        val peers = extractFieldByWord(statusLine, "Peers")
        val eta = extractEta(statusLine)

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
        text.append("ETA: ").append(eta).append("\n")
        text.append("Added: ").append(addedDate).append("\n")
        text.append("Completed: ").append(completedDate).append("\n")

        val timeLines = try {
            TorrentNative.getTorrentTimeLines(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        if (timeLines.isNotBlank()) {
            text.append(timeLines).append("\n")
        }

        text.append(trackerHost).append("\n")
        text.append("Total size: ").append(totalSize).append("\n")
        text.append("Availability: ").append(availability).append("\n")
        // Bug fix: swarmHealth (getTorrentSwarmHealth) already opens with
        // its own "Swarm Health" heading, so a "Swarm:" label here just
        // duplicated it - General used to show both stacked.
        text.append(swarmHealth).append("\n")
        text.append("Web Seeds: ").append(webSeedCount).append("\n")
        text.append("Private: ").append(privateTorrent).append("\n")
        val peerSources = try {
            TorrentNative.getTorrentPeerSources(torrentIndex)
        } catch (e: Throwable) {
            ""
        }
        if (peerSources.isNotBlank()) {
            text.append(peerSources).append("\n")
        }
        text.append("Source: ").append(source).append("\n")
        text.append("Encoding: ").append(encoding).append("\n")

        if (pieceSize.isNotBlank()) {
            text.append(pieceSize).append("\n")
        }

        // Feature 5: this torrent's actual folder - either the one it was
        // added into, or wherever it's since been moved to.
        val currentTorrentSaveFolder =
            getSavedSaveFolderForHash(hash) ?: getGlobalSaveFolder()

        text.append("\nSave path:\n").append(currentTorrentSaveFolder).append("\n\n")
        text.append("Hash:\n").append(hash).append("\n\n")
        text.append("Magnet:\n").append(magnet)

        contentText.text = highlightTimeLines(colorSwarmHealth(text.toString()))
    }

    private fun getTorrentStatusLine(torrentIndex: Int): String {
        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (e: Throwable) {
            ""
        }

        val lines = status.lines().filter { it.isNotBlank() }

        return if (torrentIndex - 1 in lines.indices) {
            lines[torrentIndex - 1]
        } else {
            ""
        }
    }

    private fun extractTorrentName(
        statusLine: String,
        torrentIndex: Int
    ): String {
        if (statusLine.isBlank()) {
            return "Torrent $torrentIndex"
        }

        val parts = statusLine.split(" • ")

        return parts.firstOrNull()?.ifBlank {
            "Torrent $torrentIndex"
        } ?: "Torrent $torrentIndex"
    }

    private fun extractFieldAfterName(
        statusLine: String,
        fieldIndex: Int
    ): String {
        val parts = statusLine.split(" • ")

        return parts.getOrNull(fieldIndex) ?: "--"
    }

    private fun extractFieldByPrefix(
        statusLine: String,
        prefix: String
    ): String {
        val parts = statusLine.split(" • ")

        return parts.firstOrNull {
            it.trim().startsWith(prefix)
        } ?: "--"
    }

    private fun extractFieldByWord(
        statusLine: String,
        word: String
    ): String {
        val parts = statusLine.split(" • ")

        return parts.firstOrNull {
            it.trim().startsWith(word)
        }?.removePrefix(word)?.trim() ?: "--"
    }

    private fun extractEta(statusLine: String): String {
        val parts = statusLine.split(" • ")

        return parts.firstOrNull {
            it.trim().startsWith("ETA:")
        }?.removePrefix("ETA:")?.trim() ?: "--"
    }


    private fun getSavedFileSelectionForTorrent(torrentIndex: Int): Set<Int>? {
        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        if (hash.isBlank() || hash == "Hash not ready" || hash == "Invalid torrent") {
            return null
        }

        val key = normalizeHashForDateKey(hash)
        if (key.isBlank()) {
            return null
        }

        val prefs = getSharedPreferences("file_priorities", MODE_PRIVATE)
        val value = prefs.getString("selected_$key", null) ?: return null

        return value
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    private fun saveFileSelectionForTorrent(
        torrentIndex: Int,
        selectedIndexes: Set<Int>
    ) {
        val hash = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        if (hash.isBlank() || hash == "Hash not ready" || hash == "Invalid torrent") {
            return
        }

        val key = normalizeHashForDateKey(hash)
        if (key.isBlank()) {
            return
        }

        val value = selectedIndexes.sorted().joinToString(",")

        getSharedPreferences("file_priorities", MODE_PRIVATE)
            .edit()
            .putString("selected_$key", value)
            .commit()
    }


    private data class TorrentFileProgress(
        val name: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int
    )

    private fun getTorrentFileProgressMap(torrentIndex: Int): Map<Int, TorrentFileProgress> {
        val progressData = try {
            TorrentNative.getTorrentFileProgress(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        if (
            progressData.isBlank() ||
            progressData == "Metadata not ready" ||
            progressData == "Invalid torrent" ||
            progressData == "Progress not ready" ||
            progressData == "No files"
        ) {
            return emptyMap()
        }

        val map = mutableMapOf<Int, TorrentFileProgress>()

        for (line in progressData.lines()) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 5)
            if (parts.size < 5) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val downloaded = parts[2].toLongOrNull() ?: 0L
            val total = parts[3].toLongOrNull() ?: 0L
            val percent = (parts[4].toIntOrNull() ?: 0).coerceIn(0, 100)

            map[index] = TorrentFileProgress(
                name = name,
                downloadedBytes = downloaded,
                totalBytes = total,
                percent = percent
            )
        }

        return map
    }

    // Feature: per-file Seeds/Peers and "Uploading to" (see
    // getTorrentFileSwarm in TorrentNative.kt/torrentor.cpp for how these
    // are actually computed - from connected peers' own piece bitfields
    // and choke state, since libtorrent has no per-file counter for any
    // of this). totalSeeds/totalPeers are the same tracker-reported swarm
    // totals the main card's "Seeds X (Y)" shows - libtorrent has no such
    // total scoped to one file, so every file in a given torrent carries
    // the same pair here. uploading is genuinely per-file.
    private data class FileSwarm(
        val seeds: Int,
        val peers: Int,
        val totalSeeds: Int,
        val totalPeers: Int,
        val uploading: Int,
        // % of this file's pieces that we or a connected peer has; -1 = unknown
        val availability: Int = -1,
        // distributed copies x1000 (1234 = 1.234 copies); -1 = unknown
        val copiesMilli: Int = -1
    )

    private fun formatFileAvailability(swarm: FileSwarm?): String {
        val a = swarm?.availability ?: -1
        val c = swarm?.copiesMilli ?: -1

        val pct = if (a < 0) "Availability --" else "Availability $a%"
        val copies = if (c < 0) "Copies --" else "Copies " + String.format(java.util.Locale.US, "%.3f", c / 1000.0)

        return "$pct  •  $copies"
    }

    // Matches the main card's own "Uploading to: N peer(s)" wording
    // (st.num_uploads) so the per-file line reads the same way.
    private fun formatUploadingTo(count: Int): String {
        return "Uploading to " + count + if (count == 1) " peer" else " peers"
    }

    private fun getTorrentFileSwarmMap(torrentIndex: Int): Map<Int, FileSwarm> {
        val swarmData = try {
            TorrentNative.getTorrentFileSwarm(torrentIndex)
        } catch (_: Throwable) {
            ""
        }

        if (
            swarmData.isBlank() ||
            swarmData == "Metadata not ready" ||
            swarmData == "Invalid torrent" ||
            swarmData == "Peers not ready" ||
            swarmData == "No files"
        ) {
            return emptyMap()
        }

        val map = mutableMapOf<Int, FileSwarm>()

        for (line in swarmData.lines()) {
            if (line.isBlank()) continue

            val parts = line.split("|", limit = 8)
            if (parts.size < 6) continue

            val index = parts[0].toIntOrNull() ?: continue
            val seeds = parts[1].toIntOrNull() ?: 0
            val peers = parts[2].toIntOrNull() ?: 0
            val totalSeeds = parts[3].toIntOrNull() ?: 0
            val totalPeers = parts[4].toIntOrNull() ?: 0
            val uploading = parts[5].toIntOrNull() ?: 0
            val availability = parts.getOrNull(6)?.toIntOrNull() ?: -1
            val copiesMilli = parts.getOrNull(7)?.toIntOrNull() ?: -1

            map[index] = FileSwarm(
                seeds, peers, totalSeeds, totalPeers, uploading, availability, copiesMilli
            )
        }

        return map
    }

    private fun showFilesForOpening(
        torrentIndex: Int,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        // If this is only a rebuild of the same torrent's list, keep the ticks
        // the user has changed but not applied yet.
        val previousPending: Set<Int>? =
            if (filesBuiltForIndex == torrentIndex) {
                filesPendingSelection?.toSet()
            } else {
                null
            }

        fileListLayout.removeAllViews()
        filesProgressViews.clear()
        filesPrevProgressSnapshot.clear()
        filesBuiltForIndex = -1
        filesPendingSelection = null

        val fileData = try {
            TorrentNative.getTorrentFilesByIndex(torrentIndex)
        } catch (e: Throwable) {
            "Could not read files"
        }

        if (
            fileData.isBlank() ||
            fileData == "Metadata not ready" ||
            fileData == "Invalid torrent" ||
            fileData == "No files"
        ) {
            contentText.text = fileData
            return
        }

        val progressMap = getTorrentFileProgressMap(torrentIndex)
        val swarmMap = getTorrentFileSwarmMap(torrentIndex)

        // Feature 5: this torrent's actual folder, for locating its
        // downloaded files below - not always the global default anymore.
        val torrentHashForFiles = try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }
        val torrentSaveFolder =
            getSavedSaveFolderForHash(torrentHashForFiles) ?: getGlobalSaveFolder()

        contentText.text =
            "Files\n\n" +
                    "Tick the files you want TorrentOr to download.\n" +
                    "Untick files you want to skip.\n" +
                    "Then press Apply File Selection.\n\n" +
                    "Each file shows its own download progress.\n" +
                    "Tap Open beside a file to open it.\n" +
                    "Long press Open for Share / Delete / Rename."

        val selected = mutableSetOf<Int>()
        filesPendingSelection = selected
        val allIndexes = mutableSetOf<Int>()
        val checkBoxes = mutableListOf<CheckBox>()
        val savedSelection = getSavedFileSelectionForTorrent(torrentIndex)
        val lines = fileData.split("\n").filter { it.isNotBlank() }

        val selectButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val selectAllButton = Button(this).apply {
            text = "Select All"
            setOnClickListener {
                selected.clear()
                selected.addAll(allIndexes)

                for (checkBox in checkBoxes) {
                    checkBox.isChecked = true
                }
            }
        }

        val selectNoneButton = Button(this).apply {
            text = "Select None"
            setOnClickListener {
                selected.clear()

                for (checkBox in checkBoxes) {
                    checkBox.isChecked = false
                }
            }
        }

        val applySelectionButton = Button(this).apply {
            text = "Apply File Selection"
            setOnClickListener {
                applyFilePrioritySelection(
                    torrentIndex,
                    selected,
                    fileListLayout,
                    contentText
                )
            }
        }

        deepBlueButtons(true, selectAllButton, selectNoneButton, applySelectionButton)

        selectButtonsRow.addView(selectAllButton)
        selectButtonsRow.addView(selectNoneButton)

        fileListLayout.addView(selectButtonsRow)
        fileListLayout.addView(applySelectionButton)

        for (line in lines) {
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val sizeBytes = parts[2].toLongOrNull() ?: 0L
            val file = File(torrentSaveFolder, name)

            allIndexes.add(index)

            val shouldBeChecked =
                previousPending?.contains(index)
                    ?: savedSelection?.contains(index)
                    ?: true

            if (shouldBeChecked) {
                selected.add(index)
            }

            val progress = progressMap[index]
            val percent = progress?.percent ?: 0
            val downloadedText = if (progress != null) {
                "${formatSize(progress.downloadedBytes)} / ${formatSize(progress.totalBytes)}"
            } else {
                "Progress not ready"
            }
            val estimate = if (progress != null) {
                estimateFileEtaAndSpeed(
                    index,
                    progress.downloadedBytes,
                    progress.totalBytes - progress.downloadedBytes
                )
            } else {
                FileEtaEstimate("--", "${formatSize(0)}/s")
            }
            val etaText = estimate.etaText
            val speedText = estimate.speedText
            val swarm = swarmMap[index]
            val swarmText = "Seeds ${swarm?.seeds ?: 0} (${swarm?.totalSeeds ?: 0})" +
                    "  Peers ${swarm?.peers ?: 0} (${swarm?.totalPeers ?: 0})" +
                    "  •  ${formatUploadingTo(swarm?.uploading ?: 0)}" +
                    "  •  ${formatFileAvailability(swarm)}"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 12)
            }

            val checkBox = CheckBox(this).apply {
                text = "${fileTypeIcon(name)} $name (${formatSize(sizeBytes)})"
                setTextColor(Color.WHITE)
                isChecked = shouldBeChecked

                setOnCheckedChangeListener { _, isCheckedNow ->
                    if (isCheckedNow) {
                        selected.add(index)
                    } else {
                        selected.remove(index)
                    }
                }
            }

            checkBoxes.add(checkBox)

            val progressText = TextView(this).apply {
                text = "Progress: $percent%  •  $downloadedText  •  " +
                        "↓ $speedText  •  ETA: $etaText\n$swarmText"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, 0, 0, 4)
            }

            val progressBar = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                this.progress = percent
                progressDrawable.setTint(Color.parseColor("#4FD1C5"))
            }

            val openButton = Button(this).apply {
                text = "Open"

                setOnClickListener {
                    openDownloadedFile(file)
                }

                setOnLongClickListener {
                    showFileLongPressMenu(
                        file,
                        torrentIndex,
                        fileListLayout,
                        contentText
                    )
                    true
                }
            }

            filesProgressViews[index] = Pair(progressText, progressBar)

            deepBlueButtons(true, openButton)

            row.addView(checkBox)
            row.addView(progressText)
            row.addView(progressBar)
            row.addView(openButton)
            fileListLayout.addView(row)
        }

        val applyBottomButton = Button(this).apply {
            text = "Apply File Selection"
            setOnClickListener {
                applyFilePrioritySelection(
                    torrentIndex,
                    selected,
                    fileListLayout,
                    contentText
                )
            }
        }

        deepBlueButtons(true, applyBottomButton)

        fileListLayout.addView(applyBottomButton)

        filesBuiltForIndex = torrentIndex
    }

    // Updates only the progress text/bars of an already built Files list, so
    // tick boxes, scroll position and taps are left alone.
    private fun refreshFileProgressInPlace(torrentIndex: Int) {
        val progressMap = getTorrentFileProgressMap(torrentIndex)

        if (progressMap.isEmpty()) return

        val swarmMap = getTorrentFileSwarmMap(torrentIndex)

        for ((index, views) in filesProgressViews) {
            val progress = progressMap[index] ?: continue

            val estimate = estimateFileEtaAndSpeed(
                index,
                progress.downloadedBytes,
                progress.totalBytes - progress.downloadedBytes
            )

            val swarm = swarmMap[index]
            val swarmText = "Seeds ${swarm?.seeds ?: 0} (${swarm?.totalSeeds ?: 0})" +
                    "  Peers ${swarm?.peers ?: 0} (${swarm?.totalPeers ?: 0})" +
                    "  •  ${formatUploadingTo(swarm?.uploading ?: 0)}" +
                    "  •  ${formatFileAvailability(swarm)}"

            views.first.text =
                "Progress: ${progress.percent}%  •  " +
                        "${formatSize(progress.downloadedBytes)} / ${formatSize(progress.totalBytes)}" +
                        "  •  ↓ ${estimate.speedText}  •  ETA: ${estimate.etaText}\n$swarmText"

            views.second.progress = progress.percent
        }
    }

    private fun applyFilePrioritySelection(
        torrentIndex: Int,
        selected: Set<Int>,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        if (selected.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No files selected")
                .setMessage("This will pause downloading all files in this torrent. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply") { _, _ ->
                    applyFilePrioritySelectionNow(
                        torrentIndex,
                        selected,
                        fileListLayout,
                        contentText
                    )
                }
                .show()

            return
        }

        applyFilePrioritySelectionNow(
            torrentIndex,
            selected,
            fileListLayout,
            contentText
        )
    }

    private fun applyFilePrioritySelectionNow(
        torrentIndex: Int,
        selected: Set<Int>,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        val indexes = selected.sorted().joinToString(",")

        try {
            TorrentNative.setTorrentFilePriorities(torrentIndex, indexes)
            saveFileSelectionForTorrent(torrentIndex, selected)

            Toast.makeText(
                this,
                "File selection applied",
                Toast.LENGTH_SHORT
            ).show()

            showFilesForOpening(torrentIndex, fileListLayout, contentText)
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                "Could not apply file selection",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Feature 5: builds the "primary:relative/path" (or "VOLUME-ID:path"
    // for an SD card) document authority string ACTION_OPEN_DOCUMENT_TREE
    // expects, from a plain absolute path like the ones torrents are saved
    // to. Returns null for a path this can't translate (not under
    // /storage/...), in which case the caller falls back to the plain
    // Download folder, same as before this feature existed.
    private fun buildDocumentAuthorityForPath(path: String): String? {
        val normalized = path.trim().trimEnd('/')
        if (!normalized.startsWith("/storage/")) return null

        val afterStorage = normalized.removePrefix("/storage/")
        val segments = afterStorage.split("/", limit = 2)
        val volume = segments.getOrNull(0) ?: return null
        val relative = segments.getOrNull(1) ?: ""

        val volumeId = if (volume == "emulated") {
            // "/storage/emulated/0/X/Y" -> volume "primary", relative "X/Y"
            val afterEmulated = relative.split("/", limit = 2)
            val rest = afterEmulated.getOrNull(1) ?: ""
            return if (rest.isBlank()) "primary:" else "primary:$rest"
        } else {
            volume
        }

        return if (relative.isBlank()) "$volumeId:" else "$volumeId:$relative"
    }

    private fun openDownloadFolder(path: String = getGlobalSaveFolder()) {
        try {
            val authority = buildDocumentAuthorityForPath(path) ?: "primary:Download"

            val folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                authority
            )

            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra("android.provider.extra.INITIAL_URI", folderUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Open Download folder"))
        } catch (e: Exception) {
            e.printStackTrace()

            try {
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                        "resource/folder"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(fallback, "Open folder"))
            } catch (e2: Exception) {
                e2.printStackTrace()
                Toast.makeText(
                    this,
                    "No file manager found to open folder",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openDownloadedFile(file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(
                    this,
                    "File not found:\n${file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )

            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            e.printStackTrace()

            Toast.makeText(
                this,
                "Error opening file:\n${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showFileLongPressMenu(
        file: File,
        torrentIndex: Int,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        val options = arrayOf(
            "Open",
            "Share",
            "Delete",
            "Rename"
        )

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDownloadedFile(file)
                    1 -> shareDownloadedFile(file)
                    2 -> deleteDownloadedFile(
                        file,
                        torrentIndex,
                        fileListLayout,
                        contentText
                    )
                    3 -> renameDownloadedFile(
                        file,
                        torrentIndex,
                        fileListLayout,
                        contentText
                    )
                }
            }
            .show()
    }

    private fun shareDownloadedFile(file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(
                    this,
                    "File not found:\n${file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )

            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(intent, "Share with")
            )
        } catch (e: Exception) {
            e.printStackTrace()

            Toast.makeText(
                this,
                "Error sharing file:\n${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun deleteDownloadedFile(
        file: File,
        torrentIndex: Int,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        if (!file.exists()) {
            Toast.makeText(
                this,
                "File not found:\n${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete file?")
            .setMessage(file.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = file.delete()

                    if (deleted) {
                        Toast.makeText(
                            this,
                            "File deleted",
                            Toast.LENGTH_SHORT
                        ).show()

                        showFilesForOpening(
                            torrentIndex,
                            fileListLayout,
                            contentText
                        )
                    } else {
                        Toast.makeText(
                            this,
                            "Could not delete file",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()

                    Toast.makeText(
                        this,
                        "Error deleting file:\n${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun renameDownloadedFile(
        file: File,
        torrentIndex: Int,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        if (!file.exists()) {
            Toast.makeText(
                this,
                "File not found:\n${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = EditText(this).apply {
            setText(file.name)
            setSelectAllOnFocus(false)
            setSelection(file.nameWithoutExtension.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()

                if (newName.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Name cannot be empty",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                if (
                    newName.contains("/") ||
                    newName.contains("\\")
                ) {
                    Toast.makeText(
                        this,
                        "Name cannot contain / or \\",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val parent = file.parentFile

                if (parent == null) {
                    Toast.makeText(
                        this,
                        "Could not rename file",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }

                val newFile = File(parent, newName)

                if (newFile.exists()) {
                    Toast.makeText(
                        this,
                        "A file with that name already exists",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }

                try {
                    val renamed = file.renameTo(newFile)

                    if (renamed) {
                        Toast.makeText(
                            this,
                            "File renamed",
                            Toast.LENGTH_SHORT
                        ).show()

                        showFilesForOpening(
                            torrentIndex,
                            fileListLayout,
                            contentText
                        )
                    } else {
                        Toast.makeText(
                            this,
                            "Could not rename file",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()

                    Toast.makeText(
                        this,
                        "Error renaming file:\n${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()

        val fromMap = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)

        if (!fromMap.isNullOrBlank()) {
            return fromMap
        }

        return when (extension) {
            "mp4", "mkv", "avi", "mov", "webm", "m4v" -> "video/*"
            "mp3", "flac", "wav", "aac", "m4a", "ogg" -> "audio/*"
            "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
            "txt", "srt", "ass", "ssa", "nfo", "log", "json", "xml" -> "text/*"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
    }

    // Feature: a small emoji icon per file in the Files tab, based on
    // extension - video/audio/text-document/executable each get their own,
    // everything else falls back to a plain file icon. This is a simple
    // prefix on the existing checkbox label text, not a real ImageView,
    // consistent with the rest of the app's fully text-based UI (■/□ piece
    // map, ↓/↑ speed readout, etc).
    private fun fileTypeIcon(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "wmv", "flv",
            "ts", "mpg", "mpeg", "3gp" -> "🎬"

            "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma", "opus" -> "🎵"

            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "🖼️"

            "txt", "srt", "ass", "ssa", "nfo", "log", "json", "xml",
            "md", "csv", "pdf", "doc", "docx", "rtf" -> "📄"

            "exe", "msi", "apk", "bat", "sh", "jar", "deb", "dmg",
            "run" -> "⚙️"

            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> "📦"

            else -> "📁"
        }
    }

    private fun isFingerDown(): Boolean {
        // (a missing "finger up" event must not stop refreshing for good)
        return fingerDown && System.currentTimeMillis() - fingerDownSince < 5000L
    }

    private fun startUiUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                if (uiVisible) {
                    // torrent cards: every second, but not while touching
                    if (!isFingerDown()) {
                        updateTorrentList()
                    }

                    // detail tabs are heavier: every third round (3 s) -
                    // except Peers, Files, Trackers, General and Statistics,
                    // which the user wants live at 1 s (peer connections and
                    // speeds, per-file progress, the running time lines).
                    uiTick++

                    if (currentDetailsTab == "Peers" || currentDetailsTab == "Files" ||
                        currentDetailsTab == "Trackers" || currentDetailsTab == "General" ||
                        currentDetailsTab == "Statistics"
                    ) {
                        updateActiveDetailsTab()
                    } else if (uiTick % 3 == 0) {
                        updateActiveDetailsTab()
                    }
                }

                handler.postDelayed(this, listInterval)
            }
        })
    }

    // Live total speeds under the app name in the main screen's title bar,
    // written like qBittorrent's window title: "[D: 0 B/s, U: 0 B/s]" in
    // plain white.
    private var mainSpeedText: TextView? = null

    // Bottom status strip: "● DHT  ● PEX  ● LSD | seeds: 7 · peers: 42".
    // The dots are green when the feature is on and red when it is off (the
    // global switch from Network Features); seeds and peers are the totals
    // connected right now across all torrents.
    private var mainBottomBar: TextView? = null
    private var barSeeds = 0
    private var barPeers = 0

    private fun updateMainBottomBarText() {
        val view = mainBottomBar ?: return
        val prefs = getSharedPreferences("network_features", MODE_PRIVATE)
        val sb = android.text.SpannableStringBuilder()

        fun feature(key: String, label: String) {
            val on = prefs.getBoolean(key, true)
            val start = sb.length
            sb.append("\u25CF")
            sb.setSpan(
                android.text.style.ForegroundColorSpan(
                    Color.parseColor(if (on) "#00E676" else "#FF1744")
                ),
                start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(" ").append(label)
        }

        feature("dht", "DHT")
        sb.append("   ")
        feature("pex", "PEX")
        sb.append("   ")
        feature("lsd", "LSD")
        sb.append("   |   seeds: $barSeeds ")
        val dotStart = sb.length
        sb.append("\u25CF")
        sb.setSpan(
            android.text.style.ForegroundColorSpan(Color.parseColor("#8000FF")),
            dotStart, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.append(" peers: $barPeers")
        view.text = sb
    }

    // Adds up "Seeds N (M)" and "Peers N (M)" (the connected counts) from
    // every torrent line of the engine status.
    private fun updateMainBottomBar(status: String) {
        var seeds = 0
        var peers = 0

        if (status != "No torrents" && status != "Engine not ready") {
            val seedRegex = Regex(" \u2022 Seeds (\\d+) \\(")
            val peerRegex = Regex(" \u2022 Peers (\\d+) \\(")

            for (line in status.lines()) {
                seedRegex.findAll(line).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { seeds += it }
                peerRegex.findAll(line).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.let { peers += it }
            }
        }

        barSeeds = seeds
        barPeers = peers
        updateMainBottomBarText()
    }

    private fun formatBarSpeed(kb: Int): String {
        val v = kb.coerceAtLeast(0)
        return when {
            v <= 0 -> "0 B/s"
            v < 1024 -> "$v KB/s"
            v < 1024 * 1024 -> String.format("%.1f MB/s", v / 1024.0)
            else -> String.format("%.2f GB/s", v / 1024.0 / 1024.0)
        }
    }

    private fun updateMainSpeedText(downloadKb: Int, uploadKb: Int) {
        val view = mainSpeedText ?: return
        view.text = "[D: " + formatBarSpeed(downloadKb) + ", U: " + formatBarSpeed(uploadKb) + "]"
    }

    private fun addSpeedSample(downloadKb: Int, uploadKb: Int) {
        updateMainSpeedText(downloadKb, uploadKb)
        downloadSpeedHistory.add(downloadKb.coerceAtLeast(0))
        uploadSpeedHistory.add(uploadKb.coerceAtLeast(0))

        while (downloadSpeedHistory.size > maxSpeedHistoryPoints) {
            downloadSpeedHistory.removeAt(0)
        }

        while (uploadSpeedHistory.size > maxSpeedHistoryPoints) {
            uploadSpeedHistory.removeAt(0)
        }

        if (::speedGraphText.isInitialized) {
            speedGraphText.text = buildSpeedSummaryText()
        }

        if (::speedGraphView.isInitialized) {
            speedGraphView.setData(downloadSpeedHistory, uploadSpeedHistory)
        }
    }

    // The old block-character sparkline (▁▂▃▄▅▆▇█, built by a removed
    // buildSparkline() function) is now the drawn SpeedGraphView above this
    // text - this just builds the small colored "↓/↑ KB/s" readout line
    // that sits on top of it, in the same teal/orange as the graph's two
    // lines so it reads as one piece.
    private fun buildSpeedSummaryText(): CharSequence {
        val latestDown = downloadSpeedHistory.lastOrNull() ?: 0
        val latestUp = uploadSpeedHistory.lastOrNull() ?: 0

        val downPart = "↓ $latestDown KB/s"
        val upPart = "↑ $latestUp KB/s"
        val text = "$downPart   $upPart"

        val builder = android.text.SpannableStringBuilder(text)

        builder.setSpan(
            android.text.style.ForegroundColorSpan(Color.parseColor("#4FD1C5")),
            0,
            downPart.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val upStart = text.length - upPart.length
        builder.setSpan(
            android.text.style.ForegroundColorSpan(Color.parseColor("#B794F6")),
            upStart,
            text.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        return builder
    }

    // The per-torrent card line now shows speeds auto-scaled (e.g. "2.14
    // MB/s" instead of always "2190 KB/s"), so this parses whichever unit
    // is present and converts back to KB/s (what addSpeedSample/the speed
    // graph work in) instead of assuming "KB/s" literally.
    private fun speedUnitToKb(value: Double, unit: String): Int {
        val kb = when (unit) {
            "B" -> value / 1024.0
            "KB" -> value
            "MB" -> value * 1024.0
            "GB" -> value * 1024.0 * 1024.0
            "TB" -> value * 1024.0 * 1024.0 * 1024.0
            else -> 0.0
        }
        return kb.toInt()
    }

    private fun extractTotalSpeedsFromStatus(status: String): Pair<Int, Int> {
        var totalDown = 0
        var totalUp = 0

        val downRegex = Regex("""↓\s*([\d.]+)\s*(B|KB|MB|GB|TB)/s""")
        val upRegex = Regex("""↑\s*([\d.]+)\s*(B|KB|MB|GB|TB)/s""")

        for (line in status.lines()) {
            downRegex.find(line)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                val unit = match.groupValues.getOrNull(2)
                if (value != null && unit != null) {
                    totalDown += speedUnitToKb(value, unit)
                }
            }

            upRegex.find(line)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.toDoubleOrNull()
                val unit = match.groupValues.getOrNull(2)
                if (value != null && unit != null) {
                    totalUp += speedUnitToKb(value, unit)
                }
            }
        }

        return Pair(totalDown, totalUp)
    }

    private fun updateTorrentList() {
        if (!::listLayout.isInitialized) return

        // Select All / Select None change selectedTorrents without going
        // through a checkbox, so keep the bar's count in step here.
        updateSelectionBar()

        listLayout.removeAllViews()

        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (e: Exception) {
            "Engine not ready"
        }

        updateMainBottomBar(status)

        if (status == "No torrents" || status == "Engine not ready") {
            addSpeedSample(0, 0)
        } else {
            val speeds = extractTotalSpeedsFromStatus(status)
            addSpeedSample(speeds.first, speeds.second)
        }

        if (status == "No torrents" || status == "Engine not ready") {
            listLayout.addView(TextView(this).apply {
                text = status
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 24, 0, 0)
            })
            return
        }

        val allLines = status.lines().filter { it.isNotBlank() }
        val filteredLines = sortTorrentLines(
            allLines.withIndex().filter { item ->
                torrentMatchesFilter(item.value)
            }
        )

        if (filteredLines.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No torrents in $activeFilter"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 24, 0, 0)
            })
            return
        }

        for (item in filteredLines) {
            val position = item.index
            val line = item.value
            val torrentIndex = position + 1

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
                background = torrentCardBackground()

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 16
                }
            }

            val label = TextView(this).apply {
                text = line
                setTextColor(Color.WHITE)
                textSize = 15f
            }

            if (selectMode) {
                val checkBox = CheckBox(this).apply {
                    text = "Select"
                    setTextColor(Color.WHITE)
                    isChecked = selectedTorrents.contains(torrentIndex)

                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedTorrents.add(torrentIndex)
                        else selectedTorrents.remove(torrentIndex)
                        updateSelectionBar()
                    }
                }

                card.addView(checkBox)
            } else {
                // The whole card lightens while it is pressed, so a tap on a
                // torrent is visibly acknowledged (every theme).
                val pressedCard = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#44FFFFFF"))
                    cornerRadius = 22f
                }

                card.foreground = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), pressedCard)
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }

                card.setOnClickListener {
                    showTorrentDetails(torrentIndex)
                }

                // Long-press a torrent to start selecting (like the picture):
                // selection mode turns on with this torrent already ticked.
                card.setOnLongClickListener {
                    selectMode = true
                    selectedTorrents.clear()
                    selectedTorrents.add(torrentIndex)
                    updateSelectionBar()
                    updateTorrentList()
                    true
                }
            }

            val progressBar = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = extractPercent(line)
                progressDrawable.setTint(Color.BLACK)
            }

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val pause = Button(this).apply {
                text = "Pause"
                setOnClickListener {
                    sendTorrentAction("PAUSE_TORRENT", torrentIndex)
                    updateTorrentList()
                }
            }

            val resume = Button(this).apply {
                text = "Resume"
                setOnClickListener {
                    sendTorrentAction("RESUME_TORRENT", torrentIndex)
                    updateTorrentList()
                }
            }

            val remove = Button(this).apply {
                text = "Remove"
                setOnClickListener {
                    showRemoveDialog(torrentIndex)
                }
            }

            TabIcons.apply(pause, "pause", 18, 6)
            TabIcons.apply(resume, "play", 18, 6)
            TabIcons.apply(remove, "trash", 18, 6)
            buttonRow.addView(pause)
            buttonRow.addView(resume)
            buttonRow.addView(remove)

            // Feature: Force Recheck / Force Reannounce right on the main
            // card, instead of only inside Select Torrents mode or the
            // torrent's own General tab - a second row, same as
            // pause/resume/remove above, so five buttons don't get
            // squeezed into one line.
            val buttonRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val forceRecheck = Button(this).apply {
                text = "Force Recheck"
                setOnClickListener {
                    TorrentNative.forceRecheck(torrentIndex)
                    Toast.makeText(this@MainActivity, "Force recheck started", Toast.LENGTH_SHORT).show()
                    updateTorrentList()
                }
            }

            val forceReannounce = Button(this).apply {
                text = "Force Reannounce"
                setOnClickListener {
                    TorrentNative.forceReannounce(torrentIndex)
                    Toast.makeText(this@MainActivity, "Reannounce sent", Toast.LENGTH_SHORT).show()
                    updateTorrentList()
                }
            }

            TabIcons.apply(forceRecheck, "recheck", 18, 6)
            TabIcons.apply(forceReannounce, "reannounce", 18, 6)
            deepBlueButtons(true, pause, resume, remove, forceRecheck, forceReannounce)
            buttonRow2.addView(forceRecheck)
            buttonRow2.addView(forceReannounce)

            card.addView(label)
            card.addView(progressBar)
            card.addView(buttonRow)
            card.addView(buttonRow2)

            listLayout.addView(card)
        }
    }

    private fun torrentMatchesFilter(line: String): Boolean {
        val lower = line.lowercase()
        val query = searchQuery.trim().lowercase()

        if (query.isNotBlank() && !lower.contains(query)) {
            return false
        }

        val percent = extractPercent(line)

        return when (activeFilter) {
            "Downloading" -> lower.contains("downloading")
            "Seeding" -> lower.contains("seeding")
            "Paused" -> lower.contains("paused")
            "Completed" -> percent >= 100 || lower.contains("seeding")
            else -> true
        }
    }

    // Bug fix: "Select All" (in Select Torrents mode) called this function
    // but it didn't exist anywhere in the file, so the whole screen failed
    // to compile and none of the Select All / Select None / Force Recheck
    // Selected / Force Reannounce Selected / Remove Selected buttons ever
    // made it into a build - the only way to act on more than one torrent
    // was ticking each checkbox by hand. Mirrors the exact same
    // filter/search logic updateTorrentList() uses, so "Select All" only
    // selects torrents actually visible under the active filter right now.
    private fun getVisibleTorrentIndexes(): Set<Int> {
        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Exception) {
            return emptySet()
        }

        if (status == "No torrents" || status == "Engine not ready") {
            return emptySet()
        }

        val allLines = status.lines().filter { it.isNotBlank() }

        return allLines.withIndex()
            .filter { item -> torrentMatchesFilter(item.value) }
            .map { it.index + 1 }
            .toSet()
    }

    private fun getSafeTorrentHashForAction(torrentIndex: Int): String {
        return try {
            TorrentNative.getTorrentHash(torrentIndex)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun getSafeTorrentMagnetForAction(torrentIndex: Int): String {
        return try {
            TorrentNative.getTorrentMagnet(torrentIndex)
        } catch (_: Throwable) {
            ""
        }
    }

    // Restored: the card's Remove button asks Cancel / Remove Torrent Only /
    // Remove Torrent + Files, same as the "Remove Selected" bulk dialog,
    // instead of silently doing "Remove Only" with no confirmation.
    private fun showRemoveDialog(torrentIndex: Int) {
        fun removeTorrent(deleteFiles: Boolean) {
            val intent = Intent(this, TorrentService::class.java)
            intent.putExtra("ACTION", "REMOVE_TORRENT")
            intent.putExtra("TORRENT_INDEX", torrentIndex)
            intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
            intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))
            intent.putExtra("DELETE_FILES", deleteFiles)
            startTorrentService(intent)

            Toast.makeText(
                this,
                if (deleteFiles) "Torrent and files removed" else "Torrent removed",
                Toast.LENGTH_SHORT
            ).show()
            showMainScreen()
        }

        AlertDialog.Builder(this)
            .setTitle("Remove Torrent?")
            .setMessage("Choose how you want to remove this torrent.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove Torrent Only") { _, _ ->
                removeTorrent(false)
            }
            .setPositiveButton("Remove + Files") { _, _ ->
                removeTorrent(true)
            }
            .show()
    }

    // Feature: Delete Torrent button inside the detail screen. Same
    // underlying action as showRemoveDialog above (REMOVE_TORRENT with an
    // optional DELETE_FILES flag), just under "Delete" wording and closing
    // the detail screen back to the list afterward, since the torrent it
    // was showing no longer exists.
    private fun showDeleteDialogFromDetails(torrentIndex: Int) {
        fun deleteTorrent(deleteFiles: Boolean) {
            val intent = Intent(this, TorrentService::class.java)
            intent.putExtra("ACTION", "REMOVE_TORRENT")
            intent.putExtra("TORRENT_INDEX", torrentIndex)
            intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
            intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))
            intent.putExtra("DELETE_FILES", deleteFiles)
            startTorrentService(intent)

            Toast.makeText(
                this,
                if (deleteFiles) "Torrent and files deleted" else "Torrent deleted",
                Toast.LENGTH_SHORT
            ).show()
            showMainScreen()
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Torrent?")
            .setMessage("Choose how you want to delete this torrent.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete Torrent Only") { _, _ ->
                deleteTorrent(false)
            }
            .setPositiveButton("Delete + Files") { _, _ ->
                deleteTorrent(true)
            }
            .show()
    }

    private fun extractPercent(line: String): Int {
        val match = Regex("""(\d+)%""").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ---- Sort helpers --------------------------------------------------
    // The visible list is one plain-text status line per torrent (built
    // natively in getDetailedStatus). Sorting only reorders how those
    // lines are displayed; the IndexedValue.index each line carries is
    // always the original native-list position, which is what Pause /
    // Resume / Remove send back as TORRENT_INDEX. So sorting here can
    // never point an action at the wrong torrent.

    private fun extractNameForSort(line: String): String {
        return line.substringBefore(" • ").trim()
    }

    // The 2nd " • "-separated field is the state text built natively
    // ("Downloading", "Seeding", "Paused", "Paused (queued)",
    // "Paused (error: ...)"). Used as-is for "Status (A-Z)".
    private fun extractStatusForSort(line: String): String {
        val parts = line.split(" • ")
        return parts.getOrNull(1)?.trim() ?: ""
    }

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

    // Reads the "Size <done> <unit> / <total> <unit>" segment and returns
    // the *total* size in bytes, so "Size" sorting reflects how big the
    // torrent is rather than how far along it happens to be.
    private fun extractTotalSizeBytes(line: String): Double {
        val match = Regex(
            """Size\s+[\d.]+\s*(?:B|KB|MB|GB|TB)\s*/\s*([\d.]+)\s*(B|KB|MB|GB|TB)"""
        ).find(line) ?: return 0.0

        val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
        return value * unitMultiplier(match.groupValues[2])
    }

    // Raw epoch millis behind getTorrentAddedDate's formatted string, keyed
    // the same way (normalizeHashForDateKey), so "Date Added" sorting can
    // compare instants instead of parsing the display text back apart.
    private fun getTorrentAddedTimestamp(hash: String): Long {
        val key = normalizeHashForDateKey(hash)
        if (key.isBlank()) return 0L

        val prefs = getSharedPreferences("torrent_dates", MODE_PRIVATE)
        return prefs.getLong("added_$key", 0L)
    }

    // sortedBy/sortedByDescending are stable, so torrents tied on the
    // chosen field keep their existing relative order instead of jumping
    // around on every refresh. torrentIndex (position + 1) is the same
    // 1-based index Pause/Resume/Remove already use to look up the hash.
    private fun sortTorrentLines(
        lines: List<IndexedValue<String>>
    ): List<IndexedValue<String>> {
        return when (sortMode) {
            TorrentQueue.SORT_MODE -> {
                val rank = TorrentQueue.rankMap(this)
                lines.sortedBy {
                    rank[TorrentQueue.normalize(
                        getSafeTorrentHashForAction(it.index + 1)
                    )] ?: Int.MAX_VALUE
                }
            }

            "Name (A-Z)" ->
                lines.sortedBy { extractNameForSort(it.value).lowercase() }

            "Name (Z-A)" ->
                lines.sortedByDescending { extractNameForSort(it.value).lowercase() }

            "Size (Largest First)" ->
                lines.sortedByDescending { extractTotalSizeBytes(it.value) }

            "Size (Smallest First)" ->
                lines.sortedBy { extractTotalSizeBytes(it.value) }

            "Date Added (Newest First)" ->
                lines.sortedByDescending {
                    getTorrentAddedTimestamp(
                        getSafeTorrentHashForAction(it.index + 1)
                    )
                }

            "Date Added (Oldest First)" ->
                lines.sortedBy {
                    getTorrentAddedTimestamp(
                        getSafeTorrentHashForAction(it.index + 1)
                    )
                }

            "Progress (Highest First)" ->
                lines.sortedByDescending { extractPercent(it.value) }

            "Progress (Lowest First)" ->
                lines.sortedBy { extractPercent(it.value) }

            "Status (A-Z)" ->
                lines.sortedBy { extractStatusForSort(it.value).lowercase() }

            "Status (Z-A)" ->
                lines.sortedByDescending { extractStatusForSort(it.value).lowercase() }

            else -> lines
        }
    }

    private fun copyTorrentToFiles(uriString: String): String {
        val uri = uriString.toUri()

        val file = File(
            filesDir,
            "torrent_${java.lang.System.currentTimeMillis()}.torrent"
        )

        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Cannot open torrent file")

        return file.absolutePath
    }

    // Colours the Tracker tab: a summary line, a coloured dot on each
    // tracker's URL line, and the Status value (plus its Message / Last
    // error lines when something is wrong) in the status colour. The text
    // itself is never changed, so TorrentNotifier (which reads the raw
    // native text) is unaffected.
    // Kind of one tracker block: 0 working, 1 updating, 2 error, 3 not
    // contacted, 4 warning, 5 failing, 6 off (DHT / PeX / LSD switched off).
    private fun trackerKindOf(lines: List<String>): Int {
        val status = lines.firstOrNull { it.startsWith("Status: ") }
            ?.removePrefix("Status: ") ?: ""
        val hasLastError = lines.any { it.startsWith("Last error: ") }
        val verified = lines.any { it == "Verified: Yes" }

        return when {
            status.startsWith("Error") -> 2
            status.startsWith("Updating") -> 1
            status.startsWith("Failing") -> 5
            status.startsWith("Off") -> 6
            status.startsWith("Working") -> if (hasLastError && !verified) 4 else 0
            else -> 3
        }
    }

    private fun trackerKindColor(kind: Int): Int = Color.parseColor(
        when (kind) {
            0 -> "#00E676"
            1 -> "#4DA3FF"
            2 -> "#FF8A80"
            4, 5 -> "#FFA726"
            6 -> "#FF1744"
            else -> "#8A93A3"
        }
    )

    // Trackers tab, drawn as rounded cards: DHT / PeX / LSD first, then every
    // real tracker. Each card has the name in bold, the status or tracker
    // message on the left (red/orange when something is wrong) and "Peers: N"
    // on the right. A summary line stays above the cards.
    private fun renderTrackersTab(
        torrentIndex: Int,
        contentText: TextView,
        cards: LinearLayout?
    ) {
        val raw = try {
            TorrentNative.getTorrentTrackerStatus(torrentIndex)
        } catch (e: Throwable) {
            try {
                "Trackers\n\n" + TorrentNative.getTorrentTrackers(torrentIndex)
            } catch (e2: Throwable) {
                "Could not load tracker status"
            }
        }

        cards?.removeAllViews()

        if (!raw.startsWith("Tracker Status\n\n")) {
            contentText.text = raw
            return
        }

        val special = setOf("DHT", "PeX", "LSD")

        fun nameOf(block: List<String>): String =
            block.firstOrNull { it.startsWith("URL: ") }?.removePrefix("URL: ") ?: ""

        val all = raw.split("\n\n").drop(1).filter { it.isNotBlank() }.map { it.split("\n") }
        val ordered = all.filter { nameOf(it) in special } + all.filter { nameOf(it) !in special }
        val kinds = ordered.map { trackerKindOf(it) }

        // Summary line (counts per kind), same look as before.
        val summary = android.text.SpannableStringBuilder()
        val labels = listOf(
            0 to "working", 1 to "updating", 4 to "warning",
            5 to "failing", 2 to "error", 6 to "off", 3 to "not contacted"
        )
        var first = true
        for ((kind, label) in labels) {
            val n = kinds.count { it == kind }
            if (n == 0) continue
            if (!first) summary.append("   ")
            first = false
            val start = summary.length
            summary.append("\u25CF $n $label")
            summary.setSpan(
                android.text.style.ForegroundColorSpan(trackerKindColor(kind)),
                start, summary.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            summary.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start, summary.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        contentText.text = summary

        if (cards == null) return

        val density = resources.displayMetrics.density
        val cardBg = androidx.core.graphics.ColorUtils.blendARGB(bgColor(), Color.BLACK, 0.45f)

        for ((i, block) in ordered.withIndex()) {
            val kind = kinds[i]
            val color = trackerKindColor(kind)
            val name = nameOf(block)
            val isSpecial = name in special

            fun field(prefix: String): String =
                block.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix) ?: ""

            val status = field("Status: ")
            val message = field("Message: ")
            val lastError = field("Last error: ")

            var peers = "Peers: 0"
            val reports = field("Tracker reports: ")
            val reported = Regex("Peers (\\d+)").find(reports)?.groupValues?.get(1)
            if (reported != null) peers = "Peers: $reported"
            val own = field("Peers: ")
            if (own.isNotBlank()) peers = "Peers: $own"

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(cardBg)
                    cornerRadius = 14f * density
                    setStroke(maxOf(1, (1.2f * density).toInt()), outlineColor())
                }
                setPadding(
                    (14 * density).toInt(), (12 * density).toInt(),
                    (14 * density).toInt(), (12 * density).toInt()
                )
            }

            card.addView(TextView(this).apply {
                text = name
                textSize = 18f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })

            val left = android.text.SpannableStringBuilder()

            fun addLeft(str: String, col: Int, bold: Boolean) {
                if (str.isBlank()) return
                if (left.isNotEmpty()) left.append("\n")
                val start = left.length
                left.append(str)
                left.setSpan(
                    android.text.style.ForegroundColorSpan(col),
                    start, left.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (bold) {
                    left.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        start, left.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            val quietMessages = setOf("Announce OK", "No tracker message yet", "No message from the tracker")

            when {
                isSpecial -> addLeft(status, color, true)
                kind == 0 -> {
                    addLeft("Working", color, true)
                    if (message !in quietMessages) addLeft(message, Color.parseColor("#B0BEC5"), false)
                }
                kind == 1 || kind == 3 -> addLeft(status, color, true)
                else -> {
                    addLeft(status, color, true)
                    if (message !in quietMessages) addLeft(message, color, false)
                    addLeft(lastError, color, false)
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (6 * density).toInt(), 0, 0)
            }

            row.addView(
                TextView(this).apply {
                    text = left
                    textSize = 16f
                    setPadding(0, 0, (10 * density).toInt(), 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            row.addView(TextView(this).apply {
                text = peers
                textSize = 16f
                setTextColor(Color.WHITE)
            })

            card.addView(row)

            if (isSpecial) {
                if (message.isNotBlank()) {
                    card.addView(TextView(this).apply {
                        text = message
                        textSize = 14f
                        setTextColor(if (kind == 6) color else Color.parseColor("#B0BEC5"))
                        setPadding(0, (4 * density).toInt(), 0, 0)
                    })
                }
            } else {
                val details = listOf(
                    field("Tier: ").takeIf { it.isNotBlank() }?.let { "Tier $it" },
                    field("Fails: ").takeIf { it.isNotBlank() }?.let { "Fails $it" },
                    field("Source: ").takeIf { it.isNotBlank() }
                ).filterNotNull()

                if (details.isNotEmpty()) {
                    card.addView(TextView(this).apply {
                        text = details.joinToString("  \u00B7  ")
                        textSize = 12f
                        setTextColor(Color.parseColor("#8A93A3"))
                        setPadding(0, (6 * density).toInt(), 0, 0)
                    })
                }
            }

            cards.addView(
                card,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (6 * density).toInt() }
            )
        }
    }

    private fun colorTrackerStatus(text: String): CharSequence {
        if (!text.startsWith("Tracker Status\n\n")) return text

        val green = Color.parseColor("#4CD964")
        val blue = Color.parseColor("#4DA3FF")
        val orange = Color.parseColor("#FFA726")
        val red = Color.parseColor("#FF5252")
        val grey = Color.parseColor("#8A93A3")

        // 0 working, 1 updating, 2 error, 3 not contacted, 4 warning, 5 failing
        fun kindOf(lines: List<String>): Int {
            val status = lines.firstOrNull { it.startsWith("Status: ") }
                ?.substring("Status: ".length) ?: ""
            val hasLastError = lines.any { it.startsWith("Last error: ") }
            val verified = lines.any { it == "Verified: Yes" }
            // An old "Last error" on a verified tracker is just leftover
            // history from an earlier attempt - still green.
            return when {
                status.startsWith("Error") -> 2
                status.startsWith("Updating") -> 1
                status.startsWith("Failing") -> 5
                status.startsWith("Off") -> 6
                status.startsWith("Working") -> if (hasLastError && !verified) 4 else 0
                else -> 3
            }
        }

        fun colorOf(kind: Int): Int = when (kind) {
            0 -> green
            1 -> blue
            2, 6 -> red
            4, 5 -> orange
            else -> grey
        }

        val blocks = text.split("\n\n").drop(1).filter { it.isNotBlank() }.map { it.split("\n") }
        val kinds = blocks.map { kindOf(it) }

        val out = android.text.SpannableStringBuilder()

        fun add(str: String, color: Int?, bold: Boolean) {
            val start = out.length
            out.append(str)
            if (color != null) {
                out.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    start,
                    out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (bold) {
                out.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    out.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        add("Tracker Status\n", null, false)

        val labels = listOf(0 to "working", 1 to "updating", 4 to "warning", 5 to "failing", 2 to "error", 6 to "off", 3 to "not contacted")
        var first = true
        for ((kind, label) in labels) {
            val n = kinds.count { it == kind }
            if (n == 0) continue
            if (!first) add("   ", null, false)
            first = false
            add("\u25CF", colorOf(kind), false)
            add(" $n $label", colorOf(kind), true)
        }
        out.append("\n\n")

        for ((b, lines) in blocks.withIndex()) {
            val kind = kinds[b]
            val color = colorOf(kind)

            for ((l, line) in lines.withIndex()) {
                when {
                    line.startsWith("URL: ") -> {
                        add("\u25CF ", color, false)
                        add(line, null, true)
                    }
                    line.startsWith("Status: ") -> {
                        add("Status: ", null, false)
                        add(line.substring("Status: ".length), color, true)
                    }
                    (kind == 2 || kind == 4 || kind == 5 || kind == 6) &&
                        (line.startsWith("Message: ") || line.startsWith("Last error: ")) ->
                        add(line, color, false)
                    else -> add(line, null, false)
                }
                if (l < lines.size - 1) out.append("\n")
            }

            out.append("\n\n")
        }

        return out
    }

    // Shows the time lines (Elapsed / Time active / Seeding time / Time to
    // complete) in purple on the General and Statistics tabs. Display only;
    // the text itself is unchanged.
    private fun highlightTimeLines(text: CharSequence): CharSequence {
        val labels = listOf("Elapsed: ", "Time active: ", "Seeding time: ", "Time to complete: ")
        val out = if (text is android.text.SpannableStringBuilder) {
            text
        } else {
            android.text.SpannableStringBuilder(text)
        }

        val color = Color.parseColor("#B794F6")
        var lineStart = 0
        val plain = out.toString()

        while (lineStart <= plain.length) {
            var lineEnd = plain.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = plain.length

            val line = plain.substring(lineStart, lineEnd)

            if (labels.any { line.startsWith(it) }) {
                out.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    lineStart,
                    lineEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            lineStart = lineEnd + 1
        }

        return out
    }

    // Colours the Swarm Health text: the "Health:" word and the bar follow
    // the health level (red / orange / lime / green); the empty part of the
    // bar is dim grey. Anything unexpected is returned unchanged.
    private fun colorSwarmHealth(text: String): CharSequence {
        val level = Regex("Health: (Critical|Weak|Healthy|Excellent)").find(text)?.groupValues?.get(1)
            ?: return text

        val levelColor = Color.parseColor(
            when (level) {
                "Critical" -> "#FF5252"
                "Weak" -> "#FFA726"
                "Healthy" -> "#C0CA33"
                else -> "#4CD964"
            }
        )
        val emptyColor = Color.parseColor("#3A4150")

        val out = android.text.SpannableStringBuilder(text)

        val healthMatch = Regex("Health: $level").find(text)
        if (healthMatch != null) {
            out.setSpan(
                android.text.style.ForegroundColorSpan(levelColor),
                healthMatch.range.first + "Health: ".length,
                healthMatch.range.last + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val graphMatch = Regex("[\\u2588\\u2591]{6,}").find(text)
        if (graphMatch != null) {
            for (i in graphMatch.range) {
                out.setSpan(
                    android.text.style.ForegroundColorSpan(
                        if (text[i] == '\u2588') levelColor else emptyColor
                    ),
                    i,
                    i + 1,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return out
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"

        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$bytes B"
        }
    }

    // Same day/hour/minute rollover as the native formatEta() used for the
    // torrent-level ETA (torrentor.cpp), kept in Kotlin because this one
    // only ever gets a Kotlin-side estimate (see estimateFileEtaAndSpeed
    // below) - there is nothing to ask libtorrent for here.
    private fun formatEtaSeconds(seconds: Long): String {
        if (seconds <= 0) return "--"

        val mins = seconds / 60
        val hrs = mins / 60
        val days = hrs / 24

        return when {
            days > 0 -> "${days}d ${hrs % 24}h"
            hrs > 0 -> "${hrs}h ${mins % 60}m"
            else -> "${mins}m"
        }
    }

    // Feature: per-file download speed + ETA (see filesPrevProgressSnapshot
    // above for why both are estimated here rather than read from
    // libtorrent - it has no per-file rate at all, upload or download,
    // only one combined rate for the whole torrent). Call this once per
    // file per refresh tick - it both returns the estimate and updates the
    // snapshot used for the next call, so it must not be called more than
    // once per tick for the same index or the rate will be thrown off.
    private data class FileEtaEstimate(val etaText: String, val speedText: String)

    private fun estimateFileEtaAndSpeed(
        index: Int,
        downloadedBytes: Long,
        remainingBytes: Long
    ): FileEtaEstimate {
        val now = System.currentTimeMillis()
        val previous = filesPrevProgressSnapshot[index]
        filesPrevProgressSnapshot[index] = Pair(downloadedBytes, now)

        if (previous == null) {
            // First tick seen for this file - no rate to measure yet.
            return FileEtaEstimate("--", "${formatSize(0)}/s")
        }

        val (prevBytes, prevTime) = previous
        val deltaBytes = downloadedBytes - prevBytes
        val deltaSeconds = (now - prevTime) / 1000.0

        // Not actively downloading right now (paused, stalled, or no
        // peers for this file specifically) - 0 B/s and "--" rather than
        // a stale or divide-by-zero number.
        val bytesPerSecond = if (deltaBytes > 0 && deltaSeconds > 0.0) {
            deltaBytes / deltaSeconds
        } else {
            0.0
        }

        val speedText = "${formatSize(bytesPerSecond.toLong())}/s"

        val etaText = if (remainingBytes <= 0 || bytesPerSecond <= 0.0) {
            "--"
        } else {
            formatEtaSeconds((remainingBytes / bytesPerSecond).toLong())
        }

        return FileEtaEstimate(etaText, speedText)
    }

    private fun saveMagnetOnly(magnet: String) {
        val intent = Intent(this, TorrentService::class.java)
        intent.putExtra("ACTION", "SAVE_MAGNET_ONLY")
        intent.putExtra("MAGNET", magnet)
        startTorrentService(intent)
    }

    // ------------------------------------------------- Feature 5: Custom Save Folder
    private fun showSaveFolderScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Save Folder"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        fun infoText(): String =
            "New torrents are saved here by default, unless you pick a " +
                    "different folder for that one torrent when you add it " +
                    "(or change it later from its Details screen).\n\n" +
                    "Current default folder:\n${getGlobalSaveFolder()}"

        val info = TextView(this).apply {
            text = infoText()
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }

        val changeButton = Button(this).apply {
            text = "Change Default Folder"
            setOnClickListener {
                showFolderPickerDialog(getGlobalSaveFolder()) { newPath ->
                    setGlobalSaveFolder(newPath)

                    Toast.makeText(
                        this@MainActivity,
                        "Default save folder updated. This only affects new torrents.",
                        Toast.LENGTH_LONG
                    ).show()

                    info.text = infoText()
                }
            }
        }

        val newFolderButton = Button(this).apply {
            text = "Create Folder Here"
            setOnClickListener {
                showCreateFolderDialog(File(getGlobalSaveFolder())) {
                    info.text = infoText()
                }
            }
        }

        val back = Button(this).apply {
            text = "Back"
            setOnClickListener { showMainScreen() }
        }

        root.addView(title)
        root.addView(info)
        root.addView(changeButton)
        root.addView(newFolderButton)
        root.addView(back)

        val scroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(scroll)
    }

    // Opens the details screen of the torrent with this info-hash (used
    // when a notification is tapped). Does nothing if it is not (yet) in
    // the live session.
    private fun openTorrentDetailsByHash(hash: String) {
        val target = normalizeHashForKey(hash)
        if (target.isBlank()) return

        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        val count = status.lines().count { it.isNotBlank() }

        for (i in 1..count) {
            val h = try {
                TorrentNative.getTorrentHash(i)
            } catch (_: Throwable) {
                ""
            }

            if (normalizeHashForKey(h) == target) {
                showTorrentDetails(i)
                return
            }
        }
    }

    // Notification Settings: which alerts to show, per-torrent progress
    // notifications, update frequency, sound/silent, per-torrent off switch.
    // Move to Top / Up / Down / To Bottom for the selected torrents.
    private fun moveSelectedInQueue(dir: String) {
        if (selectedTorrents.isEmpty()) {
            Toast.makeText(this, "No torrents selected", Toast.LENGTH_SHORT).show()
            return
        }

        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (_: Throwable) {
            ""
        }

        if (status.isBlank() || status == "No torrents" || status == "Engine not ready") {
            return
        }

        val count = status.lines().count { it.isNotBlank() }

        val all = (1..count).map { getSafeTorrentHashForAction(it) }
        val selected = selectedTorrents.map { getSafeTorrentHashForAction(it) }.toSet()
        val visible = getVisibleTorrentIndexes().map { getSafeTorrentHashForAction(it) }.toSet()

        val changed = TorrentQueue.move(this, dir, all, selected, visible)

        if (sortMode != TorrentQueue.SORT_MODE) {
            sortMode = TorrentQueue.SORT_MODE
            torrentListViewPrefs().edit().putString("sort", sortMode).apply()
            Toast.makeText(this, "Sort set to Queue Order", Toast.LENGTH_SHORT).show()
            showMainScreen()
            return
        }

        if (!changed) {
            Toast.makeText(this, "Already there", Toast.LENGTH_SHORT).show()
        }

        updateTorrentList()
    }

    private fun setBackupStatus(text: String) {
        runOnUiThread { backupStatusView?.text = text }
    }

    private fun showBackupRestoreScreen() {
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        root.addView(TextView(this).apply {
            text = "Backup & Restore"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        })

        root.addView(TextView(this).apply {
            text = "A backup is one .zip file with your torrent list, per-torrent " +
                "settings (file selection, speed limits, save folders, paused state), " +
                "all app settings (connection, notifications, search providers, RSS, " +
                "Web UI) and libtorrent progress data so torrents continue without a " +
                "full recheck.\n\nDownloaded files themselves are not included."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 16)
        })

        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(255, 200, 120))
            setPadding(0, 12, 0, 12)
        }
        backupStatusView = status

        root.addView(Button(this).apply {
            text = "Create Backup"
            TabIcons.apply(this, "save", 18, 6)
            setOnClickListener {
                val stamp = java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US
                ).format(java.util.Date())
                try {
                    createBackupLauncher.launch("TorrentOr-backup-$stamp.zip")
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No file picker available", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Restore from Backup"
            TabIcons.apply(this, "folder", 18, 6)
            setOnClickListener {
                try {
                    openBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "No file picker available", Toast.LENGTH_LONG).show()
                }
            }
        })

        root.addView(status)

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener {
                backupStatusView = null
                showMainScreen()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun appVersionLabel(): String {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun runBackupTo(uri: Uri) {
        setBackupStatus("Creating backup...")

        Thread {
            var tmp: File? = null
            try {
                // Make sure the newest progress is on disk first.
                try {
                    TorrentNative.saveResumeData(2500)
                } catch (_: Throwable) {
                }

                tmp = File(cacheDir, "backup_tmp.zip")
                tmp.outputStream().use { out ->
                    BackupManager.createBackup(this, out, appVersionLabel())
                }

                val out = contentResolver.openOutputStream(uri, "wt")
                    ?: throw java.io.IOException("Could not open the chosen file for writing")

                out.use { o ->
                    tmp.inputStream().use { it.copyTo(o) }
                    o.flush()
                }

                val kb = (tmp.length() + 1023) / 1024
                setBackupStatus("Backup saved ($kb KB).")
                runOnUiThread {
                    Toast.makeText(this, "Backup saved", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                setBackupStatus("Backup failed: ${e.message ?: e.javaClass.simpleName}")
                AppLog.warning("Backup failed: ${e.message}")
            } finally {
                try {
                    tmp?.delete()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun previewAndRestore(uri: Uri) {
        setBackupStatus("Reading backup...")

        Thread {
            try {
                val preview = contentResolver.openInputStream(uri)?.use {
                    BackupManager.readPreview(this, it)
                } ?: throw BackupManager.BackupException("Could not open the chosen file.")

                runOnUiThread { showRestoreChoiceDialog(uri, preview) }
            } catch (e: BackupManager.BackupException) {
                setBackupStatus("Cannot restore: ${e.message}")
            } catch (e: Exception) {
                setBackupStatus("Cannot read the backup: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun showRestoreChoiceDialog(uri: Uri, p: BackupManager.Preview) {
        setBackupStatus("")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        box.addView(TextView(this).apply {
            text = "Created: ${BackupManager.formatDate(p.createdAt)}\n" +
                "App version: ${p.appVersion}\n" +
                "Torrents: ${p.torrentCount}\n" +
                "Progress data: ${p.resumeCount} torrent(s)\n" +
                "Settings groups: ${p.settingsFileCount}"
            textSize = 14f
        })

        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 16, 0, 0)
        }
        val mergeRadio = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Merge - add missing torrents and settings, keep what is here"
        }
        val replaceRadio = RadioButton(this).apply {
            id = android.view.View.generateViewId()
            text = "Replace - make this device match the backup"
        }
        group.addView(mergeRadio)
        group.addView(replaceRadio)
        mergeRadio.isChecked = true
        box.addView(group)

        AlertDialog.Builder(this)
            .setTitle("Restore backup")
            .setView(box)
            .setPositiveButton("Restore") { _, _ ->
                if (replaceRadio.isChecked) {
                    AlertDialog.Builder(this)
                        .setTitle("Replace everything?")
                        .setMessage(
                            "All torrents currently in TorrentOr will be removed from " +
                                "the app (their downloaded files stay on disk) and " +
                                "settings will be overwritten by the backup."
                        )
                        .setPositiveButton("Replace") { _, _ -> runRestore(uri, true) }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    runRestore(uri, false)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runRestore(uri: Uri, replace: Boolean) {
        setBackupStatus("Restoring...")

        Thread {
            try {
                val result = contentResolver.openInputStream(uri)?.use {
                    BackupManager.restore(this, it, replace)
                } ?: throw BackupManager.BackupException("Could not open the chosen file.")

                runOnUiThread {
                    try {
                        startTorrentService(
                            Intent(this, TorrentService::class.java).apply {
                                putExtra("ACTION", "RESTORE_RELOAD")
                                putExtra("REPLACE", replace)
                            }
                        )
                    } catch (e: Exception) {
                        AppLog.warning("Restore: could not reach the service: ${e.message}")
                    }

                    val msg = StringBuilder()
                    msg.append(if (replace) "Mode: Replace\n" else "Mode: Merge\n")
                    msg.append("Torrents in backup: ${result.torrentsInBackup}\n")
                    msg.append("Added: ${result.torrentsAdded}\n")
                    if (!replace) msg.append("Already present: ${result.torrentsAlreadyPresent}\n")
                    if (result.torrentsSkipped > 0) {
                        msg.append("Skipped (.torrent file missing): ${result.torrentsSkipped}\n")
                    }
                    msg.append("Progress data restored: ${result.resumeFiles}\n")
                    msg.append("Settings groups: ${result.settingsFiles}")

                    AlertDialog.Builder(this)
                        .setTitle("Restore complete")
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null)
                        .show()

                    // Stay on the Backup & Restore screen.
                    backupStatusView?.text =
                        "Restore complete (" + (if (replace) "Replace" else "Merge") + "): " +
                            result.torrentsAdded + " torrent(s) added."
                }
            } catch (e: BackupManager.BackupException) {
                setBackupStatus("Restore failed: ${e.message}")
            } catch (e: Exception) {
                setBackupStatus("Restore failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    private fun showNotificationSettingsScreen() {
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()
        stopRssUiRefresh()
        stopLogUiRefresh()

        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        fun heading(text: String) = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 6)
        }

        fun note(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 6)
        }

        fun check(text: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

        root.addView(TextView(this).apply {
            text = "Notification Settings"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
        })

        root.addView(note(
            "The ongoing \"TorrentOr is running\" notification is not affected by anything here."
        ))

        val permissionNote = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(255, 170, 120))
            setPadding(0, 4, 0, 4)
        }

        val systemSettingsButton = Button(this).apply {
            text = "Open Android notification settings"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                } catch (_: Throwable) {
                }
            }
        }

        val blocked = !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

        if (blocked) {
            permissionNote.text = "Notifications are currently blocked for TorrentOr in Android settings, so none of these will show."
            root.addView(permissionNote)
        }

        TabIcons.apply(systemSettingsButton, "bell", 18, 6)
        root.addView(systemSettingsButton)

        root.addView(heading("Notification types"))

        for (type in NotificationSettings.EVENT_TYPES) {
            root.addView(check(type.label, NotificationSettings.isTypeEnabled(this, type.id)) {
                NotificationSettings.setTypeEnabled(this, type.id, it)
            })
        }

        root.addView(heading("Progress notifications"))
        root.addView(note(
            "One live notification per downloading torrent with progress, speeds, " +
                    "size, ETA and Pause/Resume, Stop and Open buttons. Stop pauses the " +
                    "torrent and hides its notification until it runs again."
        ))

        root.addView(check(
            "Show a progress notification for each torrent",
            NotificationSettings.progressEnabled(this)
        ) { NotificationSettings.setProgressEnabled(this, it) })

        val intervalButton = Button(this).apply {
            text = "Update every ${NotificationSettings.intervalSeconds(this@MainActivity)} s"
            setOnClickListener {
                val options = NotificationSettings.INTERVAL_CHOICES_SECONDS
                    .map { "$it seconds" }.toTypedArray()

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Progress update frequency")
                    .setItems(options) { _, which ->
                        val sec = NotificationSettings.INTERVAL_CHOICES_SECONDS[which]
                        NotificationSettings.setIntervalSeconds(this@MainActivity, sec)
                        text = "Update every $sec s"
                    }
                    .show()
            }
        }

        TabIcons.apply(intervalButton, "clock", 18, 6)
        root.addView(intervalButton)

        root.addView(heading("Sound"))

        root.addView(check("Play sound for alerts", NotificationSettings.soundEnabled(this)) {
            NotificationSettings.setSoundEnabled(this, it)
        })
        root.addView(check(
            "Silent mode (no sound or vibration for any alert)",
            NotificationSettings.silentMode(this)
        ) { NotificationSettings.setSilentMode(this, it) })
        root.addView(note("Progress notifications are always silent."))

        root.addView(heading("Per torrent"))

        val perTorrentButton = Button(this).apply {
            text = "Choose torrents to notify about"
            setOnClickListener {
                val status = try {
                    TorrentNative.getDetailedStatus()
                } catch (_: Throwable) {
                    ""
                }

                val lines = if (status == "No torrents" || status == "No active torrents") {
                    emptyList()
                } else {
                    status.lines().filter { it.isNotBlank() }
                }

                val names = mutableListOf<String>()
                val hashes = mutableListOf<String>()

                for (i in lines.indices) {
                    val h = try {
                        TorrentNative.getTorrentHash(i + 1)
                    } catch (_: Throwable) {
                        ""
                    }

                    if (normalizeHashForKey(h).isBlank() || h.startsWith("ERROR") ||
                        h == "Hash not ready" || h == "Invalid torrent"
                    ) continue

                    names.add(extractTorrentName(lines[i], i + 1))
                    hashes.add(h)
                }

                if (names.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No torrents yet", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val checked = BooleanArray(names.size) {
                    !NotificationSettings.isTorrentDisabled(this@MainActivity, hashes[it])
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Notify about (unchecked = off)")
                    .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked ->
                        NotificationSettings.setTorrentDisabled(
                            this@MainActivity, hashes[which], !isChecked
                        )
                    }
                    .setPositiveButton("Done", null)
                    .show()
            }
        }

        TabIcons.apply(perTorrentButton, "select", 18, 6)
        root.addView(perTorrentButton)
        root.addView(note("Unchecked torrents get no progress or alert notifications at all."))

        root.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { showMainScreen() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showStorageSpaceScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null

        stopNetworkFeaturesAutoRefresh()
        stopGlobalStatsAutoRefresh()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor())
        }

        val title = TextView(this).apply {
            text = "Storage Space"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }

        val info = TextView(this).apply {
            text = buildStorageSpaceText()
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 16)
        }

        startStorageAutoRefresh(info)

        val refresh = Button(this).apply {
            text = "Refresh"
            setOnClickListener {
                info.text = buildStorageSpaceText()
            }
        }

        val thresholdLabel = TextView(this).apply {
            text = "Low Space Warning Threshold (MB)"
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 4)
        }

        val thresholdInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(getLowSpaceThresholdMb().toString())
        }

        val applyThresholdButton = Button(this).apply {
            text = "Apply Threshold"
            setOnClickListener {
                val mb = thresholdInput.text.toString().toIntOrNull()

                if (mb == null || mb <= 0) {
                    Toast.makeText(
                        this@MainActivity,
                        "Enter a threshold greater than 0",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                storageSettingsPrefs().edit()
                    .putInt("low_space_threshold_mb", mb)
                    .apply()

                Toast.makeText(
                    this@MainActivity,
                    "Low space threshold set to $mb MB",
                    Toast.LENGTH_SHORT
                ).show()

                info.text = buildStorageSpaceText()
            }
        }

        val back = Button(this).apply {
            text = "Back"
            setOnClickListener {
                stopStorageAutoRefresh()
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(info)
        root.addView(refresh)
        root.addView(thresholdLabel)
        root.addView(thresholdInput)
        root.addView(applyThresholdButton)
        root.addView(back)

        val scroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(scroll)
    }

    // Feature: the Free Space figure (and the warning line below it) turns
    // red the moment free space reaches the configured Low Space Warning
    // Threshold, instead of only the plain-text "⚠ LOW SPACE WARNING" line
    // that was already there - a value that's impossible to miss at a
    // glance rather than one more line of text to read. Returns a
    // SpannableStringBuilder instead of a plain String so the red can be
    // applied to just that figure; TextView.text accepts either.
    private fun buildStorageSpaceText(): android.text.SpannableStringBuilder {
        // Feature 5: individual torrents can now live in different custom
        // folders (see the Save Folder screen and the per-torrent picker on
        // each add screen) - this still monitors the global default
        // folder's volume, a reasonable approximation since custom folders
        // usually end up on the same storage partition.
        val monitoredPath = getGlobalSaveFolder()
        val builder = android.text.SpannableStringBuilder()

        try {
            val stat = StatFs(monitoredPath)

            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            val usedPercent = if (total > 0L) {
                (used.toDouble() / total.toDouble()) * 100.0
            } else {
                0.0
            }

            val thresholdMb = getLowSpaceThresholdMb()
            val isLow = free < getLowSpaceThresholdBytes()

            builder.append("Save Path:\n$monitoredPath\n\n")
            builder.append("Free Space: ")
            appendStorageSpan(builder, formatSize(free), isLow)
            builder.append("\n")
            builder.append("Used Space: ${formatSize(used)}\n")
            builder.append("Total Space: ${formatSize(total)}\n")
            builder.append("Used: ${String.format("%.1f", usedPercent)}%\n")
            builder.append("Low Space Warning Threshold: $thresholdMb MB")

            if (isLow) {
                builder.append("\n")
                appendStorageSpan(
                    builder,
                    "⚠ LOW SPACE WARNING - free space is below the " +
                            "$thresholdMb MB threshold",
                    true
                )
            }

            builder.append("\n\nAuto-refresh: Every 1 second")
        } catch (e: Throwable) {
            builder.clear()
            builder.append("Could not read storage space\n\n$monitoredPath")
        }

        return builder
    }

    private fun appendStorageSpan(
        builder: android.text.SpannableStringBuilder,
        text: String,
        red: Boolean
    ) {
        val start = builder.length
        builder.append(text)

        if (red) {
            builder.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor("#FF5252")),
                start,
                builder.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun startStorageAutoRefresh(info: TextView) {
        stopStorageAutoRefresh()

        isStorageScreenActive = true

        val runnable = object : Runnable {
            override fun run() {
                if (!isStorageScreenActive) {
                    return
                }

                try {
                    info.text = buildStorageSpaceText()
                } catch (_: Throwable) {
                    // Keep screen alive even if storage temporarily cannot be read.
                }

                handler.postDelayed(this, 1000L)
            }
        }

        storageRefreshRunnable = runnable
        handler.postDelayed(runnable, 1000L)
    }

    private fun stopStorageAutoRefresh() {
        isStorageScreenActive = false

        storageRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        storageRefreshRunnable = null
    }


    // Share the magnet links of the selected torrents through the share
    // sheet, one link per line. Torrents without a magnet link yet (metadata
    // not loaded) are skipped and counted in a short note.
    private fun shareSelectedMagnets() {
        if (selectedTorrents.isEmpty()) {
            Toast.makeText(this, "No torrents selected", Toast.LENGTH_SHORT).show()
            return
        }

        val magnets = mutableListOf<String>()
        var skipped = 0

        for (index in selectedTorrents.sorted()) {
            val magnet = try {
                TorrentNative.getTorrentMagnet(index)
            } catch (_: Throwable) {
                ""
            }

            if (magnet.startsWith("magnet:")) {
                magnets.add(magnet)
            } else {
                skipped++
            }
        }

        if (magnets.isEmpty()) {
            Toast.makeText(this, "No magnet links ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (skipped > 0) {
            Toast.makeText(
                this,
                "${magnets.size} of ${magnets.size + skipped} shared, $skipped not ready",
                Toast.LENGTH_SHORT
            ).show()
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, magnets.joinToString("\n"))
        }

        try {
            startActivity(Intent.createChooser(send, "Share magnet link"))
        } catch (_: Throwable) {
            Toast.makeText(this, "Could not open the share menu", Toast.LENGTH_SHORT).show()
        }
    }

    // A dark popup menu whose rows each have a coloured icon (the stock
    // PopupMenu cannot show icons). A row with enabled = false is dimmed and
    // does nothing.
    private class IconMenuItem(
        val label: String,
        val icon: String?,
        val enabled: Boolean,
        val action: () -> Unit
    )

    private fun showIconMenu(anchor: android.view.View, items: List<IconMenuItem>) {
        val d = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(28, 30, 38))
                cornerRadius = 14f * d
            }
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }

        val popup = android.widget.PopupWindow(
            card,
            (250 * d).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 16f * d

        for (item in items) {
            val row = TextView(this).apply {
                text = item.label
                textSize = 17f
                setTextColor(if (item.enabled) Color.WHITE else Color.parseColor("#6B7280"))
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * d).toInt(), (13 * d).toInt(), (20 * d).toInt(), (13 * d).toInt())
                alpha = 1f

                if (item.enabled) {
                    setOnClickListener {
                        popup.dismiss()
                        item.action()
                    }
                }
            }

            val icon = item.icon
            if (icon != null) {
                TabIcons.apply(row, icon, 22, 14, true)
            }

            card.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        popup.showAsDropDown(anchor, 0, 0, android.view.Gravity.END)
    }

    // ---- Three-dot menu on the main screen header + Shutdown ----

    private fun showMainOverflowMenu(anchor: android.view.View) {
        val d = resources.displayMetrics.density

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(0, 0, 0))
                cornerRadius = 20f * d
            }
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }

        val popup = android.widget.PopupWindow(
            card,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 16f * d

        val shutdownRow = TextView(this).apply {
            text = "Shutdown"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((22 * d).toInt(), (10 * d).toInt(), (26 * d).toInt(), (10 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(13, 71, 161))
                cornerRadius = 13f * d
            }
            setOnClickListener {
                popup.dismiss()
                confirmShutdown()
            }
        }
        TabIcons.apply(shutdownRow, "powerred", 54, 18, true)

        val rowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 0)
        }
        card.addView(shutdownRow, rowParams)

        popup.showAsDropDown(anchor, (-28 * d).toInt(), 0, android.view.Gravity.END)
    }

    private fun confirmShutdown() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Shut down TorrentOr?")
            .setMessage(
                "Your torrents are saved first. Downloads, seeding and the Web UI stop " +
                        "until you open TorrentOr again."
            )
            .setPositiveButton("Shut down") { _, _ -> shutdownApp() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Saves every torrent's resume data, turns off the Web UI, stops the
    // service (which removes the notification) and ends the process so the
    // engine really stops. Everything is restored the next time the app opens.
    //
    // The app stays on screen behind a "Shutting down" box until everything
    // is saved, and the process is ended a split second after the window
    // closes. That way there is almost no moment where the app looks closed
    // but its process is still dying - opening the app again in that gap
    // used to start it inside the dying process, so it opened and closed.
    private fun shutdownApp() {
        AppLog.info("Shutdown requested from the main screen")

        try {
            android.app.AlertDialog.Builder(this)
                .setMessage("Shutting down TorrentOr...")
                .setCancelable(false)
                .show()
        } catch (_: Throwable) {
        }

        Thread {
            try {
                TorrentNative.saveResumeData(2500)
            } catch (_: Throwable) {
            }

            runOnUiThread {
                try {
                    WebUiServer.stop()
                } catch (_: Throwable) {
                }

                try {
                    stopService(Intent(this, TorrentService::class.java))
                } catch (_: Throwable) {
                }
            }

            // Let the system register the service stop (so it is not
            // restarted), then close the window and end the process at once.
            try {
                Thread.sleep(700)
            } catch (_: InterruptedException) {
            }

            runOnUiThread { finishAndRemoveTask() }

            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
            }

            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    private fun startTorrentService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}


// Marks a button that already has the Deep Blue look.
private class DeepBlueRipple(
    color: android.content.res.ColorStateList,
    content: android.graphics.drawable.Drawable
) : android.graphics.drawable.RippleDrawable(color, content, null)
