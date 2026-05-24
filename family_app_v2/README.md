# 家庭共享 v2.0 - 荣耀息屏保活修复版

## 这一版修了什么

### 核心修复：荣耀息屏到点不发的问题

**问题原因**：荣耀/MagicOS 在息屏时，CPU 会在 `AlarmReceiver.onReceive()` 执行完后立刻重新睡着，
导致 `LocationService` 还没来得及启动，整条链路就断了。亮屏那一刻定位图标一闪，是因为闹钟
被系统攒着，等用户亮屏才统一派发。

**修复方案**：
1. **WakeLock 提前抢**：在 `AlarmReceiver.onReceive()` 第一行就抢 CPU 唤醒锁，`LocationService`
   启动后立刻接管并释放，确保整条链路 CPU 不断电。
2. **双保险调度**：每次设置闹钟时，同时设置一个 WorkManager 备用任务（+5分钟）。
   如果主路（精确闹钟）被荣耀延迟了，WorkManager 走 JobScheduler 通道补发一次。
   如果主路正常发了，备用路检测到"今天已发"直接退出，不重复发送。
3. **防重复发送**：`LocationService` 和 `LocationWorker` 都检查"今天是否已发"，
   无论哪条路先到，后到的自动跳过。
4. **通知图标更换**：把系统定位图标换成 App 自己的图标，通知级别降到 IMPORTANCE_MIN，
   运行时状态栏不再出现醒目的定位针图标。

## 修改了哪些文件

| 文件 | 改动 |
|------|------|
| `AlarmReceiver.kt` | 新增静态 WakeLock，onReceive 第一行抢锁 |
| `AlarmScheduler.kt` | 设置闹钟时同步安排 WorkManager 备用路；修复 cancelAll |
| `LocationService.kt` | 启动时接管 WakeLock；加今日已发去重；换通知图标 |
| `LocationWorker.kt` | **新文件**，WorkManager 备用路实现 |
| `Prefs.kt` | 新增 isSentToday() 方法 |
| `AndroidManifest.xml` | 新增荣耀开机广播；WorkManager Provider |
| `app/build.gradle` | 新增 WorkManager 依赖 |
| `proguard-rules.pro` | 新增 WorkManager 混淆保留规则 |

## 荣耀手机必须设置（和之前一样，必须做）

设置 → 应用 → 应用启动管理 → 家庭共享

- 自动管理：**关闭**
- 允许自启动：**开启**
- 允许关联启动：**开启**
- 允许后台活动：**开启**

还要检查：

- 位置权限：**始终允许 + 精确位置**
- 通知权限：允许
- 闹钟和提醒/精确提醒：允许
- 电池优化：**不允许优化/无限制**

## GitHub Actions 打包

上传整个项目到 GitHub 后，进入 Actions，运行 `Build Android APK Final Pro`。

成功后优先找：
- Code 页面里的 `apk-output/FamilyShare-final-all-in-one.apk`

如果没有，就进入 Actions 成功任务底部的 Artifacts，下载：
- `FamilyShare-final-all-in-one-apk`

解压后得到 `app-debug.apk`。

## 重要限制（不变）

如果用户在系统设置里点「强行停止」App，Android 不允许 App 自己复活。必须手动打开一次 App。
