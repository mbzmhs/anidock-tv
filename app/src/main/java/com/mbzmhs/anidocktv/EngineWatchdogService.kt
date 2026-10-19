package com.mbzmhs.anidocktv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mbzmhs.anidocktv.data.EngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 下载引擎看门狗前台服务:
 * - 前台服务把 app 进程权重顶到"前台"级,降低电视关屏待机时整个进程被系统清掉的概率;
 * - 服务内巡环每 5s ping 一次引擎端口,引擎进程被系统杀掉(电视待机常见)时
 *   自动 ensureRunning 拉起——二进制与数据目录均持久,引擎启动后自动恢复未完成任务;
 * - 高负载下 ping 假超时先隔 1s 复判,防误判拉起重复进程;拉起失败退避 30s 防死循环;
 * - 若 app 进程整体被杀:电视唤醒后重新进入 app,Application.onCreate 会重新拉起服务与引擎。
 */
class EngineWatchdogService : Service() {

    companion object {
        private const val TAG = "AnidockTV"
        private const val CHANNEL_ID = "engine_watchdog"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 5_000L        // 常态巡检间隔
        private const val FAIL_TICK_MS = 30_000L  // 拉起失败后的退避间隔
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "下载引擎",
                NotificationManager.IMPORTANCE_LOW,   // 无声无光,仅角标
            ).apply { description = "下载引擎看门狗:引擎被系统杀掉时自动拉起" }
        )
        startForeground(NOTIF_ID, buildNotification(nm, "下载引擎运行中"))

        loopJob = scope.launch {
            while (isActive) {
                if (!engineDead()) {
                    delay(TICK_MS)
                    continue
                }
                Log.w(TAG, "watchdog: 引擎进程丢失,拉起中")
                notifyUser(nm, "下载引擎异常,正在拉起…")
                val ok = runCatching { EngineService.ensureRunning(this@EngineWatchdogService) }
                    .getOrDefault(false)
                if (ok) {
                    Log.w(TAG, "watchdog: 引擎拉起成功")
                    notifyUser(nm, "下载引擎运行中")
                    delay(TICK_MS)
                } else {
                    Log.e(TAG, "watchdog: 引擎拉起失败,30s 后重试")
                    notifyUser(nm, "下载引擎异常,重试中…")
                    delay(FAIL_TICK_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** 判定引擎已死:连续两次 ping 都失败(隔 1s 复判,过滤 TV 高负载下的 ping 假超时)。 */
    private suspend fun engineDead(): Boolean {
        if (EngineService.isRunning()) return false
        delay(1_000)
        return !EngineService.isRunning()
    }

    private fun buildNotification(nm: NotificationManager, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AniDock TV")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .build()
    }

    private fun notifyUser(nm: NotificationManager, text: String) {
        runCatching { nm.notify(NOTIF_ID, buildNotification(nm, text)) }
    }
}
