package org.holio.game

import android.graphics.Canvas
import android.view.SurfaceHolder

/**
 * The game loop. Runs on its own thread, updating and rendering at roughly
 * 60 FPS using a capped variable timestep.
 */
class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView,
) : Thread() {

    @Volatile
    var running = false

    override fun run() {
        var lastNanos = System.nanoTime()
        while (running) {
            val frameStart = System.nanoTime()
            var dt = (frameStart - lastNanos) / 1_000_000_000f
            lastNanos = frameStart
            // Cap dt so a hitch (or resume) can't teleport the hole across the map.
            if (dt > MAX_DT) dt = MAX_DT

            gameView.update(dt)

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        gameView.render(canvas)
                    }
                }
            } finally {
                if (canvas != null) surfaceHolder.unlockCanvasAndPost(canvas)
            }

            // Sleep to cap the frame rate.
            val elapsedMs = (System.nanoTime() - frameStart) / 1_000_000
            val sleepMs = TARGET_FRAME_MS - elapsedMs
            if (sleepMs > 0) {
                try {
                    sleep(sleepMs)
                } catch (ignored: InterruptedException) {
                }
            }
        }
    }

    companion object {
        private const val MAX_DT = 0.05f
        private const val TARGET_FRAME_MS = 16L
    }
}
