package org.holio.game

import kotlin.math.PI

/** The kind of object sitting on the map, with its size range, score and colors. */
enum class PropType(
    val minRadius: Float,
    val maxRadius: Float,
    val points: Int,
    val bodyColor: Int,
    val accentColor: Int,
    /**
     * Upright drawing height as a multiple of the prop's radius, used by the
     * isometric renderer to stand the prop up off its ground footprint.
     */
    val heightFactor: Float,
) {
    // Small groundcover — the very first things a tiny hole can eat.
    BUSH(7f, 18f, 1, 0xFF66BB6A.toInt(), 0xFF388E3C.toInt(), 1.1f),
    // Trees — a canopy with a trunk; range from saplings to big oaks.
    TREE(15f, 36f, 3, 0xFF2E7D32.toInt(), 0xFF5D4037.toInt(), 2.6f),
    // Cars — from compacts to vans.
    CAR(16f, 34f, 6, 0xFFE53935.toInt(), 0xFF212121.toInt(), 1.0f),
    // Buildings — cottages up to whole blocks; the big prize.
    HOUSE(30f, 90f, 15, 0xFFECC08D.toInt(), 0xFF8D3B2E.toInt(), 1.9f),
}

/**
 * A single swallowable object on the map.
 *
 * While being swallowed it animates from its resting position toward the hole
 * centre, shrinking as it "falls in".
 */
class Prop(
    val x: Float,
    val y: Float,
    val radius: Float,
    val type: PropType,
    /** Small per-instance rotation so props of the same type don't look identical. */
    val rotationDeg: Float,
) {
    /** 0 = untouched, 1 = fully gone. */
    var swallowT: Float = 0f
    var removed: Boolean = false

    /** Which hole is currently swallowing this prop (null until claimed). */
    var swallower: Hole? = null

    /** Interpolated draw state while sinking into the hole. */
    var drawX: Float = x
    var drawY: Float = y
    var drawScale: Float = 1f

    val beingSwallowed: Boolean get() = swallowT > 0f
    val points: Int get() = type.points

    /** Area used for the hole's growth math. */
    val area: Float get() = (PI * radius * radius).toFloat()
}
