package org.holio.game

/**
 * The read-only view the [Renderer] needs to draw a round. Implemented by the
 * authoritative [GameWorld] (single-player and multiplayer host) and by the
 * network-fed [ClientScene] on multiplayer clients.
 */
interface Scene {
    val worldSize: Float
    val viewportW: Float
    val viewportH: Float

    /** The local player's hole — the camera focus. */
    val hole: Hole

    /** Every hole to draw (order is stable within a round). */
    val holes: List<Hole>

    /** Every prop to draw. */
    val props: List<Prop>

    val state: GameWorld.State

    /** Radius of the largest prop in this level — the size-bar's "full" mark. */
    val biggestPropRadius: Float

    fun secondsLeft(): Int
    fun standings(): List<Hole>
}
