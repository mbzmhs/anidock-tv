package com.mbzmhs.anidocktv.data

import android.content.Context
import android.util.Base64
import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.model.EpisodeView
import com.mbzmhs.anidocktv.model.Anime
import com.mbzmhs.anidocktv.model.Release
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 数据仓库:
 * - 番剧列表:按需从 anidock API 分页拉取(year/quarter 筛选,limit/offset 分页),不整库同步。
 * - 追番:本地 Room 持久化(用户在列表里关注某番剧时 upsert,取消时标记)。
 * - 详情/剧集:按需从 API 拉取。
 * - 下载:种子/磁链 → ZDownloader 引擎 BT 任务;关联表(番剧→剧集→种子→任务)记进度供详情页展示。
 */
class AnidockRepository(
    private val api: AnidockApi,
    private val dao: AnimeDao,
    private val settingDao: SettingDao,
    private val downloadDao: DownloadTaskDao,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    /** 下载建任务的锁:按"番剧id:集数"一个条目一把锁。每个剧集条目独立串行(查重+建原子),
     *  不同剧集条目并发。防止快速连点同一集并发建出同 infoHash 的重复任务。 */
    private val dlLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    /** 登录失败标记:失败后不再重试(避免 429 死循环),用户改密码/地址后重置。 */
    @Volatile var loginError: String = ""
        private set

    /** 未登录则用设置里的访客密码自动登录;失败后锁定不再重试。 */
    suspend fun autoLogin() {
        if (api.token.isNotEmpty()) return
        if (loginError.isNotEmpty()) return  // 已失败,不再重试
        try {
            api.login(com.mbzmhs.anidocktv.Services.visitorPassword)
            loginError = ""
        } catch (e: Exception) {
            loginError = e.message ?: "登录失败"
        }
    }

    /** 用户主动改密码/地址后调用,重置失败锁。 */
    fun resetLoginError() {
        loginError = ""
    }

    /** 设置项读写(设置页)。 */
    suspend fun string(key: String, default: String): String = settingDao.get(key) ?: default
    suspend fun set(key: String, value: String) = settingDao.put(SettingEntity(key, value))

    /** 清除本地追番数据 + 下载关联(设置页;不动引擎里的任务)。 */
    suspend fun wipeFollowed() {
        dao.clear()
        downloadDao.clear()
    }

    // ---------- 下载(番剧→剧集→种子→BT 任务 关联) ----------

    /** 某番剧的下载任务列表(详情页剧集按钮状态)。 */
    suspend fun downloadTasks(animeId: Long): List<DownloadTaskEntity> = downloadDao.byAnime(animeId)

    /**
     * 开始下载某来源:服务器一般给种子文件链接 → 引擎 HTTP 引擎下载 .torrent(多线程+全局代理,
     * 拉完由 syncDownloadTasks 建 BT;磁链直接建 BT),保存目录 = 下载根目录/番剧名,关联落库。
     * 返回引擎任务 id(种子下载阶段是 HTTP 任务 id,建 BT 后换成 BT 任务 id)。
     */
    suspend fun startDownload(context: Context, anime: Anime, rel: Release): String {
        if (!EngineService.ensureRunning(context)) error("下载引擎启动失败")
        // 未追番也能下载:确保本地有番剧行,否则侧边栏查不到标题(显示"番剧#id")。
        // 只在无行时建(followed=false,不强制追番);已有行(追没追)绝不动,不丢追番状态。
        if (dao.byAnidockId(anime.id) == null) dao.upsert(anime.toEntity())
        val ep = rel.episode ?: 0f
        val relTitle = rel.title.ifBlank { rel.groupName }
        // 条目锁:同一集同一来源一把锁,条目内串行(查重+占位+建任务原子),不同集并发
        val lock = dlLocks.getOrPut("${anime.id}:$ep:$relTitle") { Mutex() }
        return lock.withLock {
            // 查重:已有进行中(含占位的 fetching/creating)就返回,不重复建
            val existing = downloadDao.byAnime(anime.id).find {
                it.episode == ep && it.releaseTitle == relTitle && it.status in ACTIVE_DL_STATUS
            }
            if (existing != null) {
                android.util.Log.w("AnidockTV", "startDownload: 已有进行中,跳过重复建 ep=$ep status=${existing.status} taskId=${existing.taskId}")
                return@withLock existing.taskId
            }
            // 下载链选择(与后端 RSS 同逻辑):优先服务器镜像(稳定,不被上游站点拦截)→ 原下载链;
            // rel.url 是原详情页链接(HTML),绝不能当种子。magnet 直接用。
            val dl = rel.downloadUrl
            val url = when {
                dl.startsWith("magnet:") -> dl
                dl.isNotBlank() && rel.mirrorPath.isNotBlank() -> "${Services.api.currentBaseUrl}/mirror/${rel.mirrorPath}"
                else -> dl
            }
            require(url.isNotBlank()) { "该来源没有种子/磁力链接" }
            val title = anime.title.ifBlank { "anime${anime.id}" }
            // 引擎直接下最终目录(去私有工作区):dir = 下载根目录/<番剧名>
            val dir = File(Services.downloadDir, title).apply { mkdirs() }
            // 换源/重下:同一集之前的文件先删(成片+半成品+种子 .torrent;合集=整目录树),
            // 旧源文件不会被子任务覆盖
            runCatching {
                for (old in downloadDao.byAnime(anime.id).filter { it.episode == ep }) {
                    deleteEpisodeFiles(old.fileDir, old.fileName)
                }
            }
            // 占位行:状态 fetching。整个生命周期被状态机盖住,后续重复点击被查重拦下。
            val now = System.currentTimeMillis()
            downloadDao.upsert(
                DownloadTaskEntity(
                    anidockId = anime.id, episode = ep, releaseTitle = relTitle,
                    url = url, taskId = "", status = "fetching",
                    size = 0, downloaded = 0, fileDir = dir.absolutePath, fileName = "",
                    createdAt = now, updatedAt = now,
                )
            )
            try {
                if (url.startsWith("magnet:")) {
                    // 磁链:无种子下载阶段,直接建 BT 任务
                    downloadDao.upsert(
                        DownloadTaskEntity(
                            anidockId = anime.id, episode = ep, releaseTitle = relTitle,
                            url = url, taskId = "", status = "creating",
                            size = 0, downloaded = 0, fileDir = dir.absolutePath, fileName = "",
                            createdAt = now, updatedAt = System.currentTimeMillis(),
                        )
                    )
                    val task = EngineService.createBtTask(url, null, dir.absolutePath) ?: error("引擎创建任务失败")
                    downloadDao.upsert(
                        DownloadTaskEntity(
                            anidockId = anime.id, episode = ep, releaseTitle = relTitle,
                            url = url, taskId = task.id, status = mapZdlStatus(task.status),
                            size = task.size, downloaded = task.arrived,
                            fileDir = dir.absolutePath, fileName = task.filename,
                            createdAt = now, updatedAt = System.currentTimeMillis(),
                        )
                    )
                    task.id
                } else {
                    // .torrent 链接:引擎 HTTP 引擎下载种子(多线程+全局代理),拉完由 syncDownloadTasks 建 BT
                    val httpTask = EngineService.createHttpTask(url, dir.absolutePath, "", "")
                        ?: error("建种子下载任务失败")
                    downloadDao.upsert(
                        DownloadTaskEntity(
                            anidockId = anime.id, episode = ep, releaseTitle = relTitle,
                            url = url, taskId = httpTask.id, status = "fetching",
                            size = httpTask.size, downloaded = httpTask.downloaded,
                            fileDir = dir.absolutePath, fileName = httpTask.filename,
                            createdAt = now, updatedAt = System.currentTimeMillis(),
                        )
                    )
                    httpTask.id
                }
            } catch (e: Exception) {
                // 出错:删占位行(fetching/creating),不留痕迹
                downloadDao.byAnime(anime.id).filter {
                    it.episode == ep && it.releaseTitle == relTitle && it.status in setOf("fetching", "creating")
                }.forEach { downloadDao.deleteRow(it.id) }
                throw e
            }
        }
    }

    /** 引擎任务进度回写关联表(详情页 3s 轮询调用);引擎未运行时 listTasks 返回空,保持本地状态。
     *  同步范围含 error 行:引擎侧出错任务可能被治愈(如待机杀引擎后人工/自动 resume 续传),
     *  不把它纳入回写,详情页会永远显示"出错"而任务实际在跑。 */
    suspend fun syncDownloadTasks(animeId: Long) {
        val active = downloadDao.byAnime(animeId).filter { it.taskId.isNotEmpty() && it.status in SYNC_DL_STATUS }
        if (active.isEmpty()) return
        val n = syncTaskRows(active)
        android.util.Log.d("AnidockTV", "sync: 活跃行=${active.size} 回写=$n")
    }

    /** 全部需回写任务回写引擎进度(侧边栏 3s 轮询调用,含 error 自愈行);引擎未运行时 listTasks 返回空,保持本地状态。 */
    suspend fun syncAllDownloadTasks() {
        val rows = downloadDao.syncable()
        if (rows.isEmpty()) return
        syncTaskRows(rows)
    }

    /**
     * 详情页/侧边栏共用的同步核心:拉一次引擎任务列表,逐行回写,返回回写行数。
     * 不用 isRunning() ping 做门槛:TV 高负载下 1s ping 易超时导致整轮同步被跳过,
     * 且阻塞调用会卡主线程。直接拉任务列表(引擎未运行时快速返回空)。
     */
    private suspend fun syncTaskRows(rows: List<DownloadTaskEntity>): Int {
        val all = EngineService.listTasks()
        if (all.isEmpty()) return 0
        val tasks = all.associateBy { it.id }
        val now = System.currentTimeMillis()
        var updated = 0
        for (r in rows) {
            val t = tasks[r.taskId] ?: continue
            // 种子下载阶段(引擎 HTTP 任务拉 .torrent):拉完建 BT 任务(手动控制,不用引擎 autoOpenTorrent)。
            // 按任务 kind 而非行状态分支:行状态被进程中途被杀/出错重试带偏成 creating/error 时,
            // 只要仍引用 HTTP 任务就还能完成转换——否则种子任务挂死在引擎(重启后持久化复活)、
            // .torrent 永不清理(实测事故:引擎重启窗口期 deleteTask 失败,8 个种子文件残留)。
            if (t.kind == "http") {
                if (t.status == "completed") {
                    // 每任务锁+重读行:详情/侧边栏两路轮询可能同时看到同一 completed 任务,串行防重复建 BT
                    dlLocks.getOrPut("httpconv:${r.taskId}") { Mutex() }.withLock {
                        val cur = downloadDao.byAnime(r.anidockId).firstOrNull { it.id == r.id } ?: return@withLock
                        if (cur.taskId != r.taskId) return@withLock   // 另一轮已完成转换(行已不再引用该种子任务)
                        val b64 = runCatching { File(cur.fileDir, t.filename).readBytes() }.getOrNull()
                            ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                        val bt = b64?.let { EngineService.createBtTask(null, it, cur.fileDir) }
                        if (bt != null) {
                            // 顺序关键:先把行挂到 BT 任务(此后即使进程被杀,行仍指向 BT 任务,启动修复能收尾),
                            // 再删种子任务(file=1 连同 .torrent)——反序会让行引用一个即将消失的任务
                            downloadDao.upsert(
                                cur.copy(taskId = bt.id, status = mapZdlStatus(bt.status), size = bt.size,
                                    downloaded = bt.arrived, fileName = bt.filename, updatedAt = now)
                            )
                        } else {
                            downloadDao.upsert(cur.copy(status = "error", updatedAt = now))
                        }
                        EngineService.deleteTask(t.id, true)
                        runCatching { File(cur.fileDir, t.filename).delete() }   // .torrent 兜底删(建完 BT 即删,不留下载目录)
                    }
                } else if (t.status == "error" || t.status == "canceled") {
                    // 拉取失败:保留任务(与 BT 出错态同口径,重试可 resume 重拉),只回写行状态
                    downloadDao.upsert(r.copy(status = "error", updatedAt = now))
                } else {
                    // 种子还在下,回写进度+状态(出错行重试 resume 后能反映真实状态)
                    downloadDao.upsert(
                        r.copy(status = mapZdlStatus(t.status), size = t.size, downloaded = t.downloaded, speed = t.speed,
                            progress = if (t.size > 0) (t.downloaded.toFloat() / t.size).coerceIn(0f, 1f) else 0f,
                            updatedAt = now)
                    )
                }
                updated++
                continue
            }
            // BT 任务(含 choosing=多文件合集待选文件):choosing 行回写后剧集按钮显示"待选文件",
            // 单击/长按可进合集文件列表对话框(chooseFiles 落定后引擎转 waiting 开始下载)。
            // BT 产物:引擎直写最终目录,completed 即最终态,无需搬文件
            val done = if (t.kind == "bt" && t.arrived > 0) t.arrived else t.downloaded
            val st = mapZdlStatus(t.status)
            val progress = if (st == "completed") 1f else if (t.size > 0) (done.toFloat() / t.size).coerceIn(0f, 1f) else 0f
            downloadDao.upsert(
                r.copy(
                    status = st,
                    progress = progress,
                    size = t.size,
                    downloaded = done,
                    speed = t.speed,
                    fileName = t.filename.ifBlank { r.fileName },
                    updatedAt = now,
                )
            )
            updated++
        }
        return updated
    }

    /**
     * 启动修复种子拉取流程 + 清孤儿种子任务(只动 HTTP 种子任务和 .torrent 文件,
     * 绝不碰 BT 任务与成片):
     * 1. 行仍引用已 completed 的 HTTP 种子任务(建 BT 转换没走完:进程中途被杀、
     *    引擎重启窗口期 deleteTask 失败等)→ 种子文件还在盘上就补建 BT 任务、行挂回,
     *    继续正常下载;文件丢了就把行标 error(重试会重拉)。
     * 2. 无任何行引用的终态 HTTP 任务(引擎持久化落盘后每次重启都会复活)→
     *    删任务连同 .torrent,不再滞留下载目录。
     */
    suspend fun repairTorrentFlow() {
        val all = EngineService.listTasks()
        if (all.isEmpty()) return
        val tasks = all.associateBy { it.id }
        val rows = downloadDao.all()
        val now = System.currentTimeMillis()
        for (r in rows) {
            if (r.taskId.isEmpty()) continue
            val t = tasks[r.taskId] ?: continue
            if (t.kind != "http" || t.status != "completed") continue
            dlLocks.getOrPut("httpconv:${r.taskId}") { Mutex() }.withLock {
                val cur = downloadDao.byAnime(r.anidockId).firstOrNull { it.id == r.id } ?: return@withLock
                if (cur.taskId != r.taskId) return@withLock
                val file = File(cur.fileDir, t.filename)
                val b64 = if (file.exists()) {
                    runCatching { file.readBytes() }.getOrNull()
                        ?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                } else null
                val bt = b64?.let { EngineService.createBtTask(null, it, cur.fileDir) }
                if (bt != null) {
                    // 与普通路径同序:行先挂 BT 任务,再删种子任务+文件
                    downloadDao.upsert(
                        cur.copy(taskId = bt.id, status = mapZdlStatus(bt.status), size = bt.size,
                            downloaded = bt.arrived, fileName = bt.filename, updatedAt = now)
                    )
                } else if (cur.status != "error") {
                    // 种子文件已丢、无法补建:行标 error(单击重试走完整重拉流程)
                    downloadDao.upsert(cur.copy(status = "error", updatedAt = now))
                }
                EngineService.deleteTask(t.id, true)
                runCatching { file.delete() }
            }
        }
        // 孤儿种子任务:终态且没有任何行引用 → 删任务(连同 .torrent)。
        // 只删终态:刚创建还没落行的任务在 running/waiting,不会被误杀。
        val referenced = rows.map { it.taskId }.filter { it.isNotEmpty() }.toSet()
        for (t in all) {
            if (t.kind == "http" && t.status in setOf("completed", "error", "canceled") && t.id !in referenced) {
                EngineService.deleteTask(t.id, true)
            }
        }
        // 卡死占位行:进程在建任务前被杀(taskId 为空,引擎里没有任何任务)→ 标 error,
        // 单击重试会走完整重拉流程;不修的话行永远停在 fetching/creating
        for (r in rows) {
            if (r.taskId.isEmpty() && r.status in setOf("fetching", "creating")) {
                downloadDao.upsert(r.copy(status = "error", updatedAt = now))
            }
        }
    }

    /**
     * 重试出错的任务(详情页单击):引擎侧任务还在 → resume(从盘上分片续传,最无损;
     * 引擎 Resume 支持 error/canceled/paused 态)。引擎侧任务已消失或无任务记录 →
     * 返回 false,调用方回退完整流程(最优来源重拉种子+建任务)。
     */
    suspend fun retryDownload(animeId: Long, episode: Float): Boolean {
        val row = downloadDao.byAnime(animeId)
            .firstOrNull { it.episode == episode && it.status == "error" && it.taskId.isNotEmpty() }
            ?: return false
        val t = EngineService.listTasks().firstOrNull { it.id == row.taskId } ?: return false
        // 已在进行中/已完成:无需动作,等 sync 把真实状态回写即可
        if (t.status != "error" && t.status != "canceled" && t.status != "paused" && t.status != "choosing") return true
        return EngineService.actionTask(row.taskId, "resume")
    }

    /** 取消下载(菜单里唯一的删除入口):删引擎任务(带文件)、清最终目录与工作区残留、
     *  同集多行全删,不留痕迹。旧实现只 cancelTask(任务还挂在引擎里 canceled)+删一行
     *  TV 行,.mp4/.!zd/.zdl.part 全留着。 */
    suspend fun cancelDownload(row: DownloadTaskEntity) {
        // 删引擎任务(file=1 连 .torrent 一并删);同集多行全删,不留任何 TV 行痕迹。
        if (row.taskId.isNotEmpty()) EngineService.deleteTask(row.taskId, true)
        deleteEpisodeFiles(row.fileDir, row.fileName)
        downloadDao.byAnime(row.anidockId).filter { it.episode == row.episode }
            .forEach { downloadDao.deleteRow(it.id) }
    }

    /** 短按切换 暂停/继续。成功返回 true;调用方随后刷新关联表(状态以引擎回写为准)。 */
    suspend fun togglePauseDownload(row: DownloadTaskEntity): Boolean {
        if (row.taskId.isEmpty()) return false
        val action = if (row.status == "paused") "resume" else "pause"
        return EngineService.actionTask(row.taskId, action)
    }

    /** 递归删除文件/目录树(合集任务的产物是整个目录;单文件退化为普通删除)。 */
    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        runCatching { f.delete() }
    }

    /** 删除某集的旧产物(换源重下/取消下载共用):合集=整目录树,单文件=成片+半成品。 */
    private fun deleteEpisodeFiles(dir: String, fileName: String) {
        if (dir.isBlank() || fileName.isBlank()) return
        try {
            val f = File(dir, fileName)
            if (f.isDirectory) {
                deleteTree(f)
            } else {
                f.delete()
                File(dir, fileName + ".zdl.part").delete()
                File(dir, fileName + ".!zd").delete()
            }
        } catch (_: Exception) {
        }
    }

    /** 需展示/回写的任务(含番剧名),侧边栏任务列表用:进行中 + 出错(出错一目了然,可就地重试)。 */
    suspend fun activeDownloadTasks(): List<DownloadTaskView> {
        syncAllDownloadTasks()
        val rows = downloadDao.syncable()
        if (rows.isEmpty()) return emptyList()
        return rows.map { r ->
            val title = dao.byAnidockId(r.anidockId)?.title ?: "番剧#${r.anidockId}"
            DownloadTaskView(r, title)
        }
    }

    /**
     * 缓存使用量(字节):孤儿半成品 .!zd/.zdl.part + 图片缓存。
     * 引擎里还存在的任务(含出错可续传的)的文件不计入——那不是"可清理的缓存"。
     */
    suspend fun cacheUsage(): Long = withContext(Dispatchers.IO) {
        val knownNames = knownEngineFileNames()
        var total = 0L
        // 引擎直写最终目录,孤儿半成品 .!zd/.zdl.part 直接落在下载根目录
        File(Services.downloadDir).walkTopDown().forEach { f ->
            if (f.isFile && (f.name.endsWith(".!zd") || f.name.endsWith(".zdl.part"))) {
                val base = f.name.removeSuffix(".!zd").removeSuffix(".zdl.part")
                if (base !in knownNames) total += f.length()
            }
        }
        File(Services.appContext.cacheDir, "image_cache").listFiles()
            ?.forEach { total += it.length() }
        total
    }

    /** 引擎现存任务(任何状态)的文件名集合:出错任务的分片也是续传资产,不算孤儿。 */
    private suspend fun knownEngineFileNames(): Set<String> = try {
        EngineService.listTasks().map { it.filename }.toSet()
    } catch (_: Exception) { emptySet() }

    /**
     * 清理缓存(设置页手动):删下载目录里"引擎已不认识的"孤儿半成品(.!zd/.zdl.part)
     * + 清图片缓存(封面,清了会重新下载)。引擎里还存在的任务(含出错可续传的)
     * 的文件一律不动——删任务/删文件是用户在剧集上长按"取消下载"的权限,
     * 清理缓存只收真正的孤儿。返回清理描述(供设置页状态栏显示)。
     */
    suspend fun cleanCache(): String = withContext(Dispatchers.IO) {
        var files = 0
        var freed = 0L
        val knownNames = knownEngineFileNames()
        // 引擎直写最终目录,按番剧分子目录,递归找孤儿半成品 .!zd/.zdl.part
        File(Services.downloadDir).walkTopDown().forEach { f ->
            if (f.isFile && (f.name.endsWith(".!zd") || f.name.endsWith(".zdl.part"))) {
                val base = f.name.removeSuffix(".!zd").removeSuffix(".zdl.part")
                if (base !in knownNames && f.delete()) {
                    files++
                    freed += f.length()
                }
            }
        }
        // 图片缓存(封面/海报)
        File(Services.appContext.cacheDir, "image_cache").listFiles()
            ?.forEach { if (it.delete()) files++ }
        "清理 $files 个文件,释放 ${freed / 1024 / 1024} MB"
    }

    /**
     * 启动时清 DB 里 completed 但文件已丢的幽灵行(如下载目录被外部清空):
     * 文件没了记录还在,详情页会显示"已下载"但点开没有文件,删掉回到可重新下载。
     * 只动 completed 幽灵行;出错任务一律不清——留着有续传复活的机会
     * (单击重试 resume/引擎重启恢复),删任务/文件归用户在剧集上长按。
     */
    suspend fun cleanupGhostCompletedRows() = withContext(Dispatchers.IO) {
        try {
            downloadDao.all().filter {
                it.status == "completed" && it.fileName.isNotBlank() && !File(it.fileDir, it.fileName).exists()
            }.forEach { downloadDao.deleteRow(it.id) }
        } catch (_: Exception) {
        }
    }

    private fun mapZdlStatus(s: String): String = when (s) {
        "waiting" -> "queued"
        "choosing" -> "choosing"   // 多文件合集:元数据已就绪,等用户在文件列表里勾选
        "running" -> "downloading"
        "seeding" -> "seeding"
        "completed" -> "completed"
        "error" -> "error"
        else -> s
    }

    private companion object {
        // 进行中全集:含占位的 fetching(拉种子)/creating(建任务)、搬文件的 moving
        // 与合集 choosing(待选文件;不算终态,查重需拦住重复建任务)。
        // 查重命中这些就不建新任务(注意:不含 error,失败的行不挡重试)。
        val ACTIVE_DL_STATUS = setOf("fetching", "creating", "choosing", "queued", "downloading", "seeding", "paused", "moving")
        // sync 回写目标集 = 进行中 + error(error 行可能已在引擎侧自愈续传,需把真实状态写回)。
        val SYNC_DL_STATUS = ACTIVE_DL_STATUS + "error"
    }

    /** 番剧列表当前页(从 API)。page 从 1 起;hasEps=true 只看已有剧集的番剧(与 web 同逻辑);
     *  search 关键字搜索(服务端匹配标题/别名/标签);sort: latest|season。 */
    suspend fun listPage(year: Int, quarter: Int, page: Int, pageSize: Int, hasEps: Boolean = false, search: String = "", sort: String = "season"): List<AnimeEntity> {
        val remote = api.listAnime(year, quarter, hasEps, search, pageSize, (page - 1) * pageSize, sort)
        return remote.map { it.toEntity() }
    }

    /** 当前筛选下的番剧总数(含 search)。 */
    suspend fun total(year: Int, quarter: Int, hasEps: Boolean = false, search: String = ""): Int = api.countAnime(year, quarter, hasEps, search)

    /** 追番列表(本地 Room,分页;与番剧列表共享 year/quarter/hasEps/sort 筛选,纯本地过滤不额外发请求)。
     *  注意 DAO 参数顺序是 (limit, offset),别传反。 */
    suspend fun followed(page: Int, pageSize: Int, year: Int, quarter: Int, hasEps: Boolean, sort: String = "season"): List<AnimeEntity> {
        val offset = (page - 1) * pageSize
        return if (sort == "latest") dao.followedLatest(pageSize, offset, year, quarter, if (hasEps) 1 else 0)
        else dao.followedSeason(pageSize, offset, year, quarter, if (hasEps) 1 else 0)
    }

    suspend fun followedCount(year: Int, quarter: Int, hasEps: Boolean): Int =
        dao.followedCount(year, quarter, if (hasEps) 1 else 0)

    /**
     * 批量刷新追番剧的最新状态:一个请求拉回所有追番的摘要,回写本地。
     * 只更新列表响应里有的字段(title/cover/latestEpisode/episodeCount),
     * 不碰 synopsis/poster 等本地专有字段;失败静默(追番页仍展示本地数据)。
     */
    suspend fun refreshFollowed() {
        try {
            val ids = dao.followedIds()
            if (ids.isEmpty()) return
            val remote = api.listAnimeByIds(ids)
            android.util.Log.d("AnidockTV", "refreshFollowed: ids=$ids remote=${remote.size}")
            val now = System.currentTimeMillis()
            for (r in remote) {
                val local = dao.byAnidockId(r.id)
                if (local == null) {
                    android.util.Log.d("AnidockTV", "refreshFollowed: #${r.id} 本地无行,跳过")
                    continue
                }
                if (!local.followed) continue
                val newLatest = r.latestEpisode ?: 0f
                val newEps = r.episodeCount ?: 0
                val newUpdatedAt = isoToMillis(r.latestUpdatedAt)
                // 只有真的出集(集数变化)才刷新 updatedAt;latestUpdatedAt 取本地/远端的较新值
                // 补全存量行(旧版本无此列,迁移后为 0,首次刷新后"最新更新"排序才生效)
                val changed = newLatest != local.latestEpisode || newEps != local.episodeCount
                dao.upsert(
                    local.copy(
                        title = r.title,
                        cover = r.cover,
                        latestEpisode = newLatest,
                        episodeCount = newEps,
                        latestUpdatedAt = maxOf(local.latestUpdatedAt, newUpdatedAt),
                        updatedAt = if (changed) now else local.updatedAt,
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("AnidockTV", "refreshFollowed fail", e)
        }
    }

    suspend fun getAnime(id: Long): Anime = api.getAnime(id)
    suspend fun episodes(animeId: Long): List<EpisodeView> = api.episodes(animeId)

    /** 关注/取消关注某番剧(本地持久化)。关注时把完整数据存进 Room。 */
    suspend fun setFollowed(anime: AnimeEntity, followed: Boolean) {
        if (followed) {
            dao.upsert(anime.copy(followed = true, updatedAt = System.currentTimeMillis()))
        } else {
            dao.setFollowed(anime.anidockId, false)
        }
    }

    /** 详情页追番/取消(API 模型版;关注时把详情数据转存进 Room)。 */
    suspend fun setFollowedByAnime(anime: Anime, followed: Boolean) {
        try {
            if (followed) {
                // 保留本地 lastOpenedEpisode(封面胶囊),不被 toEntity 的 0f 覆盖
                val lastOpened = dao.byAnidockId(anime.id)?.lastOpenedEpisode ?: 0f
                dao.upsert(anime.toEntity().copy(followed = true, lastOpenedEpisode = lastOpened, updatedAt = System.currentTimeMillis()))
            } else {
                dao.setFollowed(anime.id, false)
            }
            android.util.Log.d("AnidockTV", "follow set: #${anime.id} ${anime.title} -> $followed")
        } catch (e: Exception) {
            android.util.Log.e("AnidockTV", "setFollowedByAnime fail", e)
        }
    }

    suspend fun isFollowed(animeId: Long): Boolean = dao.byAnidockId(animeId)?.followed ?: false

    /** 番剧最后一次成功打开播放的集(详情页标记用);没记录返回 0。 */
    suspend fun lastOpenedEpisode(animeId: Long): Float = dao.byAnidockId(animeId)?.lastOpenedEpisode ?: 0f

    /** 记录用户成功打开播放某集(封面胶囊"上次看到n")。番剧不在本地则建最小行。 */
    suspend fun recordLastOpened(animeId: Long, episode: Float) {
        if (dao.byAnidockId(animeId) == null) {
            dao.upsert(AnimeEntity(anidockId = animeId, title = "", lastOpenedEpisode = episode, updatedAt = System.currentTimeMillis()))
        }
        dao.setLastOpened(animeId, episode)
    }

    /** 给列表合并本地元数据:lastOpenedEpisode(本地记录)+ 已下载集数(封面两胶囊)+ 未读蓝点。 */
    suspend fun enrichWithLocalMeta(animes: List<AnimeEntity>): List<AnimeCardData> {
        if (animes.isEmpty()) return emptyList()
        val localBy = dao.byAnidockIds(animes.map { it.anidockId }).associateBy { it.anidockId }
        val dlCounts = downloadDao.completedCounts().associate { it.animeId to it.cnt }
        val activeDl = downloadDao.activeDownloads().associate { it.animeId to it }
        return animes.map { a ->
            val local = localBy[a.anidockId]
            val lastSeen = local?.lastSeenEpisode ?: -1f   // -1 = 从未打开(含本地无记录行)
            val act = activeDl[a.anidockId]
            AnimeCardData(
                anime = a.copy(lastOpenedEpisode = local?.lastOpenedEpisode ?: 0f),
                downloadedCount = dlCounts[a.anidockId] ?: 0,
                // 蓝点仅对追番:从未打开(-1)直接点亮;打开过则当前总集数 > 基线才亮(基线 0 合法)
                hasNewEpisodes = local?.followed == true &&
                    (lastSeen < 0f || a.episodeCount.toFloat() > lastSeen),
                activeDlCount = act?.cnt ?: 0,
                activeDlProgress = act?.maxProgress ?: 0f,
            )
        }
    }

    /** 点开详情时记录当前总集数(0 合法),作为未读蓝点基线(看完即闭环)。 */
    suspend fun markSeen(anime: AnimeEntity) {
        dao.setLastSeen(anime.anidockId, anime.episodeCount.toFloat())
    }

    /** 一次性归一(开发期存量数据修复):旧行 lastSeenEpisode=0 → -1,设置标记防重跑。 */
    suspend fun ensureSeenBaseline() {
        if (settingDao.get(SettingKeys.SEEN_BASELINE_RESET) == "1") return
        dao.resetSeenBaseline()
        settingDao.put(SettingEntity(SettingKeys.SEEN_BASELINE_RESET, "1"))
    }
}

/** API 模型 → Room 实体。 */
private fun Anime.toEntity(): AnimeEntity = AnimeEntity(
    anidockId = id,
    title = title,
    titleOrig = titleOrig,
    seasonLabel = seasonLabel,
    year = year,
    quarter = quarter,
    cover = cover,
    poster = poster,
    synopsis = synopsis,
    studio = studio,
    latestEpisode = latestEpisode ?: 0f,
    episodeCount = episodeCount ?: 0,
    latestUpdatedAt = isoToMillis(latestUpdatedAt),
    followed = false,
    lastSeenEpisode = -1f,   // 从未打开
    updatedAt = System.currentTimeMillis(),
)

/** 服务端时间串(UTC RFC3339,经 NormalizePubDate 归一)→ epoch ms;空/解析失败返回 0。 */
private fun isoToMillis(s: String): Long {
    if (s.isBlank()) return 0
    try {
        return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    try {
        return java.time.LocalDateTime.parse(s).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    } catch (_: Exception) {
    }
    return 0
}
