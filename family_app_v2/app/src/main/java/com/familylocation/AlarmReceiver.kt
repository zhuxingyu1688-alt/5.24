package com.familylocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        // 静态 WakeLock：在 Receiver 里立刻抢，防止 CPU 在 Receiver→Service 之间睡回去
        @Volatile private var receiverWakeLock: PowerManager.WakeLock? = null

        fun acquireReceiverWakeLock(context: Context) {
            try {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyShare:ReceiverBridge")
                lock.setReferenceCounted(false)
                lock.acquire(90_000L) // 90秒兜底，LocationService 启动后会主动释放
                receiverWakeLock = lock
            } catch (_: Exception) {}
        }

        fun releaseReceiverWakeLock() {
            try {
                val lock = receiverWakeLock
                if (lock != null && lock.isHeld) lock.release()
            } catch (_: Exception) {}
            receiverWakeLock = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ⚡ 第一步：立刻抢 WakeLock，不能让 CPU 在这里睡回去再也不醒
        acquireReceiverWakeLock(context)

        if (!Prefs.isEnabled(context)) {
            releaseReceiverWakeLock()
            Prefs.setLastWake(context, "${Prefs.nowText()}：闹钟唤醒，但自动发送已关闭，未定位")
            return
        }

        val target = intent.getLongExtra(AlarmScheduler.EXTRA_TARGET_TIME, 0L)
        if (target <= 0L) {
            releaseReceiverWakeLock()
            Prefs.setLastWake(context, "${Prefs.nowText()}：旧闹钟被唤醒，已跳过并重新安排，未定位")
            AlarmScheduler.schedule(context)
            return
        }

        // WakeLock 已持有，交给 LocationService 接管后释放
        val serviceIntent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_SCHEDULED
            putExtra(AlarmScheduler.EXTRA_TARGET_TIME, target)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        AlarmScheduler.rescheduleForTomorrow(context)
    }
}
