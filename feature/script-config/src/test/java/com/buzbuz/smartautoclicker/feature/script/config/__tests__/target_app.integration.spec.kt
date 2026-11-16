package com.buzbuz.smartautoclicker.feature.script.config

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.buzbuz.smartautoclicker.core.script.packages.ScriptManifest
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TargetAppIntegrationTest {
    private fun manifest(target: String = "") = ScriptManifest.parse("""{"formatVersion":1,"apiVersion":1,"id":"example","name":"Exemplo","scriptVersion":"1","entry":"entry.js"$target}""")

    @Test fun declaredPackageOpensItsLauncherAndLegacyPackagesKeepTheirExistingFlow() {
        val context = mockk<Context>(relaxed = true)
        val packages = mockk<PackageManager>()
        every { context.packageManager } returns packages
        every { packages.getLaunchIntentForPackage("com.example.game") } returns Intent(Intent.ACTION_MAIN).setPackage("com.example.game")
        val launcher = ScriptTargetAppLauncher(context)
        launcher.open(manifest().targetPackage)
        verify(exactly = 0) { context.startActivity(any()) }
        launcher.open(manifest(""", "targetPackage":"com.example.game"""").targetPackage)
        verify(exactly = 1) { context.startActivity(match { it.`package` == "com.example.game" && it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 }) }
    }

    @Test fun missingOrDisabledTargetIsReportedBeforeAutomationCanStart() {
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager.getLaunchIntentForPackage("com.example.missing") } returns null
        val failure = assertThrows(IllegalStateException::class.java) { ScriptTargetAppLauncher(context).open("com.example.missing") }
        assertTrue(failure.message!!.contains("com.example.missing"))
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test fun malformedTargetPackagesAreRejectedAtImport() {
        for (target in listOf("null", "123", "\"\"", "\"../game\"", "\"com.example/game\"")) {
            assertThrows(IllegalArgumentException::class.java) { manifest(",\"targetPackage\":$target") }
        }
    }
}
