package com.mbzmhs.anidocktv.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC 进程级单例:native 库加载与实例创建只做一次,播放器会话关闭后复用。
 * 选项沿用官方 App 的稳定默认:倍速保音调(--audio-time-stretch)+ soxr 重采样。
 * 注:3.7.2 绑定无 buildFontCache 预热接口(3.7.3+ 才有),首个字幕渲染会现场扫字体,首次略慢属正常。
 */
object VlcEngine {
    @Volatile private var lib: LibVLC? = null

    /**
     * 解码模式(进程级;0=硬件直渲默认,1=硬件无直渲,2=软件)。
     * - 硬件直渲:MediaCodec 输出直接上屏,最快;但直渲与 Surface 的绑定偶发失败 → 黑屏有声,
     *   开播时序已修正 + 看门狗自愈,仍偶发可切"硬件无直渲"。
     * - 硬件无直渲(:no-mediacodec-dr):仍走 MediaCodec,帧拷贝后再上屏,绕开直渲绑定路径,
     *   适合 10bit HEVC(芯片能硬解,但本机 armv7 软解 10bit 几乎不出帧,软解救不了)。
     * - 软件:ffmpeg 全量软解,给芯片缺解码器的老编码用(8bit 可用,10bit HEVC 算力不够)。
     */
    @Volatile var decodeMode: Int = 0

    /**
     * 进程级唯一 MediaPlayer(官方 App 同构:播放器随服务活整个进程,只换媒体不销毁实例)。
     * 实测本机直渲下反复创建/释放播放器会滞留 MediaCodec 实例,循环播放几十次即占满
     * 电视硬解通道(全系统播放器都起不来,需重启电视恢复)。
     */
    @Volatile private var sharedPlayer: MediaPlayer? = null
    @Volatile internal var viewsOwner: Any? = null   // 当前占用视频画面的控制器(换会话校验,防串扰)

    /**
     * 播放器原生控制(stop/setMedia/play/attachViews)的专用单线程。libvlc 的 stop 要同步
     * join 输入线程,快进快出/换源与 stop 时序交叠可能死锁(join 永不返回);全部串行到这
     * 一条线程执行,且任何情况下不得在主线程调用。历史教训:主线程直接调 stop/attachViews
     * 会对着卡死的 stop 干等 → 应用冻成 ANR → 进程在解码器仍挂载时被杀 → media.codec 走
     * 死亡回收路径泄漏 → 数轮后全系统解码通道耗尽(所有播放器都起不来,只能重启电视)。
     */
    internal val playerDispatcher: CoroutineDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-ctl") }
            .asCoroutineDispatcher()

    /** 后台守护线程池:看门狗与 ctlAsync 的编排都在这(绝不能用 playerDispatcher 编排——
     *  它可能正是卡死的那条线程,排队的协程起不来、超时也发不出去)。 */
    internal val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 所有原生播放器操作(stop/setMedia/play/attachViews/getVideoTracks…)共用的互斥:
     *  保证任一时刻只有一个线程在碰播放器;等锁的协程是挂起(线程不阻塞),楔死的操作
     *  只会让后来者超时,不会再拖死任何线程。 */
    internal val ctrlMutex = Mutex()

    /** 当前这次 stop 的起点(uptime 毫秒;0=没有 stop 在途)。 */
    @Volatile internal var stopSinceMs: Long = 0

    /**
     * 原生层已楔死(某次原生操作超时未归,实测是 stop 等"读已停摆网络流"的输入线程 join
     * 永不返回):实例不可恢复,只能重启应用重建。置位后 open/attach/ctl 全部拒绝执行,
     * 保住主线程;若 stop 只是慢、事后返回了会自动解除(自愈,见 [VlcPlayerController.stopGuarded])。
     */
    @Volatile var wedged: Boolean = false

    /** 原生控制序列的时限,超时即判楔死。 */
    internal const val CTRL_TIMEOUT_MS = 20_000L
    const val WEDGE_MSG = "播放器内部卡死,请完全退出应用后重新打开"

