package com.example.torrentor

import android.content.Context
import org.json.JSONArray
import java.util.Locale

/**
 * Remembers recent Online Search queries (newest first, no duplicates,
 * capped). Stored as a JSON array in the existing online_search_settings
 * prefs file. Also holds the "show suggestions" switch.
 */
object SearchHistory {
    private const val PREFS = "online_search_settings"
    private const val KEY = "search_history"
    private const val KEY_SUGGEST = "search_suggestions_enabled"
    private const val MAX = 50

    fun load(context: Context): List<String> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()

        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<String>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONArray(list).toString()).apply()
    }

    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return

        val list = load(context).filter { !it.equals(q, ignoreCase = true) }.toMutableList()
        list.add(0, q)
        save(context, list.take(MAX))
    }

    fun remove(context: Context, query: String) {
        save(context, load(context).filter { it != query })
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    fun suggestionsEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SUGGEST, true)
    }

    fun setSuggestionsEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SUGGEST, enabled).apply()
    }

    // Past queries to offer in the search box dropdown (empty when the
    // suggestions switch is off).
    fun suggestions(context: Context): List<String> {
        return if (suggestionsEnabled(context)) load(context) else emptyList()
    }
}
