package com.mbzmhs.anidocktv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mbzmhs.anidocktv.player.ChapterRow
import com.mbzmhs.anidocktv.player.PlaybackSource
import com.mbzmhs.anidocktv.player.PlayerEpisode
import com.mbzmhs.anidocktv.player.PlayerExtras
import com.mbzmhs.anidocktv.player.PlayerSwitch
import com.mbzmhs.anidocktv.player.TrackRow
import com.mbzmhs.anidocktv.player.VlcPlayerController
import com.mbzmhs.anidocktv.ui.theme.Anidock
import com.mbzmhs.anidocktv.ui.tv.K
import com.mbzmhs.anidocktv.ui.tv.KeyHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** 自动连播开关(进程级,播放菜单可切,默认开)。 */
private val autoNextEnabled = mutableStateOf(true)

private const val HUD_AUTO_HIDE_MS = 3_000L

/** 渐进式快进档位(首档 10 秒):10秒→30秒→1分→3分→5分→10分→20分,之后每档 +10 分钟。 */
private val SEEK_LADDER_MS = longArrayOf(10_000L, 30_000L, 60_000L, 180_000L, 300_000L, 600_000L, 1_200_000L)

private fun seekLadderMs(idx: Int): Long =
    if (idx < SEEK_LADDER_MS.size) SEEK_LADDER_MS[idx]
    else 1_200_000L + (idx - SEEK_LADDER_MS.size + 1) * 600_000L

/** 播放器内识别 MENU(根节点把它打包成 KeyHit 分发过来)。 */
private val K_MENU = Key(android.view.KeyEvent.KEYCODE_MENU)

/** 菜单分区:主菜单与各二级列表(外挂字幕从字幕菜单进入)。 */
private enum class MenuSection { MAIN, EPISODES, AUDIO, SUBTITLE, SUB_FILE, VIDEO, CHAPTER, RATE, SCALE }

/** 菜单行:mark=当前生效项(✓);run=null 表示纯提示行(不可选)。 */
private class MenuRow(val text: String, val mark: Boolean = false, val action: (() -> Unit)? = null)

private val RATE_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

private val SCALE_OPTIONS = listOf(
    MediaPlayer.ScaleType.SURFACE_BEST_FIT to "自适应",
    MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "贴合屏幕",
    MediaPlayer.ScaleType.SURFACE_FILL to "拉伸铺满",
    MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
    MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
    MediaPlayer.ScaleType.SURFACE_ORIGINAL to "原始尺寸",
)

/** 与视频同目录的外挂字幕扩展名(libass/freetype 均支持)。 */
private val SUB_EXTS = setOf("srt", "ass", "ssa", "vtt", "sub", "smi", "sup")

/**
 * 内置播放器(全屏覆盖层):VLCVideoLayout 出画面,控制 UI 全自研。
 * 独立组件:只依赖 [PlaybackSource]/[keyHit]/[onClose],不 import 任何业务类型。
 * 按键(focus-as-state,与全 App 一致):
 * - CENTER=播放/暂停(长按=菜单);MENU=播放菜单;UP/DOWN=上一章/下一章;BACK=关菜单/退出
 * - LEFT/RIGHT=渐进式快退/快进:档位 0→10秒→30秒→1分→3分→5分→10分→20分→之后每档+10分;
 *   同向连按爬档,反向按键沿档位原路退回(经 0 进入反向,落在 0 即不跳),停按 1 秒落地执行;
 *   越界档自动收为"到片头/到片尾"(末档保留),已到头/尾档后同向按键不再涨
 * - 菜单内 UP/DOWN 移动,CENTER 应用,LEFT/BACK 返回上级;延迟调节后不关菜单便于连按
 */
