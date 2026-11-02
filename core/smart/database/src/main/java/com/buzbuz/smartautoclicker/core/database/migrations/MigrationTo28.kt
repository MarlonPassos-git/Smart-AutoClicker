package com.buzbuz.smartautoclicker.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.buzbuz.smartautoclicker.core.base.migrations.addMissingColumns
import com.buzbuz.smartautoclicker.core.base.migrations.columnNames
import com.buzbuz.smartautoclicker.core.base.migrations.hasTable
import com.buzbuz.smartautoclicker.core.database.ACTION_TABLE
import com.buzbuz.smartautoclicker.core.database.CONDITION_TABLE
import com.buzbuz.smartautoclicker.core.database.GESTURE_FINGER_TABLE
import com.buzbuz.smartautoclicker.core.database.IMAGE_REFERENCE_TABLE

/**
 * Joins fork schemas 23–27 and upstream schemas 23–25 without changing their published migration paths.
 * Example: register `MigrationTo28(27)` for an installed fork database.
 */
class MigrationTo28(from: Int) : Migration(from, 28) {

    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasTable(GESTURE_FINGER_TABLE)) migrateLegacyGestures(db)

        db.addMissingColumns(ACTION_TABLE, mapOf(
            "pressDuration" to "INTEGER",
            "zoom_direction" to "TEXT",
            "zoom_intensity" to "INTEGER",
            "zoom_center_x" to "INTEGER",
            "zoom_center_y" to "INTEGER",
            "zoom_duration" to "INTEGER",
            "areaClickCount" to "INTEGER",
            "areaClickInterval" to "INTEGER",
            "areaClickDistribution" to "TEXT",
            "areaClickVertices" to "TEXT",
            "counter_operation_condition_id" to "INTEGER",
            "counter_operation_condition_position_type" to "TEXT",
        ))
        db.addMissingColumns(CONDITION_TABLE, mapOf("text_to_detect_alternatives" to "TEXT"))
        if (!db.hasTable(IMAGE_REFERENCE_TABLE)) Migration26to27.migrate(db)
    }

    private fun migrateLegacyGestures(db: SupportSQLiteDatabase) {
        // Upstream removes pressDuration after converting clicks. Polygon actions still own that field.
        val areaDurations = mutableMapOf<Long, Long>()
        if ("areaClickCount" in db.columnNames(ACTION_TABLE)) {
            db.query("SELECT id, pressDuration FROM `$ACTION_TABLE` WHERE type = 'AREA_CLICK'").use { cursor ->
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(1)) areaDurations[cursor.getLong(0)] = cursor.getLong(1)
                }
            }
        }
        Migration23to24.migrate(db)
        db.addMissingColumns(ACTION_TABLE, mapOf("pressDuration" to "INTEGER"))
        areaDurations.forEach { (id, duration) ->
            db.execSQL("UPDATE `$ACTION_TABLE` SET pressDuration = ? WHERE id = ?", arrayOf(duration, id))
        }
    }
}