    init {
        // 全局看门狗:stopGuarded 置 stopSinceMs 后若超时不归,必是原生层卡死(此时 vlc-ctl
        // 线程被占、open 的超时也未必有人触发得到),由本线程兜底置 wedged。
        guardScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                val since = stopSinceMs
                if (since != 0L && android.os.SystemClock.uptimeMillis() - since > CTRL_TIMEOUT_MS && !wedged) {
                    wedged = true
                    android.util.Log.e("AnidockTV", "vlc 原生 stop 超时未归,判楔死")
                }
            }
        }
    }

    /** 把一次原生播放器操作丢到后台串行执行:互斥(与 open/shutdown/attach 的原生序列互斥)、
     *  不在主线程、限时(排队或执行超时判楔死)。主线程绝不直接调任何会等播放器内部锁的
     *  原生接口——stop、getVideoTracks、setTime 等在最坏情况下都会永久等锁(已各被 ANR 实锤)。 */
    internal fun ctlAsync(tag: String, block: () -> Unit) {
        guardScope.launch {
            val ok = withTimeoutOrNull(CTRL_TIMEOUT_MS) {
                ctrlMutex.withLock { runCatching(block) }
            }
            if (ok == null) {
                wedged = true
                android.util.Log.e("AnidockTV", "vlc ctl 超时判楔死: $tag")
            }
        }
    }

    fun player(context: Context): MediaPlayer = sharedPlayer ?: synchronized(this) {
        sharedPlayer ?: MediaPlayer(get(context)).also { sharedPlayer = it }
    }

    fun get(context: Context): LibVLC = lib ?: synchronized(this) {
        lib ?: LibVLC(
            context.applicationContext,
            arrayListOf("--audio-time-stretch", "--audio-resampler", "soxr"),
        ).also { lib = it }
    }
}

/** 菜单行用的轨道描述(id 为轨道 id,可直接回传 setXxxTrack;禁用字幕轨约定为 -1)。 */
data class TrackRow(val id: Int, val name: String)

/** 章节行(timeOffsetMs 为相对全片起点的毫秒偏移,普通 MKV 的章节即全片时间轴)。 */
data class ChapterRow(val index: Int, val name: String, val timeOffsetMs: Long)

/**
 * 内置播放器控制核心:包装 org.videolan.libvlc.MediaPlayer 并持有全部播放状态。
 * 只认 [PlaybackSource],不依赖任何业务类型。
 * 绑定层把事件投递到主线程,这里直接写 Compose 状态,UI 侧无需再切线程;
 * 轨道/章节等菜单数据在"打开菜单/ES 变化"时刷新快照,不逐帧轮询。
 * 注意 libvlc 的坑(官方 App 同款处理):
 * - 轨道列表与 setXxxTrack 必须在输入打开(ES 事件)之后才有效;
 * - getXSpuTrack/getXTrack 返回 -2 表示"无活动输入",-1 才是"禁用";
 * - rate 与音/字幕延迟在换 Media 后被重置;
 * - 章节无变化事件,靠 TimeChanged 轮询 getChapter() 对比。
 */
class VlcPlayerController(context: Context) {
    private val vlc = VlcEngine.get(context)
    /** 进程级共享播放器(见 VlcEngine.player 注释):控制器只是会话包装,不持有独立实例。 */
    val player = VlcEngine.player(context)

    // ---- 播放状态(事件驱动) ----
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(false)
        private set
    var timeMs by mutableLongStateOf(0L)
        private set
    var lengthMs by mutableLongStateOf(0L)
        private set
    var seekable by mutableStateOf(false)
        private set
    var pausable by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    /** 输入读取速率(字节/秒,媒体统计的 inputBitrate):边下边播环回流量下≈引擎供数/BT 下载速度。 */
    var netRateBps by mutableLongStateOf(0L)
        private set

