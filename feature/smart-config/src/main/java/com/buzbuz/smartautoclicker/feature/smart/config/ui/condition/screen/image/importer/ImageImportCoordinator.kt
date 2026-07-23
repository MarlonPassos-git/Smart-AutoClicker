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

import android.content.ActivityNotFoundException
import android.content.Context

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageImportCoordinator @Inject constructor() {

    private val nextRequestId = AtomicLong(0)
    private val pendingResults = ConcurrentHashMap<Long, CompletableDeferred<ImageImportResult>>()

    internal fun createRequest(): Long {
        val requestId = nextRequestId.incrementAndGet()
        pendingResults[requestId] = CompletableDeferred()
        return requestId
    }

    internal suspend fun awaitResult(requestId: Long): ImageImportResult {
        val pendingResult = pendingResults[requestId]
            ?: return ImageImportResult.Failure(ImageImportFailure.INVALID_CONTENT)

        return try {
            pendingResult.await()
        } finally {
            pendingResults.remove(requestId)
        }
    }

    internal fun complete(requestId: Long, result: ImageImportResult) {
        pendingResults[requestId]?.complete(result)
    }

    internal suspend fun requestImage(context: Context): ImageImportResult {
        val requestId = createRequest()
        return try {
            context.startActivity(ImageImportActivity.getStartIntent(context, requestId))
            awaitResult(requestId)
        } catch (_: ActivityNotFoundException) {
            pendingResults.remove(requestId)
            ImageImportResult.Failure(ImageImportFailure.PICKER_UNAVAILABLE)
        } catch (_: SecurityException) {
            pendingResults.remove(requestId)
            ImageImportResult.Failure(ImageImportFailure.PICKER_UNAVAILABLE)
        }
    }
}
