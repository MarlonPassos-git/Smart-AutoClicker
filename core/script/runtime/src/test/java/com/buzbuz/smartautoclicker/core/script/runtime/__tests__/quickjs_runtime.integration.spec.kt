package com.buzbuz.smartautoclicker.core.script.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.ModuleContent
import com.dokar.quickjs.moduleLoader
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the published JNI artifact, rather than a mocked interpreter. */
class QuickJsRuntimeIntegrationTest {
    @Test(timeout = 10000)
    fun recursiveCodeFailsWithinTheStackLimit() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            runtime.maxStackSize = 128 * 1024
            runtime.evaluationTimeoutMillis = 2000
            var failure: QuickJsException? = null
            try {
                runtime.evaluate<Unit>("function recurse() { return 1 + recurse(); } recurse();")
            } catch (error: QuickJsException) {
                failure = error
            }
            assertTrue("Recursion must fail with a stack error", failure?.message?.contains("stack", ignoreCase = true) == true)
            assertTrue(failure !is QuickJsInterruptedException)
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 10000)
    fun invalidSyntaxReportsTheSourceWithoutCallingHostFunctions() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            var calls = 0
            runtime.function("click") { calls++; Unit }
            var failure: QuickJsException? = null
            try {
                runtime.compile("click(); const = ;", filename = "invalid.js")
            } catch (error: QuickJsException) {
                failure = error
            }
            assertEquals("invalid.js", failure?.fileName)
            assertTrue(failure?.lineNumber != null)
            assertEquals(0, calls)
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 10000)
    fun cancellationInterruptsBusyJavaScript() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        try {
            runtime.function("entered") { entered.complete(Unit); Unit }
            val evaluation = launch(Dispatchers.Default) {
                runtime.evaluate<Unit>("entered(); while(true) {}", filename = "busy.js")
            }
            withTimeout(3000) { entered.await() }
            withTimeout(3000) { evaluation.cancelAndJoin() }
            assertTrue(evaluation.isCancelled)
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 10000)
    fun memoryExhaustionFailsAndANewRuntimeStillWorks() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            runtime.memoryLimit = 2 * 1024 * 1024
            runtime.evaluationTimeoutMillis = 2000
            var failed = false
            try {
                runtime.evaluate<Unit>("const arrays = []; while(true) arrays.push(new Array(10000).fill(42));")
            } catch (error: QuickJsException) {
                assertTrue("Memory must fail before the busy-code timeout", error !is QuickJsInterruptedException)
                failed = true
            }
            assertTrue("Memory limit must be enforced", failed)
        } finally {
            runtime.close()
        }
        val next = QuickJs.create(Dispatchers.Default)
        try {
            assertEquals(42, next.evaluate<Int>("21 * 2"))
        } finally {
            next.close()
        }
    }

    @Test(timeout = 10000)
    fun localModulesAndAsyncBindingsReturnResults() = runBlocking {
        val loader = moduleLoader {
            load { name ->
                if (name == "math.js") ModuleContent.Source("export const answer = 42;") else null
            }
        }
        val runtime = QuickJs.create(Dispatchers.Default, loader)
        try {
            var result: Int? = null
            runtime.function("report") { result = (it.first() as Number).toInt() }
            runtime.asyncFunction("increment") { (it.first() as Number).toInt() + 1 }
            runtime.evaluate<Unit>(
                "import { answer } from 'math.js'; report(await increment(answer));",
                filename = "entry.js",
                asModule = true,
            )
            assertEquals(43, result)
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 10000)
    fun stoppingAnAwaitCancelsThePendingHostCallback() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        try {
            runtime.asyncFunction("waitForScreen") {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            }
            val evaluation = launch(Dispatchers.Default) {
                runtime.evaluate<Unit>("await waitForScreen()", filename = "waiting.js")
            }
            withTimeout(3000) { entered.await() }
            evaluation.cancelAndJoin()
            withTimeout(3000) { released.await() }
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 10000)
    fun syntaxValidationDoesNotExecuteCodeAndErrorsKeepTheirLocation() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            var calls = 0
            runtime.function("click") { calls++; Unit }
            runtime.compile("click();", filename = "draft.js")
            assertEquals(0, calls)
            var failure: QuickJsException? = null
            try {
                runtime.evaluate<Unit>("throw new Error('failure');", filename = "broken.js")
            } catch (error: QuickJsException) {
                failure = error
            }
            assertEquals("broken.js", failure?.fileName)
            assertTrue(failure?.lineNumber != null)
        } finally {
            runtime.close()
        }
    }

    @Test(timeout = 10000)
    fun functionsAndLoopsExecuteAndReleaseNativeResources() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            runtime.memoryLimit = 16 * 1024 * 1024
            runtime.maxStackSize = 256 * 1024
            assertEquals(45, runtime.evaluate<Int>(
                "function sum(n) { let s = 0; for(let i=0;i<n;i++) s+=i; return s; } sum(10);",
                filename = "entry.js",
            ))
        } finally {
            runtime.close()
        }
        assertTrue(runtime.isClosed)
    }

    @Test(timeout = 10000)
    fun busyJavaScriptIsInterruptedWithoutSuspensionPoints() = runBlocking {
        val runtime = QuickJs.create(Dispatchers.Default)
        try {
            runtime.evaluationTimeoutMillis = 100
            var interrupted = false
            try {
                runtime.evaluate<Unit>("while (true) {}", filename = "busy.js")
            } catch (_: QuickJsInterruptedException) {
                interrupted = true
            }
            assertTrue("Busy JavaScript must be interruptible", interrupted)
        } finally {
            runtime.close()
        }
        assertTrue(runtime.isClosed)
    }
}
