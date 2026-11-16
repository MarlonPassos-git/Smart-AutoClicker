package com.buzbuz.smartautoclicker.feature.script.config

import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.buzbuz.smartautoclicker.feature.script.config.R
import com.buzbuz.smartautoclicker.core.script.packages.ScriptManifest
import com.buzbuz.smartautoclicker.core.script.packages.StoredScriptPackage
import com.buzbuz.smartautoclicker.feature.script.config.ScriptHomeAdapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScriptHomeIntegrationTest {
    @Test fun configurationControlOpensSetupForTheDisplayedRevisionWithoutStartingFarm() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.AppTheme)
        val script = StoredScriptPackage("configured-revision", ScriptManifest.parse("""
            {"formatVersion":1,"apiVersion":1,"id":"farm","name":"Farm","scriptVersion":"3","entry":"entry.js",
                "executionState":{"version":1},"setup":{"entry":"setup.js","label":"Configurar vilas"}}
        """.trimIndent()))
        val configured = mutableListOf<StoredScriptPackage>()
        val started = mutableListOf<StoredScriptPackage>()
        val adapter = ScriptHomeAdapter(onStart = started::add, onSetup = configured::add)
        adapter.submitList(listOf(script))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        val setup = holder.itemView.findViewById<TextView>(R.id.script_setup)
        assertEquals("Configurar vilas", setup.text.toString())
        assertEquals(android.view.View.VISIBLE, setup.visibility)
        setup.performClick()
        assertEquals(listOf(script), configured)
        assertTrue(started.isEmpty())
    }

    @Test fun homeCardStartsTheDisplayedRevisionFromEitherTheTitleOrPlayControl() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.AppTheme)
        val script = StoredScriptPackage("reviewed-revision", ScriptManifest.parse("""
            {"formatVersion":1,"apiVersion":1,"id":"farm","name":"Farm V2","scriptVersion":"2.0.0","entry":"entry.js"}
        """.trimIndent()))
        val started = mutableListOf<StoredScriptPackage>()
        val adapter = ScriptHomeAdapter(started::add)
        adapter.submitList(listOf(script))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Farm V2", holder.itemView.findViewById<TextView>(R.id.script_name).text.toString())
        val play = holder.itemView.findViewById<android.view.View>(R.id.script_start)
        assertEquals("Iniciar Farm V2", play.contentDescription.toString())
        play.performClick()
        holder.itemView.performClick()
        assertEquals(listOf(script, script), started)
    }
}
