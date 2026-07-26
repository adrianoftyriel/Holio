package org.holio.game

/**
 * A place to play. [Classic] is the original hand-tuned procedural field and is
 * always available offline; the [Osm] levels are real-world locations pulled
 * from OpenStreetMap (© OpenStreetMap contributors, ODbL) at play time and
 * turned into things to swallow.
 */
sealed class Level(val id: String, val title: String, val subtitle: String) {

    object Classic : Level("classic", "Classic Field", "Offline · the original map")

    /** A real location, described by a lat/lon bounding box for Overpass. */
    class Osm(
        id: String,
        title: String,
        subtitle: String,
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    ) : Level(id, title, subtitle)

    companion object {
        /** Levels offered on the picker, in order. */
        val ALL: List<Level> = listOf(
            Classic,
            Osm(
                "central_park", "Central Park", "New York · park",
                40.7780, -73.9700, 40.7850, -73.9600,
            ),
            Osm(
                "mit", "MIT Campus", "Cambridge, MA · campus",
                42.3580, -71.0960, 42.3620, -71.0880,
            ),
            Osm(
                "mont_saint_michel", "Mont-Saint-Michel", "France · walled town",
                48.6340, -1.5130, 48.6370, -1.5085,
            ),
            Osm(
                "golden_gate_park", "Golden Gate Park", "San Francisco · park",
                37.7670, -122.4720, 37.7710, -122.4640,
            ),
            Osm(
                "hyde_park", "Hyde Park", "London · park",
                51.5055, -0.1680, 51.5090, -0.1600,
            ),
            Osm(
                "stanford", "Stanford Quad", "California · campus",
                37.4260, -122.1710, 37.4300, -122.1655,
            ),
            Osm(
                "venice", "Venice", "Italy · canal town",
                45.4330, 12.3350, 45.4355, 12.3410,
            ),
        )
    }
}
