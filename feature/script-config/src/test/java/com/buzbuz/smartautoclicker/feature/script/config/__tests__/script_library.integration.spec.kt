package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import androidx.lifecycle.SavedStateHandle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptLibraryIntegrationTest {
    private val farmId = "farm-walls-sm-s916b"
    @get:Rule val temporary = TemporaryFolder()
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test(timeout = 15000)
    fun firstLibraryOpenInstallsTheBundledFarmOnce() = runBlocking {
        val application = testApplication()
        val model = ScriptLibraryViewModel(application, SavedStateHandle())
        idle(model)

        val farm = model.state.value.packages.single()
        assertEquals(farmId, farm.manifest.id)
        assertEquals("Farm — vilas e muros", farm.manifest.name)
        assertTrue(farm.manifest.setup != null)
        assertTrue("maxBattles" in farm.manifest.parameters)
        assertEquals(null, model.state.value.selected)
        assertEquals(farm, ScriptLibraryStorage.listPackages(application).single())
        assertEquals(farm, ScriptLibraryStorage.repository(application).listPackages().single())
    }

    @Test(timeout = 15000)
    fun previouslyImportedFarmKeepsItsRevisionAndSavedRound() = runBlocking {
        val application = testApplication()
        val repository = ScriptLibraryStorage.repository(application)
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """
                {"formatVersion":1,"apiVersion":1,"id":"$farmId","name":"Meu farm","scriptVersion":"3.5.0","entry":"entry.js"}
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("entry.js"))
            zip.write("// Personalizado".toByteArray())
            zip.closeEntry()
        }
        val previous = repository.importPackage(ByteArrayInputStream(archive.toByteArray()))
        val progress = buildJsonObject {
            put("formatVersion", 1)
            put("revision", previous.storageId)
            put("completed", false)
            putJsonObject("answers") { put("maxBattles", 2) }
            putJsonObject("state") { put("battles", 1) }
        }
        val configuration = buildJsonObject { putJsonArray("villages") { add("Minha vila") } }
        val states = repository.executionStates()
        states.acquire(farmId).use {
            it.writeConfiguration(configuration)
            it.write(progress)
        }

        val model = ScriptLibraryViewModel(application, SavedStateHandle())
        idle(model)

        assertEquals(previous, model.state.value.packages.single())
        assertEquals(previous, repository.listPackages().single())
        assertEquals(progress, states.read(farmId))
        assertEquals(configuration, states.readConfiguration(farmId))
    }

    @Test(timeout = 15000)
    fun failedStartupCanBeExportedWithoutStartingTheService() = runBlocking {
        val application = testApplication()
        val resolver = mockk<android.content.ContentResolver>()
        every { application.contentResolver } returns resolver
        val uri = android.net.Uri.parse("content://documents/history.zip")
        val exported = java.io.ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns exported
        val model = ScriptLibraryViewModel(application, SavedStateHandle())
        idle(model)
        model.recordStartFailure("reviewed-revision", "Aplicativo ausente", IllegalStateException("Aplicativo ausente")).join()
        model.exportHistory(uri)
        withTimeout(10000) { model.state.first { !it.busy } }
        assertEquals("Histórico exportado", model.state.value.message)
        val entries = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(exported.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val summary = Json.parseToJsonElement(entries.filterKeys { it.endsWith("summary.json") }.values.single()).jsonObject
        assertEquals(JsonPrimitive("FAILED"), summary["status"])
        assertEquals(JsonPrimitive("reviewed-revision"), summary["revision"])
        assertEquals(JsonPrimitive("start"), summary["phase"])
    }

    @Test(timeout = 15000)
    fun syntaxValidationReportsTheSourceFileAndLineWhileKeepingTheDraft() = runBlocking {
        val application = testApplication()
        val model = ScriptLibraryViewModel(application, SavedStateHandle())
        idle(model)
        model.create("Erro localizado")
        idle(model)
        val path = model.state.value.path!!
        val invalid = "const broken = ;"
        model.edit(invalid)
        model.validate()
        withTimeout(10000) { model.state.first { !it.busy } }
        assertTrue(model.state.value.message, model.state.value.message.orEmpty().contains("$path:1"))
        assertEquals(invalid, model.state.value.draft)
        assertTrue(model.state.value.dirty)
    }

    @Test(timeout = 15000)
    fun projectionConsentKeepsTheReviewedRevisionAfterLibrarySelectionChanges() = runBlocking {
        val application = testApplication()
        val saved = SavedStateHandle()
        val model = ScriptLibraryViewModel(application, saved)
        idle(model)
        model.create("Revisão conferida")
        idle(model)
        val reviewed = model.state.value.selected!!.storageId
        model.prepareRun(kotlinx.serialization.json.JsonObject(emptyMap()))
        model.create("Outra seleção")
        idle(model)
        val restored = ScriptLibraryViewModel(application, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        idle(restored)
        assertEquals(reviewed to "{}", restored.pendingRun())
        restored.clearPendingRun()
        assertTrue(restored.pendingRun() == null)
    }

    @Test(timeout = 15000)
    fun recentAnswersSurviveReopeningWithoutChangingDefaultsAndIgnoreIncompatibleDefinitions() = runBlocking {
        val application = testApplication()
        val preferences = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("recent-answers-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        every { application.getSharedPreferences(any(), any()) } returns preferences
        val model = ScriptLibraryViewModel(application, SavedStateHandle())
        idle(model)
        model.create("Respostas recentes")
        idle(model)
        model.selectFile("manifest.json")
        idle(model)
        val definition = Json.parseToJsonElement("""{"limit":{"type":"number","label":"Limite","default":2,"min":0,"max":10}}""")
        model.edit(JsonObject(Json.parseToJsonElement(model.state.value.draft).jsonObject + ("parameters" to definition)).toString())
        model.save()
        idle(model)
        val answers = buildJsonObject { put("limit", 7) }
        model.prepareRun(answers)
        val saved = SavedStateHandle(mapOf("selected" to model.state.value.selected!!.storageId))
        val restored = ScriptLibraryViewModel(application, saved)
        idle(restored)
        assertEquals(answers, restored.recentAnswers())
        assertEquals(2, restored.state.value.selected!!.manifest.parameters.getValue("limit").default.jsonPrimitive.int)
        restored.selectFile("manifest.json")
        idle(restored)
        restored.edit(restored.state.value.draft.replace("\"max\":10", "\"max\":5"))
        restored.save()
        idle(restored)
        assertEquals(JsonObject(emptyMap()), restored.recentAnswers())
        assertEquals(7, Json.parseToJsonElement(model.pendingRun()!!.second).jsonObject.getValue("limit").jsonPrimitive.int)
    }

    @Test(timeout = 15000)
    fun restoredEditorKeepsTheDraftAndOriginalPackageSeparate() = runBlocking {
        val application = testApplication()
        val saved = SavedStateHandle()
        val model = ScriptLibraryViewModel(application, saved)
        idle(model)
        model.create("Exemplo")
        idle(model)
        val original = model.state.value.original
        model.edit("const unsaved = true;")
        val restored = ScriptLibraryViewModel(application, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        idle(restored)
        assertEquals("const unsaved = true;", restored.state.value.draft)
        assertEquals(original, restored.state.value.original)
        assertTrue(restored.state.value.dirty)
    }

    @Test(timeout = 15000)
    fun savingManifestEditsPublishesANewRevision() = runBlocking {
        val application = testApplication()
        val model = ScriptLibraryViewModel(application, SavedStateHandle())
        idle(model)
        model.create("Original")
        idle(model)
        val previousId = model.state.value.selected!!.storageId
        model.selectFile("manifest.json")
        idle(model)
        model.edit(model.state.value.draft.replace("Original", "Editado"))
        model.save()
        idle(model)
        assertEquals("Editado", model.state.value.selected!!.manifest.name)
        assertTrue(previousId != model.state.value.selected!!.storageId)
        assertEquals(
            model.state.value.selected!!.storageId,
            model.state.value.packages.single { it.manifest.id != farmId }.storageId,
        )
        val original = ScriptLibraryViewModel(application, SavedStateHandle(mapOf("selected" to previousId)))
        idle(original)
        assertEquals("Original", original.state.value.selected!!.manifest.name)
        assertEquals("Editado", original.state.value.packages.single { it.manifest.id != farmId }.manifest.name)
        assertTrue(!model.state.value.dirty)
    }

    private fun testApplication(): Application {
        val assets = ApplicationProvider.getApplicationContext<Application>().assets
        return mockk<Application>(relaxed = true) {
            every { filesDir } returns temporary.root
            every { this@mockk.assets } returns assets
        }
    }

    private suspend fun idle(model: ScriptLibraryViewModel) {
        withTimeout(10000) { model.state.first { !it.busy } }
        check(model.state.value.message == null) { model.state.value.message ?: "Unexpected library error" }
    }
}
