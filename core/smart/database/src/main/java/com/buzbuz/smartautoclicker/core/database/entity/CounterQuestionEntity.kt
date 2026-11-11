package com.buzbuz.smartautoclicker.core.database.entity

import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Portable question definition; confirmed answers are deliberately stored outside the scenario. */
@Serializable
data class CounterQuestionEntity(
    val title: String,
    val description: String = "",
    val order: Int = 0,
    val type: CounterQuestionType = CounterQuestionType.NUMBER,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val options: List<CounterQuestionOptionEntity> = emptyList(),
    val visibleWhen: CounterQuestionVisibilityEntity? = null,
)

@Serializable
enum class CounterQuestionType { BOOLEAN, CHOICE, NUMBER }

@Serializable
data class CounterQuestionOptionEntity(val label: String, val value: Double)

@Serializable
data class CounterQuestionVisibilityEntity(val counterName: String, val value: Double)

/** Room encoding for the portable definition, e.g. `encode(question)` before storing a row. */
class CounterQuestionConverters {
    @TypeConverter
    fun encode(question: CounterQuestionEntity?): String? = question?.let { Json.encodeToString(it) }

    @TypeConverter
    fun decode(encoded: String?): CounterQuestionEntity? = encoded?.let { Json.decodeFromString(it) }
}
