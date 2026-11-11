package com.buzbuz.smartautoclicker.feature.script.config

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.buzbuz.smartautoclicker.core.script.runtime.api.ScriptDiagnostic
import com.buzbuz.smartautoclicker.core.script.runtime.session.ScriptSessionStatus

/** Keeps the retained history available while formatting only rows needed by the visible viewport. */
internal class ScriptDiagnosticAdapter : RecyclerView.Adapter<ScriptDiagnosticAdapter.Holder>() {
    private sealed interface Row {
        data class Event(val diagnostic: ScriptDiagnostic) : Row
        data class Summary(val text: String) : Row
    }
    private var rows: List<Row> = emptyList()

    fun update(status: ScriptSessionStatus, events: List<ScriptDiagnostic>) {
        val summary = listOfNotNull(status.failure?.scriptFailureDetails(), status.reason).joinToString("\n")
        replace(buildList {
            if (summary.isNotBlank()) add(Row.Summary(summary))
            events.forEach { add(Row.Event(it)) }
        })
    }

    fun clear() = replace(emptyList())

    private fun replace(next: List<Row>) {
        val previous = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = previous[oldItemPosition]
                val new = next[newItemPosition]
                return if (old is Row.Event && new is Row.Event) old.diagnostic === new.diagnostic
                    else old is Row.Summary && new is Row.Summary
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition] == next[newItemPosition]
        }, false)
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_script_diagnostic, parent, false) as TextView)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = when (val row = rows[position]) {
            is Row.Summary -> row.text
            is Row.Event -> {
                val event = row.diagnostic
                if (event.operation == null) "${event.stage ?: event.kind}: ${event.message}"
                else {
                    val context = holder.text.context
                    val outcome = context.getString(when (event.outcome) {
                        "success" -> R.string.script_operation_success
                        "cancelled" -> R.string.script_operation_cancelled
                        "stopped" -> R.string.script_operation_stopped
                        "stale_observation" -> R.string.script_operation_stale
                        else -> R.string.script_operation_failed
                    })
                    context.getString(R.string.script_operation_timing, event.operation, outcome,
                        (event.durationNanos ?: 0) / 1_000_000.0) +
                        event.details?.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
                }
            }
        }
    }

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
