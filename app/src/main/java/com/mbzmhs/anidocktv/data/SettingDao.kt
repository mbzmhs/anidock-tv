package com.mbzmhs.anidocktv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 设置项(key-value 单表)。 */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface SettingDao {
    @Query("SELECT value FROM setting WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SettingEntity)
}

/** 设置项键名与默认值(默认值 = 上线前的硬编码值)。 */
object SettingKeys {
    const val BASE_URL = "baseUrl"
    const val VISITOR_PASSWORD = "visitorPassword"
    const val DOWNLOAD_DIR = "downloadDir"
    const val ENGINE_PORT = "enginePort"
    // 侧边栏筛选记忆:两 tab 各存一套独立筛选(HomeFilters 的 JSON),互不影响
    const val LIST_FILTERS = "listFilters"        // 番剧列表 tab 的筛选(可含 search)
    const val FOL_FILTERS = "folFilters"          // 我的追番 tab 的筛选(无 search)
    const val HOME_TAB = "homeTab"                // LIST | FOLLOWED(默认打开哪个 tab)
    const val BG_IMAGE = "bgImage"                // 背景图路径,空 = 未启用
    const val BG_OPACITY = "bgOpacity"            // 背景图不透明度 0-100
    const val SEEN_BASELINE_RESET = "seenBaselineReset"   // 一次性:旧行 lastSeenEpisode 0→-1 归一已完成

    const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"
    const val DEFAULT_PASSWORD = ""
    const val DEFAULT_DOWNLOAD_DIR = "/storage/emulated/0/Download/anidock"
    const val DEFAULT_ENGINE_PORT = 49100
    const val DEFAULT_HOME_TAB = "LIST"
    const val DEFAULT_BG_IMAGE = ""
    const val DEFAULT_BG_OPACITY = 40
}
