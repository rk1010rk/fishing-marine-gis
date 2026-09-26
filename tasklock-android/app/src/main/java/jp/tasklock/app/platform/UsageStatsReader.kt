package jp.tasklock.app.platform

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import jp.tasklock.core.verify.UsageEvent
import jp.tasklock.core.verify.UsageTimeCalculator
import java.time.Duration
import java.time.Instant

/**
 * UsageStatsManager のラッパー。
 *
 * - queryUsageStats() の集計値はバケット境界の都合で当日分を正確に切り出せないため、
 *   queryEvents() の生イベントから前面時間を計算する（計算自体は :core の UsageTimeCalculator）。
 * - イベントの保持期間はOS依存で数日程度。当日分の検証にしか使わない前提。
 */
class UsageStatsReader(private val context: Context) {

    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun foregroundTime(packageName: String, start: Instant, end: Instant): Duration {
        if (!hasPermission()) return Duration.ZERO
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val raw = usm.queryEvents(start.toEpochMilli(), end.toEpochMilli())
        val events = ArrayList<UsageEvent>()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            val kind = when (e.eventType) {
                EVENT_ACTIVITY_RESUMED -> UsageEvent.Kind.FOREGROUND
                EVENT_ACTIVITY_PAUSED -> UsageEvent.Kind.BACKGROUND
                EVENT_SCREEN_NON_INTERACTIVE, EVENT_DEVICE_SHUTDOWN -> UsageEvent.Kind.END_ALL
                else -> null
            } ?: continue
            events += UsageEvent(Instant.ofEpochMilli(e.timeStamp), e.packageName, kind)
        }
        return UsageTimeCalculator.foregroundTime(events, packageName, start, end)
    }

    private companion object {
        // API 29 で ACTIVITY_RESUMED/PAUSED に改名されたが値は旧 MOVE_TO_FOREGROUND/BACKGROUND と同じ
        const val EVENT_ACTIVITY_RESUMED = 1
        const val EVENT_ACTIVITY_PAUSED = 2
        // API 28+ でのみ発生する。それ以前は来ないだけで害はない
        const val EVENT_SCREEN_NON_INTERACTIVE = 16
        const val EVENT_DEVICE_SHUTDOWN = 26
    }
}
