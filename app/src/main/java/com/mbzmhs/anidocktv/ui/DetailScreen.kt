package com.mbzmhs.anidocktv.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mbzmhs.anidocktv.Services
import com.mbzmhs.anidocktv.data.DownloadTaskEntity
import com.mbzmhs.anidocktv.model.Anime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mbzmhs.anidocktv.model.Cast
import com.mbzmhs.anidocktv.model.EpisodeView
import com.mbzmhs.anidocktv.model.Release
import com.mbzmhs.anidocktv.ui.theme.Anidock
import com.mbzmhs.anidocktv.ui.tv.K
import com.mbzmhs.anidocktv.ui.tv.KeyHit
import coil.compose.AsyncImage

/** 焦点目标锚点 id(用于测量内容内 top 偏移)。 */
private const val ANCHOR_TAG = 6
private const val ANCHOR_FOLLOW = 5
private const val ANCHOR_SYN = 1
private const val ANCHOR_EP = 2
private const val ANCHOR_PROD = 3
private const val ANCHOR_CAST = 4

/** 焦点目标类型。 */
private const val KIND_TAG = 5
private const val KIND_FOLLOW = 4
private const val KIND_SYN = 0
private const val KIND_EP = 1
private const val KIND_PROD = 2
private const val KIND_CAST = 3

private const val EP_BTN_W = 46
private const val EP_BTN_H = 40
private const val EP_GAP = 8

private data class Tgt(val kind: Int, val ep: Int)

/** 剧集按钮下载状态(status 直接透传 + none);row 携带文件位置供完成后打开。 */
private data class EpDlInfo(val state: String, val progress: Float, val speed: Long, val row: DownloadTaskEntity? = null)

private data class EpMenu(val ep: EpisodeView, val actions: List<String>)

/** 某集的代表任务:活跃(拉种子/建任务/排队/下载/做种/暂停/搬文件)优先,其次完成,再次错误。 */
private fun bestTask(ep: Float, tasks: List<DownloadTaskEntity>): DownloadTaskEntity? {
    val mine = tasks.filter { it.episode == ep }
    if (mine.isEmpty()) return null
    return mine.firstOrNull { it.status in listOf("fetching", "creating", "queued", "downloading", "seeding", "paused", "moving") }
        ?: mine.firstOrNull { it.status == "completed" }
        ?: mine.firstOrNull { it.status == "error" }
        ?: mine.firstOrNull()
}

private fun epDlInfo(ep: Float, tasks: List<DownloadTaskEntity>): EpDlInfo {
    val r = bestTask(ep, tasks) ?: return EpDlInfo("none", 0f, 0)
    return EpDlInfo(r.status, r.progress, r.speed, r)
}

private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "avi", "mov", "ts", "flv", "wmv", "webm", "mpg", "mpeg")

private fun videoMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "ts" -> "video/mp2t"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "flv" -> "video/x-flv"
    "webm" -> "video/webm"
    else -> "video/*"
}

/**
 * 下载产物定位为可直接播放的视频文件:目录(BT 多文件种子)取字典序第一个视频,
 * 单文件直接返回;不存在/无视频返回 null。
 */
private fun resolveVideoFile(file: java.io.File): java.io.File? {
    return if (file.isDirectory) {
        file.listFiles { f -> f.isFile && f.length() > 0 && f.extension.lowercase() in VIDEO_EXTS }
            ?.sortedBy { it.name }?.firstOrNull()
    } else if (file.isFile && file.length() > 0) file else null
}

/**
 * 下载完成后打开文件:ACTION_VIEW 交给系统分发给可用播放器(Kodi/系统视频应用)。
 * - 共享存储(/storage、/sdcard,含 U 盘)直接 file:// 路径:Kodi 对 content URI
 *   有 _onNewIntent 崩溃 bug(19.0 实测),file:// 是它最稳的入口;本进程已关闭
 *   StrictMode 的 file URI 外发检查(文件在共享存储,无真实泄露);
 * - 应用私有目录走 FileProvider 换 content URI;
 * - 下载结果是目录(BT 多文件)时取其中字典序第一个视频文件。
 * 无应用可播放/文件缺失时抛异常,由调用方 toast。
 */
