package com.buzbuz.smartautoclicker.core.script.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import io.mockk.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunLogRecoveryIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun systemEvidenceIdentifiesANativeCrashWhileAnOlderPidRecordCannotExplainANewRun() {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns temporary.root
        every { context.packageName } returns "com.example.automation"
        val activityManager = mockk<ActivityManager>()
        every { context.getSystemService(ActivityManager::class.java) } returns activityManager
        val nativeCrash = mockk<ApplicationExitInfo>()
        every { nativeCrash.timestamp } answers { System.currentTimeMillis() + 1000 }
        every { nativeCrash.reason } returns ApplicationExitInfo.REASON_CRASH_NATIVE
        every { nativeCrash.status } returns 11
        every { nativeCrash.description } returns "SIGSEGV"
        val olderExit = mockk<ApplicationExitInfo>()
        every { olderExit.timestamp } returns 1
        every { activityManager.getHistoricalProcessExitReasons("com.example.automation", 123, 8) } returns listOf(nativeCrash)
        every { activityManager.getHistoricalProcessExitReasons("com.example.automation", 456, 8) } returns listOf(olderExit)
        val logs = ScriptRunLogs.open(context)
        for (pid in listOf(123, 456)) logs.start(buildJsonObject {
            put("processInstance", "previous-process"); put("pid", pid)
        })
        val active = logs.start(ScriptRunLogs.environment(context))
        ScriptRunLogs.open(context)
        val summaries = File(temporary.root, "scripts/execution-logs").listFiles()!!.map {
            Json.parseToJsonElement(it.resolve("summary.json").readText()).jsonObject
        }
        val crash = summaries.first { it["pid"] == JsonPrimitive(123) }
        assertEquals(JsonPrimitive("INTERRUPTED"), crash["status"])
        assertEquals(JsonPrimitive("crash_native"), crash["processExit"]?.jsonObject?.get("reason"))
        assertEquals(JsonPrimitive(true), crash["processExit"]?.jsonObject?.get("crashConfirmed"))
        assertNull(summaries.first { it["pid"] == JsonPrimitive(456) }["processExit"])
        assertEquals(1, summaries.count { it["status"] == JsonPrimitive("RUNNING") })
        active.finish("COMPLETED")
    }
}
