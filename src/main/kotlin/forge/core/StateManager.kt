package forge.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exception thrown when the pipeline is stopped by the user.
 */
class StoppedException : RuntimeException("Pipeline stopped by user")

/**
 * Manages the execution state of the Forge pipeline, providing pause, resume,
 * and stop capabilities. Also installs a SIGINT handler so that:
 *   - First Ctrl+C pauses the pipeline.
 *   - Second Ctrl+C stops the pipeline.
 *
 * Thread-safe: all state transitions use atomic operations and a [CountDownLatch]
 * for blocking during pause.
 */
class StateManager {

    private val paused = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val sigintCount = AtomicInteger(0)

    @Volatile
    private var pauseLatch = CountDownLatch(0)

    init {
        installSignalHandler()
    }

    // -- Public control methods -----------------------------------------------

    /**
     * Pauses the pipeline. Any thread calling [checkPauseOrStop] will block
     * until [resume] is called.
     */
    fun pause() {
        if (!paused.getAndSet(true)) {
            pauseLatch = CountDownLatch(1)
        }
    }

    /**
     * Resumes the pipeline after a pause, unblocking any threads waiting
     * in [checkPauseOrStop].
     */
    fun resume() {
        if (paused.getAndSet(false)) {
            pauseLatch.countDown()
        }
    }

    /**
     * Signals the pipeline to stop. Any thread calling [checkPauseOrStop]
     * will receive a [StoppedException].
     */
    fun stop() {
        stopped.set(true)
        // Also release any paused threads so they can observe the stop
        if (paused.getAndSet(false)) {
            pauseLatch.countDown()
        }
    }

    /**
     * Clears all accumulated state: stops any pause, resets the stop flag,
     * and resets the SIGINT counter. Use this between pipeline runs.
     */
    fun clear() {
        resume()
        stopped.set(false)
        sigintCount.set(0)
    }

    /**
     * Full reset: equivalent to [clear]. Provided for semantic clarity
     * when starting a completely new session.
     */
    fun reset() {
        clear()
    }

    /**
     * Returns true if the pipeline is currently paused.
     */
    fun isPaused(): Boolean = paused.get()

    /**
     * Returns true if the pipeline has been stopped.
     */
    fun isStopped(): Boolean = stopped.get()

    // -- Checkpoint method ----------------------------------------------------

    /**
     * Checkpoint that pipeline stages call periodically. Behaviour:
     * - If the pipeline is paused, blocks the calling thread until [resume] is called.
     * - If the pipeline is stopped (either before or after a pause), throws [StoppedException].
     *
     * @throws StoppedException if the pipeline has been stopped
     */
    @Throws(StoppedException::class)
    fun checkPauseOrStop() {
        if (stopped.get()) {
            throw StoppedException()
        }

        if (paused.get()) {
            try {
                pauseLatch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw StoppedException()
            }
        }

        // Re-check after waking from pause
        if (stopped.get()) {
            throw StoppedException()
        }
    }

    // -- SIGINT handler -------------------------------------------------------

    /**
     * Installs a SIGINT (Ctrl+C) handler using sun.misc.Signal:
     *   - First press: pauses the pipeline and prints a message.
     *   - Second press: stops the pipeline and prints a message.
     *   - Third press: exits the JVM immediately.
     */
    private fun installSignalHandler() {
        try {
            sun.misc.Signal.handle(sun.misc.Signal("INT")) {
                val count = sigintCount.incrementAndGet()
                when {
                    count == 1 -> {
                        System.err.println("\n[Forge] Pausing... (press Ctrl+C again to stop)")
                        pause()
                    }
                    count == 2 -> {
                        System.err.println("\n[Forge] Stopping pipeline...")
                        stop()
                    }
                    else -> {
                        System.err.println("\n[Forge] Force exit.")
                        Runtime.getRuntime().halt(1)
                    }
                }
            }
        } catch (_: Exception) {
            // Signal handling may not be available on all platforms.
            // Silently ignore -- the pipeline will still work, just without
            // graceful Ctrl+C handling.
        }
    }
}
