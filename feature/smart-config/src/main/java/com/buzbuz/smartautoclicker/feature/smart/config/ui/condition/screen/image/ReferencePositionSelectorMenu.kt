/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.buzbuz.smartautoclicker.core.common.overlays.menu.OverlayMenu
import com.buzbuz.smartautoclicker.core.ui.views.areaselector.AreaSelectorView
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.databinding.OverlayValidationMenuBinding

class ReferencePositionSelectorMenu(
    private val initialArea: Rect,
    private val onAreaSelected: (Rect) -> Unit,
) : OverlayMenu() {

    private lateinit var viewBinding: OverlayValidationMenuBinding
    private lateinit var selectorView: AreaSelectorView

    override fun onCreateMenu(layoutInflater: LayoutInflater): ViewGroup {
        selectorView = AreaSelectorView(context, displayConfigManager).apply {
            setSelectionSizeLocked(true)
        }
        viewBinding = OverlayValidationMenuBinding.inflate(layoutInflater)
        return viewBinding.root
    }

    override fun onCreateOverlayView(): View = selectorView

    override fun onStart() {
        super.onStart()
        selectorView.setSelection(initialArea, initialArea)
    }

    override fun onMenuItemClicked(viewId: Int) {
        when (viewId) {
            R.id.btn_confirm -> {
                onAreaSelected(selectorView.getSelection())
                back()
            }
            R.id.btn_cancel -> back()
        }
    }
}
