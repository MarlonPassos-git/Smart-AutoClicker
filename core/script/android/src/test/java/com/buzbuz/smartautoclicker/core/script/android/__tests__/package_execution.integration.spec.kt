package com.buzbuz.smartautoclicker.core.script.android

import android.graphics.Bitmap
import android.graphics.Point
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutor
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackageExecutionIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 20000)
    fun importedJavaScriptUsesParametersOcrAndTheExistingGestureExecutorOnOneDetectorThread() = runBlocking {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            val sources = mapOf(
                "manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1","entry":"entry.js",
                    "parameters":{"minimum":{"type":"number","label":"Mínimo","default":1,"min":0}}}""",
                "entry.js" to """
                    import { observe, read, parameters, tap, stop } from 'klickr';
                    const options = await parameters();
                    const frame = await observe();
                    const value = await read(frame, { kind: 'number', threshold: 80,
                        region: { left: 0, top: 0, right: 50, bottom: 50 } });
                    if (value.number >= options.minimum) await tap(value);
                    await stop('Meta atingida');
                """.trimIndent(),
            )
            sources.forEach { (name, source) -> zip.putNextEntry(ZipEntry(name)); zip.write(source.toByteArray()); zip.closeEntry() }
        }
        val repository = ScriptPackageRepository(temporary.newFolder("packages"))
        val stored = repository.importPackage(ByteArrayInputStream(archive.toByteArray()))
        val threads = mutableSetOf<Thread>()
        fun recordThread() { threads.add(Thread.currentThread()) }
        val models = mutableListOf<Set<String>>()
        val detector = mockk<ImageDetector>(relaxed = true)
        every { detector.init() } answers { recordThread() }
        every { detector.detectNumber(any(), any(), any()) } answers {
            recordThread()
            DetectionResult(isDetected = true, position = Point(10, 10), numberDetected = 9.0)
        }
        every { detector.close() } answers { recordThread() }
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        every { actions.getHeldPointer() } returns null
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val execution = PackageScriptExecution(repository, dispatcher, actions, { detector }, { bitmap },
                { _, required -> models.add(required) }, "package-processing-tag")
            execution.diagnostics.setDetailed(true)
            val job = launch { execution.run(stored.storageId, buildJsonObject { put("minimum", 5) }) }
            job.join()
            assertEquals(ScriptSessionState.STOPPED, execution.status.value.state)
            assertEquals("Meta atingida", execution.status.value.reason)
            val readDiagnostic = execution.diagnostics.snapshot().single { it.operation == "read" }
            assertEquals("9.0", readDiagnostic.details!!.getValue("number").jsonPrimitive.content)
            assertEquals("Meta atingida", execution.diagnostics.latest.value!!.message)
            assertTrue(execution.diagnostics.snapshot().any { it.operation == "stop" && it.outcome == "stopped" })
            assertEquals(listOf(setOf("LATIN")), models)
            assertEquals(1, threads.size)
            assertFalse(bitmap.isRecycled)
            verify(exactly = 1) { detector.init() }
            verify(exactly = 1) { detector.setScreenBitmap(bitmap, "package-processing-tag") }
            verify(exactly = 1) { detector.close() }
            coVerify(exactly = 1) { actions.dispatchBuiltGesture(any()) }
        }
        bitmap.recycle()
    }
}