@Composable
fun PlayerScreen(
    source: PlaybackSource,
    keyHit: KeyHit,
    onClose: () -> Unit,
    /** 流场景的真实网络速度采样(字节/秒,如引擎 BT 任务速度);不提供时回退 libvlc 媒体统计。 */
    speedSampler: (suspend () -> Long)? = null,
    /** 续播起点(毫秒,>0 时等媒体就绪即跳);详情页续播对话框传入。 */
    startAtMs: Long = 0,
    /** 退出时回传最后播放位置(时间/时长),供续播记录;null=不记录。 */
    onExitPosition: ((positionMs: Long, lengthMs: Long) -> Unit)? = null,
    /** 选集/自动连播上下文(宿主注入;null=无选集能力,菜单不显示选集)。 */
    extras: PlayerExtras? = null,
    /** 切换播放目标(选集/自动连播):宿主替换整个会话,播放器保留控制器原地换源。 */
    onSwitchSource: (PlayerSwitch) -> Unit = {},
) {
    val context = LocalContext.current
    // 控制器不随 source 重建:选集/自动连播换源时保留 vout/Surface,原地 open 平滑切换
    val controller = remember { VlcPlayerController(context.applicationContext) }
    val pScope = rememberCoroutineScope()

    // 打开播放器的那次按键(剧集单击/文件列表播放键)不泄漏进播放器:首次组合时
    // LaunchedEffect 以当前 seq 立即触发一次,长按确认会被误判成"长按=弹菜单"。
    val openedSeq = remember { keyHit.seq }

    // 视频 Surface 挂载完成标记:开播必须晚于 attachViews(播放先于 Surface 挂载启动,
    // vout 建立会偶发失败 → 黑屏有声;Surface 就绪后重开即恢复)。
    var surfaceReady by remember { mutableStateOf(false) }

    // 待跳起点(续播起点/切解码后原地续播):拿到时长(媒体就绪)即跳,跳完清零
    var pendingSeekMs by remember { mutableStateOf(startAtMs) }

    var section by remember { mutableStateOf<MenuSection?>(null) }
    var menuIdx by remember { mutableIntStateOf(0) }
    // 外挂字幕浏览:当前目录(起始=subLoadDir,可进子目录;返回不越过起始目录)
    var subDir by remember(source) { mutableStateOf<java.io.File?>(null) }
    var hudSeq by remember { mutableIntStateOf(0) }
    var hudVisible by remember { mutableStateOf(false) }
    var seekToast by remember { mutableStateOf<String?>(null) }
    // 渐进式快进的待落地状态:seekLevel 为带符号档位(0=不跳,±1 起映射档位表,反向按键原路退回);
    // seekBaseMs=本会话第一次按键时的播放位置(冻结,目标/边界都基于它算,不随播放漂移);
    // seekArmed=有进行中的预跳会话(0 档也保留显示,1 秒无按键后按档位落地,0 档即不跳)
    var seekLevel by remember { mutableIntStateOf(0) }
    var seekArmed by remember { mutableStateOf(false) }
    var seekBaseMs by remember { mutableLongStateOf(0L) }
    var seekArmTick by remember { mutableIntStateOf(0) }

    // 选集列表(打开选集菜单时取一次;不可播目标选中后刷新重取)
    var epItems by remember { mutableStateOf<List<PlayerEpisode>?>(null) }
    var epLoadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(epLoadTick) {
        if (epLoadTick > 0 && extras != null) {
            epItems = runCatching { extras.listEpisodes() }.getOrNull()
        }
    }

    fun pulseHud() { hudSeq++ }
    LaunchedEffect(hudSeq) { hudVisible = true; delay(HUD_AUTO_HIDE_MS); hudVisible = false }
    LaunchedEffect(seekToast) { if (seekToast != null) { delay(800); seekToast = null } }

    fun closeMenu() { section = null; menuIdx = 0 }

    // 外挂字幕目录:本地文件=视频同目录;边下边播=源或选集上下文给的任务下载目录(流无同目录概念)
    val subLoadDir: java.io.File? = when (source) {
        is PlaybackSource.LocalFile -> java.io.File(source.path).parentFile
        is PlaybackSource.HttpStream -> (source.subtitleDir ?: extras?.subtitleDir)?.let { java.io.File(it) }
    }

    fun openSection(s: MenuSection) {
        if (s == MenuSection.SUB_FILE) {
            subDir = subLoadDir
        }
        if (s == MenuSection.EPISODES) {
            epItems = null
            epLoadTick++
        }
        controller.refreshSnapshot()
        section = s
        menuIdx = 0
    }

    fun backFromMenu() {
        section = when (section) {
            MenuSection.SUB_FILE -> MenuSection.SUBTITLE
            MenuSection.MAIN -> null
            else -> MenuSection.MAIN
        }
        if (section == null) menuIdx = 0
    }

    /** 该档位实际生效的跳转毫秒数(带符号):越界档自动钳到"恰好到片头/片尾"的量。 */
    fun armedAmountMs(level: Int): Long {
        if (level == 0) return 0L
        val mag = seekLadderMs(kotlin.math.abs(level) - 1)
        return if (level > 0)
            mag.coerceAtMost(controller.lengthMs - seekBaseMs)
        else
            (-mag).coerceAtLeast(-seekBaseMs)
    }

    /** 当前档位对应的跳转毫秒数(带符号;0 档=0)。 */
    fun ladderAmountMs(): Long = armedAmountMs(seekLevel)

    /** 渐进式快进:RIGHT 升档、LEFT 降档(原路退回,经 0 入反向);每次按键重置 1 秒倒计时。
     *  起始位置冻结在会话第一次按键处;已钳到片头/片尾档(量不再变大)后,同向按键不涨。 */
    fun armSeek(dir: Int) {
        if (!controller.seekable || controller.lengthMs <= 0) return
        if (!seekArmed) {
            seekBaseMs = controller.timeMs
            seekArmed = true
        }
        val next = seekLevel + dir
        val cur = ladderAmountMs()
        val nextAmt = armedAmountMs(next)
        // 远离 0 的方向上,档位生效量必须严格变大才允许涨(钳住的到头/到尾档后再按无效);退回方向恒允许
        val blocked = (dir > 0 && next > 0 && nextAmt <= cur) || (dir < 0 && next < 0 && nextAmt >= cur)
        if (blocked) return
        seekLevel = next
        seekArmTick++
        hudSeq++
    }

    fun executePendingSeek() {
        val delta = ladderAmountMs()
        seekLevel = 0
        seekArmed = false
        if (delta != 0L) {
            controller.seekTo(seekBaseMs + delta)   // controller 内钳制在 [0, 时长-0.5s],不越片头片尾
            pulseHud()
        }
    }

    fun jumpChapterBy(delta: Int) {
        if (controller.chapters.isEmpty()) { seekToast = "本片无章节"; return }
        val target = controller.chapterIdx + delta
        if (target < 0 || target >= controller.chapters.size) {
            seekToast = if (delta < 0) "已是第一章" else "已是最后一章"
            return
        }
        controller.jumpChapter(target)
        seekToast = (if (delta < 0) "◀ " else "▶ ") + controller.chapters.getOrNull(target)?.name.orEmpty()
        pulseHud()
    }

    // 待跳快进落地:1 秒无按键即执行(每次 armSeek 重置倒计时)
    LaunchedEffect(seekArmTick) {
        if (seekArmed) { delay(1_000); executePendingSeek() }
    }

    fun trackRows(rows: List<TrackRow>, curId: Int, apply: (Int) -> Unit): List<MenuRow> =
        rows.map { t -> MenuRow(t.name, t.id == curId) { apply(t.id); closeMenu(); pulseHud() } }

    /** 选集行:当前播放 ✓;未下载/不可播为纯提示行(灰显不可选);可选行确认后换源。 */
    fun episodeRows(items: List<PlayerEpisode>): List<MenuRow> = items.map { e ->
        val text = e.label + if (e.sub.isNotBlank()) "  ·  ${e.sub}" else ""
        MenuRow(
            text, mark = e.current,
            action = if (e.enabled) {
                {
                    pScope.launch {
                        val sw = runCatching { extras?.playEpisode?.invoke(e.id) }.getOrNull()
                        if (sw != null) {
                            closeMenu()
                            // 换源前先回传当前进度(选集跳走也算一次播放退出)
                            onExitPosition?.invoke(controller.timeMs, controller.lengthMs)
                            onSwitchSource(sw)
                        } else {
                            epItems = null; epLoadTick++   // 状态已变(如刚建任务),刷新列表
                        }
                    }
                }
            } else null,
        )
    }

    fun buildRows(sec: MenuSection): List<MenuRow> = when (sec) {
        MenuSection.EPISODES -> when (val items = epItems) {
            null -> listOf(MenuRow("加载中…"))
            else -> if (items.isEmpty()) listOf(MenuRow("没有可选集")) else episodeRows(items)
        }
        MenuSection.MAIN -> buildList {
            if (extras != null) {
                add(MenuRow("选集") { openSection(MenuSection.EPISODES) })
                add(MenuRow("自动连播:${if (autoNextEnabled.value) "开" else "关"}(播完接下一集)") {
                    autoNextEnabled.value = !autoNextEnabled.value
                })
            }
            val audioName = controller.audioTracks.firstOrNull { it.id == controller.audioTrackId }?.name
            val spuName = controller.spuTracks.firstOrNull { it.id == controller.spuTrackId }?.name
            val videoName = controller.videoTracks.firstOrNull { it.id == controller.videoTrackId }?.name
            add(MenuRow("音轨:${audioName ?: "默认"}") { openSection(MenuSection.AUDIO) })
            add(MenuRow("字幕:${spuName ?: "关闭"}") { openSection(MenuSection.SUBTITLE) })
            if (controller.videoTracks.size > 1)
                add(MenuRow("视频轨:${videoName ?: "默认"}") { openSection(MenuSection.VIDEO) })
            if (controller.chapters.isNotEmpty())
                add(MenuRow("章节(${controller.chapters.size})") { openSection(MenuSection.CHAPTER) })
            add(MenuRow("倍速:${fmtRate(controller.rate)}") { openSection(MenuSection.RATE) })
            add(MenuRow("画面比例:${SCALE_OPTIONS.firstOrNull { it.first == controller.scaleType }?.second ?: "自适应"}") {
                openSection(MenuSection.SCALE)
            })
            // 解码模式循环:硬件直渲 → 硬件无直渲(黑屏自愈档,10bit HEVC 用)→ 软件(老编码兜底)。
            // 切换后原地续播
            add(MenuRow("解码:${decodeName(com.mbzmhs.anidocktv.player.VlcEngine.decodeMode)}(确认切换)") {
                val keep = controller.timeMs
                com.mbzmhs.anidocktv.player.VlcEngine.decodeMode = (com.mbzmhs.anidocktv.player.VlcEngine.decodeMode + 1) % 3
                controller.open(source)
                pendingSeekMs = keep
                closeMenu(); pulseHud()
            })
        }
        MenuSection.AUDIO -> trackRows(controller.audioTracks, controller.audioTrackId) { controller.setAudioTrack(it) } +
                listOf(
                    MenuRow("音频延迟 -0.1秒(当前 ${fmtDelay(controller.audioDelayMs)})") { controller.adjustAudioDelay(-100) },
                    MenuRow("音频延迟 +0.1秒") { controller.adjustAudioDelay(100) },
                )
        MenuSection.SUBTITLE ->
            listOf(MenuRow("关闭字幕", controller.spuTrackId == -1) { controller.setSpuTrack(-1); closeMenu() }) +
                    trackRows(controller.spuTracks, controller.spuTrackId) { controller.setSpuTrack(it) } +
                    (if (subLoadDir != null)
                        listOf(MenuRow("加载外挂字幕…") { openSection(MenuSection.SUB_FILE) }) else emptyList()) +
                    listOf(
                        MenuRow("字幕延迟 -0.1秒(当前 ${fmtDelay(controller.spuDelayMs)})") { controller.adjustSpuDelay(-100) },
                        MenuRow("字幕延迟 +0.1秒") { controller.adjustSpuDelay(100) },
                    )
        MenuSection.SUB_FILE -> {
            val dir = subDir
            when (dir) {
                null -> listOf(MenuRow("加载中…"))
                else -> {
                    val entries = dir.listFiles().orEmpty()
                        .filter { it.isDirectory || (it.isFile && it.extension.lowercase() in SUB_EXTS) }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    // 向上返回:上界=下载根目录(extras.subtitleRoot),起始目录即上界时不可再上
                    val subRoot = extras?.subtitleRoot?.let { java.io.File(it) } ?: subLoadDir
                    val canUp = dir.parentFile != null && subRoot != null &&
                        dir.absolutePath != subRoot.absolutePath && dir.absolutePath.startsWith(subRoot.absolutePath)
                    val upRows = if (canUp) {
                        listOf(MenuRow("‹ 返回上级") { subDir = dir.parentFile; menuIdx = 0 })
                    } else emptyList()
                    val entryRows = entries.map { f ->
                        if (f.isDirectory) MenuRow("▸ ${f.name}/") { subDir = f; menuIdx = 0 }
                        else MenuRow(f.name) { controller.addSubtitle(f.absolutePath); closeMenu() }
                    }
                    if (upRows.isEmpty() && entryRows.isEmpty())
                        listOf(MenuRow("未找到外挂字幕(srt/ass/ssa/vtt/sub/smi/sup)"))
                    else upRows + entryRows
                }
            }
        }
        MenuSection.VIDEO -> trackRows(controller.videoTracks, controller.videoTrackId) { controller.setVideoTrack(it) }
        MenuSection.CHAPTER -> controller.chapters.map { c: ChapterRow ->
            MenuRow("${fmtTime(c.timeOffsetMs)}  ${c.name}", c.index == controller.chapterIdx) {
                controller.jumpChapter(c.index); closeMenu(); pulseHud()
            }
        }
        MenuSection.RATE -> RATE_OPTIONS.map { r ->
            MenuRow(fmtRate(r), r == controller.rate) { controller.changeRate(r); closeMenu(); pulseHud() }
        }
        MenuSection.SCALE -> SCALE_OPTIONS.map { (t, name) ->
            MenuRow(name, t == controller.scaleType) { controller.setScale(t); closeMenu() }
        }
    }

    fun onKey(key: Key, longPress: Boolean) {
        // 出错面板:确认重试,其余按键(除 BACK,由 BackHandler 走退出)忽略
        if (controller.failed != null) {
            if (key == K.CENTER || key == K.ENTER) controller.open(source)
            return
        }
        val sec = section
        if (sec != null) {
            val rows = buildRows(sec)
            when (key) {
                K.UP -> if (menuIdx > 0) menuIdx--
                K.DOWN -> if (menuIdx < rows.size - 1) menuIdx++
                K.CENTER, K.ENTER -> rows.getOrNull(menuIdx)?.action?.invoke()
                K.LEFT -> backFromMenu()
                else -> {}
            }
            return
        }
        when (key) {
            K.CENTER, K.ENTER ->
                if (longPress) openSection(MenuSection.MAIN)
                else { controller.togglePlayPause(); pulseHud() }
            K.LEFT -> armSeek(-1)
            K.RIGHT -> armSeek(1)
            K.UP -> jumpChapterBy(-1)
            K.DOWN -> jumpChapterBy(1)
            K_MENU -> openSection(MenuSection.MAIN)
            else -> {}
        }
    }

    fun exitPlayer() {
        // 退出即上报最后位置(看完/中途退出都算),续播记录由调用方落库
        onExitPosition?.invoke(controller.timeMs, controller.lengthMs)
        onClose()
    }

    BackHandler { if (section != null) backFromMenu() else exitPlayer() }

    LaunchedEffect(keyHit.seq) {
        if (keyHit.seq > 0 && keyHit.seq > openedSeq) onKey(keyHit.key, keyHit.longPress)
    }

    // 待跳起点落地:媒体就绪(拿到时长)即跳;稍等播放起步,避免 seek 被开场状态吞掉
    LaunchedEffect(controller.lengthMs, pendingSeekMs) {
        if (pendingSeekMs in 1 until controller.lengthMs) {
            val target = pendingSeekMs
            delay(300)
            controller.seekTo(target)
            pendingSeekMs = 0
        }
    }

    // 黑屏自愈看门狗:播放进行中(时间在走)但视频输出从未建立超过 6 秒 → 原地重开一次。
    // vout/Surface 偶发竞态(与硬软解无关,重开即恢复),最多自愈 2 次,之后交人工(菜单切解码)。
    var voutRetry by remember(source) { mutableStateOf(0) }
    LaunchedEffect(controller.playing, controller.voutCount, controller.timeMs, voutRetry) {
        if (voutRetry < 2 && controller.playing && controller.voutCount == 0 && controller.timeMs > 6_000) {
            voutRetry++
            val keep = controller.timeMs
            controller.open(source)
            pendingSeekMs = keep
        }
    }

    // 边下边播:每秒采样真实下载速度(调用方注入引擎任务速度;libvlc 统计实测常为 0 只作回退)
    if (source is PlaybackSource.HttpStream) {
        LaunchedEffect(controller) {
            while (true) {
                if (speedSampler != null) controller.setNetRate(runCatching { speedSampler() }.getOrDefault(0L))
                else controller.pollStats()
                delay(1_000)
            }
        }
    }

    // 播放结束:自动连播下一个(菜单可关,默认开;无下一目标/关闭时)稍候退出回详情页。
    // 结束时位置≈片尾,回传后续播记录被清(看完),下次从头
    LaunchedEffect(controller.ended) {
        if (!controller.ended) return@LaunchedEffect
        delay(800)
        if (autoNextEnabled.value && extras?.autoNext != null) {
            val sw = runCatching { extras.autoNext?.invoke() }.getOrNull()
            if (sw != null) {
                onExitPosition?.invoke(controller.timeMs, controller.lengthMs)
                onSwitchSource(sw)
                return@LaunchedEffect
            }
        }
        exitPlayer()
    }

    // 边下边播的"未就绪"是正常生命周期(引擎在抓头尾元数据/连 peer),不弹错误面板:
    // 失败期间保持转圈,并无限自动重试(间隔 2.5 秒)直到出画面;退出随时可按 BACK。
    // 本地文件错误是真实故障,仍走错误面板(见下)。
    LaunchedEffect(controller.failed) {
        if (source !is PlaybackSource.HttpStream) return@LaunchedEffect
        while (controller.failed != null) {
            delay(2_500)
            if (controller.failed != null) controller.open(source)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 视频画面:VLCVideoLayout(SurfaceView+独立字幕层)。挂载必须等 View attach 后(主线程),
        // 用 View.post 保证;attachViews 一次性,重组不重复。
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx -> VLCVideoLayout(ctx).also { l -> l.post { controller.attachTo(l); surfaceReady = true } } },
        )

        // 缓冲转圈:流场景失败期间也维持转圈(未就绪是正常生命周期,交给后台无限重试)
        val streamWaiting = source is PlaybackSource.HttpStream && controller.failed != null
        if ((controller.buffering || streamWaiting) && !controller.ended) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(44.dp), color = Anidock.Accent)
                if (source is PlaybackSource.HttpStream && controller.netRateBps > 0) {
                    OutlinedText(
                        fmtNetRate(controller.netRateBps),
                        color = Anidock.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // 错误面板只用于本地文件(真实故障);流场景的失败是等待生命周期,保持转圈不弹面板
        if (source is PlaybackSource.LocalFile) {
            controller.failed?.let { msg ->
                MenuPanel(title = "播放失败") {
                    Text(msg, color = Anidock.Err, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    Text("按确认键重试,按返回键退出", color = Anidock.Text3, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
                }
            }
        }

        // 暂停角标(常显,区别于缓冲/出错)
        if (!controller.playing && !controller.ended && controller.failed == null && controller.lengthMs > 0) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
            ) {
                Text("‖ 已暂停", color = Anidock.Text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }

        seekToast?.let { toast ->
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(toast, color = Anidock.Text, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            }
        }

        // 渐进式快进待跳提示:档位 + 目标时间(1 秒无按键后执行;0 档不显示任何提示);
        // 钳到边界的末档显示"到片头/到片尾"
        if (seekArmed && controller.lengthMs > 0 && ladderAmountMs() != 0L) {
            val delta = ladderAmountMs()
            val target = (seekBaseMs + delta).coerceIn(0L, controller.lengthMs)
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val big = when {
                        target >= controller.lengthMs -> "到片尾"
                        target <= 0L -> "到片头"
                        else -> (if (delta > 0) "+" else "-") + fmtLadder(delta)
                    }
                    Text(big, color = Anidock.Accent, fontSize = 26.sp)
                    Text("${fmtTime(seekBaseMs)} → ${fmtTime(target)}", color = Anidock.Text2, fontSize = 14.sp)
                }
            }
        }

        // 底部信息条:标题/时间/进度/倍速,操作后短暂显示
        AnimatedVisibility(
            visible = hudVisible && controller.lengthMs > 0 && controller.failed == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedText(
                        source.label, color = Anidock.Text, fontSize = 15.sp,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (source is PlaybackSource.HttpStream && controller.netRateBps > 0) {
                        OutlinedText(fmtNetRate(controller.netRateBps), color = Anidock.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                    if (controller.rate != 1f) {
                        OutlinedText(fmtRate(controller.rate), color = Anidock.Accent, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                    OutlinedText(
                        "${fmtTime(controller.timeMs)} / ${fmtTime(controller.lengthMs)}",
                        color = Anidock.Text, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 进度条;预跳会话中给出预览:实时进度暗色,冻结起点→目标亮色段,目标处白色刻度
                val liveFrac = (controller.timeMs.toFloat() / controller.lengthMs).coerceIn(0f, 1f)
                val pendingDelta = if (seekArmed) ladderAmountMs() else 0L
                val previewing = seekArmed && pendingDelta != 0L
                val baseFrac = (seekBaseMs.toFloat() / controller.lengthMs).coerceIn(0f, 1f)
                val targetFrac = if (previewing)
                    ((seekBaseMs + pendingDelta).toFloat() / controller.lengthMs).coerceIn(0f, 1f)
                else liveFrac
                androidx.compose.foundation.Canvas(
                    Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(2.5.dp)),
                ) {
                    val w = size.width
                    drawRect(Anidock.Border)
                    if (previewing) {
                        drawRect(Anidock.Accent.copy(alpha = 0.4f), size = androidx.compose.ui.geometry.Size(liveFrac * w, size.height))
                        val from = kotlin.math.min(baseFrac, targetFrac) * w
                        val segW = kotlin.math.abs(targetFrac - baseFrac) * w
                        drawRect(Anidock.Accent, topLeft = androidx.compose.ui.geometry.Offset(from, 0f), size = androidx.compose.ui.geometry.Size(segW, size.height))
                        drawRect(
                            Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset((targetFrac * w - 2.dp.toPx()).coerceAtLeast(0f), 0f),
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height),
                        )
                    } else {
                        drawRect(Anidock.Accent, size = androidx.compose.ui.geometry.Size(liveFrac * w, size.height))
                    }
                }
            }
        }

        // 菜单覆盖层(样式对齐详情页 SourcePick/EpMenu);行列表限高+可滚(选集/章节等长列表),
        // 焦点行自动滚进可见区
        section?.let { sec ->
            val rows = buildRows(sec)
            if (menuIdx >= rows.size) menuIdx = (rows.size - 1).coerceAtLeast(0)
            val menuListState = rememberLazyListState()
            LaunchedEffect(menuIdx, rows.size, sec) {
                if (menuIdx in 0 until rows.size) runCatching { menuListState.animateScrollToItem(menuIdx) }
            }
            MenuPanel(
                title = when (sec) {
                    MenuSection.MAIN -> "播放菜单"
                    MenuSection.EPISODES -> "选集"
                    MenuSection.AUDIO -> "音轨"
                    MenuSection.SUBTITLE -> "字幕"
                    MenuSection.SUB_FILE -> "外挂字幕"
                    MenuSection.VIDEO -> "视频轨"
                    MenuSection.CHAPTER -> "章节"
                    MenuSection.RATE -> "倍速"
                    MenuSection.SCALE -> "画面比例"
                },
            ) {
                LazyColumn(
                    state = menuListState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                ) {
                    items(rows.size) { i -> MenuRowItem(rows[i], selected = i == menuIdx) }
                }
            }
        }
    }

    // 开播/换源:等视频 Surface 挂载完成才 open(见 surfaceReady 注释);选集/自动连播由宿主
    // 替换会话(source 变),控制器保留原地换源,不重建画面;换源同时重置待跳起点/快进档
    var openedSource by remember { mutableStateOf<PlaybackSource?>(null) }
    LaunchedEffect(source, surfaceReady) {
        if (!surfaceReady) return@LaunchedEffect
        if (openedSource != source) {
            openedSource = source
            pendingSeekMs = startAtMs
            seekLevel = 0
            seekArmed = false
            controller.open(source)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            // 关闭顺序:stop → 卸画面 → release(直渲下乱序会滞留硬解实例,见 shutdownAsync 注释)
            controller.shutdownAsync()
        }
    }

    // 下一集预热:播放超过 90% 时把下一个目标(同任务合集文件)头/尾元数据并入最高优先级,
    // 只预热一次;跨任务(单集场景)引擎不支持,宿主不给 warmNext 即跳过
    var warmed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(controller.timeMs, controller.lengthMs, source) {
        val len = controller.lengthMs
        if (!warmed && len > 0 && controller.timeMs * 10 >= len * 9 && extras?.warmNext != null) {
            warmed = true
            runCatching { extras.warmNext?.invoke() }
        }
    }
}

/** 菜单行:选中高亮描边;run=null 的纯提示行灰显(action==null 即无动作)。 */
@Composable
private fun MenuRowItem(row: MenuRow, selected: Boolean) {
    Box(
        Modifier.fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Anidock.Accent.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected) Anidock.Accent else Color.Transparent, RoundedCornerShape(6.dp)),
    ) {
        Text(
            (if (row.mark) "✓ " else "") + row.text + (if (selected && row.action != null) "  ▸" else ""),
            color = if (row.action == null) Anidock.Text3 else Anidock.Text,
            fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** 居中面板容器(暗幕+Surface,与详情页覆盖层同风格)。 */
@Composable
private fun MenuPanel(title: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        Surface(
            color = Anidock.BgSoft,
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Anidock.Border),
            modifier = Modifier.align(Alignment.Center).width(460.dp),
        ) {
            Column(Modifier.padding(vertical = 14.dp)) {
                Text(title, color = Anidock.Text2, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 6.dp))
                content()
            }
        }
    }
}

/**
 * 视频画面上直接绘制的文字:黑色描边 + 原色填充,亮色画面(白底雪景等)下也保持可读(反色轮廓)。
 * 描边层必须与填充层共用同一基础样式(主题 LocalTextStyle,仅叠加 drawStyle),否则两层度量
 * 不一致会发生错位、看起来像"阴影"。
 */
@Composable
private fun OutlinedText(
    text: String,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val strokeStyle = androidx.compose.material3.LocalTextStyle.current.copy(
        drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = fontSize.value * 0.3f,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        ),
    )
    Box(modifier) {
        Text(text, color = Color.Black, fontSize = fontSize, overflow = overflow, style = strokeStyle)
        Text(text, color = color, fontSize = fontSize, overflow = overflow)
    }
}

/** 解码模式名(菜单显示)。 */
private fun decodeName(mode: Int): String = when (mode) {
    1 -> "硬件无直渲"
    2 -> "软件"
    else -> "硬件直渲"
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

private fun fmtRate(r: Float) = if (r % 1f == 0f) "${r.toInt()}x" else "${r}x"

/** 档位毫秒数的展示(带符号值须先取绝对值,负号由调用方拼,避免出现 "--")。 */
private fun fmtLadder(ms: Long): String {
    val s = kotlin.math.abs(ms) / 1000
    return if (s < 60) "$s 秒"
    else "${s / 60} 分" + if (s % 60 > 0) " ${s % 60} 秒" else ""
}

/** 读取速率展示:<1MB/s 用 KB/s,否则保留一位小数 MB/s。 */
private fun fmtNetRate(bps: Long): String =
    if (bps < 1024 * 1024) "${bps / 1024} KB/s" else "%.1f MB/s".format(bps / 1024f / 1024f)

private fun fmtDelay(ms: Long): String {
    val v = "%.1f".format(ms / 1000f)
    return (if (ms >= 0) "+" else "") + "$v 秒"
}
