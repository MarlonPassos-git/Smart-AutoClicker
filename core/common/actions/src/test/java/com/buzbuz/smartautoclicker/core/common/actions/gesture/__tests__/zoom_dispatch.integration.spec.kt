package com.buzbuz.smartautoclicker.core.common.actions.gesture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Point
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.common.actions.AndroidActionExecutorImpl
import com.buzbuz.smartautoclicker.core.common.actions.gesture.builder.AndroidGestureBuilder
import com.buzbuz.smartautoclicker.core.common.actions.model.BuiltGesture
import com.buzbuz.smartautoclicker.core.common.actions.notification.NotificationRequestExecutor
import com.buzbuz.smartautoclicker.core.common.actions.text.TextExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ZoomDispatchIntegrationTest {

    @Test
    fun `zoom continues the held pointer alongside both zoom fingers`() = runTest {
        val builder = AndroidGestureBuilder.createNewBuilder(null)
        val owner = Any()
        val finger = builder.newFinger(0, 0L).press(Point(10, 10), 100).release(hold = true).build()!!
        val held = builder.build(listOf(finger), "Hold", owner = owner)!!.heldPointer!!
        val gestures = mock<GestureExecutor>()
        val service = mock<AccessibilityService>()
        whenever(service.resources).thenReturn(ApplicationProvider.getApplicationContext<Context>().resources)
        whenever(gestures.getHeldPointer()).thenReturn(held)
        whenever(gestures.checkHeldPointer()).thenReturn(true)
        whenever(gestures.dispatchBuiltGesture(any(), any())).thenReturn(true)
        val executor = AndroidActionExecutorImpl(gestures, mock<NotificationRequestExecutor>(), mock<TextExecutor>())
        executor.init(service)

        executor.dispatchZoomGesture(Point(100, 100), 40, 300, ZoomDirection.IN, null)

        val built = argumentCaptor<BuiltGesture>()
        verify(gestures).dispatchBuiltGesture(eq(service), built.capture())
        assertEquals(3, built.firstValue.chains.first().first().strokeCount)
        assertNotNull(built.firstValue.heldPointer)
        assertSame(owner, built.firstValue.heldPointer!!.owner)
        assertEquals(Point(10, 10), built.firstValue.heldPointer!!.position)
    }
}
