package com.buzbuz.smartautoclicker.core.dumb.data.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.buzbuz.smartautoclicker.core.base.migrations.addMissingColumns
import com.buzbuz.smartautoclicker.core.base.migrations.hasTable
import com.buzbuz.smartautoclicker.core.dumb.data.database.DUMB_ACTION_TABLE
import com.buzbuz.smartautoclicker.core.dumb.data.database.DUMB_GESTURE_FINGER_TABLE

/**
 * Preserves fork zoom actions and upstream gestures despite their different schema 3 definitions.
 * Example: register `MigrationTo4(3)` for either published lineage.
 */
class MigrationTo4(from: Int) : Migration(from, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.hasTable(DUMB_GESTURE_FINGER_TABLE)) Migration2to3.migrate(db)
        db.addMissingColumns(DUMB_ACTION_TABLE, mapOf(
            "zoom_direction" to "TEXT",
            "zoom_intensity" to "INTEGER",
            "zoom_center_x" to "INTEGER",
            "zoom_center_y" to "INTEGER",
            "zoom_duration" to "INTEGER",
        ))
    }
}