private fun openDownloadedVideo(context: android.content.Context, file: java.io.File) {
    val target = if (file.isDirectory) {
        val vids = file.listFiles { f -> f.isFile && f.length() > 0 && f.extension.lowercase() in VIDEO_EXTS }
            ?.sortedBy { it.name }
        if (vids.isNullOrEmpty()) throw IllegalStateException("目录里没有视频文件: ${file.path}")
        vids[0]
    } else {
        if (!file.exists()) throw IllegalStateException("文件不存在: ${file.path}")
        file
    }
    val uri = if (target.absolutePath.startsWith("/storage") || target.absolutePath.startsWith("/sdcard")) {
        android.net.Uri.fromFile(target)
    } else {
        try {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        } catch (e: IllegalArgumentException) {
            android.net.Uri.fromFile(target)
        }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(uri, videoMime(target.name))
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

/**
 * 番剧详情(紧凑版,对齐 web 信息量):
 * 封面+缩略图(左) + 信息列(右:标签/标题/RSS/简介/统计/剧集小按钮/制作信息/角色声优)。
 * 简介·制作信息·角色声优 均为「可折叠焦点项」(聚焦按确认展开/收起);
 * 剧集为倒序紧凑小按钮(一行放不下自动换行),2D 网格焦点导航。
 * 焦点即状态:整页可滚动,UP/DOWN/LEFT/RIGHT 在焦点目标间移动并带动滚动,CENTER 激活。
 */
@Composable
fun DetailScreen(
    animeId: Long,
    onBack: () -> Unit,
    onTagSearch: (String) -> Unit,
    keyHit: KeyHit,
    fromFollowed: Boolean = false,
    active: Boolean = true,
    onPlayFile: (file: java.io.File, label: String) -> Unit = { _, _ -> },
    onPlayStream: (taskId: String, url: String, label: String) -> Unit = { _, _, _ -> },
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val density = LocalDensity.current

    var anime by remember { mutableStateOf<Anime?>(null) }
    var episodes by remember { mutableStateOf<List<EpisodeView>?>(null) }
    var error by remember { mutableStateOf("") }
    var synExpanded by remember { mutableStateOf(false) }
    var prodExpanded by remember { mutableStateOf(false) }
    var castExpanded by remember { mutableStateOf(false) }
    var focusIdx by remember { mutableStateOf(0) }   // 焦点目标下标
    var tagIdx by remember { mutableStateOf(0) }     // tag 行内选中下标(LEFT/RIGHT 导航)
    var followed by remember { mutableStateOf(false) }
    var lastOpened by remember { mutableStateOf(0f) } // 最后打开播放的集(标记用)
    val tagScroll = androidx.compose.foundation.rememberScrollState() // tag 行水平滚动
    val tagWidths = remember { mutableStateOf<List<Float>>(emptyList()) } // 每个 tag 的宽度(px)
    val tagSpacingPx = 8.dp.value * density.density
    val tagViewportW = remember { mutableStateOf(0f) } // tag 行可见宽度(px)
    // 下载:剧集按钮状态 + 两个覆盖层(来源选择 / 长按菜单)
    var dlTasks by remember { mutableStateOf<List<DownloadTaskEntity>>(emptyList()) }
    var sourcePick by remember { mutableStateOf<EpisodeView?>(null) }  // 选来源覆盖层
    var sourceIdx by remember { mutableStateOf(0) }
    var epMenu by remember { mutableStateOf<EpMenu?>(null) }          // 长按菜单覆盖层
    var menuIdx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 覆盖层(来源选择/长按菜单)打开时 BACK 先关覆盖层
    BackHandler(enabled = sourcePick != null || epMenu != null) {
        if (sourcePick != null) sourcePick = null else epMenu = null
    }

    val scrollState = rememberScrollState()
    val visH = remember { mutableStateOf(0) }         // 视口高(px)
    // 剧集流容器宽(px),用于算列数。初值解析计算:屏宽 - 左右 padding(88dp) - 封面列(300dp) - 列间距(24dp),
    // 首帧即为正确列数,避免先按 cols=1 画成单列、等 onSizeChanged 测宽后整页重排造成的闪烁;
    // 之后 onSizeChanged 用实测值覆盖(正常两者一致)。
    val screenW = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    val epFlowW = remember { mutableStateOf((screenW - with(density) { 412.dp.roundToPx() }).coerceAtLeast(0)) }
    val containerRootY = remember { mutableStateOf(0) }                       // 滚动容器屏幕 top(px)
    val anchorPos = remember { mutableStateOf<Map<Int, Pair<Int, Int>>>(emptyMap()) }  // anchorId -> (屏幕top, 测量时scroll)
    val topSink: (Int, LayoutCoordinates) -> Unit = { id, coords ->
        anchorPos.value = anchorPos.value + (id to (coords.localToScreen(Offset.Zero).y.toInt() to scrollState.value))
    }

    val btnW = with(density) { EP_BTN_W.dp.roundToPx() }
    val gapPx = with(density) { EP_GAP.dp.roundToPx() }
    val btnH = with(density) { EP_BTN_H.dp.roundToPx() }
    val rowH = btnH + gapPx

    LaunchedEffect(animeId) {
        error = ""; synExpanded = false; prodExpanded = false; castExpanded = false
        anime = null; episodes = null; sourcePick = null; epMenu = null
        followed = Services.repo.isFollowed(animeId)
        lastOpened = Services.repo.lastOpenedEpisode(animeId)
        dlTasks = Services.repo.downloadTasks(animeId)
        try {
            anime = Services.repo.getAnime(animeId)
            episodes = Services.repo.episodes(animeId)
            // 初始焦点:番剧列表→追番按钮;我的追番→上次看的下一集(无则第一集)
            val epListDesc = (episodes ?: emptyList()).sortedByDescending { it.episode }
            val hasT = anime!!.seasonLabel.isNotBlank() || anime!!.genres.isNotEmpty()
            val fIdx = if (hasT) 1 else 0   // 追番按钮下标
            val hasSyn0 = !anime!!.synopsis.isNullOrBlank()
            val epStart0 = fIdx + (if (hasSyn0) 1 else 0) + 1
            if (!fromFollowed) {
                focusIdx = fIdx   // 追番按钮
            } else {
                val nextEp = if (lastOpened > 0f) lastOpened + 1f else -1f
                val nextIdx = epListDesc.indexOfFirst { it.episode == nextEp }
                focusIdx = if (nextIdx >= 0) epStart0 + nextIdx
                else epStart0   // 第一集
            }
        } catch (e: Exception) {
            error = e.message ?: "加载失败"
        }
    }

    // 下载进度轮询:引擎任务进度回写关联表,刷新剧集按钮状态(引擎未运行时自动空转)
    LaunchedEffect(animeId) {
        while (true) {
            try {
                Services.repo.syncDownloadTasks(animeId)
                dlTasks = Services.repo.downloadTasks(animeId)
            } catch (_: Exception) {
            }
            delay(3000)
        }
    }

    val a = anime
    val epList = (episodes ?: emptyList()).sortedByDescending { it.episode }
    val hasSyn = !a?.synopsis.isNullOrBlank()
    val hasProd = a != null && (a.studio.isNotBlank() || a.info.isNotEmpty())
    val hasCast = a != null && (a.cast.isNotEmpty() || a.characters.isNotEmpty())

    // 焦点目标: tag 行(最顶) → 追番按钮 → 简介? → 各集(倒序) → 制作信息? → 角色声优?
    val hasTags = a != null && (a.seasonLabel.isNotBlank() || a.genres.isNotEmpty())
    val targets = buildList {
        if (hasTags) add(Tgt(KIND_TAG, -1))
        add(Tgt(KIND_FOLLOW, -1))
        if (hasSyn) add(Tgt(KIND_SYN, -1))
        epList.forEachIndexed { i, _ -> add(Tgt(KIND_EP, i)) }
        if (hasProd) add(Tgt(KIND_PROD, -1))
        if (hasCast) add(Tgt(KIND_CAST, -1))
    }
    val followIdx = if (hasTags) 1 else 0
    val synIdx = if (hasSyn) followIdx + 1 else -1
    val epStart = if (hasSyn) synIdx + 1 else followIdx + 1
    val epEnd = epStart + epList.size
    val cols = if (epFlowW.value > 0 && epList.isNotEmpty())
        ((epFlowW.value + gapPx) / (btnW + gapPx)).coerceIn(1, epList.size) else 1
    val rows = if (epList.isEmpty() || cols <= 0) 0 else (epList.size + cols - 1) / cols

    // 焦点下标越界保护(数据加载前后 targets 会变化)
    if (targets.isNotEmpty() && focusIdx !in targets.indices) focusIdx = 0
    if (targets.isEmpty() && focusIdx != 0) focusIdx = 0

    // 手动滚动标记:按下自由滚动时置位,焦点回滚 LaunchedEffect 看到就跳过,
    // 否则声优等长块往下滚会被焦点目标拉回可见区(瞬间弹回)
    var manualScroll by remember { mutableStateOf(false) }
    suspend fun freeScroll(dir: Int) {
        val vh = visH.value
        if (vh <= 0) return
        manualScroll = true
        val step = (vh * 0.4f).toInt().coerceAtLeast(1)
        scrollState.scrollTo((scrollState.value + dir * step).coerceIn(0, scrollState.maxValue))
    }
    // 焦点变化时清除手动标记,恢复焦点回滚(新焦点目标要滚进可见区)
    LaunchedEffect(focusIdx) { if (focusIdx != 0) { manualScroll = false; tagIdx = 0 } }
    // tag 行:选中 tag 超出可见区时才滚动(左出→滚到左对齐,右出→滚到右对齐)
    LaunchedEffect(tagIdx, tagWidths) {
        val vw = tagViewportW.value
        if (tagIdx >= 0 && tagIdx < tagWidths.value.size && vw > 0f) {
            val tagW = tagWidths.value[tagIdx]
            if (tagW <= 0f) return@LaunchedEffect
            var x = 0f
            for (i in 0 until tagIdx) x += tagWidths.value.getOrElse(i) { 0f } + tagSpacingPx
            val cur = tagScroll.value
            if (x < cur) {
                tagScroll.scrollTo(x.toInt().coerceIn(0, tagScroll.maxValue.coerceAtLeast(0)))
            } else if (x + tagW > cur + vw) {
                val target = (x + tagW - vw).toInt().coerceIn(0, tagScroll.maxValue.coerceAtLeast(0))
                if (target > cur) tagScroll.scrollTo(target)
            }
        }
    }

    fun refreshDl() {
        scope.launch { dlTasks = Services.repo.downloadTasks(animeId) }
    }

    /** 开始下载某来源(种子先抓取再交引擎建 BT 任务)。 */
    fun startEpDownload(ep: EpisodeView, rel: Release) {
        val aa = a ?: return
        scope.launch {
            try {
                // 用 EpisodeView.episode(非空,合集=-1)覆盖 Release.episode(可空,合集=null),
                // 否则 TV 行 episode 落 0.0,详情页按 -1 匹配不到,出错/完成态显示不出来。
                Services.repo.startDownload(context, aa, rel.copy(episode = ep.episode))
                dlTasks = Services.repo.downloadTasks(animeId)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "下载启动失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 单击重试出错集:优先 resume 引擎侧任务(盘上分片续传);引擎侧任务已消失则回退完整流程(最优来源重下)。 */
    fun retryEpDownload(ep: EpisodeView) {
        scope.launch {
            val ok = runCatching { Services.repo.retryDownload(animeId, ep.episode) }.getOrDefault(false)
            if (!ok) startEpDownload(ep, ep.best)
            dlTasks = Services.repo.downloadTasks(animeId)
        }
    }

    /** 短按:下载中→暂停;已暂停→继续。状态以引擎回写为准(随后刷新关联表)。 */
    fun pauseResumeEpDownload(ep: EpisodeView) {
        val row = bestTask(ep.episode, dlTasks) ?: return
        val resuming = row.status == "paused"
        scope.launch {
            val ok = Services.repo.togglePauseDownload(row)
            dlTasks = Services.repo.downloadTasks(animeId)
            if (!ok) Toast.makeText(context, if (resuming) "继续失败,稍后再试" else "暂停失败,稍后再试", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, if (resuming) "已继续" else "已暂停", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteEpDownload(ep: EpisodeView) {
        // 同一集可能有多行(不同源,唯一索引含 releaseTitle);须清全部行,
        // 否则删一行剩其他行,epDlInfo 仍读到 completed,按钮不变,要删两次才回到剧集数字
        val rows = dlTasks.filter { it.episode == ep.episode }
        if (rows.isEmpty()) return
        scope.launch {
            for (row in rows) Services.repo.cancelDownload(row)
            dlTasks = Services.repo.downloadTasks(animeId)
        }
    }

    /** 边下边播:直接播引擎环回流。引擎按播放头 Range 重排 piece 优先级(先头尾元数据再顺序窗口),
     *  v 代次隔离会话;打开即记"上次看到",与本地播放一致。 */
    fun startStreamPlayback(ep: EpisodeView) {
        val row = bestTask(ep.episode, dlTasks)
        if (row == null || row.taskId.isBlank()) {
            Toast.makeText(context, "任务尚未建立,稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        lastOpened = ep.episode
        scope.launch { Services.repo.recordLastOpened(animeId, ep.episode) }
        val gen = System.currentTimeMillis() / 1000
        val url = com.mbzmhs.anidocktv.data.EngineService.streamUrl(row.taskId, gen)
        val epTag = if (ep.episode > 0f) "第${ep.episode.toInt()}集" else "合集"
        onPlayStream(
            row.taskId, url,
            listOf(a?.title, epTag, "边下边播").filterNotNull().filter { it.isNotBlank() }.joinToString(" "),
        )
    }

    /** 长按菜单:按当前下载状态给出不同操作。 */
    fun openEpMenu(ep: EpisodeView) {
        menuIdx = 0
        epMenu = EpMenu(ep, when (epDlInfo(ep.episode, dlTasks).state) {
            // 下载中:短按已改为边下边播,暂停/继续收进长按菜单
            "fetching", "creating", "queued", "downloading", "seeding", "moving" -> listOf("暂停下载", "取消下载")
            "paused" -> listOf("继续下载", "取消下载")
            "completed" -> listOf("重新下载(选来源)", "删除文件")
            "error" -> listOf("重试", "取消下载")
            else -> listOf("下载(最优来源)", "选择来源下载")
        })
    }

    suspend fun handleDetailKey(key: Key, longPress: Boolean) {
        // 覆盖层打开时按键全归覆盖层,底层页面不响应
        sourcePick?.let { ep ->
            val rels = listOf(ep.best) + ep.alternatives
            when (key) {
                K.UP -> if (sourceIdx > 0) sourceIdx--
                K.DOWN -> if (sourceIdx < rels.size - 1) sourceIdx++
                K.CENTER, K.ENTER -> {
                    val rel = rels.getOrNull(sourceIdx) ?: return@let
                    sourcePick = null
                    startEpDownload(ep, rel)
                }
            }
            return
        }
        epMenu?.let { m ->
            when (key) {
                K.UP -> if (menuIdx > 0) menuIdx--
                K.DOWN -> if (menuIdx < m.actions.size - 1) menuIdx++
                K.CENTER, K.ENTER -> {
                    val act = m.actions.getOrNull(menuIdx) ?: return@let
                    val ep = m.ep
                    epMenu = null
                        when (act) {
                            "下载(最优来源)" -> startEpDownload(ep, ep.best)
                            "重试" -> retryEpDownload(ep)
                            "选择来源下载", "重新下载(选来源)" -> { sourcePick = ep; sourceIdx = 0 }
                            "暂停下载", "继续下载" -> pauseResumeEpDownload(ep)
                            "取消下载", "删除文件" -> deleteEpDownload(ep)
                        }
                }
            }
            return
        }
        if (a == null || targets.isEmpty()) return
        val t = targets.getOrNull(focusIdx) ?: return
        when (key) {
            K.DOWN -> when (t.kind) {
                KIND_TAG -> { focusIdx = (focusIdx + 1).coerceAtMost(targets.size - 1); tagIdx = 0 }
                KIND_SYN -> focusIdx = (focusIdx + 1).coerceAtMost(targets.size - 1)
                KIND_EP -> {
                    val i = t.ep; val r = i / cols
                    if (r < rows - 1) focusIdx = epStart + (r + 1) * cols + (i % cols)
                    else focusIdx = epEnd.coerceAtMost(targets.size - 1)
                }
                else -> if (focusIdx < targets.size - 1) focusIdx++ else freeScroll(1)
            }
            K.UP -> when (t.kind) {
                KIND_TAG -> freeScroll(-1)   // tag 行是最顶,按 UP 自由滚动
                KIND_EP -> {
                    val i = t.ep; val r = i / cols
                    if (r > 0) focusIdx = epStart + (r - 1) * cols + (i % cols)
                    else focusIdx = (epStart - 1).coerceAtLeast(0)
                }
                else -> focusIdx = (focusIdx - 1).coerceAtLeast(0)
            }
            K.RIGHT -> when (t.kind) {
                KIND_TAG -> {
                    val n = tagCount(a!!)
                    if (tagIdx < n - 1) tagIdx++
                }
                KIND_EP -> {
                    val i = t.ep
                    if (i % cols < cols - 1 || i + 1 < epList.size) focusIdx = epStart + (i + 1).coerceAtMost(epList.size - 1)
                }
                else -> {}
            }
            K.LEFT -> when (t.kind) {
                KIND_TAG -> {
                    if (tagIdx > 0) tagIdx--
                }
                KIND_EP -> {
                    val i = t.ep
                    if (i % cols > 0 || i - 1 >= 0) focusIdx = epStart + (i - 1).coerceAtLeast(0)
                }
                else -> {}
            }
            K.CENTER, K.ENTER -> when (t.kind) {
                KIND_FOLLOW -> {
                    val next = !followed
                    followed = next
                    scope.launch { Services.repo.setFollowedByAnime(a!!, next) }
                }
                KIND_TAG -> {
                    // 点 tag 直通首页搜索:用当前聚焦的 tag(季度 chip 或某 genre)作为搜索词,回首页按它过滤
                    val tag = if (tagIdx == 0) a!!.seasonLabel else a!!.genres.getOrNull(tagIdx - 1)
                    if (!tag.isNullOrBlank()) onTagSearch(tag)
                }
                KIND_SYN -> synExpanded = !synExpanded
                KIND_EP -> {
                    val ep = epList.getOrNull(t.ep) ?: return
                    if (longPress) {
                        openEpMenu(ep)
                    } else {
                        // 单击按状态走:无文件=下载最优来源;出错=重试(优先续传,引擎侧任务没了才重下);
                        // 下载中/排队/做种=边下边播(暂停走长按菜单);已暂停=继续;完成=内置播放器播放。
                        // 删除只走长按菜单。
                        when (epDlInfo(ep.episode, dlTasks).state) {
                            "none" -> startEpDownload(ep, ep.best)
                            "error" -> retryEpDownload(ep)
                            "queued", "downloading", "seeding" -> startStreamPlayback(ep)
                            "paused" -> pauseResumeEpDownload(ep)
                            "completed" -> {
                                val row = epDlInfo(ep.episode, dlTasks).row
                                if (row == null || row.fileDir.isBlank() || row.fileName.isBlank()) {
                                    Toast.makeText(context, "已完成但缺少文件记录", Toast.LENGTH_SHORT).show()
                                } else {
                                    val file = resolveVideoFile(java.io.File(row.fileDir, row.fileName))
                                    if (file == null) {
                                        Toast.makeText(context, "下载目录里没有可播放的视频文件", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // 打开内置播放器即视为"上次看到"(与旧版外接播放器行为一致),立即刷新指示器并落库
                                        lastOpened = ep.episode
                                        scope.launch { Services.repo.recordLastOpened(animeId, ep.episode) }
                                        val epTag = if (ep.episode > 0f) "第${ep.episode.toInt()}集" else "合集"
                                        onPlayFile(file, listOf(a?.title, epTag).filterNotNull().filter { it.isNotBlank() }.joinToString(" "))
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                KIND_PROD -> prodExpanded = !prodExpanded
                KIND_CAST -> castExpanded = !castExpanded
            }
            else -> {}
        }
    }

    LaunchedEffect(keyHit.seq) {
        // 内置播放器覆盖期间不消费按键(active 门控,与首页同款)
        if (keyHit.seq > 0 && active) handleDetailKey(keyHit.key, keyHit.longPress)
    }

    // 滚动让焦点目标进入可见区。
    // A = 屏幕top + 测量时scroll = 容器top + 内容top,是滚动不变量;当前屏幕位置 = A - 当前scroll。
    fun focusedA(): Int? {
        val t = targets.getOrNull(focusIdx) ?: return null
        return when (t.kind) {
            KIND_FOLLOW -> anchorPos.value[ANCHOR_FOLLOW]?.let { it.first + it.second }
            KIND_TAG -> anchorPos.value[ANCHOR_TAG]?.let { it.first + it.second }
            KIND_SYN -> anchorPos.value[ANCHOR_SYN]?.let { it.first + it.second }
            KIND_EP -> anchorPos.value[ANCHOR_EP]?.let { it.first + it.second }?.let { it + (t.ep / cols) * rowH }
            KIND_PROD -> anchorPos.value[ANCHOR_PROD]?.let { it.first + it.second }
            KIND_CAST -> anchorPos.value[ANCHOR_CAST]?.let { it.first + it.second }
            else -> null
        }
    }
    LaunchedEffect(focusIdx, anchorPos.value, containerRootY.value, visH.value) {
        // 手动滚动中:跳过焦点回滚,让用户的滚动保持(声优等长块往下看不被拉回)
        if (manualScroll) return@LaunchedEffect
        val vh = visH.value
        if (vh <= 0) return@LaunchedEffect
        val A = focusedA() ?: return@LaunchedEffect
        val cur = scrollState.value
        val pad = with(density) { 40.dp.roundToPx() }
        val onScreenY = A - cur
        val vt = containerRootY.value + pad
        val vb = containerRootY.value + vh - pad
        val target = when {
            onScreenY < vt -> (cur - (vt - onScreenY)).coerceAtLeast(0)
            onScreenY > vb -> cur + (onScreenY - vb)
            else -> cur
        }
        if (target != cur) scrollState.scrollTo(target.coerceIn(0, scrollState.maxValue))
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Anidock.Bg))   // 底色(背景图为半透明,垫深色底保证可读)
        BackgroundLayer()
        // 背景图解码属"静默等待"(不转圈):就绪前不显示页面内容,避免"内容先出、背景事后弹出"的闪烁;
        // 缓存命中时这里几乎瞬间通过,无感。转圈只用于下面从服务器拉数据的加载态。
        val bgPath by BackgroundUi.path
        val bgLoading by BackgroundUi.loading
        if (bgPath.isEmpty() || !bgLoading) {
        when {
            error.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载失败: $error", color = Anidock.Err, style = MaterialTheme.typography.titleLarge)
            }
            // 卡片与剧集必须一起出来:两者都是网络请求,任一未就绪都停留在加载态,
            // 避免"卡片先渲染、剧集按钮后冒出"的半加载闪烁。转圈复用分页那套 accent 圆环。
            anime == null || episodes == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Anidock.Accent,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("加载中…", color = Anidock.Text2, style = MaterialTheme.typography.titleMedium)
                }
            }
            else -> {
                val aa = a!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPlaced { containerRootY.value = it.localToScreen(Offset.Zero).y.toInt() }
                        // 不透底色:根 Box 底层已有 Bg + 背景图层
                        .onSizeChanged { visH.value = it.height }
                        .verticalScroll(scrollState)
                        .padding(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 24.dp),
                ) {
                    DetailHeader(
                        a = aa,
                        epList = epList,
                        dlTasks = dlTasks,
                        followed = followed,
                        lastOpened = lastOpened,
                        hasSyn = hasSyn, hasProd = hasProd, hasCast = hasCast,
                        synExpanded = synExpanded, prodExpanded = prodExpanded, castExpanded = castExpanded,
                        cols = cols, focusIdx = focusIdx,
                        tagIdx = tagIdx,
                        tagScroll = tagScroll,
                        tagWidths = tagWidths,
                        tagViewportW = tagViewportW,
                        topSink = topSink,
                        onEpFlowWidth = { epFlowW.value = it },
                    )
                }
            }
        }
        }
        // 覆盖层:选来源 / 长按菜单(暗幕 + 居中面板)
        sourcePick?.let { ep -> SourcePickOverlay(ep = ep, idx = sourceIdx) }
        epMenu?.let { m -> EpMenuOverlay(menu = m, idx = menuIdx) }
    }
}

/** 详情头部:封面+缩略图(左) + 信息列(右)。信息列含可折叠焦点项与剧集小按钮。 */
@Composable
private fun DetailHeader(
    a: Anime,
    epList: List<EpisodeView>,
    dlTasks: List<DownloadTaskEntity>,
    followed: Boolean,
    lastOpened: Float,
    hasSyn: Boolean, hasProd: Boolean, hasCast: Boolean,
    synExpanded: Boolean, prodExpanded: Boolean, castExpanded: Boolean,
    cols: Int, focusIdx: Int,
    tagIdx: Int,
    tagScroll: androidx.compose.foundation.ScrollState,
    tagWidths: androidx.compose.runtime.MutableState<List<Float>>,
    tagViewportW: androidx.compose.runtime.MutableState<Float>,
    topSink: (Int, LayoutCoordinates) -> Unit,
    onEpFlowWidth: (Int) -> Unit,
) {
    // 焦点下标: 0=tag(若有), 1=追番, 2=简介(若有), 之后是各集…
    val hasTags = a.seasonLabel.isNotBlank() || a.genres.isNotEmpty()
    val followIdx = if (hasTags) 1 else 0
    val synIdx = if (hasSyn) followIdx + 1 else -1
    val epStart = if (hasSyn) synIdx + 1 else followIdx + 1
    val epEnd = epStart + epList.size
    val prodIdx = if (hasProd) epEnd else -1
    val castIdx = if (hasCast) (if (hasProd) epEnd + 1 else epEnd) else -1

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // 封面列
        Column(modifier = Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(450.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Anidock.Border, RoundedCornerShape(14.dp)),
            ) {
                if (a.cover.isNotBlank()) {
                    AsyncImage(
                        model = Services.api.resolveMedia(a.cover),
                        contentDescription = a.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(a.title.take(1), color = Anidock.Text3, style = MaterialTheme.typography.displayLarge)
                    }
                }
            }
            if (a.images.isNotEmpty()) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    a.images.take(4).forEach { img ->
                        Box(
                            modifier = Modifier
                                .width(72.dp).height(100.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Anidock.BorderSoft, RoundedCornerShape(8.dp)),
                        ) {
                            AsyncImage(
                                model = Services.api.resolveMedia(img),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        // 信息列
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // tag 行:无焦点时所有 tag 不亮,有焦点时选中 tag 亮;切走时 tagIdx 归 0
            val tagRowFocused = hasTags && focusIdx == 0
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { s -> tagViewportW.value = s.width.toFloat() }
                    .horizontalScroll(tagScroll)
                    .onPlaced { topSink(ANCHOR_TAG, it) },
            ) {
                if (a.seasonLabel.isNotBlank()) {
                    Box(modifier = Modifier.onSizeChanged { s ->
                        val w = s.width.toFloat()
                        val cur = tagWidths.value.toMutableList()
                        if (cur.size < 1) cur.add(0f)
                        cur[0] = w
                        tagWidths.value = cur
                    }) { TagChip(a.seasonLabel, season = true, focused = tagRowFocused && tagIdx == 0) }
                }
                a.genres.forEachIndexed { i, g ->
                    val idx = if (a.seasonLabel.isNotBlank()) i + 1 else i
                    Box(modifier = Modifier.onSizeChanged { s ->
                        val w = s.width.toFloat()
                        val cur = tagWidths.value.toMutableList()
                        while (cur.size <= idx) cur.add(0f)
                        cur[idx] = w
                        tagWidths.value = cur
                    }) { TagChip(g, season = false, focused = tagRowFocused && tagIdx == idx) }
                }
            }
            Text(a.title, color = Anidock.Text, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            if (a.titleOrig.isNotBlank() && a.titleOrig != a.title) {
                Text(a.titleOrig, color = Anidock.Text3, style = MaterialTheme.typography.bodyMedium)
            }
            FollowButton(
                modifier = Modifier.onPlaced { topSink(ANCHOR_FOLLOW, it) },
                focused = focusIdx == followIdx,
                followed = followed,
            )

            if (hasSyn) {
                CollapsibleBlock(
                    modifier = Modifier.onPlaced { topSink(ANCHOR_SYN, it) },
                    label = "简介", focused = focusIdx == synIdx, expanded = synExpanded,
                    preview = {
                        Text(
                            a.synopsis,
                            color = Anidock.Text3,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    },
                ) {
                    Text(a.synopsis, color = Anidock.Text2, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // 统计
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Stat((a.episodeCount ?: 0).toString(), "已更新集数")
                Stat((a.feedCount ?: 0).toString(), "RSS 源")
                Column {
                    if (a.latestGroup.isNotBlank()) FeedBadge(a.latestGroup, a.latestGroupColor)
                    else Text("—", color = Anidock.Text3, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text("最近更新组", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
                }
            }

            // 剧集:倒序紧凑小按钮
            if (epList.isNotEmpty()) {
                Column {
                    Text("剧集", color = Anidock.Text2, style = MaterialTheme.typography.labelLarge)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(EP_GAP.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .onSizeChanged { onEpFlowWidth(it.width) }
                            .onPlaced { topSink(ANCHOR_EP, it) },
                    ) {
                        val rowLists = List((epList.size + cols - 1) / cols) { r ->
                            epList.subList(r * cols, minOf(epList.size, (r + 1) * cols))
                        }
                        rowLists.forEachIndexed { r, rowList ->
                            Row(horizontalArrangement = Arrangement.spacedBy(EP_GAP.dp)) {
                                rowList.forEachIndexed { c, ep ->
                                    val lin = r * cols + c
                                    EpisodeButton(
                                        text = ep.episode.toEpText(),
                                        focused = focusIdx == epStart + lin,
                                        dl = epDlInfo(ep.episode, dlTasks),
                                        isLastWatched = lastOpened > 0f && ep.episode == lastOpened,
                                    )
                                }
                                repeat(cols - rowList.size) { Spacer(Modifier.width(EP_BTN_W.dp)) }
                            }
                        }
                    }
                }
            }

            if (hasProd) {
                CollapsibleBlock(
                    modifier = Modifier.onPlaced { topSink(ANCHOR_PROD, it) },
                    label = "制作信息", focused = focusIdx == prodIdx, expanded = prodExpanded,
                ) {
                    ProductionContent(a)
                }
            }

            if (hasCast) {
                CollapsibleBlock(
                    modifier = Modifier.onPlaced { topSink(ANCHOR_CAST, it) },
                    label = "角色 / 声优", focused = focusIdx == castIdx, expanded = castExpanded,
                ) {
                    if (a.cast.isNotEmpty()) CastContent(a.cast) else CharactersContent(a.characters)
                }
            }
        }
    }
}

/** 可折叠焦点项:标题行常显,展开后显示 content;聚焦按确认展开/收起。 */
@Composable
private fun CollapsibleBlock(
    modifier: Modifier, label: String, focused: Boolean, expanded: Boolean,
    preview: (@Composable () -> Unit)? = null,   // 收起态预览(如简介两行)
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Anidock.Accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = Anidock.Text2, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "▾ 收起" else "▸ 按确认展开",
                    color = if (focused) Anidock.Accent else Anidock.Text3,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            } else {
                preview?.invoke()
            }
        }
    }
}

/**
 * 剧集编号按钮:状态一眼可辨,下载中直接显示进度百分比。
 * - none:普通卡;queued:浅蓝填充;downloading:底部进度条按 progress 填充(最小可见)+百分比;
 * - paused:紫色填充;seeding:绿色填充;completed:满填充+白字;error:红框。
 * 进行中任务即使未聚焦也保留彩色边框。
 */
@Composable
private fun EpisodeButton(text: String, focused: Boolean, dl: EpDlInfo, isLastWatched: Boolean = false) {
    val borderColor = when {
        dl.state == "error" -> Anidock.Err
        focused -> Anidock.Accent
        isLastWatched -> Color(0xFFE8862B)   // 上次看过=橙色边框(与封面"上次看到"胶囊同色)
        dl.state == "paused" -> Anidock.Accent2
        dl.state in listOf("fetching", "creating", "queued", "downloading", "seeding", "moving", "completed") -> Anidock.Accent
        else -> Anidock.BorderSoft
    }
    Box(
        Modifier
            .width(EP_BTN_W.dp)
            .height(EP_BTN_H.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Anidock.Accent.copy(alpha = 0.28f) else Anidock.Card)
            .border(
                width = if (focused) 3.dp else if (isLastWatched) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (dl.state) {
            "queued", "fetching", "creating", "moving" -> Box(Modifier.fillMaxSize().background(Anidock.Accent.copy(alpha = 0.10f)))
            "paused" -> Box(Modifier.fillMaxSize().background(Anidock.Accent2.copy(alpha = 0.22f)))
            "seeding" -> Box(Modifier.fillMaxSize().background(Anidock.Ok.copy(alpha = 0.16f)))
            // completed 不再填充底色:只用文字颜色区分(白=已下载,灰=未下载),更干净
            "downloading" -> {
                // 轨道常显 + 填充(最小 10%:进度为 0 时按钮也有可辨的下载态)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Anidock.Accent.copy(alpha = 0.18f)),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(dl.progress.coerceAtLeast(0.10f))
                        .height(5.dp)
                        .background(Anidock.Accent),
                )
            }
        }
        val sub = when (dl.state) {
            "downloading" -> "${(dl.progress * 100f).toInt()}%"
            "queued" -> "排队中"
            "fetching" -> "拉种子中"
            "creating" -> "建任务中"
            "moving" -> "搬成片中"
            "paused" -> "已暂停"
            "seeding" -> "做种中"
            "error" -> "出错"
            else -> null
        }
        if (sub == null) {
            Text(
                text,
                color = when {
                    dl.state == "error" -> Anidock.Err
                    dl.state == "completed" -> Color.White   // 已下载=白
                    focused -> Color.White
                    else -> Anidock.Text2                   // 未下载=灰
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal
                ),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text,
                    color = when {
                        dl.state == "completed" -> Color.White
                        dl.state == "error" -> Anidock.Err
                        focused -> Color.White
                        else -> Anidock.Text2
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (focused || dl.state == "completed") FontWeight.Bold else FontWeight.Normal
                    ),
                )
                Text(
                    sub,
                    color = when {
                        dl.state == "error" -> Anidock.Err
                        dl.state == "paused" -> Anidock.Accent2
                        dl.state == "seeding" -> Anidock.Ok
                        focused -> Color.White
                        else -> Anidock.Text3
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 选来源覆盖层:某集的 best + 备选,回车开始下载。 */
@Composable
private fun SourcePickOverlay(ep: EpisodeView, idx: Int) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .width(560.dp)
                .border(2.dp, Anidock.Accent, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选择下载来源 · 第${ep.episode.toEpText()}集",
                    color = Anidock.Text,
                    style = MaterialTheme.typography.titleMedium,
                )
                (listOf(ep.best) + ep.alternatives).forEachIndexed { i, rel ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = if (i == idx) 2.dp else 1.dp,
                                color = if (i == idx) Anidock.Accent else Anidock.BorderSoft,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            rel.title.ifBlank { rel.feedName.ifBlank { rel.groupName } },
                            color = Anidock.Text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${rel.groupName} · ${rel.quality} · ${if (rel.url.startsWith("magnet:")) "磁力" else "种子"}",
                            color = Anidock.Text3,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text("上下选择,回车开始下载,BACK 取消", color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** 长按菜单覆盖层:按剧集下载状态给出操作。 */
@Composable
private fun EpMenuOverlay(menu: EpMenu, idx: Int) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Anidock.Card,
            modifier = Modifier
                .width(360.dp)
                .border(2.dp, Anidock.Accent, RoundedCornerShape(14.dp)),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "第${menu.ep.episode.toEpText()}集",
                    color = Anidock.Text3,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                menu.actions.forEachIndexed { i, act ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(if (i == idx) Anidock.Accent.copy(alpha = 0.25f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(act, color = if (i == idx) Color.White else Anidock.Text2, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** 追番按钮:聚焦按确认追番/取消,接入本地追番系统(替代原 RSS 订阅按钮)。 */
@Composable
private fun FollowButton(modifier: Modifier, focused: Boolean, followed: Boolean) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Anidock.Accent else if (followed) Anidock.Accent.copy(alpha = 0.5f) else Anidock.Border,
                shape = RoundedCornerShape(14.dp),
            )
            .background(
                when {
                    focused -> Anidock.Accent.copy(alpha = 0.15f)
                    followed -> Anidock.Accent.copy(alpha = 0.07f)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (followed) "★ 追番中" else "＋ 追番",
            color = if (followed) Anidock.Accent else Anidock.Text2,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (followed) "按确认取消追番" else "按确认追番,首页可看更新",
            color = Anidock.Text3,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun TagChip(text: String, season: Boolean, focused: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Anidock.Accent.copy(alpha = 0.40f)
                    season -> Anidock.Accent.copy(alpha = 0.22f)
                    else -> Anidock.Accent.copy(alpha = 0.10f)
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = Anidock.Accent.copy(alpha = if (focused) 1f else if (season) 0.5f else 0.25f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(text, color = if (focused) Color.White else if (season) Color(0xFFC3D4FF) else Color(0xFF9DB8FF), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Stat(num: String, label: String) {
    Column {
        Text(num, color = Anidock.Text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(label, color = Anidock.Text3, style = MaterialTheme.typography.labelSmall)
    }
}

/** 制作信息内容:制作公司 + infobox 列表。 */
@Composable
private fun ProductionContent(a: Anime) {
    if (a.studio.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("制作", color = Anidock.Text2, style = MaterialTheme.typography.bodyMedium)
            StudioChip(a.studio)
        }
    }
    if (a.info.isNotEmpty()) {
        Column(modifier = Modifier.padding(top = if (a.studio.isNotBlank()) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            a.info.forEach { item ->
                val v = item.value.ifBlank { item.values.joinToString(" / ") }
                if (v.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.key, color = Anidock.Text3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
                        Text(v, color = Anidock.Text2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioChip(studio: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFFF9E3D).copy(alpha = 0.14f))
            .border(1.dp, Color(0xFFFF9E3D).copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 3.dp),
    ) {
        Text(studio, color = Color(0xFFFFD9A0), style = MaterialTheme.typography.bodyMedium)
    }
}

/** 角色/声优:两列卡片。 */
@Composable
private fun CastContent(cast: List<Cast>) {
    val rowLists = List((cast.size + 1) / 2) { r -> cast.subList(r * 2, minOf(cast.size, (r + 1) * 2)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rowLists.forEach { rl ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rl.forEach { c -> CastCard(c, Modifier.weight(1f)) }
                if (rl.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CastCard(c: Cast, modifier: Modifier) {
    val leadBadge = when (c.lead) {
        1 -> "主"; 2 -> "配"; 3 -> "客"; else -> null
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Anidock.BorderSoft, RoundedCornerShape(10.dp))
            .background(Anidock.BgSoft)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, color = Anidock.Text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            if (leadBadge != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Anidock.Accent.copy(alpha = if (c.lead == 1) 0.55f else 0.2f), RoundedCornerShape(6.dp))
                        .background(if (c.lead == 1) Anidock.Accent.copy(alpha = 0.25f) else Color.Transparent)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(leadBadge, color = if (c.lead == 1) Color(0xFFC3D4FF) else Anidock.Text3, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (c.seiyuu.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                c.seiyuu.take(3).forEach { s ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (s.ver == 0) Anidock.BorderSoft else Anidock.Text3, RoundedCornerShape(12.dp))
                            .background(Anidock.Text2.copy(alpha = 0.05f))
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        Text(s.name + if (s.ver != 0) "·其它" else "", color = if (s.ver == 0) Anidock.Text2 else Anidock.Text3, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (c.seiyuu.size > 3) {
                    Text(" +${c.seiyuu.size - 3}", color = Anidock.Accent, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CharactersContent(chars: List<String>) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chars.forEach { c ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Anidock.BorderSoft, RoundedCornerShape(14.dp))
                    .background(Anidock.Text2.copy(alpha = 0.05f))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(c, color = Anidock.Text2, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 制作组徽标(带色)。 */
@Composable
private fun FeedBadge(name: String, colorHex: String) {
    val c = parseColor(colorHex)
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(c.copy(alpha = 0.15f))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(name.ifBlank { "未知" }, color = c, style = MaterialTheme.typography.labelMedium)
    }
}

private fun parseColor(h: String): Color = try {
    val s = if (h.startsWith("#")) h else "#$h"
    if (s.length == 4 || s.length == 7 || s.length == 9) Color(android.graphics.Color.parseColor(s)) else Anidock.Accent
} catch (e: Exception) {
    Anidock.Accent
}

/** tag 行 tag 总数(季度 chip + 各 genre chip)。 */
private fun tagCount(a: Anime): Int = (if (a.seasonLabel.isNotBlank()) 1 else 0) + a.genres.size

private fun Float.toEpText(): String = if (this % 1f == 0f) "${toInt()}" else "${Math.round(this * 10) / 10f}"
