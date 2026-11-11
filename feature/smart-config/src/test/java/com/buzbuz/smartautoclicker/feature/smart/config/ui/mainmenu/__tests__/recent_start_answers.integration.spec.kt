package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterStartQuestion
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecentStartAnswersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val counter = Counter("target", 50.0, Identifier(databaseId = 1L), CounterStartQuestion("Meta", minimum = 1.0, maximum = 100.0))
    private val store = RecentStartAnswers(context)

    @Before fun clear() { context.getSharedPreferences("scenario_start_answers", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun `confirmed answers survive store recreation and remain isolated by scenario`() {
        store.write(1, listOf(counter), mapOf("target" to 75.0))
        assertEquals(75.0, RecentStartAnswers(context).read(1, listOf(counter)).getValue("target"), 0.0)
        assertEquals(50.0, store.read(2, listOf(counter)).getValue("target"), 0.0)
        assertEquals(50.0, counter.defaultValue, 0.0)
    }

    @Test fun `edited limits options and renamed or deleted counters cannot restore invalid answers`() {
        store.write(1, listOf(counter), mapOf("target" to 75.0))
        val narrower = counter.copy(startQuestion = counter.startQuestion!!.copy(maximum = 60.0))
        assertEquals(50.0, store.read(1, listOf(narrower)).getValue("target"), 0.0)
        val choice = counter.copy(startQuestion = CounterStartQuestion("Meta", type = CounterStartQuestion.Type.CHOICE,
            options = listOf(CounterStartQuestion.Option("Padrão", 50.0))))
        assertEquals(50.0, store.read(1, listOf(choice)).getValue("target"), 0.0)
        assertEquals(50.0, store.read(1, listOf(counter.copy(counterName = "renamed"))).getValue("renamed"), 0.0)
        assertTrue(store.read(1, emptyList()).isEmpty())
    }
}
