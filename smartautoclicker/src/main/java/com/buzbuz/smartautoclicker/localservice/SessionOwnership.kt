package com.buzbuz.smartautoclicker.localservice

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Shared across accessibility-service reconnections so an old cleanup cannot affect a new session. */
@Singleton
class SessionOwnership @Inject constructor() {
    private val owner = AtomicReference<Any?>(null)

    /** Returns an exclusive lease or null while cleanup still owns resources.
     * Example: `val lease = ownership.tryAcquire() ?: return`; close only after native and service cleanup.
     */
    fun tryAcquire(): AutoCloseable? {
        val token = Any()
        if (!owner.compareAndSet(null, token)) return null
        return AutoCloseable { owner.compareAndSet(token, null) }
    }
}
