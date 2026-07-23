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
package com.buzbuz.smartautoclicker.core.domain.model.condition

import android.graphics.Rect
import com.buzbuz.smartautoclicker.code.smart.detectionmodels.text.domain.OCRAlphabet
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.entity.ConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.ConditionType
import com.buzbuz.smartautoclicker.core.database.entity.CompleteConditionEntity
import com.buzbuz.smartautoclicker.core.database.entity.CounterOperationValueType
import com.buzbuz.smartautoclicker.core.database.entity.ImageReferenceEntity
import com.buzbuz.smartautoclicker.core.database.entity.NumberFormatType as DbNumberFormatType
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.domain.model.counter.toDomain
import com.buzbuz.smartautoclicker.core.domain.model.counter.toEntity

internal fun Condition.toEntity() = when (this) {
    is ScreenCondition.Color -> toColorConditionEntity()
    is ScreenCondition.Image -> toImageConditionEntity()
    is ScreenCondition.Number -> toNumberConditionEntity()
    is ScreenCondition.Text -> toTextConditionEntity()
    is TriggerCondition.OnBroadcastReceived -> toBroadcastReceivedEntity()
    is TriggerCondition.OnCounterCountReached -> toCounterReachedEntity()
    is TriggerCondition.OnTimerReached -> toTimerReachedEntity()
}

private fun ScreenCondition.Color.toColorConditionEntity() = ConditionEntity(
    id = id.databaseId,
    eventId = eventId.databaseId,
    name = name,
    priority = priority,
    type = ConditionType.ON_COLOR_DETECTED,
    threshold = threshold,
    shouldBeDetected = shouldBeDetected,
    colorRgba = color,
    detectionAreaLeft = detectionArea.left,
    detectionAreaTop = detectionArea.top,
    detectionAreaRight = detectionArea.right,
    detectionAreaBottom = detectionArea.bottom,
)

/** @return the entity equivalent of this condition. */
private fun ScreenCondition.Image.toImageConditionEntity() = ConditionEntity(
    id = id.databaseId,
    eventId = eventId.databaseId,
    name = name,
    priority = priority,
    type = ConditionType.ON_IMAGE_DETECTED,
    threshold = threshold,
    shouldBeDetected = shouldBeDetected,
    path = path,
    areaLeft = area.left,
    areaTop = area.top,
    areaRight = area.right,
    areaBottom = area.bottom,
    detectionType = detectionType,
    detectionAreaLeft = detectionArea?.left,
    detectionAreaTop = detectionArea?.top,
    detectionAreaRight = detectionArea?.right,
    detectionAreaBottom = detectionArea?.bottom,
)

internal fun ScreenCondition.Image.toReferenceEntities(conditionId: Long): List<ImageReferenceEntity> =
    references.mapIndexed { index, reference ->
        ImageReferenceEntity(
            conditionId = conditionId,
            priority = index,
            path = reference.path,
            areaLeft = reference.area.left,
            areaTop = reference.area.top,
            areaRight = reference.area.right,
            areaBottom = reference.area.bottom,
        )
    }

private fun ScreenCondition.Number.toNumberConditionEntity(): ConditionEntity {
    val isNumberValue = counterValue is CounterOperationValue.Number

    return ConditionEntity(
        id = id.databaseId,
        eventId = eventId.databaseId,
        name = name,
        priority = priority,
        type = ConditionType.ON_NUMBER_DETECTED,
        threshold = threshold,
        shouldBeDetected = true,
        detectionAreaLeft = detectionArea.left,
        detectionAreaTop = detectionArea.top,
        detectionAreaRight = detectionArea.right,
        detectionAreaBottom = detectionArea.bottom,
        numberCounterComparisonOperation = comparisonOperation.toEntity(),
        numberCounterOperationValueType = if (isNumberValue) CounterOperationValueType.NUMBER else CounterOperationValueType.COUNTER,
        numberCounterValue = if (isNumberValue) counterValue.value else null,
        numberCounterOperationCounterName = if (isNumberValue) null else counterValue.value as String,
        numberFormatType = numberFormatType.toEntity(),
    )
}

private fun ScreenCondition.Text.toTextConditionEntity() = ConditionEntity(
    id = id.databaseId,
    eventId = eventId.databaseId,
    name = name,
    priority = priority,
    type = ConditionType.ON_TEXT_DETECTED,
    threshold = threshold,
    shouldBeDetected = shouldBeDetected,
    textToDetect = texts.first(),
    textToDetectAlternatives = texts.encodeTextAlternatives(),
    textAlphabet = alphabet.name,
    detectionAreaLeft = detectionArea.left,
    detectionAreaTop = detectionArea.top,
    detectionAreaRight = detectionArea.right,
    detectionAreaBottom = detectionArea.bottom,
)

