package com.example.torrentor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.core.graphics.PathParser

// Flat, multi-colour icons for buttons and tabs.
//
// Every icon is a few coloured shapes drawn in a 24x24 box: solid fills for
// the main body and thick rounded strokes for details. The same shapes (and
// colours) are used in the Web UI (see WebUiServer.kt), so the app and the
// browser look alike. The icons keep their own colours on every theme.
//
// Usage:  TabIcons.apply(someButton, "rss")
object TabIcons {

    // One coloured shape: a fill, a stroke, or both.
    private class Layer(
        val d: String,
        val fill: Int?,
        val stroke: Int?,
        val width: Float
    )

    private fun f(d: String, color: String) = Layer(d, Color.parseColor(color), null, 0f)

    private fun s(d: String, color: String, width: Float) =
        Layer(d, null, Color.parseColor(color), width)

    private fun fs(d: String, fillColor: String, strokeColor: String, width: Float) =
        Layer(d, Color.parseColor(fillColor), Color.parseColor(strokeColor), width)

    private val ICONS: Map<String, List<Layer>> by lazy {
        mapOf(
        "general" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#42A5F5"),
            s("M12 11v6", "#FFFFFF", 2.6f),
            s("M12 7h.01", "#FFFFFF", 3.4f)
        ),
        "files" to listOf(
            f("M6 2h8l6 6v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "#64B5F6"),
            f("M14 2v6h6z", "#1E88E5"),
            s("M8 13h8", "#FFFFFF", 2f),
            s("M8 17h6", "#FFFFFF", 2f)
        ),
        "priority" to listOf(
            s("M5 3v18", "#90A4AE", 2.2f),
            s("M12 3v18", "#90A4AE", 2.2f),
            s("M19 3v18", "#90A4AE", 2.2f),
            f("M1.8 15a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", "#EF5350"),
            f("M8.8 8a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", "#FFCA28"),
            f("M15.8 14a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z", "#42A5F5")
        ),
        "peers" to listOf(
            f("M14 7a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#90CAF9"),
            f("M13 20v-1a4 4 0 0 1 4-4h1a4 4 0 0 1 4 4v1z", "#90CAF9"),
            f("M5.5 8a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", "#42A5F5"),
            f("M2 21v-2a5 5 0 0 1 5-5h4a5 5 0 0 1 5 5v2z", "#1E88E5")
        ),
        "trackers" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#EF5350"),
            f("M6 12a6 6 0 1 0 12 0a6 6 0 1 0 -12 0z", "#FFFFFF"),
            f("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#EF5350")
        ),
        "webseeds" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#42A5F5"),
            f("M7 6c2-1 3 1 5 1s1 3-1 3-1 2-3 2-3-2-2-4 1-2 1-2z", "#66BB6A"),
            f("M14 14c2 0 4 1 4 3s-2 3-3 3-2-2-2-3 0-3 1-3z", "#66BB6A")
        ),
        "swarm" to listOf(
            s("M2 12h5l3-8 4 16 3-8h5", "#26A69A", 2.6f)
        ),
        "pieces" to listOf(
            f("M5 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#EF5350"),
            f("M15 3h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#FFCA28"),
            f("M15 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#66BB6A"),
            f("M5 13h4a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-4a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2z", "#42A5F5")
        ),
        "statistics" to listOf(
            f("M4.5 11h2a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-7a1.5 1.5 0 0 1 1.5 -1.5z", "#42A5F5"),
            f("M11 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#FFA726"),
            f("M17.5 8h2a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-10a1.5 1.5 0 0 1 1.5 -1.5z", "#66BB6A")
        ),
        "statsbars" to listOf(
            f("M4.5 11h2a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-7a1.5 1.5 0 0 1 1.5 -1.5z", "#4FD1C5"),
            f("M11 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#B794F6"),
            f("M17.5 8h2a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-10a1.5 1.5 0 0 1 1.5 -1.5z", "#4CD964")
        ),
        "globe" to listOf(
            s("M2.5 12a9.5 9.5 0 1 0 19 0a9.5 9.5 0 1 0 -19 0z", "#4FA3E8", 1.8f),
            s("M12 2.5c-2.6 2.4-4 5.6-4 9.5s1.4 7.1 4 9.5c2.6-2.4 4-5.6 4-9.5s-1.4-7.1-4-9.5z", "#4FA3E8", 1.8f),
            s("M2.5 12h19", "#4FA3E8", 1.8f),
            s("M4 7h16", "#4FA3E8", 1.8f),
            s("M4 17h16", "#4FA3E8", 1.8f)
        ),
        "user" to listOf(
            f("M12 3.5a4.5 4.5 0 1 0 0 9a4.5 4.5 0 1 0 0-9z", "#4FA3E8"),
            f("M3.5 21c0-5 4-7.5 8.5-7.5s8.5 2.5 8.5 7.5z", "#4FA3E8")
        ),
        "refreshteal" to listOf(
            s("M20.5 15a9 9 0 1 1-2.1-9.4L22 9", "#4FD1C5", 2.6f),
            s("M22 3v6h-6", "#4FD1C5", 2.6f)
        ),
        "settings" to listOf(
            s("M12 2.5v3", "#90A4AE", 3f),
            s("M12 18.5v3", "#90A4AE", 3f),
            s("M2.5 12h3", "#90A4AE", 3f),
            s("M18.5 12h3", "#90A4AE", 3f),
            s("M5.3 5.3l2.1 2.1", "#90A4AE", 3f),
            s("M16.6 16.6l2.1 2.1", "#90A4AE", 3f),
            s("M18.7 5.3l-2.1 2.1", "#90A4AE", 3f),
            s("M7.4 16.6l-2.1 2.1", "#90A4AE", 3f),
            s("M5.5 12a6.5 6.5 0 1 0 13 0a6.5 6.5 0 1 0 -13 0z", "#78909C", 3f),
            f("M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", "#FFA726")
        ),
        "comment" to listOf(
            f("M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10l-5 4.5V17H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "#FFB300"),
            s("M7 8h10", "#FFFFFF", 2f),
            s("M7 12h6", "#FFFFFF", 2f)
        ),
        "pause" to listOf(
            f("M6.5 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#FFA726"),
            f("M15.5 3h2a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-2a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z", "#FFA726")
        ),
        "play" to listOf(
            fs("M7 4.5v15l13-7.5z", "#66BB6A", "#66BB6A", 2.4f)
        ),
        // Distinct from "play": a screen with a play button, used only by Stream.
        "stream" to listOf(
            f("M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z", "#26C6DA"),
            f("M10 7v6l5-3z", "#FFFFFF"),
            s("M8 21h8", "#26C6DA", 2.6f),
            s("M12 17v4", "#26C6DA", 2.4f)
        ),
        "select" to listOf(
            f("M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12a3 3 0 0 1 3 -3z", "#66BB6A"),
            s("M7.5 12.5l3 3 6-7", "#FFFFFF", 2.6f)
        ),
        "trash" to listOf(
            f("M5 8h14l-1 12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2z", "#EF5350"),
            s("M3 5h18", "#B0BEC5", 2.6f),
            s("M9 5V3.5A1.5 1.5 0 0 1 10.5 2h3A1.5 1.5 0 0 1 15 3.5V5", "#B0BEC5", 2.2f),
            s("M10 11v6", "#FFFFFF", 1.8f),
            s("M14 11v6", "#FFFFFF", 1.8f)
        ),
        "portfwd" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#FFA726"),
            s("M7 12h9", "#FFFFFF", 2.4f),
            s("M12.5 8l4 4-4 4", "#FFFFFF", 2.4f)
        ),
        "network" to listOf(
            s("M2.5 9.5a14 14 0 0 1 19 0", "#90CAF9", 2.4f),
            s("M5.5 13a9.5 9.5 0 0 1 13 0", "#42A5F5", 2.4f),
            s("M8.8 16.2a5 5 0 0 1 6.4 0", "#1E88E5", 2.4f),
            f("M10.1 19.8a1.9 1.9 0 1 0 3.8 0a1.9 1.9 0 1 0 -3.8 0z", "#1E88E5")
        ),
        "connection" to listOf(
            s("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "#42A5F5", 2.6f),
            s("M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71", "#FFA726", 2.6f)
        ),
        "speed" to listOf(
            fs("M13 2L4 14h7l-1 8 10-12h-7l1-8z", "#FFCA28", "#FFA000", 1f)
        ),
        "storage" to listOf(
            f("M5 6h14a3 3 0 0 1 3 3v6a3 3 0 0 1 -3 3h-14a3 3 0 0 1 -3 -3v-6a3 3 0 0 1 3 -3z", "#78909C"),
            f("M2 12V9a3 3 0 0 1 3-3h14a3 3 0 0 1 3 3v3z", "#B0BEC5"),
            f("M5.9 15.2a1.1 1.1 0 1 0 2.2 0a1.1 1.1 0 1 0 -2.2 0z", "#66BB6A"),
            s("M10.5 15.2h6", "#CFD8DC", 1.8f)
        ),
        "folder" to listOf(
            f("M2 6a2 2 0 0 1 2-2h5l2 2.5h9a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z", "#FFA726"),
            f("M2 10a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z", "#FFCA28")
        ),
        "rss" to listOf(
            f("M7 2h10a5 5 0 0 1 5 5v10a5 5 0 0 1 -5 5h-10a5 5 0 0 1 -5 -5v-10a5 5 0 0 1 5 -5z", "#FFA726"),
            s("M6.5 11a6.5 6.5 0 0 1 6.5 6.5", "#FFFFFF", 2.2f),
            s("M6.5 6a11.5 11.5 0 0 1 11.5 11.5", "#FFFFFF", 2.2f),
            f("M5.7 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", "#FFFFFF")
        ),
        "log" to listOf(
            f("M5 4h14a3 3 0 0 1 3 3v10a3 3 0 0 1 -3 3h-14a3 3 0 0 1 -3 -3v-10a3 3 0 0 1 3 -3z", "#37474F"),
            s("M6 9l3.5 3L6 15", "#66BB6A", 2.2f),
            s("M12 16h6", "#B0BEC5", 2.2f)
        ),
        "search" to listOf(
            fs("M3 10.5a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0z", "#E3F2FD", "#42A5F5", 2.6f),
            s("M16 16l5.5 5.5", "#EF5350", 3f)
        ),
        "webui" to listOf(
            f("M4 3h16a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-9a2 2 0 0 1 2 -2z", "#5C6BC0"),
            f("M5 5h14a1 1 0 0 1 1 1v7a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-7a1 1 0 0 1 1 -1z", "#E8EAF6"),
            s("M12 16v4", "#7986CB", 2.4f),
            s("M8 21h8", "#7986CB", 2.6f)
        ),
        "magnet" to listOf(
            s("M5 5v7a7 7 0 0 0 14 0V5", "#EF5350", 4.6f),
            s("M5 4.5v3.2", "#B0BEC5", 4.6f),
            s("M19 4.5v3.2", "#B0BEC5", 4.6f),
            s("M10.2 1.5l-1.6 3h2l-1.2 3", "#FFCA28", 1.6f),
            s("M14.2 1.5l-1.6 3h2l-1.2 3", "#FFCA28", 1.6f)
        ),
        "recheck" to listOf(
            s("M4 12a8 8 0 0 1 14-5.3", "#42A5F5", 2.6f),
            s("M18.5 3v4.5H14", "#42A5F5", 2.6f),
            s("M20 12a8 8 0 0 1-14 5.3", "#66BB6A", 2.6f),
            s("M5.5 21v-4.5H10", "#66BB6A", 2.6f)
        ),
        "reannounce" to listOf(
            f("M22 2L2 9.5l7.5 3 3 7.5z", "#42A5F5"),
            f("M22 2L9.5 12.5l3 7.5z", "#1E88E5")
        ),
        "sort" to listOf(
            s("M3 6h18", "#EF5350", 3f),
            s("M3 12h13", "#FFA726", 3f),
            s("M3 18h8", "#66BB6A", 3f)
        ),
        "filter" to listOf(
            f("M3 4h18l-7 8.5V20l-4-2v-5.5z", "#AB47BC")
        ),
        "list" to listOf(
            s("M9 6h12", "#90A4AE", 2.4f),
            s("M9 12h12", "#90A4AE", 2.4f),
            s("M9 18h12", "#90A4AE", 2.4f),
            f("M2.8 6a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "#EF5350"),
            f("M2.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "#FFCA28"),
            f("M2.8 18a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0z", "#66BB6A")
        ),
        "download" to listOf(
            s("M12 3v12", "#42A5F5", 3f),
            s("M6.5 10l5.5 5.5 5.5-5.5", "#42A5F5", 3f),
            s("M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3", "#78909C", 2.6f)
        ),
        "stalled" to listOf(
            s("M7 20V5", "#78909C", 2.8f),
            s("M3 9l4-4 4 4", "#78909C", 2.8f),
            s("M17 4v15", "#8BC34A", 2.8f),
            s("M13 15l4 4 4-4", "#8BC34A", 2.8f)
        ),
        "upload" to listOf(
            s("M12 15V3", "#66BB6A", 3f),
            s("M6.5 8.5L12 3l5.5 5.5", "#66BB6A", 3f),
            s("M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3", "#78909C", 2.6f)
        ),
        "check" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#66BB6A"),
            s("M7 12.5l3.5 3.5 6.5-7", "#FFFFFF", 2.8f)
        ),
        "copy" to listOf(
            f("M5 3h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z", "#90CAF9"),
            f("M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z", "#42A5F5")
        ),
        "hash" to listOf(
            s("M4 9h16", "#FFA726", 2.6f),
            s("M4 15h16", "#FFA726", 2.6f),
            s("M10 3L8 21", "#AB47BC", 2.6f),
            s("M16 3l-2 18", "#AB47BC", 2.6f)
        ),
        "sequential" to listOf(
            s("M5 6l6 6-6 6", "#90CAF9", 3f),
            s("M12 6l6 6-6 6", "#1E88E5", 3f)
        ),
        "flag" to listOf(
            s("M5 22V3", "#78909C", 2.6f),
            f("M5 4h14l-3 4.5 3 4.5H5z", "#EF5350")
        ),
        "edit" to listOf(
            f("M15.5 4.5l4 4L8 20l-4-4z", "#FFCA28"),
            f("M15.5 4.5l2-2a2.1 2.1 0 0 1 3 0l1 1a2.1 2.1 0 0 1 0 3l-2 2z", "#EF5350"),
            f("M4 16l4 4-5 1z", "#8D6E63")
        ),
        "save" to listOf(
            f("M5 3h12l4 4v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "#42A5F5"),
            f("M7 3h8v6H7z", "#E3F2FD"),
            f("M7 14h10v7H7z", "#E3F2FD"),
            s("M12 4.5v3", "#1E88E5", 1.8f)
        ),
        "back" to listOf(
            s("M20 12H5", "#FFA726", 3f),
            s("M11 5l-7 7 7 7", "#FFA726", 3f)
        ),
        "theme" to listOf(
            f("M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z", "#FFCA28"),
            s("M17 2v4", "#FFCA28", 1.6f),
            s("M15 4h4", "#FFCA28", 1.6f)
        ),
        "selectall" to listOf(
            s("M2 13l4 4 8-9", "#66BB6A", 2.8f),
            s("M10 15l2 2 9-11", "#42A5F5", 2.8f)
        ),
        "selectnone" to listOf(
            f("M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12a3 3 0 0 1 3 -3z", "#EF5350"),
            s("M9 9l6 6", "#FFFFFF", 2.4f),
            s("M15 9l-6 6", "#FFFFFF", 2.4f)
        ),
        "addfile" to listOf(
            f("M6 2h8l6 6v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "#64B5F6"),
            f("M14 2v6h6z", "#1E88E5"),
            f("M11.5 17a5 5 0 1 0 10 0a5 5 0 1 0 -10 0z", "#66BB6A"),
            s("M16.5 14.5v5", "#FFFFFF", 2f),
            s("M14 17h5", "#FFFFFF", 2f)
        ),
        "addfeed" to listOf(
            f("M7 2h10a5 5 0 0 1 5 5v10a5 5 0 0 1 -5 5h-10a5 5 0 0 1 -5 -5v-10a5 5 0 0 1 5 -5z", "#FFA726"),
            s("M6.5 11a6.5 6.5 0 0 1 6.5 6.5", "#FFFFFF", 2.2f),
            s("M6.5 6a11.5 11.5 0 0 1 11.5 11.5", "#FFFFFF", 2.2f),
            f("M5.7 17a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0z", "#FFFFFF"),
            f("M13 18a5 5 0 1 0 10 0a5 5 0 1 0 -10 0z", "#66BB6A"),
            s("M18 15.5v5", "#FFFFFF", 2f),
            s("M15.5 18h5", "#FFFFFF", 2f)
        ),
        "refresh" to listOf(
            s("M20.5 15a9 9 0 1 1-2.1-9.4L22 9", "#26A69A", 2.6f),
            s("M22 3v6h-6", "#FFA726", 2.6f)
        ),
        "encryption" to listOf(
            s("M12 4.5V2.2", "#2E7D32", 1.4f),
            s("M12 19.5v2.3", "#2E7D32", 1.4f),
            s("M4.5 12H2.2", "#2E7D32", 1.4f),
            s("M19.5 12h2.3", "#2E7D32", 1.4f),
            f("M10.5 2a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", "#2E7D32"),
            f("M10.5 22a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", "#2E7D32"),
            f("M0.7 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", "#2E7D32"),
            f("M20.3 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0z", "#2E7D32"),
            f("M4.5 12a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0 -15 0z", "#1E88E5"),
            s("M9.8 11.5V9.8a2.2 2.2 0 0 1 4.4 0V11.5", "#FFFFFF", 1.8f),
            f("M8.5 11.5h7a1 1 0 0 1 1 1v4a1 1 0 0 1 -1 1h-7a1 1 0 0 1 -1 -1v-4a1 1 0 0 1 1 -1z", "#FFFFFF"),
            s("M12 13.6v1.6", "#1E88E5", 1.4f)
        ),
        "checklist" to listOf(
            s("M4.9 8.2L7.4 10.7L11.2 6.4", "#22B14C", 2.4f),
            s("M12.8 8.7H18.8", "#42A5F5", 2.2f),
            s("M7.6 12.9h.01", "#42A5F5", 2.2f),
            s("M10.8 12.9H18.8", "#42A5F5", 2.2f),
            s("M7.6 17.2h.01", "#42A5F5", 2.2f),
            s("M10.8 17.2H18.8", "#42A5F5", 2.2f)
        ),
        "plus" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#66BB6A"),
            s("M12 7v10", "#FFFFFF", 2.6f),
            s("M7 12h10", "#FFFFFF", 2.6f)
        ),
        "bell" to listOf(
            f("M12 2.5a6 6 0 0 0 -6 6v4.5l-2 3.5h16l-2 -3.5v-4.5a6 6 0 0 0 -6 -6z", "#FFB300"),
            f("M9.5 19a2.5 2.5 0 0 0 5 0z", "#FFA726"),
            s("M12 2.5v-0.5", "#FFA726", 2f)
        ),
        "clock" to listOf(
            fs("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#E3F2FD", "#42A5F5", 2f),
            s("M12 6.5V12l3.5 2.5", "#FFA726", 2.4f)
        ),
        "announce" to listOf(
            s("M4.9 4.9a10.4 10.4 0 0 0 0 14.2", "#FFD54F", 2.2f),
            s("M19.1 4.9a10.4 10.4 0 0 1 0 14.2", "#FFD54F", 2.2f),
            s("M7.9 7.9a6.2 6.2 0 0 0 0 8.2", "#FFB300", 2.4f),
            s("M16.1 7.9a6.2 6.2 0 0 1 0 8.2", "#FFB300", 2.4f),
            f("M9.4 12a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0z", "#FFB300")
        ),
        "dht" to listOf(
            s("M12 7.5v4", "#90A4AE", 2f),
            s("M12 11.5l-5.5 5.5", "#90A4AE", 2f),
            s("M12 11.5l5.5 5.5", "#90A4AE", 2f),
            f("M9.2 5a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0z", "#42A5F5"),
            f("M3.2 18.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#66BB6A"),
            f("M14.8 18.5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#FFC107")
        ),
        "pex" to listOf(
            s("M3.5 8h15", "#42A5F5", 2.6f),
            s("M14.5 4l4 4-4 4", "#42A5F5", 2.6f),
            s("M20.5 16h-15", "#66BB6A", 2.6f),
            s("M9.5 12l-4 4 4 4", "#66BB6A", 2.6f)
        ),
        "lsd" to listOf(
            f("M2 11.5L12 3l10 8.5z", "#EF5350"),
            f("M5 11h14v9a1 1 0 0 1 -1 1h-12a1 1 0 0 1 -1 -1z", "#FFCA28"),
            f("M10 14h4v7h-4z", "#8D6E63")
        ),
        "poweron" to listOf(
            s("M7 6.3a8 8 0 1 0 10 0", "#00C853", 2.8f),
            s("M12 2.8v8.4", "#00C853", 2.8f)
        ),
        "share" to listOf(
            s("M8.6 10.7l6.8-4", "#90A4AE", 2f),
            s("M8.6 13.3l6.8 4", "#90A4AE", 2f),
            f("M15 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#42A5F5"),
            f("M3 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#66BB6A"),
            f("M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "#AB47BC")
        ),
        "powerred" to listOf(
            f("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0z", "#FFFFFF"),
            f("M4.4 12a7.6 7.6 0 1 0 15.2 0a7.6 7.6 0 1 0 -15.2 0z", "#FF0000"),
            s("M8.5 8.4a5 5 0 1 0 7 0", "#FFFFFF", 1.9f),
            s("M12 6.6v5.2", "#FFFFFF", 1.9f)
        ),
        "battery" to listOf(
            f("M5 7a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2z", "#66BB6A"),
            f("M9 2.5h4a1 1 0 0 1 1 1V5H8V3.5a1 1 0 0 1 1-1z", "#B0BEC5"),
            f("M12.4 8.5L8.8 13.6h2.6L10.6 18l3.6-5.2h-2.6z", "#FFFFFF")
        ),
        "move" to listOf(
            f("M2 6a2 2 0 0 1 2-2h5l2 2.5h9a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z", "#FFB000"),
            f("M2 10a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2z", "#FFC400"),
            s("M7 14h9", "#FFFFFF", 2.4f),
            s("M13 11l3 3-3 3", "#FFFFFF", 2.4f)
        ),
        "up" to listOf(
            s("M12 19V5", "#42A5F5", 2.8f),
            s("M6 11l6-6 6 6", "#42A5F5", 2.8f)
        ),
        "down" to listOf(
            s("M12 5v14", "#42A5F5", 2.8f),
            s("M6 13l6 6 6-6", "#42A5F5", 2.8f)
        ),
        "totop" to listOf(
            s("M5 4h14", "#66BB6A", 2.8f),
            s("M12 20V9", "#66BB6A", 2.8f),
            s("M6.5 14.5L12 9l5.5 5.5", "#66BB6A", 2.8f)
        ),
        "tobottom" to listOf(
            s("M5 20h14", "#66BB6A", 2.8f),
            s("M12 4v11", "#66BB6A", 2.8f),
            s("M6.5 9.5L12 15l5.5-5.5", "#66BB6A", 2.8f)
        ),
        "poweroff" to listOf(
            s("M7 6.3a8 8 0 1 0 10 0", "#FF1744", 2.8f),
            s("M12 2.8v8.4", "#FF1744", 2.8f)
        )

        )
    }

    // Puts the named icon to the left of the button's text. The icon is
    // shifted sideways while it is DRAWN so that icon + text sit together in
    // the middle of the button (a full-width button would otherwise leave
    // the icon stranded at the far left edge). Nothing about the button's
    // size or padding is ever changed, so rotating the screen or resizing
    // the window can't leave a button stretched or squashed.
    // An unknown name does nothing, so a typo can never crash the screen.
    // leftAligned = true keeps the icon at the far left (for headings and
    // other text that is not centred inside a full-width button).
    // Light Blue theme only: the Force Reannounce plane and the Force
    // Recheck arrows are drawn fully black, because their normal blue and
    // green blend into the light blue button.
    private fun themedLayers(view: TextView, name: String, layers: List<Layer>): List<Layer> {
        if (name != "reannounce" && name != "recheck") return layers
        val theme = try {
            view.context.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
                .getString("theme_color", "blue")
        } catch (_: Throwable) {
            "blue"
        }
        if (theme != "blue") return layers
        return layers.map {
            Layer(
                it.d,
                if (it.fill != null) Color.BLACK else null,
                if (it.stroke != null) Color.BLACK else null,
                it.width
            )
        }
    }

    fun apply(
        view: TextView,
        name: String,
        sizeDp: Int = 20,
        gapDp: Int = 8,
        leftAligned: Boolean = false
    ) {
        val layers = themedLayers(view, name, ICONS[name] ?: return)
        val density = view.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val gap = (gapDp * density).toInt()

        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            ColorIconDrawable(layers, size, gap, view, if (leftAligned) 3 else 0),
            null,
            null,
            null
        )
        view.compoundDrawablePadding = gap
    }

    // Same as apply(), but with a coloured icon on BOTH sides of the text
    // (for example the magnet icon before "Add Magnet / Hash" and the hash
    // icon after it). The text stays centred; each icon is drawn next to it.
    // An unknown name does nothing.
    fun applyBoth(
        view: TextView,
        leftName: String,
        rightName: String,
        sizeDp: Int = 20,
        gapDp: Int = 8
    ) {
        val leftLayers = ICONS[leftName] ?: return
        val rightLayers = ICONS[rightName] ?: return
        val density = view.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val gap = (gapDp * density).toInt()

        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            ColorIconDrawable(leftLayers, size, gap, view, 1),
            null,
            ColorIconDrawable(rightLayers, size, gap, view, 2),
            null
        )
        view.compoundDrawablePadding = gap
    }

