package com.example.torrentor

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-configured search provider (Online Search -> Provider Settings ->
 * Add Custom Provider). Unlike the built-in providers above, this one has
 * no hand-written parsing: the user supplies a JSON search API URL plus the
 * field names that API uses, and this class maps that onto
 * TorrentSearchResult generically. Only JSON APIs are supported this way -
 * a site with no API, like 1337x, needs a hand-written regex provider
 * instead, which isn't something that can be configured from the app.
 */
data class CustomProviderConfig(
    val name: String,
    // The search URL. {query} is replaced with the (URL-encoded) search text.
    val urlTemplate: String,
    // Dot-separated path to the JSON array of results, e.g. "results" or
    // "data.items". Blank means the response body is itself a JSON array.
    val resultsPath: String = "",
    val titleField: String = "title",
    val infohashField: String = "",
    val magnetField: String = "",
    val sizeField: String = "",
    val seedersField: String = "",
    val leechersField: String = "",
    val categoryField: String = "",
    val dateField: String = "",
    // Used together with detailUrlTemplate (which has its own {id}
    // placeholder) to build a link to the torrent's own page.
    val idField: String = "",
    val detailUrlTemplate: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("urlTemplate", urlTemplate)
            put("resultsPath", resultsPath)
            put("titleField", titleField)
            put("infohashField", infohashField)
            put("magnetField", magnetField)
            put("sizeField", sizeField)
            put("seedersField", seedersField)
            put("leechersField", leechersField)
            put("categoryField", categoryField)
            put("dateField", dateField)
            put("idField", idField)
            put("detailUrlTemplate", detailUrlTemplate)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): CustomProviderConfig {
            return CustomProviderConfig(
                name = obj.optString("name", ""),
                urlTemplate = obj.optString("urlTemplate", ""),
                resultsPath = obj.optString("resultsPath", ""),
                titleField = obj.optString("titleField", "title").ifBlank { "title" },
                infohashField = obj.optString("infohashField", ""),
                magnetField = obj.optString("magnetField", ""),
                sizeField = obj.optString("sizeField", ""),
                seedersField = obj.optString("seedersField", ""),
                leechersField = obj.optString("leechersField", ""),
                categoryField = obj.optString("categoryField", ""),
                dateField = obj.optString("dateField", ""),
                idField = obj.optString("idField", ""),
                detailUrlTemplate = obj.optString("detailUrlTemplate", "")
            )
        }

        fun listToJson(configs: List<CustomProviderConfig>): String {
            val array = JSONArray()
            for (config in configs) array.put(config.toJson())
            return array.toString()
        }

        fun listFromJson(text: String): List<CustomProviderConfig> {
            if (text.isBlank()) return emptyList()

            return try {
                val array = JSONArray(text)
                val list = mutableListOf<CustomProviderConfig>()

                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }

                list
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }
}

class CustomSearchProvider(private val config: CustomProviderConfig) : SearchProvider {
    override val name: String = config.name

    override fun search(query: String): List<TorrentSearchResult> {
        val url = config.urlTemplate.replace("{query}", SearchHttp.encode(query))
        val body = SearchHttp.get(url).trim()

        val root: Any = if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
        val results = navigateToArray(root, config.resultsPath)

        val list = mutableListOf<TorrentSearchResult>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val title = item.optString(config.titleField, "")

            if (title.isBlank()) continue

            val hash = if (config.infohashField.isNotBlank()) {
                item.optString(config.infohashField, "")
            } else {
                ""
            }

            val magnetFromField = if (config.magnetField.isNotBlank()) {
                item.optString(config.magnetField, "")
            } else {
                ""
            }

            val id = if (config.idField.isNotBlank()) {
                item.optString(config.idField, "")
            } else {
                ""
            }

            list.add(
                TorrentSearchResult(
                    name = title,
                    sourceName = name,
                    sizeBytes = if (config.sizeField.isNotBlank()) {
                        item.optLong(config.sizeField, 0L)
                    } else {
                        0L
                    },
                    seeders = if (config.seedersField.isNotBlank()) {
                        item.optInt(config.seedersField, -1)
                    } else {
                        -1
                    },
                    leechers = if (config.leechersField.isNotBlank()) {
                        item.optInt(config.leechersField, -1)
                    } else {
                        -1
                    },
                    publicationDate = if (config.dateField.isNotBlank()) {
                        item.optString(config.dateField, "")
                    } else {
                        ""
                    },
                    category = if (config.categoryField.isNotBlank()) {
                        item.optString(config.categoryField, "")
                    } else {
                        ""
                    },
                    infoHash = hash,
                    magnetUri = magnetFromField.ifBlank {
                        if (hash.isNotBlank()) SearchHttp.magnetFromHash(hash, title) else ""
                    },
                    torrentUrl = "",
                    resultPageUrl = if (config.detailUrlTemplate.isNotBlank() && id.isNotBlank()) {
                        config.detailUrlTemplate.replace("{id}", id)
                    } else {
                        ""
                    }
                )
            )
        }

        return list
    }

    // Walks a dot-separated path ("data.items") down from the parsed JSON
    // root to the array of results. An empty path means the root itself is
    // the array.
    private fun navigateToArray(root: Any, path: String): JSONArray {
        if (path.isBlank()) {
            return root as? JSONArray ?: JSONArray()
        }

        var current: Any = root

        for (key in path.split(".")) {
            val obj = current as? JSONObject ?: return JSONArray()
            current = obj.opt(key) ?: return JSONArray()
        }

        return current as? JSONArray ?: JSONArray()
    }
}
