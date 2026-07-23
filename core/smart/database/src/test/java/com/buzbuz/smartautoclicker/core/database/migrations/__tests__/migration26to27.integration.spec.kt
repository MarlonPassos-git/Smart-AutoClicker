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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import com.buzbuz.smartautoclicker.core.database.ClickDatabase

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class Migration26to27Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ClickDatabase::class.java,
    )

    private lateinit var databasePath: String

    @Before
    fun setUp() {
        databasePath = ApplicationProvider.getApplicationContext<Context>()
            .getDatabasePath("migration-26-27-test").path
    }

    @Test
    fun `legacy image becomes ordered reference with duplicate paths and cascade support`() {
        helper.createDatabase(databasePath, 26).use(::insertLegacyConditions)

        helper.runMigrationsAndValidate(databasePath, 27, true, Migration26to27).use { database ->
            assertMigratedReference(database)
            insertDuplicatePath(database)
            assertOrderedDuplicatePaths(database)
            assertCascadeDeletion(database)
        }
    }

    private fun insertLegacyConditions(database: SupportSQLiteDatabase) {
        database.execSQL("INSERT INTO scenario_table (id, name, detection_quality) VALUES (1, 'Scenario', 400)")
        database.execSQL(
            "INSERT INTO event_table (id, scenario_id, name, operator, priority, enabled_on_start, type) " +
                    "VALUES (1, 1, 'Event', 0, 0, 1, 'SCREEN_EVENT')",
        )
        database.execSQL(
            "INSERT INTO condition_table " +
                    "(id, eventId, name, type, priority, path, area_left, area_top, area_right, area_bottom) " +
                    "VALUES (10, 1, 'Image', 'ON_IMAGE_DETECTED', 0, 'shared.png', 1, 2, 31, 42)",
        )
        database.execSQL(
            "INSERT INTO condition_table (id, eventId, name, type, priority) " +
                    "VALUES (11, 1, 'Timer', 'ON_TIMER_REACHED', 1)",
        )
    }

    private fun assertMigratedReference(database: SupportSQLiteDatabase) {
        database.query(SELECT_REFERENCES).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(10L, cursor.getLong(0))
            assertEquals(0, cursor.getInt(1))
            assertEquals("shared.png", cursor.getString(2))
            assertEquals(listOf(1, 2, 31, 42), (3..6).map(cursor::getInt))
        }
    }

    private fun insertDuplicatePath(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO image_reference_table " +
                    "(conditionId, priority, path, area_left, area_top, area_right, area_bottom) " +
                    "VALUES (10, 1, 'shared.png', 4, 5, 24, 25)",
        )
    }

    private fun assertOrderedDuplicatePaths(database: SupportSQLiteDatabase) {
        database.query(SELECT_REFERENCES).use { cursor ->
            val priorities = mutableListOf<Int>()
            val paths = mutableListOf<String>()
            while (cursor.moveToNext()) {
                priorities += cursor.getInt(1)
                paths += cursor.getString(2)
            }
            assertEquals(listOf(0, 1), priorities)
            assertEquals(listOf("shared.png", "shared.png"), paths)
        }
    }

    private fun assertCascadeDeletion(database: SupportSQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("DELETE FROM condition_table WHERE id = 10")

        database.query("SELECT COUNT(*) FROM image_reference_table WHERE conditionId = 10").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    private companion object {
        const val SELECT_REFERENCES = "SELECT conditionId, priority, path, area_left, area_top, area_right, " +
                "area_bottom FROM image_reference_table ORDER BY priority"
    }
}
