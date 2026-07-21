package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.areaclick

import android.graphics.Point
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.core.ui.utils.AutoHideAnimationController
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.OverlayAreaClickEditorMenuBinding
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.OverlayAreaClickEditorViewBinding

internal class AreaClickEditorMenu(
    private val confirmedVertices: List<Point>,
    private val onConfirm: (List<Point>) -> Unit,
) : OverlayMenu(recreateOverlayViewOnRotation = true) {

    private lateinit var menuBinding: OverlayAreaClickEditorMenuBinding
    private lateinit var editorBinding: OverlayAreaClickEditorViewBinding
    private lateinit var instructionsController: AutoHideAnimationController
    private var workingVertices = confirmedVertices.map(::Point)

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        menuBinding = OverlayAreaClickEditorMenuBinding.inflate(layoutInflater)
        return menuBinding.root
    }

    override fun onCreateOverlayView(): View {
        preserveWorkingVertices()
        editorBinding = OverlayAreaClickEditorViewBinding.inflate(LayoutInflater.from(context))
        configureInstructions()
        configureEditor()
        editorBinding.areaEditor.setVertices(workingVertices)
        return editorBinding.root
    }

    override fun onScreenOverlayVisibilityChanged(isVisible: Boolean) {
        if (isVisible) instructionsController.showOrResetTimer()
    }

    override fun onMenuItemClicked(viewId: Int) {
        when (viewId) {
            R.id.btn_confirm -> confirmArea()
            R.id.btn_cancel -> back()
            R.id.btn_add_vertex -> editorBinding.areaEditor.addVertex()
            R.id.btn_remove_vertex -> editorBinding.areaEditor.removeVertex()
            R.id.btn_preview -> editorBinding.areaEditor.regeneratePreview()
        }
    }

    private fun configureInstructions() {
        instructionsController = AutoHideAnimationController().apply {
            attachToView(
                editorBinding.layoutInstructions,
                AutoHideAnimationController.ScreenSide.TOP,
            )
        }
    }

    private fun configureEditor() {
        editorBinding.areaEditor.onValidityChanged = { isValid ->
            workingVertices = editorBinding.areaEditor.getVertices()
            updateMenuState(isValid)
        }
        updateMenuState(false)
    }

    private fun preserveWorkingVertices() {
        if (!::editorBinding.isInitialized) return
        workingVertices = editorBinding.areaEditor.getVertices()
    }

    private fun updateMenuState(isValid: Boolean) {
        val vertexCount = editorBinding.areaEditor.getVertices().size
        setMenuItemViewEnabled(menuBinding.btnConfirm, isValid, isValid)
        setMenuItemViewEnabled(menuBinding.btnPreview, isValid, isValid)
        setMenuItemViewEnabled(menuBinding.btnAddVertex, vertexCount in 3..7)
        setMenuItemViewEnabled(menuBinding.btnRemoveVertex, vertexCount in 4..8)
    }

    private fun confirmArea() {
        if (!editorBinding.areaEditor.isValid) return
        val selectedVertices = workingVertices.map(::Point)
        back()
        onConfirm(selectedVertices)
    }
}
