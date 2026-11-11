package com.buzbuz.smartautoclicker.feature.smart.config.ui.counter.questions

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier
import com.buzbuz.smartautoclicker.core.common.overlays.di.OverlaysEntryPoint
import com.buzbuz.smartautoclicker.core.common.overlays.manager.OverlayManager
import com.buzbuz.smartautoclicker.core.common.overlays.menu.implementation.common.OverlayMenuPositionDataSource
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter
import com.buzbuz.smartautoclicker.core.domain.model.counter.CounterStartQuestion
import com.buzbuz.smartautoclicker.feature.smart.config.R
import com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu.StartQuestionsRequest
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.materialswitch.MaterialSwitch
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = QuestionFormTestApplication::class)
class QuestionFormIntegrationTest {
    private val counter = Counter("target", 50.0, Identifier(databaseId = 1L), CounterStartQuestion("Meta", minimum = 1.0, maximum = 100.0))

    @Test fun `invalid input remains visible then one valid confirmation delivers answers`() {
        val confirmed = mutableListOf<Map<String, Double>>()
        val form = StartQuestionsDialog(StartQuestionsRequest(1, listOf(counter), mapOf("target" to 75.0)), confirmed::add, {})
        val root = render(form)
        val input = descendants(root).filterIsInstance<EditText>().single()
        assertEquals(75.0, input.text.toString().toDouble(), 0.0)
        input.setText("101")
        root.findViewById<View>(R.id.confirm).performClick()
        assertTrue(confirmed.isEmpty())
        assertNotNull(descendants(root).filterIsInstance<TextInputLayout>().single().error)
        input.setText("85")
        root.findViewById<View>(R.id.confirm).performClick()
        root.findViewById<View>(R.id.confirm).performClick()
        assertEquals(listOf(mapOf("target" to 85.0)), confirmed)
        assertEquals(50.0, counter.defaultValue, 0.0)
    }

    @Test fun `cancel does not submit edited draft`() {
        var cancelled = false
        var submitted = false
        val root = render(StartQuestionsDialog(StartQuestionsRequest(1, listOf(counter), mapOf("target" to 50.0)),
            { submitted = true }, { cancelled = true }))
        descendants(root).filterIsInstance<EditText>().single().setText("90")
        root.findViewById<View>(R.id.cancel).performClick()
        assertTrue(cancelled); assertFalse(submitted)
    }

    @Test
    @Config(sdk = [24])
    fun `boolean and labelled choices submit numeric values in author order`() {
        val boolean = counter.copy(counterName = "enabled", defaultValue = 1.0,
            startQuestion = CounterStartQuestion("Ativar", type = CounterStartQuestion.Type.BOOLEAN, order = 1))
        val choice = counter.copy(counterName = "resource", defaultValue = 10.0,
            startQuestion = CounterStartQuestion("Recurso", type = CounterStartQuestion.Type.CHOICE, order = 0,
                options = listOf(CounterStartQuestion.Option("Ouro", 10.0), CounterStartQuestion.Option("Elixir", 20.0))))
        var submitted: Map<String, Double>? = null
        val root = render(StartQuestionsDialog(StartQuestionsRequest(1, listOf(boolean, choice), mapOf("enabled" to 1.0, "resource" to 10.0)),
            { submitted = it }, {}))
        val dropdown = descendants(root).filterIsInstance<MaterialAutoCompleteTextView>().single()
        assertEquals("Ouro", dropdown.text.toString())
        select(dropdown, 1)
        descendants(root).filterIsInstance<MaterialSwitch>().single { it.text == "Ativar" }.performClick()
        root.findViewById<View>(R.id.confirm).performClick()
        assertEquals(mapOf("enabled" to 0.0, "resource" to 20.0), submitted)
    }

    @Test fun `dependent percentage hides and restores its draft when the finishing mode changes`() {
        val (form, root) = dependentForm {}
        val percentage = descendants(root).filterIsInstance<TextInputLayout>().single { it.hint == "Meta" }
        percentage.editText!!.setText("85")
        val mode = descendants(root).filterIsInstance<MaterialAutoCompleteTextView>().single()
        select(mode, 1)
        assertFalse(isVisible(percentage))
        select(mode, 0)
        assertTrue(isVisible(percentage))
        assertEquals("85", percentage.editText!!.text.toString())
        select(mode, 2)
        val recreated = render(form)
        assertEquals("Até o fim", descendants(recreated).filterIsInstance<MaterialAutoCompleteTextView>().single().text.toString())
        assertFalse(isVisible(descendants(recreated).filterIsInstance<TextInputLayout>().single { it.hint == "Meta" }))
    }

