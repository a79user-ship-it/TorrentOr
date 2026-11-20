package com.example.torrentor

import android.content.Context

/**
 * User choices for TorrentOr's notifications (the always-on "TorrentOr is
 * running" foreground notification is NOT controlled from here).
 *
 * Defaults keep the behavior from before this screen existed: only
 * "download completed" and "low storage" alerts are on, and the new
 * per-torrent progress notifications are off until the user turns them on.
 */
object NotificationSettings {
    private const val PREFS = "notification_settings"

    data class EventType(val id: String, val label: String, val defaultOn: Boolean)

    val EVENT_TYPES = listOf(
        EventType("started", "Download started", false),
        EventType("completed", "Download completed", true),
        EventType("failed", "Download failed", true),
        EventType("paused", "Torrent paused", false),
        EventType("resumed", "Torrent resumed", false),
        EventType("seeding", "Seeding started (also when selected files finish)", true),
        EventType("lowstorage", "Low storage", true),
        EventType("error", "Torrent / tracker error", true)
    )

    val INTERVAL_CHOICES_SECONDS = listOf(1, 2, 3, 5, 10, 30, 60)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isTypeEnabled(context: Context, id: String): Boolean {
        val default = EVENT_TYPES.firstOrNull { it.id == id }?.defaultOn ?: false
        return prefs(context).getBoolean("type_$id", default)
    }

    fun setTypeEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("type_$id", enabled).apply()
    }

    fun progressEnabled(context: Context): Boolean =
        prefs(context).getBoolean("progress_enabled", false)

    fun setProgressEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("progress_enabled", enabled).apply()
    }

    fun intervalSeconds(context: Context): Int {
        val v = prefs(context).getInt("interval_seconds", 1)
        return if (v in INTERVAL_CHOICES_SECONDS) v else 1
    }

    fun setIntervalSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt("interval_seconds", seconds).apply()
    }

    // Sound for event notifications (completed, failed, ...). Progress
    // notifications are always silent.
    fun soundEnabled(context: Context): Boolean =
        prefs(context).getBoolean("sound", true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("sound", enabled).apply()
    }

    // Silent mode: every event notification is delivered without sound or
    // vibration, whatever the sound switch says.
    fun silentMode(context: Context): Boolean =
        prefs(context).getBoolean("silent", false)

    fun setSilentMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("silent", enabled).apply()
    }

    fun quiet(context: Context): Boolean = silentMode(context) || !soundEnabled(context)

    private fun key(hash: String): String =
        hash.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    // Per-torrent switch, keyed by info-hash (never list position).
    fun isTorrentDisabled(context: Context, hash: String): Boolean {
        val k = key(hash)
        if (k.isEmpty()) return false
        return (prefs(context).getStringSet("disabled_hashes", emptySet()) ?: emptySet()).contains(k)
    }

    fun setTorrentDisabled(context: Context, hash: String, disabled: Boolean) {
        val k = key(hash)
        if (k.isEmpty()) return

        val set = HashSet(prefs(context).getStringSet("disabled_hashes", emptySet()) ?: emptySet())
        if (disabled) set.add(k) else set.remove(k)
        prefs(context).edit().putStringSet("disabled_hashes", set).apply()
    }
}
