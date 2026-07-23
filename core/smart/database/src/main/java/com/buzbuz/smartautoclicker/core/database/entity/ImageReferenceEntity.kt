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
package com.buzbuz.smartautoclicker.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Relation
import com.buzbuz.smartautoclicker.core.database.IMAGE_REFERENCE_TABLE
import kotlinx.serialization.Serializable

/** Persisted image reference ordered within one image condition. */
@Entity(
    tableName = IMAGE_REFERENCE_TABLE,
    primaryKeys = ["conditionId", "priority"],
    indices = [Index("path")],
    foreignKeys = [ForeignKey(
        entity = ConditionEntity::class,
        parentColumns = ["id"],
        childColumns = ["conditionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
@Serializable
data class ImageReferenceEntity(
    @ColumnInfo(name = "conditionId") val conditionId: Long,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "area_left") val areaLeft: Int,
    @ColumnInfo(name = "area_top") val areaTop: Int,
    @ColumnInfo(name = "area_right") val areaRight: Int,
    @ColumnInfo(name = "area_bottom") val areaBottom: Int,
)

/** A condition and its normalized image references. */
@Serializable
data class CompleteConditionEntity(
    @Embedded val condition: ConditionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "conditionId",
    )
    val imageReferences: List<ImageReferenceEntity> = emptyList(),
)
