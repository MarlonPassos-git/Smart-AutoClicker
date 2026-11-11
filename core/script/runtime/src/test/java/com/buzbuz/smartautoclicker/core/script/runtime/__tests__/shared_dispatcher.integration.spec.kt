package com.buzbuz.smartautoclicker.core.script.runtime

import com.buzbuz.smartautoclicker.core.script.runtime.session.AutomationSessionCoordinator
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedDispatcherIntegrationTest {
    @Test(timeout = 15000)
    fun blockingHostWorkOnTheInterpreterThreadDoesNotConsumeItsBusyBudget() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val coordinator = AutomationSessionCoordinator(dispatcher) {}
            val runtime = QuickJsScriptRuntime(dispatcher, ScriptRuntimeLimits(busyTimeoutMs = 100))
            val requests = mutableListOf<String>()
            var failure: ScriptExecutionException? = null
            try {
                coordinator.withSession { access ->
                    runtime.execute(program("await __klickrCall('wait'); await __klickrCall('after-wait'); while(true) {}")) { request ->
                        access.perform {
                            requests.add(request)
                            // Native detector calls block this thread; their time must not count as busy JavaScript.
                            if (request == "wait") Thread.sleep(300)
                            "{}"
                        }
                    }
                }
            } catch (error: ScriptExecutionException) {
                failure = error
            }
            assertEquals(listOf("wait", "after-wait"), requests)
            assertTrue(failure?.cause is com.dokar.quickjs.QuickJsInterruptedException)
            coordinator.withSession { access ->
                runtime.execute(program("await __klickrCall('next-session');")) { request ->
                    access.perform { requests.add(request); "{}" }
                }
            }
            assertEquals("next-session", requests.last())
        }
    }

    @Test(timeout = 15000)
    fun cancellingBusyCodeOnTheResourceThreadReleasesOwnershipBeforeTheNextSession() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val released = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val coordinator = AutomationSessionCoordinator(dispatcher) { released.complete(Unit) }
            val runtime = QuickJsScriptRuntime(dispatcher)
            val execution = launch(Dispatchers.Default) {
                coordinator.withSession { access ->
                    runtime.execute(program("await __klickrCall('entered'); while(true) {}")) {
                        access.perform { entered.complete(Unit); "{}" }
                    }
                }
            }
            withTimeout(3000) { entered.await() }
            withTimeout(3000) { execution.cancelAndJoin() }
            assertTrue(execution.isCancelled)
            assertTrue(released.isCompleted)
            coordinator.withSession { access ->
                runtime.execute(program("await __klickrCall('next-session');")) { access.perform { "{}" } }
            }
        }
    }

    private fun program(source: String) = ScriptProgram("entry.js", mapOf("entry.js" to source))
}
