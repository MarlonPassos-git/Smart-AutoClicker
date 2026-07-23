/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.feature.smart.config.ui.condition.screen.image.importer

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Build

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.buzbuz.smartautoclicker.core.display.config.DisplayConfig
import com.buzbuz.smartautoclicker.core.display.config.DisplayConfigManager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class ImageImportDecoderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid image keeps native dimensions`() = runTest {
        val imageFile = writeBitmap("valid.png", width = 40, height = 60)

        val result = decoder().decode(Uri.fromFile(imageFile))

        assertTrue(result is ImageImportResult.Success)
        assertEquals(40, (result as ImageImportResult.Success).bitmap.width)
        assertEquals(60, result.bitmap.height)
    }

    @Test
    fun `image larger than screen is rejected`() = runTest {
        val imageFile = writeBitmap("large.png", width = 301, height = 60)

        val result = decoder().decode(Uri.fromFile(imageFile))

        assertEquals(ImageImportResult.Failure(ImageImportFailure.IMAGE_TOO_LARGE), result)
    }

    @Test
    fun `empty content is rejected separately`() = runTest {
        val imageFile = temporaryFolder.newFile("empty.png")

        val result = decoder().decode(Uri.fromFile(imageFile))

        assertEquals(ImageImportResult.Failure(ImageImportFailure.EMPTY_CONTENT), result)
    }

    @Test
    fun `invalid image content is rejected`() = runTest {
        val imageFile = temporaryFolder.newFile("invalid.png").apply { writeText("not an image") }

        val result = decoder().decode(Uri.fromFile(imageFile))

        assertEquals(ImageImportResult.Failure(ImageImportFailure.INVALID_CONTENT), result)
    }

    @Test
    fun `picker cancellation completes pending request`() = runTest {
        val coordinator = ImageImportCoordinator()
        val requestId = coordinator.createRequest()

        coordinator.complete(requestId, ImageImportResult.Cancelled)

        assertSame(ImageImportResult.Cancelled, coordinator.awaitResult(requestId))
    }

    @Test
    fun `unavailable picker returns recoverable failure`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val unavailablePickerContext = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent?) = throw ActivityNotFoundException()
        }

        val result = ImageImportCoordinator().requestImage(unavailablePickerContext)

        assertEquals(ImageImportResult.Failure(ImageImportFailure.PICKER_UNAVAILABLE), result)
    }

    private fun decoder(): ImageImportDecoder {
        val displayConfigManager = mock(DisplayConfigManager::class.java)
        `when`(displayConfigManager.displayConfig).thenReturn(
            DisplayConfig(
                sizePx = Point(300, 500),
                orientation = Configuration.ORIENTATION_PORTRAIT,
                safeInsetTopPx = 0,
                roundedCorners = emptyMap(),
            ),
        )
        return ImageImportDecoder(
            context = ApplicationProvider.getApplicationContext<Context>(),
            ioDispatcher = UnconfinedTestDispatcher(),
            displayConfigManager = displayConfigManager,
        )
    }

    private fun writeBitmap(name: String, width: Int, height: Int): File =
        temporaryFolder.newFile(name).apply {
            FileOutputStream(this).use { output ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
}