    // ---- 菜单快照(打开菜单/ES 变化时 refresh) ----
    var videoTracks by mutableStateOf(emptyList<TrackRow>())
        private set
    var audioTracks by mutableStateOf(emptyList<TrackRow>())
        private set
    var spuTracks by mutableStateOf(emptyList<TrackRow>())
        private set
    var videoTrackId by mutableIntStateOf(0)
        private set
    var audioTrackId by mutableIntStateOf(0)
        private set
    var spuTrackId by mutableIntStateOf(0)
        private set
    var chapters by mutableStateOf(emptyList<ChapterRow>())
        private set
    var chapterIdx by mutableIntStateOf(0)
        private set
    var rate by mutableFloatStateOf(1f)
        private set
    var spuDelayMs by mutableLongStateOf(0L)
        private set
    var audioDelayMs by mutableLongStateOf(0L)
        private set
    var scaleType by mutableStateOf(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        private set
    /** 当前视频输出数(0=画面未建立,黑屏看门狗的判定依据)。 */
    var voutCount by mutableIntStateOf(0)
        private set

    /** 本媒体会话是否已完成字幕自动选择(每个媒体一次,之后用户手动换轨不被覆盖)。 */
    private var autoSubDone = false

    init {
        // 关闭 libvlc 自带的 OSD 标题显示,界面提示全部由自研 UI 承担(原生调用,照规矩走后台)
        VlcEngine.ctlAsync("titleDisplay") {
            player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
        }
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> { playing = true; buffering = false }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.EndReached -> { ended = true; playing = false; buffering = false }
                MediaPlayer.Event.EncounteredError -> failed = "播放出错,请重试或换源"
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f && !ended
                MediaPlayer.Event.TimeChanged -> {
                    timeMs = e.timeChanged
                    // 章节无变化事件,轮询对比;原生 getChapter 同样可能等锁,走后台
                    if (chapters.isNotEmpty()) VlcEngine.ctlAsync("chapterPoll") {
                        chapterIdx = player.chapter.coerceAtLeast(0)
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    lengthMs = e.lengthChanged
                    refreshSnapshot()
                    autoSelectSubtitle()
                }
                MediaPlayer.Event.SeekableChanged -> seekable = e.seekable
                MediaPlayer.Event.PausableChanged -> pausable = e.pausable
                MediaPlayer.Event.Vout -> {
                    voutCount = e.getVoutCount()
                    VlcEngine.ctlAsync("updateSurfaces") { player.updateVideoSurfaces() }
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                    refreshSnapshot()
                    autoSelectSubtitle()
                }
            }
        }
    }

    /** 轮询媒体统计刷新 [netRateBps](由 UI 定时调用,仅流场景有意义;本地文件读的是盘速,不展示)。
     *  getMedia() 会 retain,读完必须 release。无媒体/无统计时清零。
     *  注意 libvlc 3.x 的 inputBitrate 实测常为 0,优先用 [setNetRate] 接外部真实速度(引擎任务速度)。 */
    fun pollStats() {
        if (VlcEngine.wedged) {
            netRateBps = 0
            return
        }
        VlcEngine.ctlAsync("pollStats") {
            val media = player.media ?: run { netRateBps = 0; return@ctlAsync }
            val stats = runCatching { media.stats }.getOrNull()
            media.release()
            netRateBps = (stats?.inputBitrate ?: 0f).coerceAtLeast(0f).toLong()
        }
    }

    /** 外部注入真实网络速度(字节/秒,如引擎 BT 任务速度),供 UI 显示。 */
    fun setNetRate(bps: Long) { netRateBps = bps }

