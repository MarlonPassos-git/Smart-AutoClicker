package com.buzbuz.smartautoclicker.core.script.runtime.api

/** Intentional stop intercepted by the session before the binding can turn it into a JS error. */
class ScriptStopRequested(val reason: String) : Exception(reason)
