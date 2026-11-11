package com.buzbuz.smartautoclicker.feature.script.config

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
    @get:Rule val temporary = TemporaryFolder()
    @Before fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test(timeout = 15000)
    fun syntaxValidationReportsTheSourceFileAndLineWhileKeepingTheDraft() = runBlocking {
        val application = mockk<Application>(relaxed = true)
        every { application.filesDir } returns temporary.root
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
        val application = mockk<Application>(relaxed = true)
        every { application.filesDir } returns temporary.root
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
        val application = mockk<Application>(relaxed = true)
        val preferences = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("recent-answers-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        every { application.filesDir } returns temporary.root
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
        val application = mockk<Application>(relaxed = true)
        every { application.filesDir } returns temporary.root
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
        val application = mockk<Application>(relaxed = true)
        every { application.filesDir } returns temporary.root
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
        assertTrue(model.state.value.packages.any { it.storageId == previousId && it.manifest.name == "Original" })
        assertTrue(!model.state.value.dirty)
    }

    private suspend fun idle(model: ScriptLibraryViewModel) {
        withTimeout(10000) { model.state.first { !it.busy } }
        check(model.state.value.message == null) { model.state.value.message ?: "Unexpected library error" }
    }
}