    /** 从播放器现读轨道/章节/倍速/延迟/画面比例,喂给菜单。输入未打开时返回空快照,安全。 */
    /** 从播放器现读轨道/章节/倍速/延迟/画面比例,喂给菜单。输入未打开时返回空快照,安全。
     *  原生轨道查询(getVideoTracks 等)最坏情况会永久等输入锁(ANR 实锤过),必须在后台走。 */
    fun refreshSnapshot() {
        if (VlcEngine.wedged) return
        VlcEngine.ctlAsync("snapshot") {
            videoTracks = player.videoTracks.orEmpty().map { TrackRow(it.id, it.name ?: "视频轨 ${it.id}") }
            audioTracks = player.audioTracks.orEmpty().map { TrackRow(it.id, it.name ?: "音轨 ${it.id}") }
            spuTracks = player.spuTracks.orEmpty().map { TrackRow(it.id, it.name ?: "字幕 ${it.id}") }
            videoTrackId = player.videoTrack
            audioTrackId = player.audioTrack
            spuTrackId = player.spuTrack
            chapters = player.getChapters(-1).orEmpty().mapIndexed { i, c ->
                ChapterRow(i, c.name ?: "第${i + 1}章", c.timeOffset)
            }
            chapterIdx = player.chapter.coerceAtLeast(0)
            rate = player.rate
            spuDelayMs = player.spuDelay / 1000
            audioDelayMs = player.audioDelay / 1000
            scaleType = player.videoScale
        }
    }

    /** 打开源(挂起函数)。原生序列整体排进 [VlcEngine.playerDispatcher] 串行执行并限时:
     *  超时判原生层楔死,置 [VlcEngine.wedged] 后本次与后续 open/attach 全部拒绝(保住主线程,
     *  提示重启应用);若在途 stop 只是慢、事后返回,楔死标记自动解除(见 [stopGuarded])。 */
    suspend fun open(source: PlaybackSource) {
        if (VlcEngine.wedged) {
            failed = VlcEngine.WEDGE_MSG
            return
        }
        failed = null
        ended = false
        timeMs = 0
        lengthMs = 0
        autoSubDone = false
        voutCount = 0
        val done = withTimeoutOrNull(VlcEngine.CTRL_TIMEOUT_MS) {
            withContext(VlcEngine.playerDispatcher) {
                VlcEngine.ctrlMutex.withLock {
                    // 出错后的重试必须先 stop:libvlc 出错后输入线程不复位,不 stop 直接换 Media 再 play()
                    // 会被静默吞掉(表现为"按重试没反应",必须重建播放器实例才恢复)。错误态下 stop 快速返回。
                    runCatching { if (player.hasMedia()) stopGuarded() }
                    // 显式建 Media 以控制解码模式(play(path) 快捷方式恒用硬解直渲)
                    val media = when (source) {
                        is PlaybackSource.LocalFile -> org.videolan.libvlc.Media(vlc, source.path)
                        is PlaybackSource.HttpStream -> org.videolan.libvlc.Media(vlc, Uri.parse(source.url))
                    }
                    media.setHWDecoderEnabled(VlcEngine.decodeMode != 2, false)
                    if (VlcEngine.decodeMode == 1) media.addOption(":no-mediacodec-dr")
                    player.media = media
                    media.release()
                    player.play()
                }
            }
        }
        if (done == null) {
            VlcEngine.wedged = true
            failed = VlcEngine.WEDGE_MSG
        }
    }

    fun togglePlayPause() {
        if (VlcEngine.wedged) return
        val wantPause = playing
        VlcEngine.ctlAsync("pause") { if (wantPause) player.pause() else player.play() }
    }

    fun seekTo(targetMs: Long) {
        if (VlcEngine.wedged) return
        if (!seekable || lengthMs <= 0) return
        val t = targetMs.coerceIn(0L, (lengthMs - 500).coerceAtLeast(0L))
        timeMs = t
        VlcEngine.ctlAsync("seek") { player.setTime(t) }
    }

    fun seekDelta(deltaMs: Long) = seekTo(timeMs + deltaMs)

    /** 倍速(0.25~4)。与官方一致:--audio-time-stretch 保音调,播放中动态改立即生效。 */
    fun changeRate(r: Float) {
        rate = r
        VlcEngine.ctlAsync("rate") { player.setRate(r) }
    }

