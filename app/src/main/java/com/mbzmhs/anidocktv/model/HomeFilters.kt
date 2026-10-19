package com.mbzmhs.anidocktv.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 首页某 tab 的完整筛选状态。番剧列表(LIST)与我的追番(FOLLOWED)各持一套、互不影响:
 * 在列表里设年份/季度/排序不会改动追番那套。search 仅 LIST 用(服务端 q 搜索),
 * FOLLOWED 本地数据无标签,search 恒为空串。page 不单独持久化,启动一律回 1。
 */
@Serializable
data class HomeFilters(
    val year: Int = 0,
    val quarter: Int = 0,
    val pageSize: Int = 12,
    val hasEps: Boolean = false,
    val sort: String = "season",   // latest | season
    val page: Int = 1,
    val search: String = "",
) {
    /** 持久化/读取用:与 AnidockApi 同款宽松解析,新增字段不炸老数据。 */
    fun toJson(): String = json.encodeToString(HomeFilters.serializer(), this)

    companion object {
        // Json 实例放 companion(不参与序列化);放在类体里会被当可序列化字段报"找不到 serializer"
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(s: String): HomeFilters? = runCatching { json.decodeFromString<HomeFilters>(s) }.getOrNull()
    }
}
