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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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
    fun independentSetupPreservesAnUnfinishedRoundAndItsAnswersAndFailedSetupPreservesConfiguration() = runBlocking {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            mapOf(
                "manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"configured","name":"Farm","scriptVersion":"3","entry":"entry.js",
                    "executionState":{"version":1},"setup":{"entry":"setup.js","label":"Configurar vilas"},
                    "parameters":{"limit":{"type":"number","label":"Limite","default":2}}}""",
                "entry.js" to "import {saveState} from 'klickr'; await saveState({attacks:1});",
                "setup.js" to "import {saveConfiguration} from 'klickr'; await saveConfiguration({formatVersion:1,capacity:470000});",
            ).forEach { (name, source) -> zip.putNextEntry(ZipEntry(name)); zip.write(source.toByteArray()); zip.closeEntry() }
        }
        val repository = ScriptPackageRepository(temporary.newFolder("setup-packages"))
        val stored = repository.importPackage(ByteArrayInputStream(archive.toByteArray()))
        val detector = mockk<ImageDetector>(relaxed = true)
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            suspend fun run(id: String = stored.storageId, setup: Boolean = false): PackageScriptExecution {
                val execution = PackageScriptExecution(repository, dispatcher, actions, { detector }, { bitmap }, { _, _ -> }, "setup-test")
                launch { execution.run(id, buildJsonObject {}, setup = setup) }.join()
                return execution
            }
            assertEquals(ScriptSessionState.COMPLETED, run().status.value.state)
            val checkpoint = repository.executionStates().read("configured")
            assertEquals(ScriptSessionState.COMPLETED, run(setup = true).status.value.state)
            assertEquals(checkpoint, repository.executionStates().read("configured"))
            val configuration = repository.executionStates().readConfiguration("configured")
            assertEquals(470000, configuration!!.getValue("capacity").jsonPrimitive.int)
            val failed = repository.saveRevision(stored.storageId, mapOf("setup.js" to "throw new Error('Unreadable capacity');".toByteArray()))
            assertEquals(ScriptSessionState.FAILED, run(failed.storageId, setup = true).status.value.state)
            assertEquals(configuration, repository.executionStates().readConfiguration("configured"))
            assertEquals(checkpoint, repository.executionStates().read("configured"))
        }
        bitmap.recycle()
    }

    @Test(timeout = 20000)
    fun checkpointSurvivesServiceRecreationAndRejectsChangedParametersOrRevisionUntilExplicitNewRound() = runBlocking {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            mapOf(
                "manifest.json" to """{"formatVersion":1,"apiVersion":1,"id":"persistent","name":"Rodada","scriptVersion":"1","entry":"entry.js",
                    "executionState":{"version":1},"parameters":{"limit":{"type":"number","label":"Limite","default":2}}}""",
                "entry.js" to """
                    import { loadState, saveState } from 'klickr';
                    const previous = await loadState();
                    await saveState({version:1, attacks:(previous?.attacks || 0)+1});
                """.trimIndent(),
            ).forEach { (name, source) -> zip.putNextEntry(ZipEntry(name)); zip.write(source.toByteArray()); zip.closeEntry() }
        }
        val repository = ScriptPackageRepository(temporary.newFolder("state-packages"))
        val original = repository.importPackage(ByteArrayInputStream(archive.toByteArray()))
        val detector = mockk<ImageDetector>(relaxed = true)
        val actions = mockk<AndroidActionExecutor>(relaxed = true)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            suspend fun run(id: String = original.storageId, limit: Int = 2, newRound: Boolean = false): PackageScriptExecution {
                val execution = PackageScriptExecution(repository, dispatcher, actions, { detector }, { bitmap }, { _, _ -> }, "state-test")
                launch { execution.run(id, buildJsonObject { put("limit", limit) }, newRound) }.join()
                return execution
            }
            assertEquals(ScriptSessionState.COMPLETED, run().status.value.state)
            assertEquals(ScriptSessionState.COMPLETED, run().status.value.state)
            val saved = repository.executionStates().read("persistent")!!
            assertEquals(2, (saved.getValue("state") as kotlinx.serialization.json.JsonObject).getValue("attacks").jsonPrimitive.int)
            assertEquals(ScriptSessionState.FAILED, run(limit = 3).status.value.state)
            assertEquals(saved, repository.executionStates().read("persistent"))
            val edited = repository.saveRevision(original.storageId, mapOf("extra.js" to "export const revision = 2;".toByteArray()))
            assertEquals(ScriptSessionState.FAILED, run(id = edited.storageId).status.value.state)
            assertEquals(saved, repository.executionStates().read("persistent"))
            assertEquals(ScriptSessionState.COMPLETED, run(id = edited.storageId, limit = 3, newRound = true).status.value.state)
            assertEquals(1, (repository.executionStates().read("persistent")!!.getValue("state") as kotlinx.serialization.json.JsonObject)
                .getValue("attacks").jsonPrimitive.int)
            val interrupted = repository.saveRevision(edited.storageId, mapOf("entry.js" to
                "import { saveState, stop } from 'klickr'; await saveState({version:1, attacks:3}); await stop('Interrupção controlada');".toByteArray()))
            assertEquals(ScriptSessionState.STOPPED, run(id = interrupted.storageId, limit = 3, newRound = true).status.value.state)
            val interruptedRecord = repository.executionStates().read("persistent")!!
            assertEquals("Interrupção controlada", interruptedRecord.getValue("interruptionReason").jsonPrimitive.content)
            assertEquals(3, (interruptedRecord.getValue("state") as kotlinx.serialization.json.JsonObject).getValue("attacks").jsonPrimitive.int)
            val noCheckpoint = repository.saveRevision(edited.storageId, mapOf("entry.js" to "const finished = true;".toByteArray()))
            assertEquals(ScriptSessionState.COMPLETED, run(id = noCheckpoint.storageId, limit = 3, newRound = true).status.value.state)
            assertEquals(true, repository.executionStates().read("persistent")!!.getValue("completed").jsonPrimitive.boolean)
        }
        bitmap.recycle()
    }

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
