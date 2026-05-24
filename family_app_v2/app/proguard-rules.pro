# 保留所有广播接收器和服务（防止混淆后找不到类）
-keep class com.familylocation.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Gson / JSON
-keepattributes *Annotation*
-keepattributes Signature

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class com.familylocation.LocationWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
