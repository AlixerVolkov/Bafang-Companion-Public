package dev.alixer.bafang

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Search-as-you-type OSM geocoding using Photon, NOT the public Nominatim API.
 * Photon demo is fair-use only: min 3 chars, debounced by UI, 1.3 s rate limit,
 * bounded results and session LRU cache. Production/high-volume use needs a service SLA.
 */
class AddressSearch {
    data class Place(val name: String, val lat: Double, val lon: Double)
    private val cache = object : LinkedHashMap<String, List<Place>>(36, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Place>>?): Boolean = size > 35
    }
    private var lastRequestAt: Long = 0L

    @Synchronized fun find(input: String): List<Place> {
        val query = input.trim().take(140)
        require(query.length >= 3) { "Introduce al menos tres caracteres" }
        val key = query.lowercase(Locale.ROOT)
        cache[key]?.let { return it }
        val elapsed = System.currentTimeMillis() - lastRequestAt
        if (elapsed in 0L..1299L) Thread.sleep(1300L - elapsed)
        lastRequestAt = System.currentTimeMillis()
        // Photon demo does not index every Android locale; unsupported lang codes return HTTP 400.
        val requestedLanguage = Locale.getDefault().language.take(2)
        val lang = requestedLanguage.takeIf { it in setOf("en", "fr", "de", "it") } ?: "en"
        val url = "https://photon.komoot.io/api/?q=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=5&lang=" + URLEncoder.encode(lang, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12_000
        conn.readTimeout = 17_000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "BafangCompanion/0.14 (private Android bicycle companion; https://github.com/AlixerVolkov/Bafang-Companion)")
        conn.setRequestProperty("Accept-Language", "$lang,en;q=0.7")
        try {
            val status = conn.responseCode
            if (status == 429 || status == 503)
                throw IOException("Servicio ocupado; prueba de nuevo en unos segundos (HTTP $status)")
            if (status != 200) throw IOException("Búsqueda Photon HTTP $status")
            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText().take(250_000) })
            val features = json.optJSONArray("features") ?: return emptyList()
            val seen = mutableSetOf<String>()
            val result = (0 until features.length()).mapNotNull { index ->
                val feature = features.optJSONObject(index) ?: return@mapNotNull null
                val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: return@mapNotNull null
                if (coords.length() < 2) return@mapNotNull null
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0)
                    return@mapNotNull null
                val p = feature.optJSONObject("properties") ?: return@mapNotNull null
                fun prop(name: String): String? = p.optString(name).trim().takeIf {
                    it.isNotEmpty() && it != "null"
                }
                val parts = linkedSetOf<String>()
                val street = prop("street")
                val name = prop("name")
                val house = prop("housenumber")
                if (name != null && name != street) parts.add(name)
                if (street != null) parts.add(listOfNotNull(street, house).joinToString(" "))
                if (street == null && name == null) house?.let(parts::add)
                listOf("postcode", "city", "district", "county", "state", "country").forEach { k ->
                    prop(k)?.let(parts::add)
                }
                if (parts.isEmpty()) return@mapNotNull null
                val title = parts.joinToString(", ").take(250)
                val id = "${lat},${lon}"
                if (!seen.add(id)) return@mapNotNull null
                Place(title, lat, lon)
            }
            cache[key] = result
            return result
        } finally { conn.disconnect() }
    }
}