    fun setVideoTrack(id: Int) = VlcEngine.ctlAsync("videoTrack") { player.setVideoTrack(id) }

    fun setAudioTrack(id: Int) = VlcEngine.ctlAsync("audioTrack") { player.setAudioTrack(id) }

    /** 禁用字幕传 -1(libvlc 约定,-2 是"无输入"不是禁用)。 */
    fun setSpuTrack(id: Int) {
        spuTrackId = id
        VlcEngine.ctlAsync("spuTrack") { player.setSpuTrack(id) }
    }

    fun adjustSpuDelay(deltaMs: Long) {
        spuDelayMs += deltaMs
        VlcEngine.ctlAsync("spuDelay") { player.setSpuDelay(spuDelayMs * 1000) }
    }

    fun adjustAudioDelay(deltaMs: Long) {
        audioDelayMs += deltaMs
        VlcEngine.ctlAsync("audioDelay") { player.setAudioDelay(audioDelayMs * 1000) }
    }

    /** 运行中动态挂载外挂字幕并立即选用(select=true,新轨经 ESAdded 事件入列表)。 */
    fun addSubtitle(path: String) {
        VlcEngine.ctlAsync("addSlave") { player.addSlave(IMedia.Slave.Type.Subtitle, path, true) }
    }

    fun jumpChapter(index: Int) {
        if (chapters.isEmpty()) return
        val i = index.coerceIn(0, chapters.size - 1)
        chapterIdx = i
        VlcEngine.ctlAsync("chapter") { player.setChapter(i) }
    }

    fun setScale(type: MediaPlayer.ScaleType) {
        scaleType = type
        VlcEngine.ctlAsync("scale") { player.setVideoScale(type) }
    }

    /** 轨道语言码表(media 轨道的 id→ISO 语言码;TrackDescription 只有名字;须在 ctlAsync 内调)。 */
    private fun subtitleLangById(): Map<Int, String> {
        val map = HashMap<Int, String>()
        val media = player.media ?: return map
        runCatching {
            for (i in 0 until media.trackCount) {
                val t = media.getTrack(i) as? IMedia.SubtitleTrack ?: continue
                if (!t.language.isNullOrBlank()) map[t.id] = t.language.lowercase()
            }
        }
        media.release()
        return map
    }

    /**
     * 字幕轨自动选择:偏好 简体 > 繁体 > 日语 > 英语 > 其他任意。
     * 关键词双路匹配:轨道名子串(简/繁/日/英/chs/cht/jpn/eng 等)+ ISO 语言码全等(zh-hans/ja/en 等);
     * 语言码用全等避免 "en" 误伤 "slovenian" 之类子串。每个媒体只自动选一次。
     */
    private fun autoSelectSubtitle() {
        if (autoSubDone || VlcEngine.wedged) return
        val candidates = spuTracks.filter { it.id != -1 }   // 剔除"禁用"行
        if (candidates.isEmpty()) return
        VlcEngine.ctlAsync("autoSub") {
            val langById = subtitleLangById()
            val prefs = listOf(
                listOf("简", "簡", "chs", "zh-hans", "zh_cn", "zh-cn", "zhcn", "simplified"),
                listOf("繁", "cht", "zh-hant", "zh_tw", "zh-tw", "zhtw", "traditional"),
                listOf("日", "jpn", "japanese", "日本語", "ja"),
                listOf("英", "eng", "english", "en"),
            )
            for (keys in prefs) {
                val hit = candidates.firstOrNull { t ->
                    val name = t.name.lowercase()
                    val lang = langById[t.id]
                    keys.any { k -> name.contains(k) || lang == k }
                }
                if (hit != null) {
                    if (player.setSpuTrack(hit.id)) spuTrackId = hit.id
                    autoSubDone = true
                    return@ctlAsync
                }
            }
            // 无任何偏好命中:选第一条可用轨(其他任意),保证默认有字幕
            candidates.firstOrNull()?.let {
                if (player.setSpuTrack(it.id)) spuTrackId = it.id
                autoSubDone = true
            }
        }
    }

