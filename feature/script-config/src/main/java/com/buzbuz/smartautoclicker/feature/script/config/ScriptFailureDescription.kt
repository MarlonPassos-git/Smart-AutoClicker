package com.buzbuz.smartautoclicker.feature.script.config

import com.buzbuz.smartautoclicker.core.script.runtime.ScriptExecutionException

/** Keeps provider location metadata in editor and execution errors, e.g. `entry.js:12:5`. */
internal fun Throwable.scriptFailureMessage(): String {
    val script = this as? ScriptExecutionException
    val location = script?.let {
        if (it.sourceFile == null && it.sourceLine == null && it.sourceColumn == null) null
        else buildList {
            add(it.sourceFile ?: "JavaScript")
            if (it.sourceLine != null || it.sourceColumn != null) add(it.sourceLine?.toString() ?: "?")
            it.sourceColumn?.let { column -> add(column.toString()) }
        }.joinToString(":")
    }
    return listOfNotNull(location, message).joinToString("\n").ifBlank { "Não foi possível concluir a operação" }
}

/** Preserves the JS stack supplied by the interpreter as well as the Kotlin cause chain. */
internal fun Throwable.scriptFailureDetails(): String = listOfNotNull(
    scriptFailureMessage(),
    (this as? ScriptExecutionException)?.scriptStack?.takeIf { it.isNotBlank() },
    stackTraceToString(),
).joinToString("\n")
