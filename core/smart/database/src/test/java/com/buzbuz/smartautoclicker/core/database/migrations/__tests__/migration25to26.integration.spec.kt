package com.buzbuz.smartautoclicker.core.database.migrations

import android.content.Context
import android.os.Build
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.buzbuz.smartautoclicker.core.database.ClickDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class Migration25to26Test {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ClickDatabase::class.java)
    private lateinit var databasePath: String

    @Before
    fun setUp() {
        databasePath = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath("migration-25-26-test").path
    }

    @Test
    fun existingActionsRemainValidWithEmptyAreaClickColumns() {
        helper.createDatabase(databasePath, 25).use(::insertExistingClick)

        helper.runMigrationsAndValidate(databasePath, 26, true).use { database ->
            database.query(AREA_CLICK_COLUMNS_QUERY).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CLICK", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
                assertNull(cursor.getString(4))
            }
        }
    }

    private fun insertExistingClick(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO scenario_table (id, name, detection_quality) VALUES (1, 'Scenario', 400)")
        database.execSQL(
            "INSERT INTO event_table (id, scenario_id, name, operator, priority, enabled_on_start, type) " +
                    "VALUES (1, 1, 'Event', 0, 0, 1, 'SCREEN_EVENT')"
        )
        database.execSQL(
            "INSERT INTO action_table (id, eventId, priority, name, type, clickPositionType, x, y, pressDuration) " +
                    "VALUES (1, 1, 0, 'Click', 'CLICK', 'USER_SELECTED', 10, 20, 30)"
        )
    }

    private companion object {
        const val AREA_CLICK_COLUMNS_QUERY = "SELECT type, areaClickCount, areaClickInterval, " +
                "areaClickDistribution, areaClickVertices FROM action_table WHERE id = 1"
    }
}
