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
import android.graphics.BitmapFactory
import android.net.Uri
import com.buzbuz.smartautoclicker.core.base.di.Dispatcher
import com.buzbuz.smartautoclicker.core.base.di.HiltCoroutineDispatchers.IO
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ImageImportDecoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
    private val displayConfigManager: DisplayConfigManager,
) {

    suspend fun decode(uri: Uri): ImageImportResult = withContext(ioDispatcher) {
        try {
            if (isEmpty(uri)) return@withContext ImageImportResult.Failure(ImageImportFailure.EMPTY_CONTENT)
            val dimensions = decodeDimensions(uri)
                ?: return@withContext ImageImportResult.Failure(ImageImportFailure.INVALID_CONTENT)
            if (!dimensionsFitScreen(dimensions.first, dimensions.second)) {
                return@withContext ImageImportResult.Failure(ImageImportFailure.IMAGE_TOO_LARGE)
            }

            val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: return@withContext ImageImportResult.Failure(ImageImportFailure.INVALID_CONTENT)
            ImageImportResult.Success(bitmap)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ImageImportResult.Failure(ImageImportFailure.INVALID_CONTENT)
        }
    }

    private fun isEmpty(uri: Uri): Boolean =
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length == 0L
        } ?: false

    private fun decodeDimensions(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }

        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun dimensionsFitScreen(width: Int, height: Int): Boolean =
        displayConfigManager.displayConfig.sizePx.let { screen -> width <= screen.x && height <= screen.y }
}
