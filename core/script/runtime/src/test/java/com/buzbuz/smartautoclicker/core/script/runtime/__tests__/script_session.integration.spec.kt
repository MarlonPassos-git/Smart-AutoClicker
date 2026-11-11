package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionCoordinator
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSession
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import com.buzbuz.smartautoclicker.core.script.runtime.session.SessionAutomationHost
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptSessionIntegrationTest {
    @Test(timeout = 15000)
    fun pauseReleasesPointersAndRevalidatesBeforeAnotherOperation() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val session = newSession()
        val host = host(events, true) { session.pause() }
        val execution = launch { session.run(program("await __klickrCall('first'); await __klickrCall('second');"), host) }
        withTimeout(5000) { session.status.first { it.state == ScriptSessionState.PAUSED } }
        assertEquals(listOf("first", "release", "invalidate"), events.toList())
        assertTrue(session.resume())
        withTimeout(5000) { execution.join() }
        assertEquals(ScriptSessionState.COMPLETED, session.status.value.state)
        assertEquals(listOf("first", "release", "invalidate", "revalidate", "second", "release", "invalidate"), events.toList())
    }

    @Test(timeout = 15000)
    fun changedScreenFailsWithoutExecutingTheNextOperation() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val session = newSession()
        val execution = launch {
            session.run(program("await __klickrCall('first'); await __klickrCall('second');"), host(events, false) { session.pause() })
        }
        withTimeout(5000) { session.status.first { it.state == ScriptSessionState.PAUSED } }
        session.resume()
        withTimeout(5000) { execution.join() }
        assertEquals(ScriptSessionState.FAILED, session.status.value.state)
        assertTrue(session.status.value.failure?.message.orEmpty().contains("A tela não foi confirmada ao retomar"))
        assertTrue("second" !in events)
    }

    @Test(timeout = 15000)
    fun stopInterruptsBusyCodeAndCleansUpPointers() = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val session = newSession()
        val entered = CompletableDeferred<Unit>()
        val execution = launch {
            session.run(program("await __klickrCall('first'); while(true) {}"), host(events, true) { entered.complete(Unit) })
        }
        withTimeout(5000) { entered.await() }
        assertTrue(session.stop())
        withTimeout(5000) { execution.join() }
        assertEquals(ScriptSessionState.STOPPED, session.status.value.state)
        assertEquals("Interrompido pelo usuário", session.status.value.reason)
        assertTrue("release" in events)
        assertTrue("invalidate" in events)
    }

    private fun newSession() = ScriptSession(
        QuickJsScriptRuntime(Dispatchers.Default),
        AutomationSessionCoordinator(Dispatchers.Default) {},
    )

    private fun program(source: String) = ScriptProgram("entry.js", mapOf("entry.js" to source))

    private fun host(events: MutableList<String>, validScreen: Boolean, onFirst: () -> Unit) = object : SessionAutomationHost {
        override suspend fun call(requestJson: String): String {
            events.add(requestJson)
            if (requestJson == "first") onFirst()
            return "{}"
        }
        override suspend fun releasePointers() { events.add("release") }
        override suspend fun invalidateObservations() { events.add("invalidate") }
        override suspend fun revalidate(): Boolean { events.add("revalidate"); return validScreen }
    }
}
