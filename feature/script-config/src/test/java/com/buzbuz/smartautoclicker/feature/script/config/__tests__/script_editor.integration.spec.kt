package com.buzbuz.smartautoclicker.feature.script.config

import android.widget.EditText
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import com.buzbuz.smartautoclicker.core.script.packages.ScriptManifest
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.serialization.json.*
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.Rule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(sdk = [35], application = HiltTestApplication::class)
class ScriptEditorIntegrationTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Before fun inject() { hilt.inject() }

    @Test(timeout = 20000)
    fun selectingAnImageShowsABoundedPreviewAndOriginalDimensionsAcrossRecreation() = runBlocking {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java).setup()
        try {
            var model = ViewModelProvider(controller.get())[ScriptLibraryViewModel::class.java]
            idle(model)
            model.create("Prévia do recurso")
            idle(model)
            val bitmap = android.graphics.Bitmap.createBitmap(2049, 64, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            val repository = com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository(
                java.io.File(controller.get().filesDir, "scripts/packages"))
            val revision = repository.saveRevision(model.state.value.selected!!.storageId, mapOf("images/ready.png" to bytes))
            model.select(revision)
            idle(model)
            model.selectFile("images/ready.png")
            idle(model)
            shadowOf(Looper.getMainLooper()).idle()
            fun checkPreview() {
                val view = controller.get().findViewById<android.widget.ImageView>(R.id.imagePreview)
                assertEquals(View.VISIBLE, view.visibility)
                val preview = (view.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertTrue(preview.width <= 1024 && preview.height <= 1024)
                assertEquals(android.graphics.Color.GREEN, preview.getPixel(0, 0))
                assertTrue(view.contentDescription.toString().contains("2049 × 64"))
                assertEquals(View.GONE, controller.get().findViewById<View>(R.id.editorContainer).visibility)
            }
            checkPreview()
            controller.recreate()
            model = ViewModelProvider(controller.get())[ScriptLibraryViewModel::class.java]
            idle(model)
            shadowOf(Looper.getMainLooper()).idle()
            checkPreview()
            assertTrue(bytes.contentEquals(repository.readFile(revision.storageId, "images/ready.png")))
            model.selectFile("entry.js")
            idle(model)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, controller.get().findViewById<View>(R.id.imagePreview).visibility)
            assertEquals(View.VISIBLE, controller.get().findViewById<View>(R.id.editorContainer).visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun aMaximumSizeDraftLeavesRoomForFrameworkSavedStateAndSurvivesRecreation() = runBlocking {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java).setup()
        try {
            val model = ViewModelProvider(controller.get())[ScriptLibraryViewModel::class.java]
            idle(model)
            model.create("Rascunho grande")
            idle(model)
            val source = "//" + "x".repeat(256 * 1024 - 2)
            model.edit(source)
            shadowOf(Looper.getMainLooper()).idle()
            controller.get().findViewById<EditText>(R.id.editor).setSelection(source.length - 30, source.length - 10)
            val state = android.os.Bundle()
            controller.saveInstanceState(state)
            val parcel = android.os.Parcel.obtain()
            try {
                state.writeToParcel(parcel, 0)
                // Binder's shared transaction buffer must also carry framework metadata, not a second editor copy.
                assertTrue("Saved activity state is ${parcel.dataSize()} bytes", parcel.dataSize() < 750 * 1024)
            } finally { parcel.recycle() }
            controller.recreate()
            val restored = ViewModelProvider(controller.get())[ScriptLibraryViewModel::class.java]
            idle(restored)
            assertEquals(source, controller.get().findViewById<EditText>(R.id.editor).text.toString())
            assertEquals(source, restored.state.value.draft)
            assertEquals(source.length - 30, controller.get().findViewById<EditText>(R.id.editor).selectionStart)
            assertEquals(source.length - 10, controller.get().findViewById<EditText>(R.id.editor).selectionEnd)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun calibrationUsesTheSharedSelectorStyleWithoutDependingOnScenarioConfiguration() {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java).setup()
        try {
            val display = io.mockk.mockk<com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager>(relaxed = true)
            io.mockk.every { display.displayConfig } returns com.buzbuz.smartautoclicker.core.display.config.DisplayConfig(
                android.graphics.Point(1000, 2000), 1, 0, emptyMap())
            val context = android.view.ContextThemeWrapper(controller.get(), R.style.ScriptCalibrationTheme)
            val selector = com.buzbuz.smartautoclicker.core.ui.views.areaselector.AreaSelectorView(context, display)
            val selected = android.graphics.Rect(100, 200, 300, 400)
            selector.setSelection(selected, android.graphics.Rect(0, 0, 8, 8))
            assertEquals(selected, selector.getSelection())
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun calibrationNameAndTypeSurviveRotationAndRejectNonportableNames() = runBlocking {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java).setup()
        try {
            idle(ViewModelProvider(controller.get())[ScriptLibraryViewModel::class.java])
            ScriptCalibrationDialog.show(controller.get(), "reviewed")
            shadowOf(Looper.getMainLooper()).idle()
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            var views = descendants(dialog.window!!.decorView)
            views.filterIsInstance<TextInputEditText>().single().setText("invalid-name")
            views.filterIsInstance<Spinner>().single().setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idle()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            views = descendants(dialog.window!!.decorView)
            assertEquals("invalid-name", views.filterIsInstance<TextInputEditText>().single().text.toString())
            assertEquals(1, views.filterIsInstance<Spinner>().single().selectedItemPosition)
            var result: android.os.Bundle? = null
            controller.get().supportFragmentManager.setFragmentResultListener(ScriptCalibrationDialog.RESULT, controller.get()) { _, answer -> result = answer }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertTrue(result == null)
            views.filterIsInstance<TextInputEditText>().single().setText("balance")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("balance", result!!.getString("name"))
            assertEquals(false, result!!.getBoolean("image"))
            assertEquals("reviewed", result!!.getString("revision"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun sharedPermissionDialogCanAttachToTheScriptActivity() {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java)
        val activity = controller.setup().get()
        try {
            activity.permissionController.startPermissionsUiFlow(activity,
                listOf(com.buzbuz.smartautoclicker.core.common.permissions.model.PermissionOverlay()), onAllGranted = {})
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun startFormValidatesNumbersAndReturnsBooleanAndChoiceValues() = runBlocking {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java)
        val activity = controller.setup().get()
        try {
            idle(ViewModelProvider(activity)[ScriptLibraryViewModel::class.java])
            val manifest = ScriptManifest.parse("""{"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1","entry":"entry.js",
                "parameters":{"limit":{"type":"number","label":"Limite","default":2,"min":0,"max":10},
                "enabled":{"type":"boolean","label":"Ativar","default":true},
                "currency":{"type":"choice","label":"Moeda","default":"gold","choices":["gold","elixir"]}}}""")
            var supplied: JsonObject? = null
            activity.supportFragmentManager.setFragmentResultListener(ScriptStartParametersDialog.RESULT, activity) { _, result ->
                supplied = Json.parseToJsonElement(result.getString("answers")!!).jsonObject
            }
            ScriptStartParametersDialog.show(activity, manifest, "reviewed")
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val views = descendants(dialog.window!!.decorView)
            val number = views.filterIsInstance<TextInputEditText>().single()
            number.setText("-1")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertTrue(supplied == null)
            number.setText("3")
            views.filterIsInstance<MaterialSwitch>().single().isChecked = false
            views.filterIsInstance<Spinner>().single().setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(buildJsonObject { put("limit", 3.0); put("enabled", false); put("currency", "elixir") }, supplied)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun rotatingTheParameterFormKeepsRawNumberBooleanChoiceAndReviewedRevision() = runBlocking {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java)
        var activity = controller.setup().get()
        try {
            idle(ViewModelProvider(activity)[ScriptLibraryViewModel::class.java])
            val manifest = ScriptManifest.parse("""{"formatVersion":1,"apiVersion":1,"id":"rotation","name":"Exemplo","scriptVersion":"1","entry":"entry.js",
                "parameters":{"limit":{"type":"number","label":"Limite","default":2,"min":0,"max":10},
                "enabled":{"type":"boolean","label":"Ativar","default":true},
                "currency":{"type":"choice","label":"Moeda","default":"gold","choices":["gold","elixir"]}}}""")
            ScriptStartParametersDialog.show(activity, manifest, "reviewed-revision")
            shadowOf(Looper.getMainLooper()).idle()
            var dialog = ShadowDialog.getLatestDialog() as AlertDialog
            var views = descendants(dialog.window!!.decorView)
            views.filterIsInstance<TextInputEditText>().single().setText("-3,")
            views.filterIsInstance<MaterialSwitch>().single().isChecked = false
            views.filterIsInstance<Spinner>().single().setSelection(1)
            shadowOf(Looper.getMainLooper()).idle()
            controller.recreate()
            activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            dialog = ShadowDialog.getLatestDialog() as AlertDialog
            views = descendants(dialog.window!!.decorView)
            assertEquals("-3,", views.filterIsInstance<TextInputEditText>().single().text.toString())
            assertEquals(false, views.filterIsInstance<MaterialSwitch>().single().isChecked)
            assertEquals("elixir", views.filterIsInstance<Spinner>().single().selectedItem)
            var result: android.os.Bundle? = null
            activity.supportFragmentManager.setFragmentResultListener(ScriptStartParametersDialog.RESULT, activity) { _, answer -> result = answer }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            views.filterIsInstance<TextInputEditText>().single().setText("4,5")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals("reviewed-revision", result!!.getString("revision"))
            assertEquals(4.5, Json.parseToJsonElement(result!!.getString("answers")!!).jsonObject.getValue("limit").jsonPrimitive.double, 0.0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test(timeout = 20000)
    fun cancellingDiscardKeepsTheDraftAndConfirmingItPreservesTheSavedPackage() = runBlocking {
        val controller = Robolectric.buildActivity(ScriptLibraryActivity::class.java)
        val activity = controller.setup().get()
        try {
            val model = ViewModelProvider(activity)[ScriptLibraryViewModel::class.java]
            idle(model)
            model.create("Teste de edição")
            idle(model)
            val original = model.state.value.original
            val selected = model.state.value.selected!!
            shadowOf(Looper.getMainLooper()).idle()
            activity.findViewById<EditText>(R.id.editor).setText("const draft = true;")
            assertEquals("const draft = true;", model.state.value.draft)
            activity.onBackPressedDispatcher.onBackPressed()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("const draft = true;", model.state.value.draft)
            activity.onBackPressedDispatcher.onBackPressed()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(model.state.value.selected == null)
            model.select(selected)
            idle(model)
            assertEquals(original, model.state.value.draft)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private suspend fun idle(model: ScriptLibraryViewModel) {
        withTimeout(10000) {
            while (model.state.value.busy) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
