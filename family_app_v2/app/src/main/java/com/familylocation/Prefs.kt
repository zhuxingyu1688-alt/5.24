package com.familylocation

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val NAME = "fl_prefs"
    private const val KEY_URL = "webhook_url"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PENDING_ENABLE = "pending_enable"
    private const val KEY_LAST_SENT = "last_sent"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_SEND_HOUR = "send_hour"
    private const val KEY_SEND_MINUTE = "send_minute"
    private const val KEY_NEXT_TRIGGER = "next_trigger"
    private const val KEY_LAST_WAKE = "last_wake"
    private const val KEY_LAST_LOCATION_ACCESS = "last_location_access"
    private const val KEY_LOCATION_ATTEMPTS = "location_attempts"
    private const val KEY_PAUSE_DATE = "pause_date"
    private const val KEY_LAST_BOOT_RESTORE = "last_boot_restore"

    private const val DEFAULT_FEISHU_URL = "https://open.feishu.cn/open-apis/bot/v2/hook/8731bf5d-87c8-400f-836f-3dd3c159a6f2"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getWebhookUrl(ctx: Context): String = sp(ctx).getString(KEY_URL, DEFAULT_FEISHU_URL) ?: DEFAULT_FEISHU_URL
    fun setWebhookUrl(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_URL, url).apply()

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)
    fun setEnabled(ctx: Context, enabled: Boolean) = sp(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun isPendingEnable(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PENDING_ENABLE, false)
    fun setPendingEnable(ctx: Context, pending: Boolean) = sp(ctx).edit().putBoolean(KEY_PENDING_ENABLE, pending).apply()

    fun getLastSent(ctx: Context): String = sp(ctx).getString(KEY_LAST_SENT, "") ?: ""
    fun setLastSent(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_SENT, value).apply()

    fun getLastStatus(ctx: Context): String = sp(ctx).getString(KEY_LAST_STATUS, "") ?: ""
    fun setLastStatus(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_STATUS, value).apply()

    fun getSendHour(ctx: Context): Int = sp(ctx).getInt(KEY_SEND_HOUR, 6).coerceIn(0, 23)
    fun getSendMinute(ctx: Context): Int = sp(ctx).getInt(KEY_SEND_MINUTE, 0).coerceIn(0, 59)
    fun setSendTime(ctx: Context, hour: Int, minute: Int) {
        sp(ctx).edit()
            .putInt(KEY_SEND_HOUR, hour.coerceIn(0, 23))
            .putInt(KEY_SEND_MINUTE, minute.coerceIn(0, 59))
            .apply()
    }

    fun getSendTimeText(ctx: Context): String = String.format(Locale.US, "%02d:%02d", getSendHour(ctx), getSendMinute(ctx))

    fun setNextTriggerAt(ctx: Context, triggerAtMs: Long) = sp(ctx).edit().putLong(KEY_NEXT_TRIGGER, triggerAtMs).apply()
    fun getNextTriggerAt(ctx: Context): Long = sp(ctx).getLong(KEY_NEXT_TRIGGER, 0L)
    fun getNextTriggerText(ctx: Context): String {
        val t = getNextTriggerAt(ctx)
        if (t <= 0L) return "未安排"
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(t))
    }

    fun setLastWake(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_WAKE, value).apply()
    fun getLastWake(ctx: Context): String = sp(ctx).getString(KEY_LAST_WAKE, "") ?: ""

    fun setLastLocationAccess(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_LOCATION_ACCESS, value).apply()
    fun getLastLocationAccess(ctx: Context): String = sp(ctx).getString(KEY_LAST_LOCATION_ACCESS, "") ?: ""

    fun setLocationAttempts(ctx: Context, attempts: Int) = sp(ctx).edit().putInt(KEY_LOCATION_ATTEMPTS, attempts).apply()
    fun getLocationAttempts(ctx: Context): Int = sp(ctx).getInt(KEY_LOCATION_ATTEMPTS, 0)

    fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    fun isPausedToday(ctx: Context): Boolean = sp(ctx).getString(KEY_PAUSE_DATE, "") == todayKey()
    fun setPauseToday(ctx: Context, pause: Boolean) {
        val editor = sp(ctx).edit()
        if (pause) editor.putString(KEY_PAUSE_DATE, todayKey()) else editor.remove(KEY_PAUSE_DATE)
        editor.apply()
    }

    fun setLastBootRestore(ctx: Context, value: String) = sp(ctx).edit().putString(KEY_LAST_BOOT_RESTORE, value).apply()
    fun getLastBootRestore(ctx: Context): String = sp(ctx).getString(KEY_LAST_BOOT_RESTORE, "") ?: ""

    fun nowText(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    /**
     * 判断今天是否已经成功发送过（用于防止主路+备用路重复发送）
     * lastSent 格式是 "MM-dd HH:mm"，取前5位和今天比较
     */
    fun isSentToday(ctx: Context): Boolean {
        val lastSent = getLastSent(ctx)
        if (lastSent.isBlank()) return false
        val today = SimpleDateFormat("MM-dd", Locale.CHINA).format(Date())
        return lastSent.startsWith(today)
    }
}
