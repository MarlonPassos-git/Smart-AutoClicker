/*
 * Copyright (C) 2024 Kevin Buzeau
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
package com.buzbuz.smartautoclicker.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.buzbuz.smartautoclicker.core.database.CONDITION_TABLE
import com.buzbuz.smartautoclicker.core.database.IMAGE_REFERENCE_TABLE

import com.buzbuz.smartautoclicker.core.database.entity.CompleteConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionType
import com.buzbuz.smartautoclicker.core.database.entity.ImageReferenceEntity

import kotlinx.coroutines.flow.Flow

/** Allows to access the conditions in the database. */
@Dao
abstract class ConditionDao {

    /**
     * Get all conditions from all events.
     *
     * @return the list containing all conditions.
     */
    @Query("SELECT * FROM $CONDITION_TABLE")
    abstract fun getAllConditions(): Flow<List<ConditionEntity>>

    /** @return all conditions with their normalized image references. */
    @Transaction
    @Query("SELECT * FROM $CONDITION_TABLE")
    abstract fun getAllCompleteConditions(): Flow<List<CompleteConditionEntity>>

    /**
     * Get the list of conditions for a given event.
     *
     * @param eventId the identifier of the event to get the conditions from.
     * @return the list of conditions for the event.
     */
    @Query("SELECT * FROM $CONDITION_TABLE WHERE eventId=:eventId ORDER BY priority")
    abstract suspend fun getConditions(eventId: Long): List<ConditionEntity>

    /** @return event conditions with their normalized image references. */
    @Transaction
    @Query("SELECT * FROM $CONDITION_TABLE WHERE eventId=:eventId ORDER BY priority")
    abstract suspend fun getCompleteConditions(eventId: Long): List<CompleteConditionEntity>

    /**
     * Get the list of image conditions that uses the legacy image format
     * @return the list of legacy conditions.
     */
    @Query("SELECT * FROM $CONDITION_TABLE WHERE type='ON_IMAGE_DETECTED' AND path IS NOT NULL AND path NOT LIKE '%.png'")
    abstract fun getLegacyImageConditionsFlow(): Flow<List<ConditionEntity>>

    /**
     * Get the list of image conditions that uses the legacy image format
     * @return the list of legacy conditions.
     */
    @Query("SELECT * FROM $CONDITION_TABLE WHERE type='ON_IMAGE_DETECTED' AND path IS NOT NULL AND path NOT LIKE '%.png'")
    abstract suspend fun getLegacyImageConditions(): List<ConditionEntity>

    /**
     * Get the list of conditions path for a given event.
     *
     * @param eventId the identifier of the event to get the conditions path from.
     * @return the list of path for the event.
     */
    @Query("SELECT reference.path FROM $IMAGE_REFERENCE_TABLE AS reference INNER JOIN $CONDITION_TABLE AS condition ON reference.conditionId=condition.id WHERE condition.eventId=:eventId ORDER BY condition.priority, reference.priority")
    abstract suspend fun getConditionsPaths(eventId: Long): List<String>

    /**
     * Get the number of times this path is used in the condition table.
     *
     * @param path the value to be searched in the path column.
     * @return the number of conditions using this path.
     */
    @Query("SELECT COUNT(*) FROM $IMAGE_REFERENCE_TABLE WHERE path=:path")
    abstract suspend fun getValidPathCount(path: String): Int

    /** Replace all normalized references for one condition. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addImageReferences(references: List<ImageReferenceEntity>)

    /** Delete normalized references before rewriting their priorities. */
    @Query("DELETE FROM $IMAGE_REFERENCE_TABLE WHERE conditionId=:conditionId")
    abstract suspend fun deleteImageReferences(conditionId: Long)

    /** Keep a migrated legacy reference synchronized with its mirrored condition path. */
    @Query("UPDATE $IMAGE_REFERENCE_TABLE SET path=:newPath WHERE conditionId=:conditionId AND priority=0")
    abstract suspend fun updateFirstImageReferencePath(conditionId: Long, newPath: String)

    /**
     * Get the name of a condition by its identifier.
     *
     * @param conditionId the identifier of the condition.
     * @return the name of the condition, or null if not found.
     */
    @Query("SELECT name FROM $CONDITION_TABLE WHERE id=:conditionId")
    abstract suspend fun getConditionName(conditionId: Long): String?

    /** @return the flow on the count of screen conditions. */
    @Query("SELECT COUNT(*) FROM $CONDITION_TABLE WHERE type IN ('ON_IMAGE_DETECTED', 'ON_COLOR_DETECTED', 'ON_NUMBER_DETECTED', 'ON_TEXT_DETECTED')")
    abstract fun getScreenConditionsCount(): Flow<Int>

    /** @return the flow on the count of trigger conditions. */
    @Query("SELECT COUNT(*) FROM $CONDITION_TABLE WHERE type IN ('ON_BROADCAST_RECEIVED', 'ON_COUNTER_REACHED', 'ON_TIMER_REACHED')")
    abstract fun getTriggerConditionsCount(): Flow<Int>

    /**
     * Add conditions to the database.
     * @param conditions the conditions to be added.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addConditions(conditions: List<ConditionEntity>): List<Long>

    /**
     * Update a condition in the database.
     * @param condition the condition to be updated.
     */
    @Update
    abstract suspend fun updateCondition(condition: ConditionEntity)

    /**
     * Update a condition in the database.
     * @param conditions the condition to be updated.
     */
    @Update
    abstract suspend fun updateConditions(conditions: List<ConditionEntity>)

    /**
     * Delete a list of conditions in the database.
     * @param conditions the conditions to be removed.
     */
    @Delete
    abstract suspend fun deleteConditions(conditions: List<ConditionEntity>)
}
