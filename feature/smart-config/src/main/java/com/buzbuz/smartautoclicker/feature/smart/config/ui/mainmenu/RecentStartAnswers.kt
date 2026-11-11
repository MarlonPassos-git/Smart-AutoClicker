package com.buzbuz.smartautoclicker.feature.smart.config.ui.mainmenu

import android.content.Context
import com.buzbuz.smartautoclicker.core.domain.model.counter.Counter

data class StartQuestionsRequest(
    val scenarioId: Long,
    val counters: List<Counter>,
    val initialAnswers: Map<String, Double>,
)

/** Local preferences, never part of exported definitions. Example: `store.read(scenarioId, counters)`. */
internal class RecentStartAnswers(context: Context) {
    private val preferences = context.getSharedPreferences("scenario_start_answers", Context.MODE_PRIVATE)

    fun read(scenarioId: Long, counters: List<Counter>): Map<String, Double> =
        counters.filter { it.startQuestion != null }.associate { counter ->
            val stored = preferences.getString(key(scenarioId, counter), null)?.toDoubleOrNull()
            counter.counterName to (stored?.takeIf { counter.startQuestion!!.accepts(it) } ?: counter.defaultValue)
        }

    fun write(scenarioId: Long, counters: List<Counter>, answers: Map<String, Double>) {
        val prefix = "$scenarioId/"
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        counters.filter { it.startQuestion != null }.forEach { counter ->
            answers[counter.counterName]?.let { editor.putString(key(scenarioId, counter), it.toString()) }
        }
        editor.apply()
    }

    private fun key(scenarioId: Long, counter: Counter): String = "$scenarioId/${counter.counterName}"
}
