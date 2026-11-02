package com.buzbuz.smartautoclicker.feature.backup.data.dumb

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.dumb.data.database.DumbActionType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ForkDumbBackupCompatibilityTest {

    @Test
    fun `fork schema 3 imports zoom and legacy swipe together`() {
        val restored = deserializeActions("""
            {"id": 1, "dumbScenarioId": 1, "name": "Zoom", "priority": 0, "type": "ZOOM",
             "zoomDirection": "OUT", "zoomIntensity": 160, "zoomCenterX": 50, "zoomCenterY": 60, "zoomDuration": 300},
            {"id": 2, "dumbScenarioId": 1, "name": "Swipe", "priority": 1, "type": "SWIPE",
             "fromX": 10, "fromY": 20, "toX": 30, "toY": 40, "swipeDuration": 500,
             "repeatCount": 1, "isRepeatInfinite": false, "repeatDelay": 0}
        """)

        assertEquals(listOf(DumbActionType.ZOOM, DumbActionType.GESTURE), restored.map { it.action.type })
        val zoom = restored.first().action
        assertEquals("OUT", zoom.zoomDirection)
        assertEquals(160, zoom.zoomIntensity)
        assertEquals(50, zoom.zoomCenterX)
        assertEquals(60, zoom.zoomCenterY)
        assertEquals(300L, zoom.zoomDuration)
        assertEquals(3, restored.last().gestureFingers.single().motions.size)
    }

    @Test
    fun `upstream schema 3 imports wrapped gesture fingers`() {
        val restored = deserializeActions("""
            {"action": {"id": 1, "dumbScenarioId": 1, "name": "Gesture", "priority": 0, "type": "GESTURE",
                        "repeatCount": 1, "isRepeatInfinite": false, "repeatDelay": 0},
             "gestureFingers": [{"finger": {"id": 2, "dumbActionId": 1, "index": 0, "startTimeMs": 0},
                                 "motions": [{"id": 3, "fingerId": 2, "index": 0, "motionType": "PRESS", "x": 42, "y": 84},
                                             {"id": 4, "fingerId": 2, "index": 1, "motionType": "RELEASE"}]}]}
        """)

        val finger = restored.single().gestureFingers.single()
        assertEquals(DumbActionType.GESTURE, restored.single().action.type)
        assertEquals(2, finger.motions.size)
        assertEquals(42, finger.motions.first().x)
        assertEquals(84, finger.motions.first().y)
    }

    private fun deserializeActions(actions: String) =
        DumbScenarioSerializer().deserialize("""
            {"version": 3, "screenWidth": 1080, "screenHeight": 1920,
             "dumbScenario": {"scenario": {"id": 1, "name": "Scenario", "repeatCount": 1,
                 "isRepeatInfinite": false, "maxDurationMin": 1, "isDurationInfinite": false, "randomize": false},
                 "dumbActions": [$actions]}}
        """.trimIndent().byteInputStream())!!.dumbScenario.dumbActions
}
