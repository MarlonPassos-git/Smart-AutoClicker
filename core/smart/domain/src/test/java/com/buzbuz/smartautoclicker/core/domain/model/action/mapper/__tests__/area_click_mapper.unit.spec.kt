package com.buzbuz.smartautoclicker.core.domain.model.action.mapper.__tests__

import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClick
import com.buzbuz.smartautoclicker.core.domain.model.action.AreaClickDistribution
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.AreaClickVerticesCodec
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toDomain
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class AreaClickMapperTest {

    @Test
    fun roundTripPreservesCompleteConfiguration() {
        val areaClick = createAreaClick()
        val restored = CompleteActionEntity(areaClick.toEntity(), emptyList(), emptyList()).toDomain()

        assertEquals(areaClick, restored)
    }

    @Test
    fun codecRejectsMalformedValueWithExpectedFormat() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AreaClickVerticesCodec.decode("v1:10,20;broken")
        }

        assertTrue(error.message!!.contains("v1:10,20;broken"))
        assertTrue(error.message!!.contains("expected v1:x,y;x,y;..."))
    }

    @Test
    fun deepCopyDuplicatesEveryVertex() {
        val original = createAreaClick()
        val copied = original.deepCopy()

        original.vertices.indices.forEach { assertNotSame(original.vertices[it], copied.vertices[it]) }
    }

    private fun createAreaClick() = AreaClick(
        id = Identifier(databaseId = 81),
        eventId = Identifier(databaseId = 82),
        name = "Area clicks",
        priority = 4,
        vertices = listOf(Point(10, 20), Point(210, 30), Point(120, 180)),
        clickCount = 17,
        distribution = AreaClickDistribution.DISTRIBUTED,
        pressDurationMs = 39,
        intervalMs = 125,
    )
}