private fun TriggerCondition.OnBroadcastReceived.toBroadcastReceivedEntity(): ConditionEntity =
    ConditionEntity(
        id = id.databaseId,
        eventId = eventId.databaseId,
        name = name,
        type = ConditionType.ON_BROADCAST_RECEIVED,
        broadcastAction = intentAction,
        priority = 0,
    )

private fun TriggerCondition.OnCounterCountReached.toCounterReachedEntity(): ConditionEntity {
    val isNumberValue = counterValue is CounterOperationValue.Number

    return ConditionEntity(
        id = id.databaseId,
        eventId = eventId.databaseId,
        name = name,
        type = ConditionType.ON_COUNTER_REACHED,
        counterName = counterName,
        counterComparisonOperation = comparisonOperation.toEntity(),
        counterOperationValueType = if (isNumberValue) CounterOperationValueType.NUMBER else CounterOperationValueType.COUNTER,
        counterValue = if (isNumberValue) counterValue.value else null,
        counterOperationCounterName = if (isNumberValue) null else counterValue.value as String,
        priority = 0,
    )
}

private fun TriggerCondition.OnTimerReached.toTimerReachedEntity(): ConditionEntity =
    ConditionEntity(
        id = id.databaseId,
        eventId = eventId.databaseId,
        name = name,
        type = ConditionType.ON_TIMER_REACHED,
        timerValueMs = durationMs,
        restartWhenReached = restartWhenReached,
        priority = 0,
    )


internal fun ConditionEntity.toDomain(cleanIds: Boolean = false): Condition =
    when (type) {
        ConditionType.ON_BROADCAST_RECEIVED -> toDomainBroadcastReceived(cleanIds)
        ConditionType.ON_COLOR_DETECTED -> toDomainColorCondition(cleanIds)
        ConditionType.ON_COUNTER_REACHED -> toDomainCounterReached(cleanIds)
        ConditionType.ON_IMAGE_DETECTED -> toDomainImageCondition(cleanIds)
        ConditionType.ON_NUMBER_DETECTED -> toDomainNumberCondition(cleanIds)
        ConditionType.ON_TEXT_DETECTED -> toDomainTextCondition(cleanIds)
        ConditionType.ON_TIMER_REACHED -> toDomainTimerReached(cleanIds)
    }

internal fun CompleteConditionEntity.toDomain(cleanIds: Boolean = false): Condition =
    if (condition.type == ConditionType.ON_IMAGE_DETECTED) {
        condition.toDomainImageCondition(cleanIds, imageReferences)
    } else {
        condition.toDomain(cleanIds)
    }

private fun ConditionEntity.toDomainColorCondition(cleanIds: Boolean = false): ScreenCondition.Color =
    ScreenCondition.Color(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        priority = priority,
        threshold = threshold!!,
        shouldBeDetected = shouldBeDetected ?: true,
        detectionArea = getDetectionArea()!!,
        color = colorRgba!!,
    )

private fun ConditionEntity.toDomainImageCondition(
    cleanIds: Boolean = false,
    relatedReferences: List<ImageReferenceEntity> = emptyList(),
): ScreenCondition.Image =
    ScreenCondition.Image(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        priority = priority,
        references = relatedReferences
            .sortedBy { reference -> reference.priority }
            .map(ImageReferenceEntity::toDomain)
            .ifEmpty { listOf(getLegacyImageReference()) },
        threshold = threshold!!,
        detectionType = detectionType!!,
        detectionArea = getDetectionArea(),
        shouldBeDetected = shouldBeDetected ?: true,
    )

private fun ImageReferenceEntity.toDomain(): ImageReference =
    ImageReference(
        path = path,
        area = Rect(areaLeft, areaTop, areaRight, areaBottom),
    )

private fun ConditionEntity.getLegacyImageReference(): ImageReference =
    ImageReference(
        path = requireNotNull(path) { "Invalid legacy image path=$path, expected a non-null path" },
        area = Rect(
            requireNotNull(areaLeft) { "Invalid legacy image area left=$areaLeft, expected an integer" },
            requireNotNull(areaTop) { "Invalid legacy image area top=$areaTop, expected an integer" },
            requireNotNull(areaRight) { "Invalid legacy image area right=$areaRight, expected an integer" },
            requireNotNull(areaBottom) { "Invalid legacy image area bottom=$areaBottom, expected an integer" },
        ),
    )

