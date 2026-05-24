package com.familylocation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LocationService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "family_share_channel"
        const val NOTIF_ID = 7001
        const val ACTION_MANUAL = "com.familylocation.ACTION_MANUAL_SEND"
        const val ACTION_SCHEDULED = "com.familylocation.ACTION_SCHEDULED_SEND"

        private const val ALLOW_EARLY_MS = 2 * 60 * 1000L
        private const val ALLOW_LATE_MS = 30 * 60 * 1000L  // 备用路到达时也在这个窗口内

        private const val LOCATION_TIMEOUT_MS = 120_000L
        private const val GOOD_ACCURACY_METERS = 30f
        private const val MAX_ACCEPT_ACCURACY_METERS = 80f
        private const val MAX_LOCATION_AGE_MS = 2 * 60 * 1000L
    }

    private enum class StartMode { MANUAL, SCHEDULED, SKIP }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚡ 第一步：接管 AlarmReceiver 里抢到的 WakeLock，防止 CPU 在 Receiver→Service 间睡掉
        AlarmReceiver.releaseReceiverWakeLock()

        val mode = validateStart(intent)
        if (mode == StartMode.SKIP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        acquireWakeLock()

        scope.launch {
            try {
                if (!isAnyLocationProviderEnabled()) {
                    val msg = "⚠️ 家庭共享位置失败\n原因：手机定位总开关未开启\n处理：请下拉状态栏打开「位置信息/GPS」，再等待下次定时或手动测试。\n时间：${fullTime()}"
                    postText(msg)
                    Prefs.setLastStatus(applicationContext, "⚠️ 定位开关关闭，已提醒飞书")
                    return@launch
                }

                Prefs.setLastLocationAccess(applicationContext, "${Prefs.nowText()}：${if (mode == StartMode.MANUAL) "手动测试" else "定时发送"}开始访问定位")
                Prefs.setLocationAttempts(applicationContext, 0)
                Prefs.setLastStatus(applicationContext, "📡 正在搜索 GPS 高精度位置，最多等待 2 分钟…")

                val loc = withTimeoutOrNull(LOCATION_TIMEOUT_MS + 10_000L) { getPreciseNativeLocation() }
                if (loc == null) {
                    val msg = "⚠️ 家庭共享位置失败\n原因：2 分钟内未获得足够精确的位置\n建议：打开精确位置，靠近窗边/室外，保持网络和 GPS 可用。\n时间：${fullTime()}"
                    postText(msg)
                    Prefs.setLastStatus(applicationContext, "⚠️ 精度不足/超时，未发送错误位置，已提醒飞书")
                    return@launch
                }

                val address = reverseGeocode(loc.latitude, loc.longitude)
                val result = postLocation(loc, address)
                Prefs.setLastSent(applicationContext, SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()))
                Prefs.setLastStatus(
                    applicationContext,
                    if (result.first) "✅ 发送成功（GPS 精度约 ${loc.accuracy.toInt()} 米）" else "❌ 飞书未接收：${result.second.take(60)}"
                )
            } catch (e: Exception) {
                Prefs.setLastStatus(applicationContext, "❌ ${e.message?.take(60) ?: "发送异常"}")
            } finally {
                releaseWakeLock()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun validateStart(intent: Intent?): StartMode {
        val action = intent?.action.orEmpty()

        if (action == ACTION_MANUAL) {
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：手动测试启动，允许定位")
            return StartMode.MANUAL
        }

        if (action != ACTION_SCHEDULED) {
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：未知/旧服务唤醒，已跳过，未访问定位")
            return StartMode.SKIP
        }

        if (!Prefs.isEnabled(applicationContext)) {
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：定时唤醒，但自动发送已关闭，未访问定位")
            return StartMode.SKIP
        }

        if (Prefs.isPausedToday(applicationContext)) {
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：今日暂停，已跳过，未访问定位")
            Prefs.setLastStatus(applicationContext, "⏸️ 今日暂停：未访问定位，未发送")
            return StartMode.SKIP
        }

        // 防重复：主路+备用路都会到这里，今天已发就直接跳过
        if (Prefs.isSentToday(applicationContext)) {
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：今日已发送过，跳过重复（主路/备用路去重）")
            return StartMode.SKIP
        }

        val target = intent?.getLongExtra(AlarmScheduler.EXTRA_TARGET_TIME, 0L) ?: 0L
        if (target <= 0L) {
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：旧闹钟无目标时间，已跳过，未访问定位")
            return StartMode.SKIP
        }

        val now = System.currentTimeMillis()
        val inWindow = now >= target - ALLOW_EARLY_MS && now <= target + ALLOW_LATE_MS
        if (!inWindow) {
            val targetText = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(target))
            Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：非预定时间唤醒，目标 $targetText，已跳过，未访问定位")
            Prefs.setLastStatus(applicationContext, "🛡️ 已拦截一次非预定时间唤醒：未访问定位")
            return StartMode.SKIP
        }

        Prefs.setLastWake(applicationContext, "${Prefs.nowText()}：定时任务到点启动，允许定位")
        return StartMode.SCHEDULED
    }

    private fun isAnyLocationProviderEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyShare:preciseOnce").apply {
                setReferenceCounted(false)
                acquire(LOCATION_TIMEOUT_MS + 20_000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }

    @Suppress("MissingPermission")
    private suspend fun getPreciseNativeLocation(): Location? = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        var best: Location? = null
        var finished = false
        var attempts = 0
        lateinit var listener: LocationListener

        fun isProviderEnabled(provider: String): Boolean = try { locationManager.isProviderEnabled(provider) } catch (_: Exception) { false }

        fun finish(location: Location?) {
            if (finished || !cont.isActive) return
            finished = true
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            cont.resume(location)
        }

        fun candidate(location: Location?) {
            if (location == null || finished || !cont.isActive) return
            attempts += 1
            Prefs.setLocationAttempts(applicationContext, attempts)
            if (!isFresh(location) || !location.hasAccuracy()) return
            best = betterOf(best, location)
            val b = best
            if (b != null && b.accuracy <= GOOD_ACCURACY_METERS) finish(b)
        }

        fun finishAtTimeout() {
            val b = best
            if (b != null && b.hasAccuracy() && b.accuracy <= MAX_ACCEPT_ACCURACY_METERS && isFresh(b)) finish(b) else finish(null)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { candidate(location) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).forEach { p ->
            try { candidate(locationManager.getLastKnownLocation(p)) } catch (_: Exception) {}
        }

        val providers = mutableListOf<String>()
        if (isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
        if (isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
        if (providers.isEmpty()) {
            finish(null)
            return@suspendCancellableCoroutine
        }

        providers.forEach { provider ->
            try { locationManager.requestLocationUpdates(provider, 2500L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
        }

        mainHandler.postDelayed({ finishAtTimeout() }, LOCATION_TIMEOUT_MS)
        cont.invokeOnCancellation {
            finished = true
            try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        }
    }

    private fun isFresh(location: Location): Boolean = System.currentTimeMillis() - location.time <= MAX_LOCATION_AGE_MS

    private fun betterOf(a: Location?, b: Location?): Location? {
        if (a == null) return b
        if (b == null) return a
        if (!isFresh(a) && isFresh(b)) return b
        if (isFresh(a) && !isFresh(b)) return a
        if (!a.hasAccuracy() && b.hasAccuracy()) return b
        if (a.hasAccuracy() && !b.hasAccuracy()) return a
        return if (b.accuracy < a.accuracy) b else a
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lng: Double): String = try {
        val geocoder = android.location.Geocoder(this, Locale.CHINA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val deferred = CompletableDeferred<String>()
            geocoder.getFromLocation(lat, lng, 1) { list -> deferred.complete(list.firstOrNull()?.getAddressLine(0) ?: "") }
            withTimeoutOrNull(4_000) { deferred.await() } ?: ""
        } else {
            geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.getAddressLine(0) ?: ""
        }
    } catch (_: Exception) { "" }

    private fun postLocation(loc: Location, address: String): Pair<Boolean, String> {
        val time = fullTime()
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val gcj = wgs84ToGcj02(loc.latitude, loc.longitude)
        val amapUrl = "https://uri.amap.com/marker?position=${gcj.second},${gcj.first}&name=家庭共享位置"
        val attempts = Prefs.getLocationAttempts(applicationContext)

        val text = "📍 家庭共享位置\n" +
            "时间：$time\n" +
            "设备：$device\n" +
            "地址：${if (address.isBlank()) "未解析到地址" else address}\n" +
            "GPS坐标：${"%.6f".format(Locale.US, loc.latitude)}, ${"%.6f".format(Locale.US, loc.longitude)}\n" +
            "GPS精度：约 ${loc.accuracy.toInt()} 米\n" +
            "定位尝试：$attempts 次\n" +
            "高德地图：$amapUrl"

        return postJson(text, loc, address, amapUrl, time, device)
    }

    private fun postText(text: String): Pair<Boolean, String> = postJson(text, null, "", "", fullTime(), "${Build.MANUFACTURER} ${Build.MODEL}")

    private fun postJson(text: String, loc: Location?, address: String, mapUrl: String, time: String, device: String): Pair<Boolean, String> {
        val url = Prefs.getWebhookUrl(applicationContext)
        if (url.isBlank()) return false to "Webhook 为空"

        val body = if (isFeishuWebhook(url)) {
            JSONObject().apply {
                put("msg_type", "text")
                put("content", JSONObject().apply { put("text", text) })
            }.toString()
        } else {
            JSONObject().apply {
                if (loc != null) {
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("accuracy", loc.accuracy)
                }
                put("address", address)
                put("time", time)
                put("device", device)
                put("map_url", mapUrl)
                put("text", text)
            }.toString()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("User-Agent", "FamilyShare/4.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val res = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@use false to "HTTP ${response.code}: $res"
                if (!isFeishuWebhook(url)) return@use true to "ok"
                val ok = res.contains("\"code\":0") || res.contains("success", ignoreCase = true)
                ok to if (ok) "ok" else res.ifBlank { "飞书返回异常" }
            }
        } catch (e: Exception) {
            false to (e.message ?: "网络异常")
        }
    }

    private fun isFeishuWebhook(url: String): Boolean = url.contains("open.feishu.cn/open-apis/bot", ignoreCase = true)

    private fun fullTime(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "家庭共享", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "后台同步"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
            )
        }
    }

    private fun buildNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        // 使用 App 自己的图标，不用系统定位图标，避免状态栏出现显眼的定位针
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("家庭共享")
        .setContentText("后台同步中…")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setShowWhen(false)
        .setSilent(true)
        .build()

    private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean = lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
