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

import android.graphics.Bitmap
import android.os.Build

import com.buzbuz.smartautoclicker.core.common.tutorial.domain.MonitoredViewsManager
import com.buzbuz.smartautoclicker.core.common.tutorial.domain.model.monitoring.MonitoredViewType
import com.buzbuz.smartautoclicker.core.display.recorder.DisplayRecorder

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CaptureViewModelTest {

    // Set main to unconfined so withContext(Dispatchers.Main) in the viewmodel runs
    // directly on the IO thread instead of posting to the blocked test thread.
    private val testDispatcher = UnconfinedTestDispatcher()

    private val mockDisplayRecorder: DisplayRecorder = mockk()
    private val mockMonitoredViewsManager: MonitoredViewsManager = mockk(relaxed = true)

    private lateinit var viewModel: CaptureViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = CaptureViewModel(mockDisplayRecorder, mockMonitoredViewsManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun takeScreenshot_callsCallbackWithBitmapFromRecorder() {
        val bitmap = mockk<Bitmap>()
        coEvery { mockDisplayRecorder.takeScreenshot() } returns bitmap
        val latch = CountDownLatch(1)
        var capturedBitmap: Bitmap? = null

        viewModel.takeScreenshot {
            capturedBitmap = it
            latch.countDown()
        }

        assertTrue("Callback not called within timeout", latch.await(1, TimeUnit.SECONDS))
        assertEquals(bitmap, capturedBitmap)
    }

    @Test
    fun takeScreenshot_callsCallbackWithNull_whenRecorderReturnsNull() {
        coEvery { mockDisplayRecorder.takeScreenshot() } returns null
        val latch = CountDownLatch(1)
        var capturedBitmap: Bitmap? = mockk() // intentionally non-null sentinel

        viewModel.takeScreenshot {
            capturedBitmap = it
            latch.countDown()
        }

        assertTrue("Callback not called within timeout", latch.await(1, TimeUnit.SECONDS))
        assertNull(capturedBitmap)
    }

    @Test
    fun takeScreenshot_notifiesMonitoredViewsManager() {
        coEvery { mockDisplayRecorder.takeScreenshot() } returns mockk()
        val latch = CountDownLatch(1)

        viewModel.takeScreenshot { latch.countDown() }

        assertTrue("Callback not called within timeout", latch.await(1, TimeUnit.SECONDS))
        verify { mockMonitoredViewsManager.notifyClick(MonitoredViewType.SCREEN_CONDITION_CAPTURE_MENU_BUTTON_CAPTURE) }
    }

}
