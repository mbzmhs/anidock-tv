package com.mbzmhs.anidocktv.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val player = MediaPlayer(vlc)

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

    private var viewsAttached = false

    /** 本媒体会话是否已完成字幕自动选择(每个媒体一次,之后用户手动换轨不被覆盖)。 */
    private var autoSubDone = false

    init {
        // 关闭 libvlc 自带的 OSD 标题显示,界面提示全部由自研 UI 承担
        player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
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
                    if (chapters.isNotEmpty()) chapterIdx = player.chapter.coerceAtLeast(0)
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
                    player.updateVideoSurfaces()
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
        val media = player.media ?: run { netRateBps = 0; return }
        val stats = runCatching { media.stats }.getOrNull()
        media.release()
        netRateBps = (stats?.inputBitrate ?: 0f).coerceAtLeast(0f).toLong()
    }

    /** 外部注入真实网络速度(字节/秒,如引擎 BT 任务速度),供 UI 显示。 */
    fun setNetRate(bps: Long) { netRateBps = bps }

    /** 从播放器现读轨道/章节/倍速/延迟/画面比例,喂给菜单。输入未打开时返回空快照,安全。 */
    fun refreshSnapshot() {
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

    fun open(source: PlaybackSource) {
        failed = null
        ended = false
        timeMs = 0
        lengthMs = 0
        autoSubDone = false
        voutCount = 0
        // 出错后的重试必须先 stop:libvlc 出错后输入线程不复位,不 stop 直接换 Media 再 play()
        // 会被静默吞掉(表现为"按重试没反应",必须重建播放器实例才恢复)。错误态下 stop 快速返回。
        runCatching { if (player.hasMedia()) player.stop() }
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

    fun togglePlayPause() {
        if (playing) player.pause() else player.play()
    }

    fun seekTo(targetMs: Long) {
        if (!seekable || lengthMs <= 0) return
        val t = targetMs.coerceIn(0L, (lengthMs - 500).coerceAtLeast(0L))
        timeMs = t
        player.setTime(t)
    }

    fun seekDelta(deltaMs: Long) = seekTo(timeMs + deltaMs)

    /** 倍速(0.25~4)。与官方一致:--audio-time-stretch 保音调,播放中动态改立即生效。 */
    fun changeRate(r: Float) {
        player.setRate(r)
        rate = r
    }

    fun setVideoTrack(id: Int) = player.setVideoTrack(id)

    fun setAudioTrack(id: Int) = player.setAudioTrack(id)

    /** 禁用字幕传 -1(libvlc 约定,-2 是"无输入"不是禁用)。 */
    fun setSpuTrack(id: Int) = player.setSpuTrack(id)

    fun adjustSpuDelay(deltaMs: Long) {
        spuDelayMs += deltaMs
        player.setSpuDelay(spuDelayMs * 1000)
    }

    fun adjustAudioDelay(deltaMs: Long) {
        audioDelayMs += deltaMs
        player.setAudioDelay(audioDelayMs * 1000)
    }

    /** 运行中动态挂载外挂字幕并立即选用(select=true,新轨经 ESAdded 事件入列表)。 */
    fun addSubtitle(path: String) {
        player.addSlave(IMedia.Slave.Type.Subtitle, path, true)
    }

    fun jumpChapter(index: Int) {
        if (chapters.isEmpty()) return
        val i = index.coerceIn(0, chapters.size - 1)
        player.setChapter(i)
        chapterIdx = i
    }

    fun setScale(type: MediaPlayer.ScaleType) {
        player.setVideoScale(type)
        scaleType = type
    }

    /**
     * 挂载视频画面。必须在主线程、View 已 attach 到窗口后调用,且一次会话仅一次
     * (重复 attach 抛 IllegalStateException);subtitles=true 额外建独立字幕层
     * SurfaceView(ass 特效渲染需要),SurfaceView 路线是官方 TV 默认,性能最好。
     */
    /** 轨道语言码表(media 轨道的 id→ISO 语言码;TrackDescription 只有名字)。 */
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
        if (autoSubDone) return
        val candidates = spuTracks.filter { it.id != -1 }   // 剔除"禁用"行
        if (candidates.isEmpty()) return
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
                return
            }
        }
        // 无任何偏好命中:选第一条可用轨(其他任意),保证默认有字幕
        candidates.firstOrNull()?.let {
            if (player.setSpuTrack(it.id)) spuTrackId = it.id
            autoSubDone = true
        }
    }

    fun attachTo(layout: VLCVideoLayout) {
        if (viewsAttached) return
        viewsAttached = true
        // textureView=true:视频走 TextureView 的 GL vout,字幕在 VLC 自己的 shader 里与视频
        // 合成——绕开"native 字幕层→安卓位图"路径的预乘缺陷(ASS 阴影/半透明描边会出白边)。
        player.attachViews(layout, null, true, true)
    }

    /** 卸载画面(主线程);播放器关闭流程:先本方法,再 [shutdown]。 */
    fun detachViews() {
        if (!viewsAttached) return
        viewsAttached = false
        runCatching { player.detachViews() }
    }

    /** 停止并释放播放器实例。会阻塞等播放线程退出,必须在后台线程调用;LibVLC 单例保留复用。 */
    fun shutdown() {
        runCatching { player.stop() }
        runCatching { player.release() }
    }
}
