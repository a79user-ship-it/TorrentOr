package com.example.torrentor

import android.content.Context
import java.io.File

// Finds the storage locations a save folder can live on: the phone's
// internal storage plus any removable SD card(s). Used by the app's folder
// picker and by the Web UI's folder picker so both offer the same quick
// "Internal storage" / "SD card" jump buttons.
//
// An SD card is found two ways (the union of both, so it works across
// Android versions and phone brands): the per-volume app folders Android
// reports (".../Android/data/<pkg>/files" -> the part before "/Android"),
// and any volume-id style folder (like "/storage/1A2B-3C4D") directly under
// /storage. Nothing here touches or changes any files.
object StorageRoots {

    const val INTERNAL_PATH = "/storage/emulated/0"

    class Root(val name: String, val path: String, val available: Boolean)

    // First entry is always internal storage. SD card entries follow; when
    // no SD card can be found, one unavailable "SD card" entry is added so
    // the button can still be shown and explain itself.
    fun list(context: Context): List<Root> {
        val result = mutableListOf(Root("Internal storage", INTERNAL_PATH, true))
        val sdPaths = linkedSetOf<String>()

        try {
            val dirs = context.applicationContext.getExternalFilesDirs(null)
            for (dir in dirs) {
                val p = dir?.absolutePath ?: continue
                if (p.startsWith(INTERNAL_PATH) || p.startsWith("/storage/emulated/")) continue
                val marker = p.indexOf("/Android/")
                if (marker > 0) sdPaths.add(p.substring(0, marker))
            }
        } catch (_: Throwable) {
        }

        try {
            val volumeId = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
            File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && volumeId.matches(f.name)) sdPaths.add(f.absolutePath)
            }
        } catch (_: Throwable) {
        }

        val found = sdPaths.filter { File(it).isDirectory }

        if (found.isEmpty()) {
            result.add(Root("SD card", "", false))
        } else {
            found.forEachIndexed { i, p ->
                result.add(Root(if (found.size == 1) "SD card" else "SD card ${i + 1}", p, true))
            }
        }

        return result
    }
}