private fun ConditionEntity.toDomainNumberCondition(cleanIds: Boolean = false): ScreenCondition.Number =
    ScreenCondition.Number(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        priority = priority,
        threshold = threshold!!,
        detectionArea = getDetectionArea()!!,
        comparisonOperation = numberCounterComparisonOperation!!.toDomain(),
        counterValue = CounterOperationValue.getCounterOperationValue(
            type = numberCounterOperationValueType,
            numberValue = numberCounterValue,
            counterName = numberCounterOperationCounterName,
        ),
        numberFormatType = numberFormatType.toDomain(),
    )

private fun ConditionEntity.toDomainTextCondition(cleanIds: Boolean = false): ScreenCondition.Text =
    ScreenCondition.Text(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        priority = priority,
        threshold = threshold!!,
        shouldBeDetected = shouldBeDetected ?: true,
        detectionArea = getDetectionArea()!!,
        texts = listOf(textToDetect!!) + textToDetectAlternatives.decodeTextAlternatives(),
        alphabet = getTextAlphabet(),
    )

private fun ConditionEntity.toDomainBroadcastReceived(cleanIds: Boolean = false): TriggerCondition =
    TriggerCondition.OnBroadcastReceived(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        intentAction = broadcastAction!!,
    )

private fun ConditionEntity.toDomainCounterReached(cleanIds: Boolean = false): TriggerCondition =
    TriggerCondition.OnCounterCountReached(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        counterName = counterName!!,
        comparisonOperation = counterComparisonOperation!!.toDomain(),
        counterValue = CounterOperationValue.getCounterOperationValue(
            type = counterOperationValueType,
            numberValue = counterValue,
            counterName = counterOperationCounterName,
        ),
    )


private fun ConditionEntity.toDomainTimerReached(cleanIds: Boolean = false): TriggerCondition =
    TriggerCondition.OnTimerReached(
        id = Identifier(id = id, asTemporary = cleanIds),
        eventId = Identifier(id = eventId, asTemporary = cleanIds),
        name = name,
        durationMs = timerValueMs!!,
        restartWhenReached = restartWhenReached!!,
    )

private fun ConditionEntity.getDetectionArea(): Rect? =
    if (detectionAreaLeft != null && detectionAreaTop != null && detectionAreaRight != null && detectionAreaBottom != null)
        Rect(detectionAreaLeft!!, detectionAreaTop!!, detectionAreaRight!!, detectionAreaBottom!!)
    else
        null

private fun DbNumberFormatType?.toDomain(): NumberFormatType =
    when (this) {
        DbNumberFormatType.DOT_DECIMAL -> NumberFormatType.DOT_DECIMAL
        DbNumberFormatType.COMMA_DECIMAL -> NumberFormatType.COMMA_DECIMAL
        DbNumberFormatType.AUTO, null -> NumberFormatType.AUTO
    }

private fun NumberFormatType.toEntity(): DbNumberFormatType =
    when (this) {
        NumberFormatType.AUTO -> DbNumberFormatType.AUTO
        NumberFormatType.DOT_DECIMAL -> DbNumberFormatType.DOT_DECIMAL
        NumberFormatType.COMMA_DECIMAL -> DbNumberFormatType.COMMA_DECIMAL
    }

private fun ConditionEntity.getTextAlphabet(): OCRAlphabet =
    textAlphabet?.let {
        try {
            OCRAlphabet.valueOf(it)
        } catch (_: IllegalArgumentException) { OCRAlphabet.LATIN }
    } ?: OCRAlphabet.LATIN

private fun List<String>.encodeTextAlternatives(): String? {
    if (size <= 1) return null

    return buildString {
        append(TEXT_ALTERNATIVES_ENCODING_PREFIX)
        this@encodeTextAlternatives.drop(1).forEach { append(it.length).append(':').append(it) }
    }
}

private fun String?.decodeTextAlternatives(): List<String> {
    if (this == null || !startsWith(TEXT_ALTERNATIVES_ENCODING_PREFIX)) return emptyList()

    val alternatives = mutableListOf<String>()
    var offset = TEXT_ALTERNATIVES_ENCODING_PREFIX.length
    while (offset < length) {
        val separator = indexOf(':', offset)
        if (separator == -1) return emptyList()
        val valueLength = substring(offset, separator).toIntOrNull() ?: return emptyList()
        val valueStart = separator + 1
        val valueEnd = valueStart + valueLength
        if (valueEnd > length) return emptyList()
        alternatives.add(substring(valueStart, valueEnd))
        offset = valueEnd
    }
    return alternatives
}

private const val TEXT_ALTERNATIVES_ENCODING_PREFIX = "v1:"
