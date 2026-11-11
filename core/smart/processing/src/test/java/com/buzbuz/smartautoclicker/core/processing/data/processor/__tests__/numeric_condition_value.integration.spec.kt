package com.buzbuz.smartautoclicker.core.processing.data.processor

import android.graphics.Point
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.detection.DetectionResult
import com.buzbuz.smartautoclicker.core.detection.ImageDetector
import com.buzbuz.smartautoclicker.core.domain.model.AND
import com.buzbuz.smartautoclicker.core.domain.model.condition.NumberFormatType
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.domain.model.counter.ComparisonOperation
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterOperationValue
import com.buzbuz.smartautoclicker.core.processing.data.processor.state.ProcessingState
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScalingManager
import com.buzbuz.smartautoclicker.core.processing.data.scaling.ScreenConditionScalingInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class NumericConditionValueTest {
    private val condition = ScreenCondition.Number(
        Identifier(databaseId = 1), Identifier(databaseId = 2), "Quantidade", 80, priority = 0,
        detectionArea = Rect(400, 925, 1900, 970), comparisonOperation = ComparisonOperation.GREATER,
        counterValue = CounterOperationValue.Number(0.0), numberFormatType = NumberFormatType.POSITIVE_QUANTITY,
    )
    private val operand = CounterOperationValue.Condition(condition.id, CounterOperationValue.Condition.PositionType.CONDITION_NUMBER)

    @Test fun `verified numeric operand contains OCR value rather than its position`() = runTest {
        val (verifier, _) = verifier(DetectionResult(true, 99.0, Point(560, 945), Point(50, 30), 29.0))
        val results = verifier.verifyConditions(AND, listOf(condition))
        assertEquals(29.0, results.getDetectedConditionValue(operand)!!, 0.0)
        assertNull(results.getDetectedConditionValue(operand.copy(conditionId = Identifier(databaseId = 3))))
    }

    @Test fun `missing rejected and nonfinite readings never become counter operands`() = runTest {
        for (detection in listOf(DetectionResult(false), DetectionResult(true, numberDetected = 0.0),
            DetectionResult(true, numberDetected = Double.NaN), DetectionResult(true, numberDetected = Double.POSITIVE_INFINITY))) {
            val (verifier, _) = verifier(detection)
            assertNull(verifier.verifyConditions(AND, listOf(condition)).getDetectedConditionValue(operand))
        }
    }

    @Test fun `a later missing frame does not reuse the previous quantity`() = runTest {
        val (verifier, detector) = verifier(DetectionResult(true, numberDetected = 29.0))
        assertEquals(29.0, verifier.verifyConditions(AND, listOf(condition)).getDetectedConditionValue(operand)!!, 0.0)
        `when`(detector.detectNumber(condition.detectionArea, 80,
            com.buzbuz.smartautoclicker.core.detection.NumberFormatType.POSITIVE_QUANTITY)).thenReturn(DetectionResult(false))
        assertNull(verifier.verifyConditions(AND, listOf(condition)).getDetectedConditionValue(operand))
    }

    private fun verifier(detection: DetectionResult): Pair<ConditionsVerifier, ImageDetector> {
        val detector = mock(ImageDetector::class.java)
        val scaling = mock(ScalingManager::class.java)
        `when`(scaling.getScreenConditionScalingInfo(condition)).thenReturn(ScreenConditionScalingInfo.Number(condition, condition.detectionArea))
        `when`(scaling.scaleUpDetectionResult(any())).doAnswer { it.getArgument<Point?>(0) }
        `when`(detector.detectNumber(condition.detectionArea, 80,
            com.buzbuz.smartautoclicker.core.detection.NumberFormatType.POSITIVE_QUANTITY)).thenReturn(detection)
        return ConditionsVerifier(ProcessingState(emptyList(), emptyList(), emptyList(), null), detector, scaling, { _, _, _ -> null }) to detector
    }
}
