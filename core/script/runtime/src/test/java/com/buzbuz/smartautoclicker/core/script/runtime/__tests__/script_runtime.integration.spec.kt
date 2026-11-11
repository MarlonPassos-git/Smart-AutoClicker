package com.buzbuz.smartautoclicker.core.script.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRuntimeIntegrationTest {
    private val runtime: ScriptRuntime = QuickJsScriptRuntime(Dispatchers.Default)

    @Test(timeout = 10000)
    fun busyBudgetInterruptsLoopsEvenWithAPendingHostPromise() = runBlocking {
        val limited = QuickJsScriptRuntime(Dispatchers.Default, ScriptRuntimeLimits(busyTimeoutMs = 100))
        for (source in listOf("while(true) {}", "__klickrCall('{}'); while(true) {}")) {
            var failure: ScriptExecutionException? = null
            try {
                limited.execute(ScriptProgram("entry.js", mapOf("entry.js" to source))) { awaitCancellation() }
            } catch (error: ScriptExecutionException) {
                failure = error
            }
            assertTrue("Busy code must be interrupted", failure?.cause is com.dokar.quickjs.QuickJsInterruptedException)
        }
    }

    @Test(timeout = 10000)
    fun hostWaitDoesNotConsumeTheBusyJavaScriptBudget() = runBlocking {
        val limited = QuickJsScriptRuntime(Dispatchers.Default, ScriptRuntimeLimits(busyTimeoutMs = 100))
        limited.execute(ScriptProgram("entry.js", mapOf(
            "entry.js" to "await __klickrCall('{}'); let total = 0; for(let i=0;i<100000;i++) total += i;",
        ))) { delay(300); "{}" }
    }

    @Test(timeout = 10000)
    fun cancellingTheAdapterStopsBusyCode() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val program = ScriptProgram("entry.js", mapOf(
            "entry.js" to "await __klickrCall('{}'); while(true) {}",
        ))
        val execution = launch(Dispatchers.Default) {
            runtime.execute(program) { entered.complete(Unit); "{}" }
        }
        withTimeout(3000) { entered.await() }
        withTimeout(3000) { execution.cancelAndJoin() }
        assertTrue(execution.isCancelled)
    }

    @Test(timeout = 10000)
    fun cancellingTheAdapterReleasesThePendingHostOperation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val program = ScriptProgram("entry.js", mapOf("entry.js" to "await __klickrCall('{}');"))
        val execution = launch(Dispatchers.Default) {
            runtime.execute(program) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
        }
        withTimeout(3000) { entered.await() }
        withTimeout(3000) { execution.cancelAndJoin() }
        withTimeout(3000) { released.await() }
        assertTrue(execution.isCancelled)
    }

    @Test(timeout = 10000)
    fun validationDoesNotCallTheHostAndExecutionSupportsLocalModules() = runBlocking {
        val program = ScriptProgram("entry.js", mapOf(
            "entry.js" to "import { request } from './request.js'; await __klickrCall(request);",
            "request.js" to "export const request = '{\"operation\":\"observe\"}';",
        ))
        val requests = mutableListOf<String>()
        runtime.validate(program)
        runtime.execute(program) { request -> requests.add(request); "{}" }
        assertEquals(listOf("{\"operation\":\"observe\"}"), requests)
    }

    @Test(timeout = 10000)
    fun globalsDoNotLeakIntoTheNextExecution() = runBlocking {
        val program = ScriptProgram("entry.js", mapOf(
            "entry.js" to "globalThis.count = (globalThis.count || 0) + 1; await __klickrCall(String(count));",
        ))
        val requests = mutableListOf<String>()
        val host = ScriptHost { request -> requests.add(request); "{}" }
        runtime.execute(program, host)
        runtime.execute(program, host)
        assertEquals(listOf("1", "1"), requests)
    }

    @Test(timeout = 10000)
    fun errorsPreserveTheirSourceAndCause() = runBlocking {
        val program = ScriptProgram("broken.js", mapOf("broken.js" to "throw new Error('failed');"))
        var failure: ScriptExecutionException? = null
        try {
            runtime.execute(program) { "{}" }
        } catch (error: ScriptExecutionException) {
            failure = error
        }
        assertEquals("broken.js", failure?.sourceFile)
        assertTrue(failure?.sourceLine != null)
        assertTrue(failure?.cause != null)
    }
}
