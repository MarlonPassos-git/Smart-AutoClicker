package com.buzbuz.smartautoclicker.core.domain.model.counter

import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import org.junit.Assert.*
import org.junit.Test

class CounterStartQuestionTest {
    private val target = Counter("target", 50.0, Identifier(databaseId = 7L), CounterStartQuestion("Meta", minimum = 1.0, maximum = 100.0))

    @Test fun `numeric answers include boundaries and reject invalid or nonfinite values`() {
        val question = target.startQuestion!!
        listOf(1.0, 50.0, 100.0).forEach { assertTrue(question.accepts(it)) }
        listOf(0.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertFalse(question.accepts(it)) }
        assertFalse(target.copy(defaultValue = 0.0).isComplete())
        assertFalse(target.copy(startQuestion = question.copy(minimum = 101.0)).isComplete())
        assertFalse(target.copy(startQuestion = question.copy(title = " ")).isComplete())
    }

    @Test fun `boolean and stable choice values constrain defaults and answers`() {
        val boolean = CounterStartQuestion("Ativar", type = CounterStartQuestion.Type.BOOLEAN)
        assertTrue(boolean.isValid(0.0)); assertTrue(boolean.accepts(1.0)); assertFalse(boolean.accepts(2.0))
        val choice = CounterStartQuestion("Prioridade", type = CounterStartQuestion.Type.CHOICE,
            options = listOf(CounterStartQuestion.Option("Ouro", 10.0), CounterStartQuestion.Option("Elixir", 20.0)))
        assertTrue(choice.isValid(10.0)); assertFalse(choice.accepts(0.0))
        assertFalse(choice.copy(options = emptyList()).isValid(10.0))
        assertFalse(choice.copy(options = choice.options + choice.options.first()).isValid(10.0))
    }

    @Test fun `execution snapshots preserve saved defaults and reset each run`() {
        val hidden = Counter("internal", 3.0, Identifier(databaseId = 7L))
        val counters = listOf(target, hidden)
        val firstRun = resolveCounterInitialValues(counters, mapOf("target" to 75.0))
        assertEquals(75.0, firstRun.first().defaultValue, 0.0)
        assertEquals(3.0, firstRun.last().defaultValue, 0.0)
        assertEquals(50.0, target.defaultValue, 0.0)
        assertEquals(50.0, resolveCounterInitialValues(counters, emptyMap()).first().defaultValue, 0.0)
    }

    @Test fun `invalid answer set is rejected without partial changes`() {
        for (answers in listOf(mapOf("target" to 101.0), mapOf("unknown" to 75.0))) {
            assertThrows(IllegalArgumentException::class.java) { resolveCounterInitialValues(listOf(target), answers) }
            assertEquals(50.0, target.defaultValue, 0.0)
        }
    }

    @Test fun `entity mapping preserves portable question metadata`() {
        val conditional = target.copy(startQuestion = target.startQuestion!!.copy(visibleWhen = CounterStartQuestion.Visibility("mode", 0.0)))
        assertEquals(conditional, conditional.toEntity().toDomain())
        assertNull(target.copy(startQuestion = null).toEntity().startQuestion)
    }

    @Test fun `conditional questions match their controller and remain editable if it was removed`() {
        val question = target.startQuestion!!.copy(visibleWhen = CounterStartQuestion.Visibility("mode", 0.0))
        assertTrue(question.isVisible(mapOf("mode" to 0.0)))
        assertFalse(question.isVisible(mapOf("mode" to 1.0)))
        assertTrue(question.isVisible(emptyMap()))
        assertFalse(question.copy(visibleWhen = CounterStartQuestion.Visibility(" ", 0.0)).isValid(50.0))
        assertFalse(question.copy(visibleWhen = CounterStartQuestion.Visibility("mode", Double.NaN)).isValid(50.0))
    }
}
