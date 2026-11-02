package com.buzbuz.smartautoclicker.feature.smart.config.ui.action.gesture.bindings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.EXACT
import com.buzbuz.smartautoclicker.core.domain.model.condition.ImageReference
import com.buzbuz.smartautoclicker.core.domain.model.condition.ScreenCondition
import com.buzbuz.smartautoclicker.core.ui.views.clickoffset.ClickOffsetView
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class GestureOffsetPreviewTest {

    @Test
    fun `offset editor retains the reference preview delivered for the selected condition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attributes = Robolectric.buildAttributeSet().build()
        val view = ClickOffsetView(context, attributes)
        val references = listOf(
            ImageReference("first.png", Rect(0, 0, 20, 20)),
            ImageReference("second.png", Rect(50, 50, 80, 80)),
        )
        val condition = ScreenCondition.Image(
            id = Identifier(databaseId = 1), eventId = Identifier(databaseId = 2), name = "Image",
            threshold = 4, shouldBeDetected = true, priority = 0, references = references, detectionType = EXACT,
        )
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        var loadedReferences: List<ImageReference>? = null

        view.bindConditionPreview(condition) { selected, onLoaded ->
            loadedReferences = selected.references
            onLoaded(bitmap)
            Job().apply { complete() }
        }

        assertEquals(references, loadedReferences)
        assertSame(bitmap, (view.drawable as BitmapDrawable).bitmap)
    }
}
