package com.buzbuz.smartautoclicker.core.base.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

/** Checks a schema's actual structure when published forks reuse version numbers. Example: `db.hasTable("actions")`. */
fun SupportSQLiteDatabase.hasTable(table: String): Boolean =
    query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

/** Reads persisted column names without assuming a version's lineage. Example: `db.columnNames("actions")`. */
fun SupportSQLiteDatabase.columnNames(table: String): Set<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }

/** Adds nullable fields missing from either supported lineage. Example: `db.addMissingColumns("actions", mapOf("zoom" to "INTEGER"))`. */
fun SupportSQLiteDatabase.addMissingColumns(table: String, columns: Map<String, String>) {
    val existing = columnNames(table)
    columns.filterKeys { it !in existing }.forEach { (name, type) ->
        execSQL("ALTER TABLE `$table` ADD COLUMN `$name` $type")
    }
}
