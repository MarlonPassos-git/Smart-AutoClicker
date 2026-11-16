package com.buzbuz.smartautoclicker.core.processing.data.processor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.min

/** Cooperative pause and active clock shared by one Smart processor; never recreates scenario state. */
internal class ProcessingPause(private val clock: () -> Long = System::currentTimeMillis) {
    private val paused = MutableStateFlow(false)
    private var pausedAt = 0L
    private var pausedDuration = 0L
    @Volatile var generation = 0L
        private set
    private var handledGeneration = 0L
    val observedGeneration: Long get() = handledGeneration
    var onPause: suspend () -> Unit = {}

    @Synchronized fun pause() {
        if (paused.value) return
        pausedAt = clock()
        generation++
        paused.value = true
    }

    @Synchronized fun resume() {
        if (!paused.value) return
        pausedDuration += (clock() - pausedAt).coerceAtLeast(0)
        paused.value = false
    }

    @Synchronized fun now(): Long = (if (paused.value) pausedAt else clock()) - pausedDuration

    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (handledGeneration != generation) {
            handledGeneration = generation
            onPause()
        }
        paused.first { !it }
        currentCoroutineContext().ensureActive()
    }

    suspend fun wait(milliseconds: Long) {
        val end = now() + milliseconds
        while (now() < end) {
            checkpoint()
            val remaining = end - now()
            if (remaining > 0) delay(min(remaining, 20))
        }
        checkpoint()
    }
}
