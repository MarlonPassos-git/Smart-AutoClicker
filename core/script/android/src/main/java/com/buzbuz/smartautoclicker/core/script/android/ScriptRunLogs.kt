package com.buzbuz.smartautoclicker.core.script.android

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import com.buzbuz.smartautoclicker.core.script.packages.ScriptRunLogRepository
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.*

/** Device evidence for private run logs. Uses Android's exit history instead of guessing a crash cause. */
object ScriptRunLogs {
    private val processInstance = UUID.randomUUID().toString()

    /** Opens/reconciles logs on an I/O thread; e.g. `ScriptRunLogs.open(context).exportZip(output)`. */
    fun open(context: Context): ScriptRunLogRepository = ScriptRunLogRepository(File(context.filesDir, "scripts/execution-logs")).also { logs ->
        logs.recoverInterrupted(processInstance) { summary ->
            if (Build.VERSION.SDK_INT < 30) return@recoverInterrupted null
            val pid = summary["pid"]?.jsonPrimitive?.intOrNull ?: return@recoverInterrupted null
            val started = summary["startedAtMillis"]?.jsonPrimitive?.longOrNull ?: return@recoverInterrupted null
            runCatching {
                context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(context.packageName, pid, 8)
                    ?.firstOrNull { it.timestamp >= started }?.let { exit -> buildJsonObject {
                        put("reasonCode", exit.reason); put("status", exit.status); put("timestampMillis", exit.timestamp)
                        put("reason", when (exit.reason) {
                            ApplicationExitInfo.REASON_CRASH -> "crash_java"
                            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
                            ApplicationExitInfo.REASON_ANR -> "anr"
                            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                            ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
                            else -> "other"
                        })
                        put("description", exit.description?.take(1024))
                        put("crashConfirmed", exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                    } }
            }.getOrNull()
        }
    }

    /** Records only app/device identity; e.g. `logs.start(ScriptRunLogs.environment(context) + revisionMetadata)`. */
    fun environment(context: Context): JsonObject = buildJsonObject {
        put("processInstance", processInstance); put("pid", Process.myPid()); put("appPackage", context.packageName)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("androidSdk", Build.VERSION.SDK_INT)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        put("appVersion", version)
    }
}
