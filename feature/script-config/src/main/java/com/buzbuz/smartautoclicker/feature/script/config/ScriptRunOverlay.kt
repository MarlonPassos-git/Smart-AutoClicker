package com.buzbuz.smartautoclicker.feature.script.config

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.core.script.android.ScriptRunController
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionState
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionStatus
import com.buzbuz.smartautoclicker.feature.script.config.databinding.OverlayScriptRunBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Reuses the existing movable overlay menu rather than creating a second window stack. */
class ScriptRunOverlay(private val controller: ScriptRunController, private val onStopClicked: () -> Unit) : OverlayMenu(
    theme = com.buzbuz.smartautoclicker.core.ui.R.style.AppTheme,
) {
    private lateinit var binding: OverlayScriptRunBinding
    private lateinit var historyAdapter: ScriptDiagnosticAdapter
    private val detailsVisible = MutableStateFlow(false)
    private var expandedControls = false

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        binding = OverlayScriptRunBinding.inflate(layoutInflater)
        binding.state.addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                // Text changes do not run the buttons' LayoutTransition. Resize after the parent finishes laying out.
                view.post { forceWindowResize(forceContentMeasure = !binding.diagnosticScroll.isVisible) }
            }
        }
        binding.state.setOnClickListener {
            if (binding.state.isClickable) {
                expandedControls = !expandedControls
                renderState(controller.status.value, controller.diagnostics.latest.value)
            }
        }
        binding.root.onScriptTouch = {
            if (controller.pause()) controller.diagnostics.record(ScriptDiagnostic(
                kind = "overlay_intersection",
                stage = "Controles de execução",
                message = binding.root.context.getString(R.string.script_overlay_intersection),
                monotonicNanos = System.nanoTime(),
            ))
        }
        historyAdapter = ScriptDiagnosticAdapter()
        binding.diagnosticScroll.apply {
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
            adapter = historyAdapter
            itemAnimator = null
            setHasFixedSize(true)
        }
        detailsVisible.value = binding.diagnosticScroll.isVisible
        return binding.root
    }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.diagnostics.setDetailed(binding.diagnosticScroll.isVisible)
                try {
                    coroutineScope {
                        launch {
                            combine(controller.status, controller.diagnostics.latest) { status, event -> status to event }
                                .collect { (status, event) -> renderState(status, event) }
                        }
                        launch {
                            detailsVisible.collectLatest { visible ->
                                controller.diagnostics.setDetailed(visible)
                                if (visible) {
                                    // Traces can arrive faster than a readable history can refresh; keep controls immediate.
                                    combine(controller.status, controller.diagnostics.revision) { _, _ -> Unit }
                                        .sample(200).collect { renderDiagnostics() }
                                }
                            }
                        }
                    }
                } finally {
                    controller.diagnostics.setDetailed(false)
                }
            }
        }
    }

    private fun renderState(status: ScriptSessionStatus, event: ScriptDiagnostic?) {
        val viewContext = binding.root.context
        val label = viewContext.getString(when (status.state) {
            ScriptSessionState.PREPARING -> R.string.script_preparing
            ScriptSessionState.RUNNING -> R.string.script_running
            ScriptSessionState.PAUSING -> R.string.script_pausing
            ScriptSessionState.PAUSED -> R.string.script_paused
            ScriptSessionState.REVALIDATING -> R.string.script_revalidating
            ScriptSessionState.STOPPING -> R.string.script_stopping
            ScriptSessionState.STOPPED -> R.string.script_stopped
            ScriptSessionState.COMPLETED -> R.string.script_completed
            ScriptSessionState.FAILED -> R.string.script_failed
        })
        val message = status.reason ?: status.failure?.message ?: event?.message?.ifBlank { event.stage }
        val active = when (status.state) {
            ScriptSessionState.PREPARING, ScriptSessionState.RUNNING, ScriptSessionState.REVALIDATING -> true
            else -> false
        }
        if (!active) expandedControls = false
        val canToggleControls = active && !detailsVisible.value
        val compact = canToggleControls && !expandedControls
        val width = viewContext.resources.getDimensionPixelSize(
            if (compact) R.dimen.script_overlay_compact_width else R.dimen.script_overlay_expanded_width)
        if (binding.state.layoutParams.width != width) {
            binding.state.layoutParams = binding.state.layoutParams.apply { this.width = width }
        }
        binding.btnMove.isVisible = !compact
        binding.btnDiagnostics.isVisible = !compact
        binding.state.isClickable = canToggleControls
        // Leave room for target recognition and deployment while keeping recovery instructions readable when paused.
        binding.state.minLines = if (compact) 1 else 3
        binding.state.maxLines = if (compact) 1 else 3
        val padding = ((if (compact) 4 else 12) * viewContext.resources.displayMetrics.density).toInt()
        binding.state.setPadding(padding, padding, padding, padding)
        binding.state.text = if (compact) message ?: label
            else listOfNotNull(label, message).joinToString("\n")
        val toggleHint = if (canToggleControls) viewContext.getString(
            if (compact) R.string.script_controls_expand else R.string.script_controls_collapse) else null
        binding.state.contentDescription = listOfNotNull(label, message, toggleHint).joinToString("\n")
        setMenuItemViewEnabled(binding.btnPlay, status.state == ScriptSessionState.RUNNING || status.state == ScriptSessionState.PAUSED)
        val paused = status.state == ScriptSessionState.PAUSED
        binding.btnPlay.setImageResource(if (paused) com.buzbuz.smartautoclicker.core.ui.R.drawable.ic_play_arrow else com.buzbuz.smartautoclicker.core.ui.R.drawable.ic_pause)
        binding.btnPlay.contentDescription = viewContext.getString(if (paused) R.string.script_resume else R.string.script_pause)
    }

    private fun renderDiagnostics() {
        if (!binding.diagnosticScroll.isVisible) return
        val status = controller.status.value
        val followLatest = !binding.diagnosticScroll.canScrollVertically(1)
        historyAdapter.update(status, controller.diagnostics.snapshot())
        val layout = binding.diagnosticScroll.layoutManager as LinearLayoutManager
        if (status.failure != null || status.reason != null) layout.scrollToPositionWithOffset(0, 0)
        else if (followLatest && historyAdapter.itemCount > 0) layout.scrollToPosition(historyAdapter.itemCount - 1)
    }

    override fun onMenuItemClicked(viewId: Int) {
        when (viewId) {
            R.id.btn_play -> if (controller.status.value.state == ScriptSessionState.PAUSED) controller.resume() else controller.pause()
            R.id.btn_stop -> onStopClicked()
            R.id.btn_diagnostics -> {
                val visible = !binding.diagnosticScroll.isVisible
                setMenuItemVisibility(binding.diagnosticScroll, visible)
                detailsVisible.value = visible
                renderState(controller.status.value, controller.diagnostics.latest.value)
                controller.diagnostics.setDetailed(visible)
                binding.btnDiagnostics.contentDescription = context.getString(if (visible) R.string.script_diagnostics_close else R.string.script_diagnostics)
                if (visible) renderDiagnostics() else historyAdapter.clear()
            }
        }
    }
}
