package com.mbzmhs.anidocktv.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 番剧(本地缓存 + 追番标记)。数据来自 anidock 公开 API,全部存本地,离线可用。
 * anidockId = anidock 后端的番剧 id(对应 /api/anime/{id})。
 */
@Entity(
    tableName = "anime",
    indices = [Index(value = ["anidockId"], unique = true)],
)
data class AnimeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val anidockId: Long,
    val title: String,
    val titleOrig: String = "",
    val seasonLabel: String = "",
    val year: Int = 0,
    val quarter: Int = 0,
    val cover: String = "",
    val poster: String = "",
    val synopsis: String = "",
    val studio: String = "",
    val latestEpisode: Float = 0f,
    val episodeCount: Int = 0,
    // 最新一集的 RSS 发布时间(epoch ms,与服务端 latest_updated_at 同口径);0=未知。"最新更新"排序依据
    val latestUpdatedAt: Long = 0,
    // 追番:用户标记 + 未读蓝点基线(上次打开时的总集数;-1=从未打开,0 是合法值)
    val followed: Boolean = false,
    val lastSeenEpisode: Float = -1f,
    // 用户最后一次成功打开播放的集数(封面胶囊"上次看到n")
    val lastOpenedEpisode: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 封面卡片渲染数据:番剧实体(含 lastOpenedEpisode) + 已下载集数(封面胶囊"已下载n集")。
 * 列表加载时由 repo 合并本地元数据后产出,AnimeCard 直接消费。
 */
data class AnimeCardData(
    val anime: AnimeEntity,
    val downloadedCount: Int,
    // 未读蓝点:追番且当前总集数 > 上次打开时记录的集数(点开详情即刷新记录,看完闭环)
    val hasNewEpisodes: Boolean = false,
    // 在途下载(排队/下载/暂停中,不含完成/做种/出错):集数 + 最高进度,首页卡片左上角下载圆环用;
    // activeDlCount=0 表示无下载,不显示圆环
    val activeDlCount: Int = 0,
    val activeDlProgress: Float = 0f,
)
