package ani.dantotsu.parsers

import okhttp3.Headers

/**
 * Header handling for streams (video, subtitles, audio tracks).
 *
 * HTTP header names are case-insensitive, but they travel through the app as plain
 * `Map<String, String>`s, so "User-Agent" from the app defaults and "user-agent" from an
 * extension used to both be sent. [merge] layers maps from lowest to highest priority
 * (app defaults -> source.headers -> video.headers), compares names case-insensitively and
 * keeps exactly one value per name, which also guarantees a single User-Agent.
 */
object StreamHeaders {

    fun merge(vararg layers: Map<String, String>?): Map<String, String> {
        // lowercase name -> (original name, value)
        val out = LinkedHashMap<String, Pair<String, String>>()
        for (layer in layers) {
            layer ?: continue
            for ((name, value) in layer) {
                val key = name.trim()
                if (key.isEmpty()) continue
                out[key.lowercase()] = key to value.trim()
            }
        }
        return out.values.associate { it.first to it.second }
    }

    /** Flattens OkHttp [Headers] (multi-valued) into a map, joining repeats with ", ". */
    fun Headers?.toFlatMap(): Map<String, String> {
        if (this == null || size == 0) return emptyMap()
        return toMultimap().mapValues { it.value.joinToString(", ") }
    }

    fun get(headers: Map<String, String>?, name: String): String? =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun without(headers: Map<String, String>, vararg names: String): Map<String, String> =
        headers.filterKeys { key -> names.none { it.equals(key, ignoreCase = true) } }
}
