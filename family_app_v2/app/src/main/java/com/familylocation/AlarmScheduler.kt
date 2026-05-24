package com.familylocation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    private const val REQUEST_CODE = 2001
    const val ACTION_DAILY_ALARM = "com.familylocation.ACTION_DAILY_ALARM"
    const val EXTRA_TARGET_TIME = "target_time_ms"

    fun schedule(ctx: Context) {
        val nextTrigger = nextTime(ctx, addDays = 0)
        setAlarm(ctx, nextTrigger)
    }

    fun rescheduleForTomorrow(ctx: Context) {
        val nextTrigger = nextTime(ctx, addDays = 1)
        setAlarm(ctx, nextTrigger)
    }

    fun cancelAll(ctx: Context) {
        // 取消主路闹钟
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cancelIntent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_DAILY_ALARM
        }
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        pi.cancel()

        // 取消备用路 WorkManager
        LocationWorker.cancel(ctx)

        Prefs.setNextTriggerAt(ctx, 0L)
    }

    private fun setAlarm(ctx: Context, triggerAtMs: Long) {
        cancelAll(ctx)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(ctx, triggerAtMs)
        Prefs.setNextTriggerAt(ctx, triggerAtMs)

        // 主路：精确闹钟（尽量准时）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                Prefs.setLastStatus(ctx, "⚠️ 未授权精确闹钟，可能延迟，建议在设置里允许「精确提醒」")
            }
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }

        // 备用路：WorkManager，在目标时间 +5 分钟后触发，主路已发则自动跳过
        LocationWorker.schedule(ctx, triggerAtMs, delayExtraMs = 5 * 60 * 1000L)
    }

    private fun buildPendingIntent(ctx: Context, targetMs: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = ACTION_DAILY_ALARM
            putExtra(EXTRA_TARGET_TIME, targetMs)
        }
        return PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextTime(ctx: Context, addDays: Int): Long {
        val hour = Prefs.getSendHour(ctx)
        val minute = Prefs.getSendMinute(ctx)
        val cal = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            if (addDays > 0) add(Calendar.DAY_OF_YEAR, addDays)
        }
        if (addDays == 0 && cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
