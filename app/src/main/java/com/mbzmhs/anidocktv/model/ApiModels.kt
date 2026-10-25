package com.mbzmhs.anidocktv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bangumi infobox 一条制作信息(制作公司/平台/官网等)。 */
@Serializable
data class InfoboxItem(
    val key: String,
    val value: String = "",
    val values: List<String> = emptyList(),
)

/** 声优。ver: 0=正片配音, 非0=其它版本(真人版等)。 */
@Serializable
data class Seyuu(
    val name: String,
    val ver: Int = 0,
)

/** 角色 + 该角色的声优。lead: 1=主 2=配 3=客 0=未标注。 */
@Serializable
data class Cast(
    val name: String,
    val lead: Int = 0,
    val seiyuu: List<Seyuu> = emptyList(),
)

/** anidock 公开 API 的番剧(与后端 model.Anime 对应)。列表返回摘要字段,详情返回完整字段。 */
@Serializable
data class Anime(
    val id: Long,
    val title: String,
    @SerialName("title_orig") val titleOrig: String = "",
    val synopsis: String = "",
    val year: Int = 0,
    val quarter: Int = 0,
    @SerialName("season_label") val seasonLabel: String = "",
    val genres: List<String> = emptyList(),
    val cover: String = "",
    val poster: String = "",
    val images: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val studio: String = "",
    val info: List<InfoboxItem> = emptyList(),
    val characters: List<String> = emptyList(),
    val cast: List<Cast> = emptyList(),
    @SerialName("latest_episode") val latestEpisode: Float? = null,
    @SerialName("latest_updated_at") val latestUpdatedAt: String = "",   // 最新一集的 RSS 发布时间(UTC RFC3339),服务端"最新更新"排序的依据
    @SerialName("latest_group") val latestGroup: String = "",
    @SerialName("latest_group_color") val latestGroupColor: String = "",
    @SerialName("episode_count") val episodeCount: Int? = null,
    @SerialName("feed_count") val feedCount: Int? = null,
)

@Serializable
data class Release(
    val id: Long,
    val title: String = "",
    val episode: Float? = null,
    val url: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    val quality: String = "",
    val spec: String = "",
    @SerialName("group_name") val groupName: String = "",
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("feed_name") val feedName: String = "",
    @SerialName("feed_color") val feedColor: String = "",
    @SerialName("mirror_path") val mirrorPath: String = "",
)

@Serializable
data class EpisodeView(
    val episode: Float,
    /** 非空 = 多文件合集区间终点(展示"X~Y 合集";episode 为 -(起点*1000+终点) 的负键,best.episode=起点)。 */
    @SerialName("episode_to") val episodeTo: Float? = null,
    val best: Release,
    @SerialName("alternatives") val alternatives: List<Release> = emptyList(),
)

@Serializable
data class LoginResponse(val token: String)