    // Draws the coloured shapes scaled from the 24x24 design box to the bounds.
    private val pathCache = HashMap<String, Path>()

    private class ColorIconDrawable(
        layers: List<Layer>,
        private val sizePx: Int,
        private val gapPx: Int,
        private val owner: TextView,
        // 0 = the only icon (left), 1 = left of a pair, 2 = right of a pair,
        // 3 = left icon that is not shifted (headings)
        private val side: Int = 0
    ) : Drawable() {

        private class Shape(val path: Path, val layer: Layer)

        // Parsed once per icon shape and reused: the main screen is rebuilt
        // on every theme switch, and re-parsing every path made it slow.
        private val shapes: List<Shape> = layers.mapNotNull { layer ->
            try {
                val path = pathCache.getOrPut(layer.d) {
                    PathParser.createPathFromPathData(layer.d)
                }
                Shape(path, layer)
            } catch (e: Throwable) {
                null
            }
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var iconAlpha = 255

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return

            // The text is already centred in the space right of the icon, so
            // only the icon has to move: from the left edge to where the
            // icon+text group starts when that group is centred.
            val shown: CharSequence =
                owner.transformationMethod?.getTransformation(owner.text, owner) ?: owner.text
            val textWidth = owner.paint.measureText(shown, 0, shown.length)
            val contentWidth = owner.width - owner.paddingLeft - owner.paddingRight
            val shift = if (side == 3) {
                0f
            } else if (side == 0) {
                maxOf(0f, (contentWidth - sizePx - gapPx - textWidth) / 2f)
            } else {
                // Icon, text and icon form one group; centre it. The left
                // icon moves right by this much, the right icon moves left.
                val groupShift = maxOf(
                    0f,
                    (contentWidth - 2f * (sizePx + gapPx) - textWidth) / 2f
                )
                if (side == 1) groupShift else -groupShift
            }

            canvas.save()
            canvas.translate(b.left.toFloat() + shift, b.top.toFloat())
            val scale = b.width() / 24f
            canvas.scale(scale, scale)

            for (shape in shapes) {
                val layer = shape.layer

                if (layer.fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.color = layer.fill
                    paint.alpha = iconAlpha * Color.alpha(layer.fill) / 255
                    canvas.drawPath(shape.path, paint)
                }

                if (layer.stroke != null) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = layer.width
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = layer.stroke
                    paint.alpha = iconAlpha * Color.alpha(layer.stroke) / 255
                    canvas.drawPath(shape.path, paint)
                }
            }

            canvas.restore()
        }

        override fun getIntrinsicWidth(): Int = sizePx
        override fun getIntrinsicHeight(): Int = sizePx

        override fun setAlpha(alpha: Int) {
            iconAlpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
