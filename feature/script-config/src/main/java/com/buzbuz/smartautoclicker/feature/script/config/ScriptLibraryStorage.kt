package com.buzbuz.smartautoclicker.feature.script.config

import android.content.Context
import com.buzbuz.smartautoclicker.core.script.packages.ScriptPackageRepository
import com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage
import java.io.File

/** Shares the installed scripts between the home screen and the script library. */
object ScriptLibraryStorage {
    private const val BUNDLED_FARM_ID = "farm-walls-sm-s916b"

    internal fun repository(context: Context): ScriptPackageRepository =
        ScriptPackageRepository(File(context.filesDir, "scripts/packages"))

    /**
     * Installs the bundled farm if its package ID is absent, then returns the visible revisions.
     * Call on an I/O dispatcher, e.g. `withContext(Dispatchers.IO) { ScriptLibraryStorage.listPackages(context) }`.
     */
    @Synchronized
    fun listPackages(context: Context): List<StoredScriptPackage> {
        val repository = repository(context)
        val installed = repository.listLatestPackages()
        if (installed.any { it.manifest.id == BUNDLED_FARM_ID }) return installed

        val farm = repository.importPackage(context.assets.open("$BUNDLED_FARM_ID.zip"))
        return (installed + farm).sortedBy { it.manifest.name.lowercase() }
    }
}
