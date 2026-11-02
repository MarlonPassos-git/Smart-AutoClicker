package com.buzbuz.smartautoclicker.core.dumb.data.database.migrations

import android.content.Context
import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.dumb.data.database.DumbDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MigrationTo4Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DumbDatabase::class.java)

    @Test
    fun `fork schema 3 preserves zoom and migrates swipes`() {
        val path = databasePath("fork")
        helper.createDatabase(path, 3).use { db ->
            db.execSQL("INSERT INTO dumb_scenario_table VALUES (1, 'Scenario', 1, 0, 1, 0, 0)")
            db.execSQL("INSERT INTO dumb_action_table (id, dumb_scenario_id, priority, name, type, zoom_direction, zoom_intensity, zoom_center_x, zoom_center_y, zoom_duration) VALUES (1, 1, 0, 'Zoom', 'ZOOM', 'OUT', 150, 50, 60, 250)")
            db.execSQL("INSERT INTO dumb_action_table (id, dumb_scenario_id, priority, name, type, fromX, fromY, toX, toY, swipe_duration) VALUES (2, 1, 1, 'Swipe', 'SWIPE', 10, 20, 30, 40, 200)")
        }
        helper.runMigrationsAndValidate(path, 4, true, MigrationTo4(3)).use { db ->
            db.query("SELECT zoom_direction, zoom_intensity, zoom_center_x, zoom_center_y, zoom_duration FROM dumb_action_table WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("OUT", cursor.getString(0))
                assertEquals(listOf(150, 50, 60, 250), (1..4).map(cursor::getInt))
            }
            db.query("SELECT type FROM dumb_action_table WHERE id = 2").use { cursor ->
                cursor.moveToFirst()
                assertEquals("GESTURE", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM dumb_gesture_finger_motion_table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(3, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `upstream schema 3 retains existing gestures`() {
        val path = databasePath("upstream")
        helper.createDatabase(path, 2).use { db ->
            db.execSQL("INSERT INTO dumb_scenario_table VALUES (1, 'Scenario', 1, 0, 1, 0, 0)")
            db.execSQL("INSERT INTO dumb_action_table (id, dumb_scenario_id, priority, name, type, fromX, fromY, toX, toY, swipe_duration) VALUES (1, 1, 0, 'Swipe', 'SWIPE', 10, 20, 30, 40, 200)")
            Migration2to3.migrate(db)
            db.version = 3
        }
        helper.runMigrationsAndValidate(path, 4, true, MigrationTo4(3)).use { db ->
            db.query("SELECT COUNT(*) FROM dumb_gesture_finger_table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM dumb_gesture_finger_motion_table").use { cursor ->
                cursor.moveToFirst()
                assertEquals(3, cursor.getInt(0))
            }
        }
    }

    private fun databasePath(name: String): String =
        ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration4-$name").path
}
