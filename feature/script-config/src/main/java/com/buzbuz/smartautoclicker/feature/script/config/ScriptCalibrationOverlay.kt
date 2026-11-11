package com.buzbuz.smartautoclicker.feature.script.config

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenRegion
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScreenSize
import com.buzbuz.smartautoclicker.core.ui.views.areaselector.AreaSelectorView
import com.buzbuz.smartautoclicker.feature.script.config.databinding.OverlayScriptCalibrationBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Uses the existing native area selector over the target application; never injects gestures. */
class ScriptCalibrationOverlay(
    private val name: String,
    private val saveSelection: suspend (ScreenSize, ScreenRegion) -> Unit,
    private val onCancelClicked: () -> Unit,
) : OverlayMenu(theme = R.style.ScriptCalibrationTheme) {
    private lateinit var binding: OverlayScriptCalibrationBinding
    private lateinit var selector: AreaSelectorView
    private var selecting = false
    private var saving = false

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        binding = OverlayScriptCalibrationBinding.inflate(layoutInflater)
        selector = AreaSelectorView(context, displayConfigManager)
        return binding.root
    }

    override fun onCreateOverlayView(): View = selector
    override fun animateOverlayView(): Boolean = false

    override fun onStart() {
        super.onStart()
        if (!selecting) setOverlayViewVisibility(false)
        setMenuItemViewEnabled(binding.btnHideOverlay, selecting)
        binding.instructions.text = context.getString(if (selecting) R.string.script_calibration_select else R.string.script_calibration_target, name)
    }

    override fun onMenuItemClicked(viewId: Int) {
        if (saving) return
        when (viewId) {
            R.id.btn_cancel -> onCancelClicked()
            R.id.btn_confirm -> if (!selecting) beginSelection() else save()
        }
    }

    private fun beginSelection() {
        selecting = true
        setMenuItemViewEnabled(binding.btnHideOverlay, true)
        val size = displayConfigManager.displayConfig.sizePx
        selector.setSelection(Rect(size.x / 4, size.y / 4, size.x * 3 / 4, size.y * 3 / 4), Rect(0, 0, 8, 8))
        binding.instructions.text = context.getString(R.string.script_calibration_select, name)
        binding.btnConfirm.setImageResource(com.buzbuz.smartautoclicker.core.ui.R.drawable.ic_confirm)
        binding.btnConfirm.contentDescription = context.getString(R.string.script_save)
        setOverlayViewVisibility(true)
    }

    private fun save() {
        val selected = selector.getSelection()
        val display = displayConfigManager.displayConfig.sizePx
        val size = ScreenSize(display.x, display.y)
        val area = ScreenRegion(selected.left, selected.top, selected.right, selected.bottom)
        saving = true
        lifecycleScope.launch {
            setMenuVisibility(View.GONE)
            setOverlayViewVisibility(false)
            try {
                delay(100) // Let the window compositor remove the selector/menu from the captured frame.
                saveSelection(size, area)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                binding.instructions.text = context.getString(R.string.script_calibration_failed, failure.message)
                setMenuVisibility(View.VISIBLE)
                setOverlayViewVisibility(true)
                saving = false
            }
        }
    }
}
