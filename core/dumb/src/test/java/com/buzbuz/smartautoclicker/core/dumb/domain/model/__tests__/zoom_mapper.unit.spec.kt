/*
 * Copyright (C) 2026 Kevin Buzeau
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.buzbuz.smartautoclicker.core.dumb.domain.model.__tests__

import android.graphics.Point
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.gesture.ZoomDirection
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.dumb.domain.model.DumbAction
import com.buzbuz.smartautoclicker.core.dumb.domain.model.toDomain
import com.buzbuz.smartautoclicker.core.dumb.domain.model.toEntity
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

        assertEquals(zoom, zoom.toEntity().toDomain())
    }

    private fun createZoom() = DumbAction.DumbZoom(
        id = Identifier(databaseId = 51),
        scenarioId = Identifier(databaseId = 52),
        name = "Gallery zoom",
        priority = 2,
        direction = ZoomDirection.IN,
        intensityPx = 180,
        center = Point(540, 960),
        zoomDurationMs = 350,
    )
}
