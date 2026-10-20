package com.mbzmhs.anidocktv

import android.app.Application
import com.mbzmhs.anidocktv.data.AnidockApi
import com.mbzmhs.anidocktv.data.AnidockDb
import com.mbzmhs.anidocktv.data.AppProxy
import com.mbzmhs.anidocktv.data.AnidockRepository
import com.mbzmhs.anidocktv.data.EngineService
import com.mbzmhs.anidocktv.data.SettingDao
import com.mbzmhs.anidocktv.data.SettingKeys
import com.mbzmhs.anidocktv.model.HomeFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 轻量服务定位(不引入 Hilt)。App 启动即构建 api/db/repo,并从 settings 表读回用户配置。 */
object Services {
    lateinit var appContext: android.content.Context
    lateinit var api: AnidockApi
    lateinit var repo: AnidockRepository
    lateinit var settingDao: SettingDao

    /** 进程级作用域:随 app 拉起/探测下载引擎等后台动作。 */
    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 运行时可变配置(设置页修改后即时生效,并回写 settings 表)
    @Volatile var visitorPassword: String = SettingKeys.DEFAULT_PASSWORD
    @Volatile var downloadDir: String = SettingKeys.DEFAULT_DOWNLOAD_DIR
    @Volatile var enginePort: Int = SettingKeys.DEFAULT_ENGINE_PORT
    @Volatile var homeTab: String = SettingKeys.DEFAULT_HOME_TAB         // LIST | FOLLOWED
    // 两 tab 各一套独立筛选记忆(启动从 settings 读回,各自独立演化)
    @Volatile var listFilters: HomeFilters = HomeFilters()
    @Volatile var folFilters: HomeFilters = HomeFilters()
    @Volatile var bgImage: String = SettingKeys.DEFAULT_BG_IMAGE         // 背景图路径,空 = 未启用
    @Volatile var bgOpacity: Int = SettingKeys.DEFAULT_BG_OPACITY        // 0-100
}

class AnidockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 关闭本进程 file:// URI 外发检查:下载文件都在共享存储,完成后要直接把
        // file:// 路径交给 Kodi 播放(Kodi 19 对 content URI 有 _onNewIntent 崩溃 bug)。
        // 该检查只约束本进程发 intent 的行为,不涉及真实数据泄露。
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().build())
        Services.appContext = this
        val db = AnidockDb.get(this)
        val settingDao = db.settingDao()
        Services.settingDao = settingDao
        // 读回持久化设置(小表,启动时一次性 runBlocking 可接受)
        runBlocking {
            settingDao.get(SettingKeys.VISITOR_PASSWORD)?.let { Services.visitorPassword = it }
            settingDao.get(SettingKeys.DOWNLOAD_DIR)?.let { Services.downloadDir = it }
            settingDao.get(SettingKeys.ENGINE_PORT)?.toIntOrNull()?.let { Services.enginePort = it }
            settingDao.get(SettingKeys.HOME_TAB)?.let { Services.homeTab = it }
            // 两 tab 各一套独立筛选记忆(启动读回,缺失则用默认)
            Services.listFilters = settingDao.get(SettingKeys.LIST_FILTERS)?.let { HomeFilters.fromJson(it) } ?: HomeFilters()
            Services.folFilters = settingDao.get(SettingKeys.FOL_FILTERS)?.let { HomeFilters.fromJson(it) } ?: HomeFilters()
            settingDao.get(SettingKeys.BG_IMAGE)?.let { Services.bgImage = it }
            settingDao.get(SettingKeys.BG_OPACITY)?.toIntOrNull()?.let { Services.bgOpacity = it }
            // app 层代理(引擎配置缓存):引擎未就绪时首页拉 API/封面也走代理
            settingDao.get(SettingKeys.APP_PROXY)?.let { AppProxy.restore(it) }
        }
        // 背景图运行时状态(设置页修改后即时生效的 SnapshotState)
        com.mbzmhs.anidocktv.ui.BackgroundUi.path.value = Services.bgImage
        com.mbzmhs.anidocktv.ui.BackgroundUi.opacity.value = Services.bgOpacity / 100f
        val api = AnidockApi(SettingKeys.DEFAULT_BASE_URL)
        runBlocking { settingDao.get(SettingKeys.BASE_URL) }?.let { api.setBaseUrl(it) }
        Services.api = api
        Services.repo = AnidockRepository(api, db.animeDao(), settingDao, db.downloadTaskDao())
        // 引擎看门狗前台服务:顶高进程权重(电视待机不易被杀)+ 引擎进程被杀时自动拉起
        runCatching {
            startForegroundService(android.content.Intent(this, EngineWatchdogService::class.java))
        }
        // 随 app 拉起下载引擎(已运行则直接返回);引擎重启后从数据目录恢复未完成任务
        Services.scope.launch {
            runCatching { Services.repo.ensureSeenBaseline() }   // 一次性:未读蓝点基线 0→-1 归一
            runCatching { EngineService.ensureRunning(this@AnidockApp) }
            // 引擎就绪后回读真实代理配置,覆盖启动缓存(AppProxy 落一份到 settings 表)
            runCatching { EngineService.getEngineConfig()?.let { AppProxy.sync(it, settingDao) } }
            // 启动时把引擎真实状态回写 DB:出错任务可能已复活(引擎重启恢复/用户 resume 续传),
            // 纠正为进行中,界面不再显示假"出错"。
            // 修复种子拉取流程遗留(建 BT 转换没走完的行 + 引擎里孤儿种子任务,连同 .torrent 清理);
            // 先于 sync:把挂死的行挂回 BT 任务后,sync 按正常任务回写。
            runCatching { Services.repo.repairTorrentFlow() }
            // 注意:启动不做出错任务清理——出错任务留着有续传复活的机会(单击重试/引擎重启恢复);
            // 删任务/文件只走用户在剧集上长按"取消下载";设置页"清理缓存"只收孤儿文件。
            runCatching { Services.repo.syncAllDownloadTasks() }
            // 清 DB 幽灵行(completed 但文件已丢),与任务/文件无关
            runCatching { Services.repo.cleanupGhostCompletedRows() }
        }
    }
}
