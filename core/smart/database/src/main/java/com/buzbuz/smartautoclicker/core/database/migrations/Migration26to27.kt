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
package com.buzbuz.smartautoclicker.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.buzbuz.smartautoclicker.core.database.CONDITION_TABLE
import com.buzbuz.smartautoclicker.core.database.IMAGE_REFERENCE_TABLE

/** Normalizes each legacy image condition into an ordered child reference. */
object Migration26to27 : Migration(26, 27) {

    override fun migrate(db: SupportSQLiteDatabase) {
        createImageReferenceTable(db)
        createPathIndex(db)
        migrateLegacyReferences(db)
    }

    private fun createImageReferenceTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `$IMAGE_REFERENCE_TABLE` (
                `conditionId` INTEGER NOT NULL,
                `priority` INTEGER NOT NULL,
                `path` TEXT NOT NULL,
                `area_left` INTEGER NOT NULL,
                `area_top` INTEGER NOT NULL,
                `area_right` INTEGER NOT NULL,
                `area_bottom` INTEGER NOT NULL,
                PRIMARY KEY(`conditionId`, `priority`),
                FOREIGN KEY(`conditionId`) REFERENCES `$CONDITION_TABLE`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createPathIndex(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_${IMAGE_REFERENCE_TABLE}_path` ON `$IMAGE_REFERENCE_TABLE` (`path`)")
    }

    private fun migrateLegacyReferences(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO `$IMAGE_REFERENCE_TABLE` (
                `conditionId`, `priority`, `path`, `area_left`, `area_top`, `area_right`, `area_bottom`
            )
            SELECT `id`, 0, `path`, `area_left`, `area_top`, `area_right`, `area_bottom`
            FROM `$CONDITION_TABLE`
            WHERE `type` = 'ON_IMAGE_DETECTED'
                AND `path` IS NOT NULL
                AND `area_left` IS NOT NULL
                AND `area_top` IS NOT NULL
                AND `area_right` IS NOT NULL
                AND `area_bottom` IS NOT NULL
            """.trimIndent()
        )
    }
}
