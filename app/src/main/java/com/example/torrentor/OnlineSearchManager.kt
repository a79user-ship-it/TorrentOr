package com.example.torrentor

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

object OnlineSearchManager {

    // Registry of every built-in provider. Enabling/disabling is a
    // per-provider on/off switch saved in prefs (see isProviderEnabled /
    // setProviderEnabled) and applies to custom providers too.
    val BUILTIN_PROVIDERS: List<SearchProvider> = listOf(
        YtsProvider(),
        PirateBayProvider(),
        NyaaProvider(),
        SolidTorrentsProvider(),
        BitSearchProvider(),
        Provider1337x(),
        EztvProvider(),
        ExtToProvider()
    )

    private const val PREFS = "online_search_settings"
    private const val CUSTOM_PROVIDERS_KEY = "custom_providers"

    // Every provider that can run a search right now: the built-ins above,
    // plus whatever the user has added themselves from Provider Settings ->
    // Add Custom Provider.
    fun allProviders(context: Context): List<SearchProvider> {
        return BUILTIN_PROVIDERS + loadCustomProviders(context).map { CustomSearchProvider(it) }
    }

    fun loadCustomProviders(context: Context): List<CustomProviderConfig> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CUSTOM_PROVIDERS_KEY, "") ?: ""

        return CustomProviderConfig.listFromJson(raw)
    }

    private fun saveCustomProviders(context: Context, configs: List<CustomProviderConfig>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CUSTOM_PROVIDERS_KEY, CustomProviderConfig.listToJson(configs))
            .commit()
    }

    // Adding a provider whose name matches an existing custom provider
    // replaces it (lets the user edit one by re-adding it with the same
    // name), rather than creating a duplicate.
    fun addCustomProvider(context: Context, config: CustomProviderConfig) {
        val existing = loadCustomProviders(context).filter { it.name != config.name }
        saveCustomProviders(context, existing + config)
    }

    fun removeCustomProvider(context: Context, name: String) {
        saveCustomProviders(context, loadCustomProviders(context).filter { it.name != name })
    }

    fun isProviderEnabled(context: Context, providerName: String): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("enabled_$providerName", true)
    }

    fun setProviderEnabled(context: Context, providerName: String, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled_$providerName", enabled)
            .commit()
    }

    fun loadTimeoutSeconds(context: Context): Int {
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("timeout_seconds", 12)
    }

    fun saveTimeoutSeconds(context: Context, seconds: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("timeout_seconds", seconds)
            .commit()
    }

    data class ProviderOutcome(
        val providerName: String,
        val resultCount: Int,
        val error: String?,
        val elapsedMs: Long = 0L
    )

    /** Returned by search(); cancel() stops delivering any further callbacks. */
    class SearchHandle internal constructor() {
        @Volatile
        var cancelled = false
            private set

        internal val workers = CopyOnWriteArrayList<Thread>()

        fun cancel() {
            cancelled = true

            for (w in workers) {
                try {
                    w.interrupt()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---- Per-provider health stats (response time, last success, error) ----

    data class ProviderStats(
        val lastAttemptMs: Long,
        val lastSuccessMs: Long,
        val lastResponseMs: Long,
        val lastError: String
    ) {
        // Unavailable = the most recent attempt failed.
        val unavailable: Boolean
            get() = lastAttemptMs > 0L && lastError.isNotBlank()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadStats(context: Context, providerName: String): ProviderStats {
        val p = prefs(context)

        return ProviderStats(
            lastAttemptMs = p.getLong("stat_try_$providerName", 0L),
            lastSuccessMs = p.getLong("stat_ok_$providerName", 0L),
            lastResponseMs = p.getLong("stat_ms_$providerName", 0L),
            lastError = p.getString("stat_err_$providerName", "") ?: ""
        )
    }

    private fun recordStats(context: Context, providerName: String, outcome: ProviderOutcome) {
        val e = prefs(context).edit()
        val now = System.currentTimeMillis()

        e.putLong("stat_try_$providerName", now)
        e.putLong("stat_ms_$providerName", outcome.elapsedMs)

        if (outcome.error == null) {
            e.putLong("stat_ok_$providerName", now)
            e.putString("stat_err_$providerName", "")
        } else {
            e.putString("stat_err_$providerName", outcome.error)
        }

        e.apply()
    }

    fun isFavorite(context: Context, providerName: String): Boolean {
        return prefs(context).getBoolean("fav_$providerName", false)
    }

    fun setFavorite(context: Context, providerName: String, favorite: Boolean) {
        prefs(context).edit().putBoolean("fav_$providerName", favorite).apply()
    }

    fun favoriteNames(context: Context): Set<String> {
        return allProviders(context).map { it.name }.filter { isFavorite(context, it) }.toSet()
    }

    // Runs one provider with a hard timeout on a daemon worker thread.
    private fun runOne(
        provider: SearchProvider,
        query: String,
        timeoutMs: Long,
        handle: SearchHandle?
    ): Pair<List<TorrentSearchResult>?, ProviderOutcome> {
        val started = System.currentTimeMillis()
        val threadResult = arrayOfNulls<List<TorrentSearchResult>>(1)
        val threadError = arrayOfNulls<String>(1)

        val worker = Thread {
            try {
                threadResult[0] = provider.search(query)
            } catch (e: Throwable) {
                threadError[0] = e.message ?: e.javaClass.simpleName
            }
        }

        worker.isDaemon = true
        handle?.workers?.add(worker)
        worker.start()

        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
        }

        val elapsed = System.currentTimeMillis() - started
        val results = threadResult[0]

        val error = when {
            worker.isAlive -> "Timed out"
            threadError[0] != null -> threadError[0]
            results == null -> "No response"
            else -> null
        }

        return Pair(
            if (error == null) results else null,
            ProviderOutcome(
                providerName = provider.name,
                resultCount = if (error == null) (results?.size ?: 0) else 0,
                error = error,
                elapsedMs = elapsed
            )
        )
    }

    /**
     * Runs one provider with a sample query so the user can see whether it
     * works right now. Records health stats; onResult runs on a background
     * thread.
     */
    fun testProvider(context: Context, providerName: String, onResult: (ProviderOutcome) -> Unit) {
        val appContext = context.applicationContext

        Thread {
            val provider = allProviders(appContext).firstOrNull { it.name == providerName }

            val outcome = if (provider == null) {
                ProviderOutcome(providerName, 0, "Provider not found")
            } else {
                runOne(provider, "ubuntu", loadTimeoutSeconds(appContext) * 1000L, null).second
            }

            if (provider != null) recordStats(appContext, providerName, outcome)
            onResult(outcome)
        }.start()
    }

    /**
     * Runs every enabled provider on its own thread at the same time.
     *
     * onProviderDone: called (background thread) as each provider finishes,
     *   success or failure.
     * onUpdate: called (background thread) after each provider finishes
     *   with the merged, de-duplicated list so far, so results can be shown
     *   as they arrive.
     * onAllDone: called once, after every provider has finished or timed
     *   out, with the final merged list.
     *
     * One provider failing, timing out, or throwing never stops the others.
     * After the returned handle's cancel() no further callbacks are made.
     */
    fun search(
        context: Context,
        query: String,
        onProviderDone: (ProviderOutcome) -> Unit,
        onAllDone: (List<TorrentSearchResult>) -> Unit,
        onUpdate: (List<TorrentSearchResult>) -> Unit = {}
    ): SearchHandle {
        val appContext = context.applicationContext
        AppLog.init(appContext)

        val handle = SearchHandle()
        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            onAllDone(emptyList())
            return handle
        }

        val providers = allProviders(appContext).filter { isProviderEnabled(appContext, it.name) }

        if (providers.isEmpty()) {
            onAllDone(emptyList())
            return handle
        }

        val timeoutMs = loadTimeoutSeconds(appContext) * 1000L
        val merged = LinkedHashMap<String, TorrentSearchResult>()
        val remaining = AtomicInteger(providers.size)

        for (provider in providers) {
            Thread {
                val (results, outcome) = runOne(provider, trimmed, timeoutMs, handle)

                // Health stats are recorded even if the search was cancelled
                // meanwhile; a cancelled search that never answered is not
                // counted as a provider failure.
                if (!handle.cancelled || results != null) {
                    recordStats(appContext, provider.name, outcome)
                }

                if (results != null) {
                    AppLog.info("Search: ${provider.name} - ${results.size} result(s) in ${outcome.elapsedMs} ms")
                } else if (!handle.cancelled) {
                    AppLog.warning("Search: ${provider.name} failed: ${outcome.error}")
                }

                var snapshot: List<TorrentSearchResult>? = null

                synchronized(merged) {
                    if (results != null) {
                        for (r in results) {
                            val key = r.dedupeKey()
                            val existing = merged[key]
                            merged[key] = if (existing == null) r else existing.mergedWith(r)
                        }
                    }

                    snapshot = merged.values.toList()
                }

                val done = remaining.decrementAndGet() == 0

                if (!handle.cancelled) {
                    onProviderDone(outcome)
                    onUpdate(snapshot ?: emptyList())

                    if (done) {
                        onAllDone(snapshot ?: emptyList())
                    }
                }
            }.start()
        }

        return handle
    }
}
