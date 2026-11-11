package com.buzbuz.smartautoclicker.feature.script.config

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.script.android.ScriptRunController
import com.buzbuz.smartautoclicker.core.script.runtime.ScriptExecutionException
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionStatus
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@HiltAndroidTest
@Config(sdk = [35], application = HiltTestApplication::class)
class ScriptDiagnosticsIntegrationTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var overlays: OverlayManager

    @Test
    @Config(qualifiers = "w832dp-h384dp-land-450dpi")
    fun aFreshSessionMatchesItsTouchWindowToTheCompactCard() {
        hilt.inject()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val diagnostics = ScriptDiagnostics()
        val controller = object : ScriptRunController {
            override val status = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.RUNNING))
            override val diagnostics = diagnostics
            override fun pause() = false
            override fun resume() = false
        }
        val overlay = ScriptRunOverlay(controller) {}
        try {
            overlays.navigateTo(context, overlay)
            idle()
            val windows = Shadow.extract<ShadowWindowManagerImpl>(overlay.context.getSystemService(WindowManager::class.java)).views
            val root = windows.single { it.findViewById<View>(R.id.diagnosticScroll) != null }
            val compactWidth = root.resources.getDimensionPixelSize(R.dimen.script_overlay_compact_width)
            assertEquals("The touch window must not retain invisible space beside the compact card",
                compactWidth, root.layoutParams.width)
        } finally {
            overlays.closeAll(context)
            idle()
        }
    }

    @Test
    fun detailedHistoryIsOptionalPreservesTheStageAndStopsTracingWhenHidden() {
        hilt.inject()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val diagnostics = ScriptDiagnostics()
        val status = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.RUNNING))
        val controller = object : ScriptRunController {
            override val status = status
            override val diagnostics = diagnostics
            override fun pause() = false
            override fun resume() = false
        }
        val overlay = ScriptRunOverlay(controller) {}
        try {
            overlays.navigateTo(context, overlay)
            idle()
            val windows = Shadow.extract<ShadowWindowManagerImpl>(overlay.context.getSystemService(WindowManager::class.java)).views
            val root = windows.single { it.findViewById<View>(R.id.diagnosticScroll) != null }
            val history = root.findViewById<RecyclerView>(R.id.diagnosticScroll)
            val state = root.findViewById<TextView>(R.id.state)
            val toggle = root.findViewById<View>(R.id.btn_diagnostics)
            diagnostics.record(ScriptDiagnostic("stage", "checking", "Conferindo valor", 1))
            idle()
            assertFalse(diagnostics.isDetailedEnabled)
            assertFalse(history.isShown)
            assertEquals(0, history.adapter!!.itemCount)
            assertTrue(state.text.contains("Conferindo valor"))

            toggle.performClick()
            idle()
            assertTrue(diagnostics.isDetailedEnabled)
            assertTrue(historyText(history).contains("Conferindo valor"))
            assertTrue("History must be visible inside the overlay window", history.getGlobalVisibleRect(Rect()))
            diagnostics.record(ScriptDiagnostic("operation", "checking", "readAll", 2, "readAll", 12_000_000, "success"))
            idle()
            assertTrue(historyText(history).contains("readAll"))
            assertTrue(historyText(history).contains("concluída"))
            assertTrue(state.text.contains("Conferindo valor"))

            toggle.performClick()
            idle()
            diagnostics.record(ScriptDiagnostic("log", "checking", "Outro valor", 3))
            idle()
            assertFalse(diagnostics.isDetailedEnabled)
            assertFalse(history.isShown)
            assertEquals(0, history.adapter!!.itemCount)
            assertTrue(state.text.contains("Outro valor"))

            toggle.performClick()
            idle()
            status.value = ScriptSessionStatus(ScriptSessionState.FAILED, IllegalStateException("diagnostic test failure"))
            idle()
            assertTrue(historyText(history).contains("diagnostic test failure"))
            status.value = ScriptSessionStatus(ScriptSessionState.FAILED, ScriptExecutionException(
                "falha no script", "entry.js", 12, 5, "at confirm(entry.js:12:5)", IllegalArgumentException("cause original")))
            idle()
            assertTrue("The script location must be displayed explicitly", historyText(history).contains("entry.js:12:5"))
            assertTrue(historyText(history).contains("at confirm"))
            overlays.hideAll()
            idle()
            assertFalse(diagnostics.isDetailedEnabled)
            overlays.restoreVisibility()
            idle()
            assertTrue(diagnostics.isDetailedEnabled)

            status.value = ScriptSessionStatus(ScriptSessionState.RUNNING)
            repeat(200) { index ->
                diagnostics.record(ScriptDiagnostic("log", "historico", "Mensagem $index " + "conteúdo ".repeat(20), index.toLong() + 10))
            }
            idle()
            assertEquals(200, history.adapter!!.itemCount)
            val layout = history.layoutManager as LinearLayoutManager
            layout.scrollToPositionWithOffset(0, 0)
            idle()
            assertTrue(historyText(history).contains("Mensagem 0 "))
            assertTrue("Only the viewport should create text views", textViews(history).size < history.adapter!!.itemCount)
            layout.scrollToPosition(199)
            idle()
            assertTrue(historyText(history).contains("Mensagem 199 "))
            layout.scrollToPositionWithOffset(50, 0)
            idle()
            repeat(20) { index ->
                diagnostics.record(ScriptDiagnostic("log", "historico", "Nova mensagem $index", index.toLong() + 1000))
            }
            idle()
            assertTrue("New events must not interrupt reading older rows", historyText(history).contains("Mensagem 50 "))
        } finally {
            overlays.closeAll(context)
            idle()
        }
        assertFalse(diagnostics.isDetailedEnabled)
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun historyText(view: View) = textViews(view).joinToString("\n") { it.text.toString() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
}
