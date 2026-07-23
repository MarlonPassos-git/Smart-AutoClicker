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
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image.importer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.buzbuz.smartautoclicker.feature.smart.config.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ImageImportActivity : AppCompatActivity() {

    companion object {
        fun getStartIntent(context: Context, requestId: Long): Intent =
            Intent(context, ImageImportActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Inject lateinit var decoder: ImageImportDecoder
    @Inject lateinit var coordinator: ImageImportCoordinator

    private val requestId: Long by lazy { intent.getLongExtra(EXTRA_REQUEST_ID, INVALID_REQUEST_ID) }
    private val documentPicker = registerForActivityResult(OpenDocument()) { uri ->
        if (uri == null) finishWith(ImageImportResult.Cancelled)
        else lifecycleScope.launch { finishWith(decoder.decode(uri)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transparent)
        if (requestId == INVALID_REQUEST_ID) {
            finish()
            return
        }
        if (savedInstanceState == null) documentPicker.launch(arrayOf("image/*"))
    }

    private fun finishWith(result: ImageImportResult) {
        coordinator.complete(requestId, result)
        finish()
    }
}

private const val EXTRA_REQUEST_ID = "image_import_request_id"
private const val INVALID_REQUEST_ID = -1L
