package com.buzbuz.smartautoclicker.core.script.runtime

/** Bundled source, never downloaded or resolved from the script package. */
internal object ScriptApiV1 {
    val source: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/klickr/api-v1.js")) { "Bundled JavaScript API v1 is missing" }
            .bufferedReader().use { it.readText() }
    }
}
