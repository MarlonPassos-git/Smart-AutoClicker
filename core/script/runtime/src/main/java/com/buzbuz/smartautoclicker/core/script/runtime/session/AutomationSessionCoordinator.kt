package com.buzbuz.smartautoclicker.core.script.runtime.session

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the shared capture/detector/gesture lifetime. Both execution modes must use one instance. */
class AutomationSessionCoordinator(
    private val resourceDispatcher: CoroutineDispatcher,
    private val releaseResources: suspend () -> Unit,
) {
    private val ownership = Mutex()

    /** Rejects concurrent sessions and releases resources even on cancellation.
     * Example: `coordinator.withSession { access -> access.perform { observe() } }`.
     */
    suspend fun <T> withSession(block: suspend (AutomationSessionAccess) -> T): T {
        check(ownership.tryLock()) { "An automation session already owns the screen resources" }
        val access = AutomationSessionAccess(resourceDispatcher)
        try {
            return coroutineScope { block(access) }
        } finally {
            try {
                withContext(NonCancellable + resourceDispatcher) {
                    access.close()
                    releaseResources()
                }
            } finally {
                ownership.unlock()
            }
        }
    }
}

/** Session-scoped access rejects callbacks retained by a completed session. */
class AutomationSessionAccess internal constructor(private val dispatcher: CoroutineDispatcher) {
    private val active = AtomicBoolean(true)
    private val operations = Mutex()

    /** Serializes screen operations on the detector's dispatcher and rejects late results.
     * Example: `access.perform { detector.detectImage(...) }`.
     */
    suspend fun <T> perform(operation: suspend () -> T): T = operations.withLock {
        check(active.get()) { "The automation session has ended" }
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            check(active.get()) { "The automation session has ended" }
            val result = operation()
            currentCoroutineContext().ensureActive()
            check(active.get()) { "The automation session has ended" }
            result
        }
    }

    internal suspend fun close() {
        active.set(false)
        // A retained callback may already be inside a non-interruptible native detection.
        // Wait for it before releasing the detector and allowing another session to start.
        operations.withLock { }
    }
}
