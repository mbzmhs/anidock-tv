package com.mbzmhs.anidocktv.player

/**
 * 播放器选集条目(泛化):宿主把业务数据映射成此结构,播放器不认识任何业务类型。
 * enabled=false 表示未下载/不可播(灰显不可选);current=当前正在播放(✓ 标记)。
 */
class PlayerEpisode(
    val id: String,            // 宿主侧唯一键(单集=集号串,合集=种子内相对路径)
    val label: String,         // 主标题(如"第 3 集"/文件名)
    val sub: String = "",      // 副标题(状态/大小,如"下载中 45%")
    val enabled: Boolean = true,
    val current: Boolean = false,
)

/** 切换播放目标:新源 + 该源专属接线(停流/测速/续播回写/新的选集上下文),由宿主构造。 */
class PlayerSwitch(
    val source: PlaybackSource,
    val onStopped: (() -> Unit)? = null,
    val speedSampler: (suspend () -> Long)? = null,
    val startAtMs: Long = 0,
    val onExitPosition: ((positionMs: Long, lengthMs: Long) -> Unit)? = null,
    val extras: PlayerExtras? = null,
)

/**
 * 播放器的选集/自动连播上下文(宿主注入;null=无选集能力)。
 * - listEpisodes:打开选集菜单时取一次(列表+可播态,宿主现查引擎/库保证新鲜);
 * - playEpisode:选中某条 → 解析出可播目标(不可播返回 null,播放器刷新列表);
 * - autoNext:播放结束后自动连播的下一个目标(无则返回 null,播放器按原行为退出);
 * - warmNext:播放超过 90% 时预热下一个目标(引擎把同任务下一文件头/尾元数据并入
 *   最高优先级;跨任务不可预热,宿主返回 null 即可)。
 */
class PlayerExtras(
    val listEpisodes: suspend () -> List<PlayerEpisode>,
    val playEpisode: suspend (id: String) -> PlayerSwitch?,
    val autoNext: (suspend () -> PlayerSwitch?)? = null,
    val warmNext: (suspend () -> Unit)? = null,
    /** 边下边播时可加载外挂字幕的本地目录(任务下载目录;流本身无同目录概念)。 */
    val subtitleDir: String? = null,
    /** 外挂字幕浏览的上界(下载根目录):允许向上返回但不越出此目录;null=不可向上。 */
    val subtitleRoot: String? = null,
)
