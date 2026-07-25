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
        )
    }
}
