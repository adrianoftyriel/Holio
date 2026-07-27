package org.holio.game

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * Turns a real-world [Level.Osm] location into a field of swallowable [Prop]s
 * by querying the OpenStreetMap Overpass API, projecting the features into the
 * game world, and mapping OSM tags to prop types.
 *
 * Runs on a background thread (it does blocking network I/O). Throws on network
 * or parse failure so the caller can fall back to another level.
 *
 * Map data © OpenStreetMap contributors, licensed under the ODbL.
 */
class OsmLevelLoader {

    data class Result(val props: List<Prop>, val worldSize: Float)

    /** Fetch + build a level. Blocking; call off the UI/game thread. */
    fun load(level: Level.Osm, worldSize: Float = 2800f): Result {
        val json = fetch(buildQuery(level))
        val root = JSONObject(json)
        val elements = root.getJSONArray("elements")

        // Collect raw (lat, lon, type) for every feature we recognise.
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val types = ArrayList<PropType>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val type = classify(tags) ?: continue
            val lat: Double
            val lon: Double
            if (e.has("lat")) {
                lat = e.getDouble("lat"); lon = e.getDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                lat = c.getDouble("lat"); lon = c.getDouble("lon")
            }
            lats.add(lat); lons.add(lon); types.add(type)
        }
        if (types.isEmpty()) return Result(emptyList(), worldSize)

        // Project lat/lon to local metres (equirectangular around the centre).
        val lat0 = (level.south + level.north) / 2.0
        val lon0 = (level.west + level.east) / 2.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(lat0))
        val mPerLat = 110_540.0
        val xs = DoubleArray(types.size)
        val ys = DoubleArray(types.size)
        for (i in types.indices) {
            xs[i] = (lons[i] - lon0) * mPerLon
            // Flip so north points "up" (smaller world Y).
            ys[i] = (lat0 - lats[i]) * mPerLat
        }

        // Fit the whole footprint into the world with a margin, keeping aspect.
        val margin = 160f
        val minX = xs.minOrNull()!!; val maxX = xs.maxOrNull()!!
        val minY = ys.minOrNull()!!; val maxY = ys.maxOrNull()!!
        val spanX = (maxX - minX).toFloat()
        val spanY = (maxY - minY).toFloat()
        val usable = worldSize - 2f * margin
        val scale = usable / max(1f, max(spanX, spanY))
        val offX = margin + (usable - spanX * scale) / 2f
        val offY = margin + (usable - spanY * scale) / 2f

        val rng = Random(level.id.hashCode().toLong())
        val center = worldSize / 2f
        val clearRadius = 240f

        val built = ArrayList<Prop>(types.size)
        for (i in types.indices) {
            val px = offX + ((xs[i] - minX).toFloat()) * scale
            val py = offY + ((ys[i] - minY).toFloat()) * scale
            if (hypot(px - center, py - center) < clearRadius) continue // keep spawn clear
            val t = types[i]
            var r = t.minRadius + rng.nextFloat() * (t.maxRadius - t.minRadius)
            if (rng.nextFloat() < 0.15f) r *= 1.4f + rng.nextFloat() * 0.6f
            built.add(Prop(px, py, r, t, rng.nextFloat() * 360f))
        }

        // Cap the count so huge parks stay performant, keeping an even spread.
        val capped = if (built.size > MAX_PROPS) {
            val step = built.size.toFloat() / MAX_PROPS
            ArrayList<Prop>(MAX_PROPS).apply {
                var f = 0f
                while (size < MAX_PROPS && f < built.size) {
                    add(built[f.toInt()]); f += step
                }
            }
        } else {
            built
        }

        return Result(capped, worldSize)
    }

    private fun classify(tags: JSONObject): PropType? {
        when (tags.optString("natural")) {
            "tree" -> return PropType.TREE
            "shrub", "scrub", "heath" -> return PropType.BUSH
        }
        if (tags.has("building")) return PropType.HOUSE
        return when (tags.optString("amenity")) {
            "" -> null
            // Small street furniture reads as low groundcover.
            "bench", "waste_basket", "drinking_water", "fountain", "bicycle_parking" -> PropType.BUSH
            else -> null
        }
    }

    private fun buildQuery(level: Level.Osm): String {
        val bbox = "${level.south},${level.west},${level.north},${level.east}"
        // Buildings first so they survive the global element cap in dense areas.
        return "[out:json][timeout:25];(" +
            "way[\"building\"]($bbox);" +
            "node[\"natural\"=\"tree\"]($bbox);" +
            "node[\"natural\"=\"shrub\"]($bbox);" +
            "node[\"amenity\"]($bbox);" +
            ");out center $ELEMENT_CAP;"
    }

    private fun fetch(query: String): String {
        val url = URL(OVERPASS + "?data=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Holio-Game")
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != 200) {
                throw java.io.IOException("Overpass HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val OVERPASS = "https://overpass-api.de/api/interpreter"
        /** Cap on elements requested from Overpass (keeps payloads sane). */
        private const val ELEMENT_CAP = 900
        /** Cap on props actually placed, for on-device performance. */
        private const val MAX_PROPS = 500
    }
}
