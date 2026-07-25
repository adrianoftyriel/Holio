package org.holio.game

import kotlin.math.PI

/** The kind of object sitting on the map, with its size range, score and colors. */
enum class PropType(
    val minRadius: Float,
    val maxRadius: Float,
    val points: Int,
    val bodyColor: Int,
    val accentColor: Int,
) {
    // Small groundcover — the very first things a tiny hole can eat.
    BUSH(10f, 15f, 1, 0xFF66BB6A.toInt(), 0xFF388E3C.toInt()),
    // Trees — a canopy with a trunk.
    TREE(15f, 22f, 3, 0xFF2E7D32.toInt(), 0xFF5D4037.toInt()),
    // Cars — medium, worth more.
    CAR(16f, 20f, 6, 0xFFE53935.toInt(), 0xFF212121.toInt()),
    // Houses — the big prize, only a grown hole can take them.
    HOUSE(30f, 44f, 15, 0xFFECC08D.toInt(), 0xFF8D3B2E.toInt()),
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

    /** Interpolated draw state while sinking into the hole. */
    var drawX: Float = x
    var drawY: Float = y
    var drawScale: Float = 1f

    val beingSwallowed: Boolean get() = swallowT > 0f
    val points: Int get() = type.points

    /** Area used for the hole's growth math. */
    val area: Float get() = (PI * radius * radius).toFloat()
}
