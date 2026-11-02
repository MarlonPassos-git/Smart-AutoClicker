package com.buzbuz.smartautoclicker.core.database.migrations

import android.content.Context
import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class MigrationTo28Test {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ClickDatabase::class.java)

    @Test
    fun `fork schemas retain zoom clicks and ordered duplicate image references`() {
        for (version in 24..27) {
            val path = databasePath("fork-$version")
            helper.createDatabase(path, version).use { db ->
                insertScenario(db)
                db.execSQL("INSERT INTO action_table (id, eventId, priority, name, type, zoom_direction, zoom_intensity, zoom_center_x, zoom_center_y, zoom_duration) VALUES (1, 1, 0, 'Zoom', 'ZOOM', 'OUT', 150, 50, 60, 250)")
                db.execSQL("INSERT INTO action_table (id, eventId, priority, name, type, clickPositionType, x, y, pressDuration) VALUES (2, 1, 1, 'Click', 'CLICK', 'USER_SELECTED', 70, 80, 120)")
                db.execSQL("INSERT INTO condition_table (id, eventId, name, type, path, area_left, area_top, area_right, area_bottom) VALUES (1, 1, 'Image', 'ON_IMAGE_DETECTED', 'shared.png', 1, 2, 30, 40)")
                if (version == 27) {
                    db.execSQL("INSERT INTO image_reference_table VALUES (1, 0, 'shared.png', 1, 2, 30, 40)")
                    db.execSQL("INSERT INTO image_reference_table VALUES (1, 1, 'shared.png', 50, 60, 80, 90)")
                }
                if (version >= 25) {
                    db.execSQL("UPDATE condition_table SET text_to_detect_alternatives = 'v1:3:a:b' WHERE id = 1")
                }
                if (version >= 26) {
                    db.execSQL("INSERT INTO action_table (id, eventId, priority, name, type, areaClickCount, areaClickInterval, areaClickDistribution, areaClickVertices, pressDuration) VALUES (3, 1, 2, 'Area', 'AREA_CLICK', 50, 30, 'RANDOM', 'v1:0,0;100,0;0,100', 75)")
                }
            }

            helper.runMigrationsAndValidate(path, 28, true, MigrationTo28(version)).use { db ->
                db.query("SELECT zoom_direction, zoom_intensity, zoom_center_x, zoom_center_y, zoom_duration FROM action_table WHERE id = 1").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("OUT", cursor.getString(0))
                    assertEquals(listOf(150, 50, 60, 250), (1..4).map(cursor::getInt))
                }
                db.query("SELECT x_literal, y_literal FROM gesture_finger_motion_table WHERE motion_type = 'PRESS'").use { cursor ->
                    assertEquals(1, cursor.count)
                    cursor.moveToFirst()
                    assertEquals(70, cursor.getInt(0))
                    assertEquals(80, cursor.getInt(1))
                }
                db.query("SELECT path, area_left FROM image_reference_table ORDER BY priority").use { cursor ->
                    assertEquals(if (version == 27) 2 else 1, cursor.count)
                    cursor.moveToFirst()
                    assertEquals("shared.png", cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                    if (version == 27) {
                        cursor.moveToNext()
                        assertEquals("shared.png", cursor.getString(0))
                        assertEquals(50, cursor.getInt(1))
                    }
                }
                if (version >= 25) {
                    db.query("SELECT text_to_detect_alternatives FROM condition_table WHERE id = 1").use { cursor ->
                        cursor.moveToFirst()
                        assertEquals("v1:3:a:b", cursor.getString(0))
                    }
                }
                if (version >= 26) {
                    db.query("SELECT areaClickCount, areaClickInterval, pressDuration FROM action_table WHERE id = 3").use { cursor ->
                        cursor.moveToFirst()
                        assertEquals(listOf(50, 30, 75), (0..2).map(cursor::getInt))
                    }
                }
            }
        }
    }

    @Test
    fun `upstream gesture schemas migrate without duplicating fingers`() {
        for (version in 24..25) {
            val path = databasePath("upstream-$version")
            helper.createDatabase(path, 23).use { db ->
                insertScenario(db)
                db.execSQL("INSERT INTO action_table (id, eventId, priority, name, type, clickPositionType, x, y, pressDuration) VALUES (1, 1, 0, 'Click', 'CLICK', 'USER_SELECTED', 70, 80, 120)")
                Migration23to24.migrate(db)
                if (version == 25) Migration24to25.migrate(db)
                db.version = version
            }
            helper.runMigrationsAndValidate(path, 28, true, MigrationTo28(version)).use { db ->
                db.query("SELECT COUNT(*) FROM gesture_finger_table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(1, cursor.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM gesture_finger_motion_table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(3, cursor.getInt(0))
                }
            }
        }
    }

    private fun insertScenario(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO scenario_table (id, name, detection_quality) VALUES (1, 'Scenario', 400)")
        db.execSQL("INSERT INTO event_table (id, scenario_id, name, operator, priority, type) VALUES (1, 1, 'Event', 0, 0, 'SCREEN_EVENT')")
    }

    private fun databasePath(name: String): String =
        ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration28-$name").path
}