    @Test fun `hidden invalid percentage does not block loot mode and uses a valid initial value`() {
        var submitted: Map<String, Double>? = null
        val (_, root) = dependentForm { submitted = it }
        descendants(root).filterIsInstance<TextInputLayout>().single { it.hint == "Meta" }.editText!!.setText("101")
        select(descendants(root).filterIsInstance<MaterialAutoCompleteTextView>().single(), 1)
        root.findViewById<View>(R.id.confirm).performClick()
        assertEquals(mapOf("mode" to 1.0, "target" to 50.0), submitted)
    }

    @Test fun `disabling walls hides their timing selection without losing the selected trigger`() {
        val enabled = counter.copy(counterName = "walls", defaultValue = 1.0,
            startQuestion = CounterStartQuestion("Melhorar muros", type = CounterStartQuestion.Type.BOOLEAN))
        val timing = counter.copy(counterName = "timing", defaultValue = 1.0,
            startQuestion = CounterStartQuestion("Quando melhorar", type = CounterStartQuestion.Type.CHOICE, order = 1,
                options = listOf(CounterStartQuestion.Option("Ouro cheio", 1.0), CounterStartQuestion.Option("Sempre", 5.0)),
                visibleWhen = CounterStartQuestion.Visibility("walls", 1.0)))
        val root = render(StartQuestionsDialog(StartQuestionsRequest(1, listOf(enabled, timing), mapOf("walls" to 1.0, "timing" to 1.0)), {}, {}))
        val dropdown = descendants(root).filterIsInstance<MaterialAutoCompleteTextView>().single()
        select(dropdown, 1)
        val toggle = descendants(root).filterIsInstance<MaterialSwitch>().single()
        toggle.performClick()
        assertFalse(isVisible(dropdown))
        toggle.performClick()
        assertTrue(isVisible(dropdown))
        assertEquals("Sempre", dropdown.text.toString())
    }

    @Test fun `advanced resource fields show full amounts and retain limits when collapsed`() {
        val advanced = counter.copy(counterName = "advanced", defaultValue = 0.0,
            startQuestion = CounterStartQuestion("Configurações avançadas", type = CounterStartQuestion.Type.BOOLEAN))
        val capacity = counter.copy(counterName = "capacity", defaultValue = 30_500_000.0,
            startQuestion = CounterStartQuestion("Capacidade de ouro", order = 1, minimum = 1.0, maximum = 1e12,
                visibleWhen = CounterStartQuestion.Visibility("advanced", 1.0)))
        val reserve = capacity.copy(counterName = "reserve", defaultValue = 0.0,
            startQuestion = capacity.startQuestion!!.copy(title = "Reserva de ouro", order = 2, minimum = 0.0))
        var submitted: Map<String, Double>? = null
        val root = render(StartQuestionsDialog(StartQuestionsRequest(1, listOf(advanced, capacity, reserve),
            mapOf("advanced" to 0.0, "capacity" to 30_500_000.0, "reserve" to 0.0)), { submitted = it }, {}))
        val fields = descendants(root).filterIsInstance<TextInputLayout>()
        val capacityField = fields.single { it.hint == "Capacidade de ouro" }
        val reserveField = fields.single { it.hint == "Reserva de ouro" }
        assertFalse(isVisible(capacityField))
        val toggle = descendants(root).filterIsInstance<MaterialSwitch>().single()
        toggle.performClick()
        assertTrue(isVisible(capacityField))
        assertEquals("30500000", capacityField.editText!!.text.toString())
        assertEquals("0", reserveField.editText!!.text.toString())
        reserveField.editText!!.setText("1000000")
        toggle.performClick()
        assertFalse(isVisible(reserveField))
        root.findViewById<View>(R.id.confirm).performClick()
        assertEquals(mapOf("advanced" to 0.0, "capacity" to 30_500_000.0, "reserve" to 1_000_000.0), submitted)
    }