    /** 挂载画面(View 已 attach 后调用)。attachViews 是主线程同步原生调用、可能等播放器内部锁:
     *  编排从 guardScope 发起(带时限),执行时先等 [VlcEngine.ctrlMutex]——等锁是协程挂起、
     *  不阻塞主线程;轮到时必无在途原生操作,不会对着卡死的 stop 干等。画面占用(viewsOwner)
     *  在锁内置位,与上一会话的关闭序列按互斥队列天然"先停后挂"排序。
     *  [onAttached] 在挂载完成或放弃后回调(主线程),用于放行开播(surfaceReady)。 */
    fun attachTo(layout: VLCVideoLayout, onAttached: () -> Unit) {
        if (VlcEngine.viewsOwner === this) {
            onAttached()
            return
        }
        VlcEngine.guardScope.launch {
            val ok = withTimeoutOrNull(VlcEngine.CTRL_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    VlcEngine.ctrlMutex.withLock {
                        if (!VlcEngine.wedged) {
                            VlcEngine.viewsOwner = this@VlcPlayerController
                            // textureView=true:视频走 TextureView 的 GL vout,字幕在 VLC 自己的 shader 里与视频
                            // 合成——绕开"native 字幕层→安卓位图"路径的预乘缺陷(ASS 阴影/半透明描边会出白边)。
                            runCatching { player.attachViews(layout, null, true, true) }
                        }
                    }
                }
            }
            if (ok == null) VlcEngine.wedged = true
            withContext(Dispatchers.Main) { onAttached() }
        }
    }

    /** 卸载画面(主线程);非本控制器的画面(新会话已挂载)不动。 */
    fun detachViews() {
        if (VlcEngine.viewsOwner !== this) return
        VlcEngine.viewsOwner = null
        runCatching { player.detachViews() }
    }

    /**
     * 会话关闭:stop 当前媒体(阻塞等播放线程退出,解码器随输入拆除回收)→ 主线程卸画面。
     * stop 在 playerDispatcher 上、经 ctrlMutex 串行(libvlc 的 stop 要 join 输入线程,对读
     * 停摆流的输入线程 join 永不返回——不能在主线程调,也不能与挂载并发);整体带时限,
     * 超时判楔死(后续 open/attach 拒绝执行,提示重启应用),主线程全程不被牵连。
     * 播放器实例进程级复用(见 VlcEngine.player),从不 release——反复建/释实例在本机直渲下
     * 会滞留 MediaCodec,占满硬解通道。
     * 新会话已接管画面(viewsOwner 换人)时本会话不再 stop,避免误停新会话的播放。
     */
    fun shutdownAsync() {
        VlcEngine.guardScope.launch {
            val ok = withTimeoutOrNull(VlcEngine.CTRL_TIMEOUT_MS) {
                withContext(VlcEngine.playerDispatcher) {
                    VlcEngine.ctrlMutex.withLock {
                        if (VlcEngine.viewsOwner === this@VlcPlayerController) stopGuarded()
                    }
                }
                withContext(Dispatchers.Main) {
                    VlcEngine.ctrlMutex.withLock { detachViews() }
                }
            }
            if (ok == null) VlcEngine.wedged = true
        }
    }

    /** stop 并做在途计时(只在 playerDispatcher 上调用):libvlc 的 stop 需 join 输入线程,
     *  对已卡的输入线程永不返回。计时供超时判定用;stop 只是慢的话,返回时顺带解除
     *  楔死标记(自愈,见 [VlcEngine.wedged])。 */
    private fun stopGuarded() {
        VlcEngine.stopSinceMs = android.os.SystemClock.uptimeMillis()
        try {
            runCatching { player.stop() }
        } finally {
            VlcEngine.stopSinceMs = 0L
            VlcEngine.wedged = false
        }
    }
}
