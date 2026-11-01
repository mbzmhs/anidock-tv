package com.mbzmhs.anidocktv.ui

import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.data.DownloadTaskEntity
import com.mbzmhs.anidocktv.model.EpisodeView
import com.mbzmhs.anidocktv.player.PlaybackSource
import com.mbzmhs.anidocktv.player.PlayerEpisode
import com.mbzmhs.anidocktv.player.PlayerExtras
import com.mbzmhs.anidocktv.player.PlayerSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 与播放定位同口径的视频扩展名。 */
internal val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "avi", "mov", "ts", "flv", "wmv", "webm", "mpg", "mpeg")

internal fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

/** 合集文件表里字典序第一个视频(合集"从头开始播放"的默认目标)。 */
internal fun firstVideoOf(files: List<com.mbzmhs.anidocktv.data.EngineService.ZdlFile>): com.mbzmhs.anidocktv.data.EngineService.ZdlFile? =
    files.filter { isVideoName(it.path.lastOrNull() ?: "") }.minByOrNull { it.path.joinToString("/") }

/** 合集文件大小展示(选集副标题用)。 */
internal fun fmtSize2(bytes: Long): String = when {
    bytes >= 1073741824 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1048576 -> "%.0f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** 外挂字幕目录:合集=种子根目录,单文件=任务目录(边下边播从该目录挂字幕)。 */
internal fun subtitleDirOf(fileDir: String, fileName: String): String {
    val f = java.io.File(fileDir, fileName)
    return if (f.isDirectory) f.absolutePath else fileDir
}

/** 某集的代表任务:活跃(拉种子/建任务/待选文件/排队/下载/做种/暂停/搬文件)优先,其次完成,再次错误。 */
internal fun bestTask(ep: Float, tasks: List<DownloadTaskEntity>): DownloadTaskEntity? {
    val mine = tasks.filter { it.episode == ep }
    if (mine.isEmpty()) return null
    return mine.firstOrNull { it.status in listOf("fetching", "creating", "choosing", "queued", "downloading", "seeding", "paused", "moving") }
        ?: mine.firstOrNull { it.status == "completed" }
        ?: mine.firstOrNull { it.status == "error" }
        ?: mine.firstOrNull()
}

internal fun Float.toEpText(): String = if (this % 1f == 0f) "${toInt()}" else "${Math.round(this * 10) / 10f}"

/**
 * 剧集按钮文案(与桌面前端同口径):合集(episode_to 非空)→ "X~Y"(起点取 best.episode,
 * 负键兜底解码);集数未知(-1)→ "?";单集 → 集号。
 */
internal fun epBtnText(ep: EpisodeView): String {
    val to = ep.episodeTo
        ?: return if (ep.episode > 0f) ep.episode.toEpText() else "?"
    val from = ep.best.episode ?: if (ep.episode < 0f) -ep.episode / 1000f else ep.episode
    return "${from.toEpText()}~${to.toEpText()}"
}

/**
 * 播放器选集/自动连播上下文构建器(详情页与侧边栏文件列表共用,同口径):
 * - 合集(relPath 非空)=种子内勾选下载的视频文件表;单集=番剧单集列表(已下载/下载中可选,其余灰显);
 * - switchFor 解析目标:该文件已下完→本地播放,否则边下边播;选择即弃旧续播记录。
 * 详情页的 title/episodes 传惰性读(委托状态,调用时取最新);侧边栏按需从 API 拉。
 */
class PlaybackExtrasBuilder(
    private val scope: CoroutineScope,
    private val animeId: Long,
    private val title: () -> String?,
    private val episodes: suspend () -> List<EpisodeView>,
    private val onOpened: (Float) -> Unit = {},
) {
    /** 解析某集当前的可播目标并打包成播放器切换请求(选集/自动连播共用);不可播返回 null。 */
    suspend fun switchFor(epKey: Float, preferRelPath: String?, startAtMs: Long): PlayerSwitch? {
        val row = bestTask(epKey, Services.repo.downloadTasks(animeId)) ?: return null
        if (row.taskId.isBlank()) return null
        val files = com.mbzmhs.anidocktv.data.EngineService.taskDetail(row.taskId)?.files ?: emptyList()
        val multi = files.size > 1
        val done = row.status == "completed"
        if (!done && row.status !in listOf("downloading", "seeding")) return null
        val relPath = if (multi) (preferRelPath ?: firstVideoOf(files)?.path?.joinToString("/")) else null
        if (multi && relPath == null) return null
        val epTag = if (epKey > 0f) "第${epKey.toInt()}集" else "合集"
        val fileName = relPath?.substringAfterLast('/') ?: ""
        val namePart = if (fileName.isNotBlank()) listOf(fileName) else emptyList()
        onOpened(epKey)
        scope.launch { Services.repo.recordLastOpened(animeId, epKey) }
        scope.launch { Services.repo.clearPlaybackPos(animeId, epKey) }
        val savePos: (Long, Long) -> Unit = { t, len ->
            scope.launch { Services.repo.savePlaybackPos(animeId, epKey, relPath ?: row.fileName, t, len) }
        }
        val label = { suffix: String ->
            (listOf(title(), epTag) + namePart + suffix).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        }
        val swExtras = buildExtras(epKey, relPath, row.taskId, subtitleDirOf(row.fileDir, row.fileName))
        // 目标文件已下完(任务整体完成,或下载中但该子文件完整)→ 本地播放;
        // 指定文件本地缺失时回退边下边播(不做目录扫描兜底,避免播错文件)
        val fileComplete = done || (
            relPath != null && files.firstOrNull { it.path.joinToString("/") == relPath }
                ?.let { it.size > 0 && it.done >= it.size } == true
            )
        val localTarget = if (fileComplete) {
            if (relPath != null) {
                java.io.File(java.io.File(row.fileDir, row.fileName), relPath).takeIf { x -> x.isFile && x.length() > 0 }
            } else {
                resolveVideoFileOf(row)
            }
        } else null
        return if (localTarget != null) {
            PlayerSwitch(
                source = PlaybackSource.LocalFile(localTarget.absolutePath, label("")),
                startAtMs = startAtMs,
                onExitPosition = savePos,
                extras = swExtras,
            )
        } else {
            val gen = System.currentTimeMillis() / 1000
            PlayerSwitch(
                source = PlaybackSource.HttpStream(
                    com.mbzmhs.anidocktv.data.EngineService.streamUrl(row.taskId, gen, relPath), label("边下边播"),
                ),
                onStopped = { scope.launch { com.mbzmhs.anidocktv.data.EngineService.stopStream(row.taskId) } },
                speedSampler = {
                    com.mbzmhs.anidocktv.data.EngineService.listTasks().firstOrNull { it.id == row.taskId }?.speed ?: 0L
                },
                startAtMs = startAtMs,
                onExitPosition = savePos,
                extras = swExtras,
            )
        }
    }

    /** 单文件已完成任务的产物定位(递归找字典序第一个视频,合集场景不经此路径)。 */
    private fun resolveVideoFileOf(row: DownloadTaskEntity): java.io.File? {
        if (row.fileDir.isBlank() || row.fileName.isBlank()) return null
        val f = java.io.File(row.fileDir, row.fileName)
        if (!f.exists()) return null
        return if (f.isDirectory) {
            f.walkTopDown()
                .filter { x -> x.isFile && x.length() > 0 && x.extension.lowercase() in VIDEO_EXTS }
                .sortedBy { it.relativeTo(f).path }
                .firstOrNull()
        } else if (f.isFile && f.length() > 0) f else null
    }

    /**
     * 播放器选集/自动连播上下文:全部现查引擎/关联表,保证列表新鲜。
     */
    fun buildExtras(curKey: Float, curRelPath: String?, taskId: String, subDir: String): PlayerExtras {
        val collectionMode = curRelPath != null
        /** 合集模式下当前任务里按序排列的勾选视频文件路径。 */
        suspend fun collectionVideos(): List<String> {
            val d = com.mbzmhs.anidocktv.data.EngineService.taskDetail(taskId)
            return (d?.files ?: emptyList())
                .filter { it.wanted && isVideoName(it.path.lastOrNull() ?: "") }
                .sortedBy { it.path.joinToString("/") }
                .map { it.path.joinToString("/") }
        }
        return PlayerExtras(
            listEpisodes = {
                if (collectionMode) {
                    val d = com.mbzmhs.anidocktv.data.EngineService.taskDetail(taskId)
                    val playable = d?.status in setOf("running", "seeding", "completed")
                    // 只列勾选下载的文件(未勾选的不进选集)
                    (d?.files ?: emptyList())
                        .filter { it.wanted && isVideoName(it.path.lastOrNull() ?: "") }
                        .sortedBy { it.path.joinToString("/") }
                        .map { f ->
                            val pct = if (f.size > 0 && f.done in 1 until f.size) " · ${f.done * 100 / f.size}%" else ""
                            PlayerEpisode(
                                id = f.path.joinToString("/"),
                                label = f.path.lastOrNull() ?: "",
                                sub = fmtSize2(f.size) + pct,
                                enabled = playable,
                                current = f.path.joinToString("/") == curRelPath,
                            )
                        }
                } else {
                    val tasks = Services.repo.downloadTasks(animeId)
                    episodes().filter { it.episode > 0f }.sortedBy { it.episode }.map { e ->
                        val info = epDlInfo(e.episode, tasks)
                        val (en, sub) = when (info.state) {
                            "completed" -> true to "已下载"
                            "downloading" -> true to "下载中 ${(info.progress * 100).toInt()}%"
                            "seeding" -> true to "做种中"
                            "choosing" -> false to "待选文件"
                            "queued" -> false to "排队中"
                            "paused" -> false to "已暂停"
                            "fetching" -> false to "拉种子中"
                            "creating" -> false to "建任务中"
                            "error" -> false to "出错"
                            else -> false to "未下载"
                        }
                        PlayerEpisode(
                            id = e.episode.toString(),
                            label = "第${epBtnText(e)}集",
                            sub = sub,
                            enabled = en,
                            current = e.episode == curKey,
                        )
                    }
                }
            },
            playEpisode = { id ->
                if (collectionMode) switchFor(curKey, id, 0)
                else id.toFloatOrNull()?.let { switchFor(it, null, 0) }
            },
            autoNext = {
                if (collectionMode) {
                    val vids = collectionVideos()
                    val i = vids.indexOf(curRelPath)
                    if (i in 0 until vids.size - 1) switchFor(curKey, vids[i + 1], 0) else null
                } else {
                    episodes().filter { it.episode > 0f }.map { it.episode }.sorted()
                        .filter { it > curKey }.firstNotNullOfOrNull { switchFor(it, null, 0) }
                }
            },
            // 预热只对同任务(合集内下一文件)有效;单集的下一集是另一任务,引擎无法预热
            warmNext = if (collectionMode) {
                {
                    val vids = collectionVideos()
                    val i = vids.indexOf(curRelPath)
                    if (i in 0 until vids.size - 1) {
                        com.mbzmhs.anidocktv.data.EngineService.warmStream(taskId, vids[i + 1])
                    }
                }
            } else null,
            subtitleDir = subDir,
            subtitleRoot = Services.downloadDir,
        )
    }
}

/** 剧集按钮下载状态(status 直接透传 + none);row 携带文件位置供完成后打开。 */
internal data class EpDlInfo(val state: String, val progress: Float, val speed: Long, val row: DownloadTaskEntity? = null)

internal fun epDlInfo(ep: Float, tasks: List<DownloadTaskEntity>): EpDlInfo {
    val r = bestTask(ep, tasks) ?: return EpDlInfo("none", 0f, 0)
    return EpDlInfo(r.status, r.progress, r.speed, r)
}
