package com.buzbuz.smartautoclicker.feature.script.config

import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.script.android.ScriptRunController
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionStatus
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptRunOverlayTest {
    @Test
    fun failedOrInterruptedExecutionOffersOneRetryWithoutResumingTheDestroyedSession() {
        for (terminal in listOf(ScriptSessionState.FAILED, ScriptSessionState.STOPPED)) {
            val status = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.RUNNING))
            val controller = mockk<ScriptRunController>(relaxed = true) {
                every { this@mockk.status } returns status
                every { diagnostics } returns ScriptDiagnostics()
            }
            var retries = 0
            val overlay = ScriptRunOverlay(controller, onRetryClicked = { retries++; true }) {}
            val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                com.buzbuz.smartautoclicker.core.ui.R.style.AppTheme)
            val root = ScriptRunOverlay::class.java.getDeclaredMethod("onCreateMenu", LayoutInflater::class.java)
                .apply { isAccessible = true }.invoke(overlay, LayoutInflater.from(context)) as ViewGroup
            val render = ScriptRunOverlay::class.java.getDeclaredMethod("renderState", ScriptSessionStatus::class.java,
                ScriptDiagnostic::class.java).apply { isAccessible = true }
            val retry = root.findViewById<android.widget.Button>(R.id.btn_retry)
            for (state in listOf(ScriptSessionState.RUNNING, ScriptSessionState.PAUSED, ScriptSessionState.COMPLETED)) {
                status.value = ScriptSessionStatus(state)
                render.invoke(overlay, status.value, null)
                assertEquals(View.GONE, retry.visibility)
            }
            status.value = ScriptSessionStatus(terminal, failure = IllegalStateException("Contador divergente"))
            render.invoke(overlay, status.value, null)
            assertEquals(View.VISIBLE, retry.visibility)
            assertEquals("Tentar novamente", retry.text.toString())
            retry.performClick()
            retry.performClick()
            assertEquals(1, retries)
            assertFalse(retry.isEnabled)
            verify(exactly = 0) { controller.resume() }
            status.value = ScriptSessionStatus(ScriptSessionState.PREPARING)
            render.invoke(overlay, status.value, null)
            status.value = ScriptSessionStatus(terminal)
            render.invoke(overlay, status.value, null)
            assertTrue(retry.isEnabled)
            retry.performClick()
            assertEquals(2, retries)
        }
    }

    @Test
    fun uncertainAttackChoicesAreAnsweredOnTheFloatingPanel() {
        val controller = mockk<ScriptRunController>(relaxed = true) {
            every { status } returns MutableStateFlow(ScriptSessionStatus(ScriptSessionState.STOPPED))
            every { diagnostics } returns ScriptDiagnostics()
            every { recoveryChoices() } returns listOf(
                com.buzbuz.smartautoclicker.core.script.android.ScriptRecoveryChoice("count", "Contar ataque concluído"),
                com.buzbuz.smartautoclicker.core.script.android.ScriptRecoveryChoice("discard", "Manter contagem"))
            every { resolveRecovery("count") } returns true
        }
        val overlay = ScriptRunOverlay(controller, onRetryClicked = { true }) {}
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
            com.buzbuz.smartautoclicker.core.ui.R.style.AppTheme)
        val root = ScriptRunOverlay::class.java.getDeclaredMethod("onCreateMenu", LayoutInflater::class.java)
            .apply { isAccessible = true }.invoke(overlay, LayoutInflater.from(context)) as ViewGroup
        ScriptRunOverlay::class.java.getDeclaredMethod("renderState", ScriptSessionStatus::class.java, ScriptDiagnostic::class.java)
            .apply { isAccessible = true }.invoke(overlay, controller.status.value, null)
        val choices = root.findViewById<ViewGroup>(R.id.recovery_choices)
        assertEquals(View.VISIBLE, choices.visibility)
        val count = choices.getChildAt(0) as android.widget.Button
        assertEquals("Contar ataque concluído", count.text)
        count.performClick()
        count.performClick()
        verify(exactly = 1) { controller.resolveRecovery("count") }
        assertFalse(choices.getChildAt(1).isEnabled)
        verify(exactly = 0) { controller.resume() }
    }

    @Test
    @Config(qualifiers = "land-450dpi")
    fun compactControlsLeaveRecognitionAndNearbyDeploymentPointsClear() {
        val status = MutableStateFlow(ScriptSessionStatus(ScriptSessionState.RUNNING))
        val event = ScriptDiagnostic("stage", "Lançamento", "Lançando tropas", 0)
        val diagnostics = ScriptDiagnostics().apply { record(event) }
        val controller = mockk<ScriptRunController>(relaxed = true) {
            every { this@mockk.status } returns status
            every { this@mockk.diagnostics } returns diagnostics
        }
        val overlay = ScriptRunOverlay(controller) {}
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
            com.buzbuz.smartautoclicker.core.ui.R.style.AppTheme)
        val root = ScriptRunOverlay::class.java.getDeclaredMethod("onCreateMenu", LayoutInflater::class.java)
            .apply { isAccessible = true }.invoke(overlay, LayoutInflater.from(context)) as ScriptControlLayout
        val render = ScriptRunOverlay::class.java.getDeclaredMethod("renderState", ScriptSessionStatus::class.java,
            ScriptDiagnostic::class.java).apply { isAccessible = true }
        render.invoke(overlay, status.value, event)
        val measure = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        root.measure(measure, measure)
        val bounds = Rect(0, 245, root.measuredWidth, 245 + root.measuredHeight)
        assertFalse("Running bounds $bounds obstruct deploy3", bounds.contains(600, 260))
        assertFalse("Running bounds $bounds obstruct deploy5", bounds.contains(400, 400))
        assertFalse("Running bounds $bounds obstruct deploy7", bounds.contains(250, 560))
        assertFalse("Running bounds $bounds obstruct recognition", Rect.intersects(bounds, Rect(160, 88, 430, 240)))
        val state = root.findViewById<android.widget.TextView>(R.id.state)
        assertEquals("Lançando tropas", state.text.toString())
        assertTrue(state.contentDescription.contains("Lançando tropas"))
        assertTrue(state.measuredHeight >= (48 * context.resources.displayMetrics.density).toInt())
        assertEquals(View.GONE, root.findViewById<View>(R.id.btn_move).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.btn_diagnostics).visibility)
        state.performClick()
        root.measure(measure, measure)
        assertTrue(root.measuredWidth > bounds.width())
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btn_move).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btn_diagnostics).visibility)
        state.performClick()
        root.measure(measure, measure)
        assertEquals(bounds.width(), root.measuredWidth)
        assertEquals(bounds.height(), root.measuredHeight)
        status.value = ScriptSessionStatus(ScriptSessionState.PAUSED)
        render.invoke(overlay, status.value,
            ScriptDiagnostic("overlay_intersection", "Controles", "Mova os controles e retome.", 0))
        root.measure(measure, measure)
        assertTrue(root.measuredHeight > bounds.height())
        assertTrue(state.text.contains("Mova os controles"))
        assertFalse(state.isClickable)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btn_move).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btn_diagnostics).visibility)
    }

    @Test
    fun anInjectedPanelTouchRequestsPauseAndExplainsHowToResume() {
        val diagnostics = ScriptDiagnostics()
        val controller = mockk<ScriptRunController> {
            every { pause() } returns true
            every { this@mockk.diagnostics } returns diagnostics
        }
        var stopped = false
        val overlay = ScriptRunOverlay(controller) { stopped = true }
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
            com.buzbuz.smartautoclicker.core.ui.R.style.AppTheme)
        val root = ScriptRunOverlay::class.java.getDeclaredMethod("onCreateMenu", LayoutInflater::class.java)
            .apply { isAccessible = true }.invoke(overlay, LayoutInflater.from(context)) as ScriptControlLayout
        root.onScriptTouch!!.invoke()
        verify(exactly = 1) { controller.pause() }
        assertFalse(stopped)
        assertEquals("overlay_intersection", diagnostics.latest.value?.kind)
        assertTrue(diagnostics.latest.value!!.message.contains("Mova os controles"))
    }

    @Test
    fun stopCommandInvokesTheSessionCallbackRatherThanTheOverlayLifecycle() {
        var requested = false
        val overlay = ScriptRunOverlay(mockk(relaxed = true)) { requested = true }
        // Exercise the framework's protected menu-command hook without opening a system overlay window.
        ScriptRunOverlay::class.java.getDeclaredMethod("onMenuItemClicked", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(overlay, R.id.btn_stop)
        assertTrue(requested)
    }
}
