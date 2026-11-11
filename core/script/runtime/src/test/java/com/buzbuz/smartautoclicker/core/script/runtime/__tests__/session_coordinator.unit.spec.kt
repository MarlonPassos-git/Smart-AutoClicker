package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionAccess
import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    @Test(timeout = 10000)
    fun cleanupWaitsForAnOperationAlreadyInFlight() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AutomationSessionCoordinator(Dispatchers.Unconfined) { events.add("release") }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        lateinit var access: AutomationSessionAccess
        val acquired = CompletableDeferred<Unit>()
        val endSession = CompletableDeferred<Unit>()
        val session = launch {
            coordinator.withSession {
                access = it
                acquired.complete(Unit)
                endSession.await()
            }
        }
        acquired.await()
        val callback = launch {
            try {
                access.perform {
                    entered.complete(Unit)
                    finish.await()
                    events.add("operation finished")
                }
            } catch (_: IllegalStateException) {
                events.add("late result rejected")
            }
        }
        entered.await()
        endSession.complete(Unit)
        yield()
        assertEquals(emptyList<String>(), events)
        finish.complete(Unit)
        callback.join()
        session.join()
        assertTrue(events.indexOf("release") > events.indexOf("operation finished"))
        assertTrue("late result rejected" in events)
    }

    @Test(timeout = 10000)
    fun concurrentSessionIsRejectedAndCancellationReleasesOwnership() = runBlocking {
        var releases = 0
        val coordinator = AutomationSessionCoordinator(Dispatchers.Unconfined) { releases++ }
        val entered = CompletableDeferred<Unit>()
        val first = launch {
            coordinator.withSession { entered.complete(Unit); awaitCancellation() }
        }
        entered.await()
        var rejected = false
        try {
            coordinator.withSession { error("Must not run") }
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        first.cancelAndJoin()
        assertEquals(1, releases)
        coordinator.withSession { access -> assertEquals(42, access.perform { 42 }) }
        assertEquals(2, releases)
    }

    @Test(timeout = 10000)
    fun retainedAccessCannotAffectTheNextSession() = runBlocking {
        val coordinator = AutomationSessionCoordinator(Dispatchers.Unconfined) {}
        lateinit var previous: AutomationSessionAccess
        coordinator.withSession { previous = it }
        var calls = 0
        coordinator.withSession {
            var rejected = false
            try {
                previous.perform { calls++ }
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
        }
        assertEquals(0, calls)
    }
}
