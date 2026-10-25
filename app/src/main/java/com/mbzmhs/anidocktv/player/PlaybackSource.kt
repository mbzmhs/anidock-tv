package com.mbzmhs.anidocktv.player

/**
 * 内置播放器的唯一输入:播放源。播放器组件与业务层(下载任务/番剧数据/仓库)完全解耦,
 * 调用方负责把业务数据折算成这里的一种;[label] 仅用于播放器界面展示(标题条)。
 */
sealed interface PlaybackSource {
    val label: String

    /** 本地视频文件(下载完成的剧集;目录由调用方先行解析成具体文件)。 */
    data class LocalFile(val path: String, override val label: String) : PlaybackSource

    /**
     * http(s) 流(引擎边下边播环回地址等)。subtitleDir=可选的本地目录(任务下载目录),
     * 提供时播放器可从该目录加载外挂字幕(流本身没有"同目录"概念)。
     */
    data class HttpStream(
        val url: String,
        override val label: String,
        val subtitleDir: String? = null,
    ) : PlaybackSource
}
