/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.domain.model.action.mapper.__tests__

import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.database.entity.CompleteActionEntity
import com.buzbuz.smartautoclicker.core.domain.model.action.Zoom
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toDomain
import com.buzbuz.smartautoclicker.core.domain.model.action.mapper.toEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class ZoomMapperTest {

    @Test
    fun zoomRoundTripPreservesGestureConfiguration() {
        val zoom = createZoom()
        val completeEntity = CompleteActionEntity(zoom.toEntity(), emptyList(), emptyList())

        assertEquals(zoom, completeEntity.toDomain())
    }

    private fun createZoom() = Zoom(
        id = Identifier(databaseId = 41),
        eventId = Identifier(databaseId = 42),
        name = "Map zoom",
        priority = 3,
        direction = ZoomDirection.OUT,
        intensityPx = 275,
        center = Point(640, 360),
        zoomDurationMs = 420,
    )
}
