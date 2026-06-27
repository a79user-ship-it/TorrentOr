package com.example.torrentor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.os.Looper
import android.os.StatFs
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var listLayout: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val interval = 3000L
    private val savePath = "/storage/emulated/0/Download"

    private var selectMode = false
    private val selectedTorrents = mutableSetOf<Int>()
    private var skipMagnetSelection = false
    private var activeFilter = "All"
    private var searchQuery = ""

    private var currentDetailsTab = ""
    private var currentDetailsTorrentIndex = -1
    private var currentDetailsContentText: TextView? = null
    private var currentDetailsFileListLayout: LinearLayout? = null

    private var networkFeaturesRefreshRunnable: Runnable? = null
    private var isNetworkFeaturesScreenActive = false

    private var storageRefreshRunnable: Runnable? = null
    private var isStorageScreenActive = false

    private var globalStatsRefreshRunnable: Runnable? = null
    private var isGlobalStatsScreenActive = false

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
            else -> Color.parseColor("#42A5F5")
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
            else -> "blue"
        }

        prefs.edit()
            .putString("theme_color", next)
            .apply()

        showMainScreen()
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

        // Start TorrentService every time the app opens.
        // This reloads saved torrents if Android killed the native session/service.
        val serviceIntent = Intent(this, TorrentService::class.java)
        startTorrentService(serviceIntent)

        showMainScreen()
        startUiUpdates()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // FIX: Consume the intent immediately with setIntent() before processing it.
    // This prevents the dialog from appearing again if the activity is recreated
    // (e.g. after cancelling), because onCreate will then find a plain blank intent
    // with no ACTION_VIEW data.
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_VIEW) return

        val data = intent.data ?: return
        val uriText = data.toString()

        // Consume the intent before doing anything else
        setIntent(Intent(this, MainActivity::class.java))

        if (uriText.startsWith("magnet:")) {
            val hash = extractHashFromMagnetInput(uriText)

            if (isTorrentAlreadyAddedByHash(hash)) {
                showAlreadyAddedMessage()
                return
            }

            showMagnetMetadataScreen(uriText)
            return
        }

        try {
            val filePath = copyTorrentToFiles(uriText)
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
        stopNetworkFeaturesAutoRefresh()
        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()

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
                    .setNeutralButton("Remove Only") { _, _ ->
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
                    showAlreadyAddedMessage()
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

        val clearSaved = Button(this).apply {
            text = "Clean Deleted Torrents"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("ACTION", "CLEAN_DELETED_SAVED_TORRENTS")
                startTorrentService(intent)

                Toast.makeText(
                    this@MainActivity,
                    "Old deleted torrents cleaned",
                    Toast.LENGTH_SHORT
                ).show()
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

        val storageSpaceButton = Button(this).apply {
            text = "Storage Space"
            setOnClickListener {
                showStorageSpaceScreen()
            }
        }

        val filterTitle = TextView(this).apply {
            text = "Filter: $activeFilter"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 4)
        }

        val filterRowOne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val filterRowTwo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        fun makeFilterButton(name: String): Button {
            return Button(this).apply {
                text = if (activeFilter == name) "[$name]" else name
                setOnClickListener {
                    activeFilter = name
                    selectedTorrents.clear()
                    selectMode = false
                    showMainScreen()
                }
            }
        }

        filterRowOne.addView(makeFilterButton("All"))
        filterRowOne.addView(makeFilterButton("Downloading"))
        filterRowOne.addView(makeFilterButton("Seeding"))

        filterRowTwo.addView(makeFilterButton("Paused"))
        filterRowTwo.addView(makeFilterButton("Completed"))

        listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(title)
        root.addView(themeButton)
        root.addView(selectModeButton)
        root.addView(forceRecheckSelected)
        root.addView(forceReannounceSelected)
        root.addView(pauseSelected)
        root.addView(resumeSelected)
        root.addView(removeSelected)
        root.addView(magnetInput)
        root.addView(addMagnet)
        root.addView(selectFile)
        root.addView(searchBox)
        root.addView(clearSearch)
        root.addView(pauseAll)
        root.addView(resumeAll)
        root.addView(clearSaved)
        root.addView(globalStatistics)
        root.addView(portForwardingStatus)
        root.addView(networkFeatures)
        root.addView(storageSpaceButton)
        root.addView(filterTitle)
        root.addView(horizontalScrollFor(filterRowOne))
        root.addView(horizontalScrollFor(filterRowTwo))
        root.addView(listLayout)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
        updateTorrentList()
    }

    private fun showNetworkFeaturesScreen() {
        currentDetailsTab = ""
        currentDetailsTorrentIndex = -1
        currentDetailsContentText = null
        currentDetailsFileListLayout = null

        stopStorageAutoRefresh()
        stopGlobalStatsAutoRefresh()

        TorrentNative.startSession(savePath)

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

        fun refreshStatus() {
            statusText.text = buildNetworkFeaturesColoredText()
        }

        startNetworkFeaturesAutoRefresh(statusText)

        val dhtRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enableDht = Button(this).apply {
            text = "Enable DHT"
            setOnClickListener {
                TorrentNative.setDhtEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "DHT enabled", Toast.LENGTH_SHORT).show()
            }
        }

        val disableDht = Button(this).apply {
            text = "Disable DHT"
            setOnClickListener {
                TorrentNative.setDhtEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "DHT disabled", Toast.LENGTH_SHORT).show()
            }
        }

        dhtRow.addView(enableDht)
        dhtRow.addView(disableDht)

        val pexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enablePex = Button(this).apply {
            text = "Enable PEX"
            setOnClickListener {
                TorrentNative.setPexEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "PEX enabled", Toast.LENGTH_SHORT).show()
            }
        }

        val disablePex = Button(this).apply {
            text = "Disable PEX"
            setOnClickListener {
                TorrentNative.setPexEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "PEX disabled", Toast.LENGTH_SHORT).show()
            }
        }

        pexRow.addView(enablePex)
        pexRow.addView(disablePex)

        val lsdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val enableLsd = Button(this).apply {
            text = "Enable LSD"
            setOnClickListener {
                TorrentNative.setLsdEnabled(true)
                refreshStatus()
                Toast.makeText(this@MainActivity, "LSD enabled", Toast.LENGTH_SHORT).show()
            }
        }

        val disableLsd = Button(this).apply {
            text = "Disable LSD"
            setOnClickListener {
                TorrentNative.setLsdEnabled(false)
                refreshStatus()
                Toast.makeText(this@MainActivity, "LSD disabled", Toast.LENGTH_SHORT).show()
            }
        }

        lsdRow.addView(enableLsd)
        lsdRow.addView(disableLsd)

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
        root.addView(refreshButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
    }

    private fun buildNetworkFeaturesText(): String {
        TorrentNative.startSession(savePath)

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
        TorrentNative.startSession(savePath)

        val networkStatus = try {
            buildString {
                append("Network Features\n\n")
                append(TorrentNative.getDhtStatus())
                append("\n\n")
                append(TorrentNative.getPexStatus())
                append("\n\n")
                append(TorrentNative.getLsdStatus())
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
        builder.append("LSD shows local network discovery status.")

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

                handler.postDelayed(this, interval)
            }
        }

        networkFeaturesRefreshRunnable = runnable
        handler.postDelayed(runnable, interval)
    }

    private fun stopNetworkFeaturesAutoRefresh() {
        isNetworkFeaturesScreenActive = false

        networkFeaturesRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        networkFeaturesRefreshRunnable = null
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

                handler.postDelayed(this, interval)
            }
        }

        globalStatsRefreshRunnable = runnable
        handler.postDelayed(runnable, interval)
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

    private fun showAlreadyAddedMessage() {
        Toast.makeText(
            this,
            "Torrent already added",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showMagnetMetadataScreen(magnet: String) {
        val hash = extractHashFromMagnetInput(magnet)

        if (isTorrentAlreadyAddedByHash(hash)) {
            showAlreadyAddedMessage()
            return
        }

        skipMagnetSelection = false

        val torrentIndex = try {
            TorrentNative.addMagnetPaused(magnet, savePath)
        } catch (e: Throwable) {
            e.printStackTrace()
            -1
        }

        if (torrentIndex <= 0) {
            Toast.makeText(this, "Could not open magnet", Toast.LENGTH_SHORT).show()
            return
        }

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

        val downloadNowButton = Button(this).apply {
            text = "Download All Without Waiting"
            setOnClickListener {
                skipMagnetSelection = true

                saveMagnetOnly(magnet)
                TorrentNative.resumeTorrent(torrentIndex)

                Toast.makeText(
                    this@MainActivity,
                    "Download started. Files will begin after metadata is ready.",
                    Toast.LENGTH_SHORT
                ).show()

                showMainScreen()
            }
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                skipMagnetSelection = true
                TorrentNative.removeTorrent(torrentIndex, false)
                showMainScreen()
            }
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(downloadNowButton)
        root.addView(cancelButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)

        waitForMagnetMetadata(torrentIndex, magnet)
    }

    private fun waitForMagnetMetadata(torrentIndex: Int, magnet: String) {
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

                    showMagnetFileSelection(torrentIndex, magnet, files)
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
        fileData: String
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
                text = "$name (${formatSize(sizeBytes)})"
                setTextColor(Color.WHITE)
                isChecked = true

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(index)
                    else selected.remove(index)
                }
            }

            fileListLayout.addView(checkBox)
        }

        val scroll = ScrollView(this).apply {
            addView(fileListLayout)
        }

        val downloadSelected = Button(this).apply {
            text = "Download Selected"
            setOnClickListener {
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Select at least one file", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val indexes = selected.sorted().joinToString(",")

                saveMagnetOnly(magnet)
                TorrentNative.setTorrentFilePriorities(torrentIndex, indexes)
                TorrentNative.resumeTorrent(torrentIndex)

                Toast.makeText(
                    this@MainActivity,
                    "Magnet download started",
                    Toast.LENGTH_SHORT
                ).show()

                showMainScreen()
            }
        }

        val downloadAll = Button(this).apply {
            text = "Download All"
            setOnClickListener {
                val indexes = allIndexes.sorted().joinToString(",")

                saveMagnetOnly(magnet)
                TorrentNative.setTorrentFilePriorities(torrentIndex, indexes)
                TorrentNative.resumeTorrent(torrentIndex)

                Toast.makeText(
                    this@MainActivity,
                    "Magnet download started",
                    Toast.LENGTH_SHORT
                ).show()

                showMainScreen()
            }
        }

        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                TorrentNative.removeTorrent(torrentIndex, false)
                showMainScreen()
            }
        }

        container.addView(title)
        container.addView(scroll)
        container.addView(downloadSelected)
        container.addView(downloadAll)
        container.addView(cancel)

        val outerScroll = ScrollView(this).apply {
            addView(container)
        }

        setContentView(outerScroll)
    }

    private fun showTorrentFileSelection(filePath: String) {
        try {
            val hash = TorrentNative.getTorrentFileHash(filePath)

            if (
                hash.isNotBlank() &&
                isTorrentAlreadyAddedByHash(hash)
            ) {
                showAlreadyAddedMessage()
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
        val lines = fileData.split("\n").filter { it.isNotBlank() }

        for (line in lines) {
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue

            val index = parts[0].toIntOrNull() ?: continue
            val name = parts[1]
            val sizeBytes = parts[2].toLongOrNull() ?: 0L

            val checkBox = CheckBox(this).apply {
                text = "$name (${formatSize(sizeBytes)})"
                setTextColor(Color.WHITE)
                isChecked = true
                selected.add(index)

                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(index)
                    else selected.remove(index)
                }
            }

            fileListLayout.addView(checkBox)
        }

        val scroll = ScrollView(this).apply {
            addView(fileListLayout)
        }

        val downloadSelected = Button(this).apply {
            text = "Download Selected"
            setOnClickListener {
                if (selected.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Select at least one file", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val indexes = selected.sorted().joinToString(",")

                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("TORRENT_PATH", filePath)
                intent.putExtra("SELECTED_INDEXES", indexes)

                startTorrentService(intent)
                showMainScreen()
            }
        }

        val downloadAll = Button(this).apply {
            text = "Download All"
            setOnClickListener {
                val intent = Intent(this@MainActivity, TorrentService::class.java)
                intent.putExtra("TORRENT_PATH", filePath)
                startTorrentService(intent)
                showMainScreen()
            }
        }

        val back = Button(this).apply {
            text = "Back"
            setOnClickListener { showMainScreen() }
        }

        container.addView(title)
        container.addView(scroll)
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

                contentText.text = try {
                    TorrentNative.getTorrentTrackerStatus(torrentIndex)
                } catch (e: Throwable) {
                    try {
                        "Trackers\n\n" + TorrentNative.getTorrentTrackers(torrentIndex)
                    } catch (e2: Throwable) {
                        "Could not load tracker status"
                    }
                }
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

                contentText.text = try {
                    TorrentNative.getTorrentSwarmHealth(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load swarm health"
                }
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
                    TorrentNative.getTorrentStatistics(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load statistics"
                }

                contentText.text = stats
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

        tabRowOne.addView(generalButton)
        tabRowOne.addView(filesButton)
        tabRowOne.addView(peersButton)

        tabRowTwo.addView(trackersButton)
        tabRowTwo.addView(webSeedsButton)
        tabRowTwo.addView(swarmButton)
        tabRowTwo.addView(piecesButton)
        tabRowTwo.addView(statisticsButton)
        tabRowTwo.addView(commentButton)

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
                showGeneralTab()
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
                showGeneralTab()
            }
        }


        val forceReannounce = Button(this).apply {
            text = "Force Reannounce"
            setOnClickListener {
                TorrentNative.forceReannounce(torrentIndex)
                Toast.makeText(this@MainActivity, "Reannounce sent", Toast.LENGTH_SHORT).show()
                showGeneralTab()
            }
        }

        val forceRecheck = Button(this).apply {
            text = "Force Recheck"
            setOnClickListener {
                TorrentNative.forceRecheck(torrentIndex)
                Toast.makeText(this@MainActivity, "Force recheck started", Toast.LENGTH_SHORT).show()
                showGeneralTab()
            }
        }

        val openFolderButton = Button(this).apply {
            text = "Open Folder"
            setOnClickListener {
                openDownloadFolder()
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
        root.addView(actionTitle)
        root.addView(copyMagnet)
        root.addView(copyHash)
        root.addView(pauseButton)
        root.addView(resumeButton)
        root.addView(forceReannounce)
        root.addView(forceRecheck)
        root.addView(openFolderButton)
        root.addView(backButton)

        val outerScroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(outerScroll)
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

        contentText.text =
            if (pieceSize.isBlank()) {
                pieceInfo
            } else {
                "$pieceInfo\n\n$pieceSize"
            }
    }

    // Auto-refresh all live tabs every 3 seconds.
    // Trackers and Files are intentionally excluded — static data.
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
                contentText.text = try {
                    TorrentNative.getTorrentSwarmHealth(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load swarm health"
                }
            }
            "Pieces" -> showPiecesDetails(torrentIndex, contentText)
            "Statistics" -> {
                contentText.text = try {
                    TorrentNative.getTorrentStatistics(torrentIndex)
                } catch (e: Throwable) {
                    "Could not load statistics"
                }
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
                contentText.text = try {
                    TorrentNative.getTorrentTrackerStatus(torrentIndex)
                } catch (e: Throwable) {
                    try {
                        "Trackers\n\n" + TorrentNative.getTorrentTrackers(torrentIndex)
                    } catch (e2: Throwable) {
                        "Could not load tracker status"
                    }
                }
            }
            "Files" -> {
                val fileListLayout = currentDetailsFileListLayout
                if (fileListLayout != null) {
                    showFilesForOpening(
                        torrentIndex,
                        fileListLayout,
                        contentText
                    )
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

        val creator = try {
            TorrentNative.getTorrentCreator(torrentIndex)
        } catch (e: Throwable) {
            "Unknown"
        }

        val createdDate = try {
            TorrentNative.getTorrentCreationDate(torrentIndex)
        } catch (e: Throwable) {
            "Unknown"
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
        text.append("Status: ").append(state).append("\n")
        text.append("Progress: ").append(progress).append("\n")
        text.append("Download: ").append(download).append("\n")
        text.append("Upload: ").append(upload).append("\n")
        text.append("Seeds: ").append(seeds).append("\n")
        text.append("Peers: ").append(peers).append("\n")
        text.append("ETA: ").append(eta).append("\n")
        text.append("Added: ").append(addedDate).append("\n")
        text.append("Completed: ").append(completedDate).append("\n")
        text.append(trackerHost).append("\n")
        text.append("Total size: ").append(totalSize).append("\n")
        text.append("Availability: ").append(availability).append("\n")
        text.append("Swarm:\n").append(swarmHealth).append("\n")
        text.append("Web Seeds: ").append(webSeedCount).append("\n")
        text.append("Creator: ").append(creator).append("\n")
        text.append("Created: ").append(createdDate).append("\n")
        text.append("Private: ").append(privateTorrent).append("\n")
        text.append("Source: ").append(source).append("\n")
        text.append("Encoding: ").append(encoding).append("\n")

        if (pieceSize.isNotBlank()) {
            text.append(pieceSize).append("\n")
        }

        text.append("\nSave path:\n").append(savePath).append("\n\n")
        text.append("Hash:\n").append(hash).append("\n\n")
        text.append("Magnet:\n").append(magnet)

        contentText.text = text.toString()
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
            .apply()
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

    private fun showFilesForOpening(
        torrentIndex: Int,
        fileListLayout: LinearLayout,
        contentText: TextView
    ) {
        fileListLayout.removeAllViews()

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

        contentText.text =
            "Files\n\n" +
                    "Tick the files you want TorrentOr to download.\n" +
                    "Untick files you want to skip.\n" +
                    "Then press Apply File Selection.\n\n" +
                    "Each file shows its own download progress.\n" +
                    "Tap Open beside a file to open it.\n" +
                    "Long press Open for Share / Delete / Rename."

        val selected = mutableSetOf<Int>()
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
            val file = File(savePath, name)

            allIndexes.add(index)

            val shouldBeChecked = savedSelection?.contains(index) ?: true

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

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 12)
            }

            val checkBox = CheckBox(this).apply {
                text = "$name (${formatSize(sizeBytes)})"
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
                text = "Progress: $percent%  •  $downloadedText"
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
                progressDrawable.setTint(Color.BLACK)
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

        fileListLayout.addView(applyBottomButton)
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

    private fun openDownloadFolder() {
        try {
            val folderUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download"
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

    private fun startUiUpdates() {
        handler.post(object : Runnable {
            override fun run() {
                updateTorrentList()
                updateActiveDetailsTab()
                handler.postDelayed(this, interval)
            }
        })
    }

    private fun updateTorrentList() {
        if (!::listLayout.isInitialized) return

        listLayout.removeAllViews()

        val status = try {
            TorrentNative.getDetailedStatus()
        } catch (e: Exception) {
            "Engine not ready"
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
        val filteredLines = allLines.withIndex().filter { item ->
            torrentMatchesFilter(item.value)
        }

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
                setPadding(16, 16, 16, 16)
                setBackgroundColor(cardColor())
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
                    }
                }

                card.addView(checkBox)
            } else {
                card.setOnClickListener {
                    showTorrentDetails(torrentIndex)
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

            buttonRow.addView(pause)
            buttonRow.addView(resume)
            buttonRow.addView(remove)

            card.addView(label)
            card.addView(progressBar)
            card.addView(buttonRow)

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

    private fun showRemoveDialog(torrentIndex: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove Torrent?")
            .setMessage("Choose how you want to remove this torrent.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove Only") { _, _ ->
                val intent = Intent(this, TorrentService::class.java)
                intent.putExtra("ACTION", "REMOVE_TORRENT")
                intent.putExtra("TORRENT_INDEX", torrentIndex)
                intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
                intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))
                intent.putExtra("DELETE_FILES", false)
                startTorrentService(intent)

                Toast.makeText(this, "Torrent removed", Toast.LENGTH_SHORT).show()
                showMainScreen()
            }
            .setPositiveButton("Remove + Files") { _, _ ->
                val intent = Intent(this, TorrentService::class.java)
                intent.putExtra("ACTION", "REMOVE_TORRENT")
                intent.putExtra("TORRENT_INDEX", torrentIndex)
                intent.putExtra("TORRENT_HASH", getSafeTorrentHashForAction(torrentIndex))
                intent.putExtra("TORRENT_MAGNET", getSafeTorrentMagnetForAction(torrentIndex))
                intent.putExtra("DELETE_FILES", true)
                startTorrentService(intent)

                Toast.makeText(this, "Torrent and files removed", Toast.LENGTH_SHORT).show()
                showMainScreen()
            }
            .show()
    }

    private fun extractPercent(line: String): Int {
        val match = Regex("""(\d+)%""").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
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

    private fun saveMagnetOnly(magnet: String) {
        val intent = Intent(this, TorrentService::class.java)
        intent.putExtra("ACTION", "SAVE_MAGNET_ONLY")
        intent.putExtra("MAGNET", magnet)
        startTorrentService(intent)
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
        root.addView(back)

        val scroll = ScrollView(this).apply {
            addView(root)
        }

        setContentView(scroll)
    }

    private fun buildStorageSpaceText(): String {
        return try {
            val stat = StatFs(savePath)

            val total = stat.totalBytes
            val free = stat.availableBytes
            val used = total - free
            val usedPercent = if (total > 0L) {
                (used.toDouble() / total.toDouble()) * 100.0
            } else {
                0.0
            }

            "Save Path:\n$savePath\n\n" +
                    "Free Space: ${formatSize(free)}\n" +
                    "Used Space: ${formatSize(used)}\n" +
                    "Total Space: ${formatSize(total)}\n" +
                    "Used: ${String.format("%.1f", usedPercent)}%\n\n" +
                    "Auto-refresh: Every 3 seconds"
        } catch (e: Throwable) {
            "Could not read storage space\n\n$savePath"
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

                handler.postDelayed(this, interval)
            }
        }

        storageRefreshRunnable = runnable
        handler.postDelayed(runnable, interval)
    }

    private fun stopStorageAutoRefresh() {
        isStorageScreenActive = false

        storageRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        storageRefreshRunnable = null
    }


    private fun startTorrentService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}