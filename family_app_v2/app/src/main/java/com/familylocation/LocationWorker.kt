package com.familylocation

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager 备用路：
 * 每次安排闹钟时同步安排一个 +5 分钟的 WorkManager 任务。
 * 如果主路（AlarmReceiver）正常触发并发送了位置，这里检测到"今天已发"直接退出。
 * 如果主路被荣耀系统压制延迟，这里作为兜底补发一次。
 */
class LocationWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // 主路已经成功发送过，不重复发
        if (Prefs.isSentToday(ctx)) {
            Prefs.setLastWake(ctx, "${Prefs.nowText()}：WorkManager 备用路到达，主路已发送，自动跳过")
            return Result.success()
        }

        if (!Prefs.isEnabled(ctx)) return Result.success()
        if (Prefs.isPausedToday(ctx)) return Result.success()

        Prefs.setLastWake(ctx, "${Prefs.nowText()}：WorkManager 备用路触发（主路未发），接管发送")

        // 启动定位服务执行发送
        val serviceIntent = Intent(ctx, LocationService::class.java).apply {
            action = LocationService.ACTION_SCHEDULED
            // 传入当前时间作为目标时间（已在备用窗口内，验证会通过）
            putExtra(AlarmScheduler.EXTRA_TARGET_TIME, System.currentTimeMillis())
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent)
            } else {
                ctx.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Prefs.setLastStatus(ctx, "❌ WorkManager 备用路启动服务失败：${e.message?.take(50)}")
            return Result.failure()
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "family_share_backup_send"

        /**
         * 安排备用任务，在 triggerAtMs + delayExtraMs 后触发
         */
        fun schedule(ctx: Context, triggerAtMs: Long, delayExtraMs: Long = 5 * 60 * 1000L) {
            try {
                val nowMs = System.currentTimeMillis()
                val delayMs = maxOf(triggerAtMs + delayExtraMs - nowMs, 60_000L)

                val request = OneTimeWorkRequestBuilder<LocationWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()

                WorkManager.getInstance(ctx)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            } catch (_: Exception) {}
        }

        fun cancel(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
            } catch (_: Exception) {}
        }
    }
}
