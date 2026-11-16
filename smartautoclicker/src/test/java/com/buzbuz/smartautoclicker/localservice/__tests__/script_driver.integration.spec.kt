package com.buzbuz.smartautoclicker.localservice

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.*
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptDriverIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 20000)
    fun preparationWaitsForTheCalibratedOrientationBeforeCreatingCaptureOrExecutingCode() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            mapOf("manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"landscape","name":"Paisagem","scriptVersion":"1","entry":"entry.js","screen":{"width":200,"height":100}}""",
                "entry.js" to "const example = true;").forEach { (path, source) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(source.toByteArray()); zip.closeEntry()
            }
        }
        val stored = ScriptPackageRepository(File(temporary.root, "scripts/packages")).importPackage(ByteArrayInputStream(bytes.toByteArray()))
        val display = mockk<DisplayConfigManager>(relaxed = true)
        var configuration = DisplayConfig(Point(100, 200), 1, 0, emptyMap())
        every { display.displayConfig } answers { configuration }
        val listener = slot<(Context) -> Unit>()
        every { display.addOrientationListener(capture(listener)) } returns Unit
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        val entered = CompletableDeferred<Unit>()
        val runtime = object : ScriptRuntime {
            override suspend fun validate(program: ScriptProgram) = Unit
            override suspend fun execute(program: ScriptProgram, host: ScriptHost) { entered.complete(Unit) }
        }
        val driver = ScriptServiceDriver(context, recorder, display, mockk(relaxed = true), mockk(relaxed = true), "orientation-test", { mockk(relaxed = true) }, runtime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            driver.start(scope, -1, Intent(), stored.storageId, "{}")
            withTimeout(5000) { driver.diagnostics.latest.first { it?.stage == "Preparando captura" } }
            coVerify(exactly = 0) { recorder.startScreenRecord(any()) }
            assertFalse(entered.isCompleted)
            configuration = DisplayConfig(Point(200, 100), 1, 1, emptyMap())
            listener.captured(context)
            withTimeout(5000) { entered.await() }
            coVerify(exactly = 1) { recorder.startScreenRecord(Point(200, 100)) }
        } finally { driver.stopAndJoin(); scope.cancel() }
    }

    @Test(timeout = 20000)
    fun rotatingTheTargetStopsExecutionBeforeCoordinatesCanBeReused() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            mapOf("manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"rotation","name":"Exemplo","scriptVersion":"1","entry":"entry.js"}""",
                "entry.js" to "const example = true;").forEach { (path, source) ->
                output.putNextEntry(ZipEntry(path)); output.write(source.toByteArray()); output.closeEntry()
            }
        }
        val repository = ScriptPackageRepository(File(temporary.root, "scripts/packages"))
        val stored = repository.importPackage(ByteArrayInputStream(zip.toByteArray()))
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        val display = mockk<DisplayConfigManager>(relaxed = true)
        every { display.displayConfig } returns DisplayConfig(Point(100, 200), 1, 0, emptyMap())
        val listener = slot<(Context) -> Unit>()
        every { display.addOrientationListener(capture(listener)) } returns Unit
        val entered = CompletableDeferred<Unit>()
        val runtime = object : ScriptRuntime {
            override suspend fun validate(program: ScriptProgram) = Unit
            override suspend fun execute(program: ScriptProgram, host: ScriptHost) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val detector = mockk<ImageDetector>(relaxed = true)
        val driver = ScriptServiceDriver(context, recorder, display, mockk(relaxed = true), mockk(relaxed = true), "driver-processing-tag", { detector }, runtime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            driver.start(scope, -1, Intent(), stored.storageId, "{}")
            withTimeout(10000) { entered.await() }
            listener.captured(context)
            withTimeout(5000) { driver.status.first { it.state == ScriptSessionState.STOPPED } }
            driver.stopAndJoin()
            assertTrue(driver.status.value.reason!!.contains("Orientação"))
            val summary = File(temporary.root, "scripts/execution-logs").listFiles()!!.single().resolve("summary.json")
            val persisted = Json.parseToJsonElement(summary.readText()).jsonObject
            assertEquals("STOPPED", persisted["status"]?.jsonPrimitive?.content)
            assertTrue(persisted["reason"]!!.jsonPrimitive.content.contains("Orientação"))
            assertEquals(stored.storageId, persisted["revision"]?.jsonPrimitive?.content)
            coVerify(exactly = 1) { recorder.stopProjection() }
            verify(exactly = 1) { display.removeOrientationListener(listener.captured) }
            verify(exactly = 1) { detector.close() }
        } finally { driver.stopAndJoin(); scope.cancel() }
    }

    @Test(timeout = 20000)
    fun captureFailurePersistsTheOperationAndErrorEvenWithTheDiagnosticViewerClosed() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            mapOf("manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"capture-failure","name":"Exemplo","scriptVersion":"1","entry":"entry.js"}""",
                "entry.js" to "const example = true;").forEach { (path, source) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(source.toByteArray()); zip.closeEntry()
            }
        }
        val stored = ScriptPackageRepository(File(temporary.root, "scripts/packages")).importPackage(ByteArrayInputStream(bytes.toByteArray()))
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        coEvery { recorder.acquireLatestBitmap() } returns null
        val display = mockk<DisplayConfigManager>(relaxed = true)
        every { display.displayConfig } returns DisplayConfig(Point(100, 200), 1, 0, emptyMap())
        val runtime = object : ScriptRuntime {
            override suspend fun validate(program: ScriptProgram) = Unit
            override suspend fun execute(program: ScriptProgram, host: ScriptHost) {
                host.call("""{"apiVersion":1,"operation":"observe"}""")
            }
        }
        val driver = ScriptServiceDriver(context, recorder, display, mockk(relaxed = true), mockk(relaxed = true), "capture-failure", { mockk(relaxed = true) }, runtime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            driver.start(scope, -1, Intent(), stored.storageId, "{}")
            withTimeout(10000) { driver.status.first { it.state == ScriptSessionState.FAILED } }
            driver.stopAndJoin()
            val history = File(temporary.root, "scripts/execution-logs").listFiles()!!.single()
            val summary = Json.parseToJsonElement(history.resolve("summary.json").readText()).jsonObject
            assertEquals(JsonPrimitive("FAILED"), summary["status"])
            assertTrue(summary["error"]!!.jsonObject["stackTrace"]!!.jsonPrimitive.content.isNotBlank())
            val events = history.resolve("events.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertTrue(events.any { it["operation"] == JsonPrimitive("observe") && it["outcome"] == JsonPrimitive("failed") })
            assertFalse(driver.diagnostics.isDetailedEnabled)
        } finally { driver.stopAndJoin(); scope.cancel() }
    }

    @Test(timeout = 20000)
    fun aCompletedScriptKeepsItsResultWhileProjectionCleanupIsStillPending() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((path, source) in mapOf("manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1","entry":"entry.js","executionState":{"version":1}}""",
                "entry.js" to "const example = true;")) {
                zip.putNextEntry(ZipEntry(path)); zip.write(source.toByteArray()); zip.closeEntry()
            }
        }
        val repository = ScriptPackageRepository(File(temporary.root, "scripts/packages"))
        val stored = repository.importPackage(ByteArrayInputStream(bytes.toByteArray()))
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val recorder = mockk<DisplayRecorder>(relaxed = true)
        coEvery { recorder.acquireLatestBitmap() } returns bitmap
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { recorder.stopProjection() } coAnswers { closing.complete(Unit); release.await() }
        val display = mockk<DisplayConfigManager>(relaxed = true)
        every { display.displayConfig } returns DisplayConfig(Point(100, 200), 1, 0, emptyMap())
        val detector = mockk<ImageDetector>(relaxed = true)
        val runtime = object : ScriptRuntime {
            override suspend fun validate(program: ScriptProgram) = Unit
            override suspend fun execute(program: ScriptProgram, host: ScriptHost) {
                host.call("""{"apiVersion":1,"operation":"saveState","value":{"version":1,"pending":{"kind":"attack","confirmedBefore":2}},"completed":false}""")
                host.call("""{"apiVersion":1,"operation":"observe"}""")
            }
        }
        val driver = ScriptServiceDriver(context, recorder, display, mockk(relaxed = true), mockk(relaxed = true), "driver-processing-tag", { detector }, runtime)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            driver.start(scope, -1, Intent(), stored.storageId, "{}")
            withTimeout(10000) { closing.await() }
            assertTrue(driver.status.value.state == ScriptSessionState.COMPLETED)
            val stopped = launch { driver.stopAndJoin() }
            yield()
            assertFalse(stopped.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { stopped.join() }
            coVerify(exactly = 1) { recorder.startProjection(any(), any(), any()) }
            coVerify(exactly = 1) { recorder.stopProjection() }
            verify(exactly = 1) { detector.close() }
            val history = File(temporary.root, "scripts/execution-logs").listFiles()!!.single()
            assertEquals("COMPLETED", Json.parseToJsonElement(history.resolve("summary.json").readText()).jsonObject["status"]?.jsonPrimitive?.content)
            val checkpoint = Json.parseToJsonElement(history.resolve("checkpoint.json").readText()).jsonObject
            assertEquals(JsonPrimitive("attack"), checkpoint["state"]?.jsonObject?.get("pending")?.jsonObject?.get("kind"))
            assertFalse(checkpoint.containsKey("answers"))
            val events = history.resolve("events.jsonl").readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertTrue(events.any { it["operation"] == JsonPrimitive("observe") && it["outcome"] == JsonPrimitive("success") })
            assertFalse(bitmap.isRecycled)
            verify(exactly = 1) { detector.setScreenBitmap(bitmap, "driver-processing-tag") }
        } finally {
            release.complete(Unit)
            driver.stopAndJoin()
            scope.cancel()
            bitmap.recycle()
        }
    }
}
