/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
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
class Migration24to25Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClickDatabase::class.java,
    )

    private lateinit var databasePath: String

    @Before
    fun setUp() {
        databasePath = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath("migration-24-25-test")
            .path
    }

    @Test
    fun `existing text condition becomes single value after migration`() {
        helper.createDatabase(databasePath, 24).use { database -> insertTextCondition(database) }

        helper.runMigrationsAndValidate(databasePath, 25, true).use { database ->
            database.query(TEXT_CONDITION_QUERY).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Marlon", cursor.getString(0))
                assertNull(cursor.getString(1))
            }
        }
    }

    private fun insertTextCondition(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO scenario_table (id, name, detection_quality) VALUES (1, 'Scenario', 400)")
        database.execSQL(
            "INSERT INTO event_table (id, scenario_id, name, operator, priority, enabled_on_start, type) " +
                    "VALUES (1, 1, 'Event', 0, 0, 1, 'SCREEN_EVENT')"
        )
        database.execSQL(
            "INSERT INTO condition_table (id, eventId, name, type, priority, text_to_detect) " +
                    "VALUES (1, 1, 'Names', 'ON_TEXT_DETECTED', 0, 'Marlon')"
        )
    }

    private companion object {
        const val TEXT_CONDITION_QUERY =
            "SELECT text_to_detect, text_to_detect_alternatives FROM condition_table WHERE id = 1"
    }
}
