package com.buzbuz.smartautoclicker.feature.script.config

import android.content.Context
import android.content.Intent

/** Opens only the declared launcher, while the consent activity is still visible. */
internal class ScriptTargetAppLauncher(private val context: Context) {
    /** Legacy packages have no target; e.g. `launcher.open(manifest.targetPackage)` before starting capture. */
    fun open(packageName: String?) {
        if (packageName == null) return
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("Aplicativo $packageName indisponível. Instale ou habilite o aplicativo e tente novamente.")
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: RuntimeException) {
            throw IllegalStateException("Não foi possível abrir $packageName. Abra o aplicativo e tente novamente.", failure)
        }
    }
}