    @Test fun `author can save conditional visibility with the portable question`() {
        var saved: Counter? = null
        val root = render(CounterQuestionDialog(counter) { saved = it })
        setField(root, R.string.start_questions_visible_counter, "mode")
        setField(root, R.string.start_questions_visible_value, "0")
        root.findViewById<View>(R.id.confirm).performClick()
        assertEquals(CounterStartQuestion.Visibility("mode", 0.0), saved!!.startQuestion!!.visibleWhen)
    }

    @Test fun `recreating the view retains unfinished answers without submitting`() {
        val form = StartQuestionsDialog(StartQuestionsRequest(1, listOf(counter), mapOf("target" to 50.0)), {}, {})
        descendants(render(form)).filterIsInstance<EditText>().single().setText("82")
        val recreated = render(form)
        assertEquals("82", descendants(recreated).filterIsInstance<EditText>().single().text.toString())
    }

    @Test fun `author can declare labelled numeric choices without changing counter identity`() {
        var saved: Counter? = null
        val root = render(CounterQuestionDialog(counter.copy(startQuestion = null)) { saved = it })
        descendants(root).filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>().single().isChecked = true
        descendants(root).filterIsInstance<RadioButton>().single { it.text == "Escolha única" }.performClick()
        setField(root, R.string.start_questions_default, "10")
        setField(root, R.string.start_questions_options, "10 = Ouro primeiro\n20 = Elixir primeiro")
        root.findViewById<View>(R.id.confirm).performClick()
        assertEquals(counter.counterName, saved!!.counterName)
        assertEquals(counter.scenarioId, saved!!.scenarioId)
        assertEquals(listOf(10.0, 20.0), saved!!.startQuestion!!.options.map { it.value })
        assertEquals(10.0, saved!!.defaultValue, 0.0)
    }

    @Test fun `duplicate author option values prevent saving with a visible field error`() {
        var saved = false
        val root = render(CounterQuestionDialog(counter) { saved = true })
        descendants(root).filterIsInstance<RadioButton>().single { it.text == "Escolha única" }.performClick()
        setField(root, R.string.start_questions_options, "50 = Ouro\n50 = Elixir")
        root.findViewById<View>(R.id.confirm).performClick()
        assertFalse(saved)
        assertNotNull(descendants(root).filterIsInstance<TextInputLayout>().single {
            it.hint == root.context.getString(R.string.start_questions_options)
        }.error)
    }

    private fun setField(root: View, label: Int, text: String) {
        descendants(root).filterIsInstance<TextInputLayout>().single { it.hint == root.context.getString(label) }
            .editText!!.setText(text)
    }

    private fun dependentForm(onConfirmed: (Map<String, Double>) -> Unit): Pair<StartQuestionsDialog, ViewGroup> {
        val mode = counter.copy(counterName = "mode", defaultValue = 0.0,
            startQuestion = CounterStartQuestion("Finalizar quando", type = CounterStartQuestion.Type.CHOICE,
                options = listOf(CounterStartQuestion.Option("Porcentagem", 0.0), CounterStartQuestion.Option("Saque", 1.0),
                    CounterStartQuestion.Option("Até o fim", 2.0))))
        val target = counter.copy(startQuestion = counter.startQuestion!!.copy(order = 1,
            visibleWhen = CounterStartQuestion.Visibility("mode", 0.0)))
        val form = StartQuestionsDialog(StartQuestionsRequest(1, listOf(mode, target), mapOf("mode" to 0.0, "target" to 50.0)), onConfirmed, {})
        return form to render(form)
    }

    private fun select(dropdown: MaterialAutoCompleteTextView, position: Int) {
        dropdown.onItemClickListener!!.onItemClick(null, null, position, position.toLong())
    }

    private fun isVisible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let(::isVisible) ?: true)

    private fun render(form: QuestionFormDialog): ViewGroup {
        form.context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.ScenarioConfigTheme)
        // Invoke the protected lifecycle entry point without starting a service or attaching a window.
        return QuestionFormDialog::class.java.getDeclaredMethod("onCreateView").apply { isAccessible = true }.invoke(form) as ViewGroup
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}

class QuestionFormTestApplication : Application(), GeneratedComponentManager<Any> {
    private val manager = mockk<OverlayManager>(relaxed = true).apply { every { navigateUp(any()) } returns true }
    override fun generatedComponent(): Any = object : OverlaysEntryPoint, GeneratedComponent {
        override fun overlayManager(): OverlayManager = manager
        override fun overlayMenuPositionDataSource(): OverlayMenuPositionDataSource = mockk(relaxed = true)
    }
}
