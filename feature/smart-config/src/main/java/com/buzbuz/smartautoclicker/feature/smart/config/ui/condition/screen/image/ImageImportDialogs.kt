/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image

import android.content.Context

import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager.Companion.showAsOverlay
import com.buzbuz.smartautoclicker.core.ui.utils.getDynamicColorsContext
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image.importer.ImageImportFailure

import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal fun Context.showImageImportFailure(
    reason: ImageImportFailure,
    onRetry: () -> Unit,
) {
    MaterialAlertDialogBuilder(getDynamicColorsContext(R.style.AppTheme))
        .setTitle(R.string.dialog_overlay_title_warning)
        .setMessage(reason.messageResource())
        .setPositiveButton(R.string.generic_retry) { _, _ -> onRetry() }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showAsOverlay()
}

internal fun Context.showImagePersistenceFailure(onRetry: () -> Unit) {
    MaterialAlertDialogBuilder(getDynamicColorsContext(R.style.AppTheme))
        .setTitle(R.string.dialog_overlay_title_warning)
        .setMessage(R.string.image_reference_save_error)
        .setPositiveButton(R.string.generic_retry) { _, _ -> onRetry() }
        .setNegativeButton(android.R.string.cancel, null)
        .create()
        .showAsOverlay()
}

private fun ImageImportFailure.messageResource(): Int = when (this) {
    ImageImportFailure.EMPTY_CONTENT -> R.string.image_import_empty_error
    ImageImportFailure.INVALID_CONTENT -> R.string.image_import_invalid_error
    ImageImportFailure.IMAGE_TOO_LARGE -> R.string.image_import_too_large_error
    ImageImportFailure.PICKER_UNAVAILABLE -> R.string.image_import_picker_unavailable_error
}
