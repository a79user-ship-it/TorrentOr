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
        val error: String?
    )

    /**
     * Runs every enabled provider on its own thread at the same time.
     * onProviderDone is called on a background thread as each provider
     * finishes (success or failure) so the UI can show progress live;
     * onAllDone is called once, after every provider has finished or timed
     * out, with the combined, de-duplicated result list.
     *
     * One provider failing, timing out, or throwing never stops the others.
     */
    fun search(
        context: Context,
        query: String,
        onProviderDone: (ProviderOutcome) -> Unit,
        onAllDone: (List<TorrentSearchResult>) -> Unit
    ) {
        AppLog.init(context.applicationContext)

        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            onAllDone(emptyList())
            return
        }

        val providers = allProviders(context).filter { isProviderEnabled(context, it.name) }

        if (providers.isEmpty()) {
            onAllDone(emptyList())
            return
        }

        val timeoutMs = loadTimeoutSeconds(context) * 1000L
        val collected = CopyOnWriteArrayList<TorrentSearchResult>()
        val remaining = AtomicInteger(providers.size)

        for (provider in providers) {
            Thread {
                val threadResult = arrayOfNulls<List<TorrentSearchResult>>(1)
                val threadError = arrayOfNulls<String>(1)

                val worker = Thread {
                    try {
                        threadResult[0] = provider.search(trimmed)
                    } catch (e: Throwable) {
                        threadError[0] = e.message ?: e.javaClass.simpleName
                    }
                }

                worker.isDaemon = true
                worker.start()
                worker.join(timeoutMs)

                val results = threadResult[0]
                val error = when {
                    worker.isAlive -> "Timed out"
                    threadError[0] != null -> threadError[0]
                    results == null -> "No response"
                    else -> null
                }

                if (results != null && error == null) {
                    collected.addAll(results)
                    AppLog.info("Search: ${provider.name} - ${results.size} result(s)")
                } else {
                    AppLog.warning("Search: ${provider.name} failed: $error")
                }

                onProviderDone(
                    ProviderOutcome(
                        providerName = provider.name,
                        resultCount = results?.size ?: 0,
                        error = error
                    )
                )

                if (remaining.decrementAndGet() == 0) {
                    onAllDone(deduplicate(collected.toList()))
                }
            }.start()
        }
    }

    private fun deduplicate(results: List<TorrentSearchResult>): List<TorrentSearchResult> {
        val seen = LinkedHashMap<String, TorrentSearchResult>()

        for (result in results) {
            val key = result.dedupeKey()
            val existing = seen[key]

            // Keep the copy with the higher seeder count; if that's a tie,
            // keep whichever was found first.
            if (existing == null || result.seeders > existing.seeders) {
                seen[key] = result
            }
        }

        return seen.values.toList()
    }
}
