package com.mbzmhs.anidocktv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 下载任务关联表:番剧(anime 表) → 剧集(episode) → 种子/磁链(url) → BT 任务(taskId → ZDownloader)。
 * 进度/状态由 TV 端定期从引擎 /api/tasks 拉取回写,详情页据此渲染剧集按钮状态。
 * 状态: queued / downloading / seeding / completed / error / canceled
 */
@Entity(
    tableName = "downloadTask",
    indices = [Index(value = ["anidockId", "episode", "releaseTitle"], unique = true)],
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val anidockId: Long,
    val episode: Float,
    val releaseTitle: String,
    val url: String,
    val taskId: String = "",
    val status: String = "queued",
    val progress: Float = 0f,
    val size: Long = 0,
    val downloaded: Long = 0,
    val speed: Long = 0,
    val fileDir: String = "",
    val fileName: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("SELECT * FROM downloadTask WHERE anidockId = :animeId ORDER BY episode ASC")
    suspend fun byAnime(animeId: Long): List<DownloadTaskEntity>

    /** 侧边栏展示 + 同步回写目标集:进行中(含合集 choosing 待选文件)+ error 行——error 行可能已在引擎侧自愈续传。 */
    @Query("SELECT * FROM downloadTask WHERE status IN ('fetching','creating','choosing','queued','downloading','seeding','paused','moving','error') ORDER BY createdAt DESC")
    suspend fun syncable(): List<DownloadTaskEntity>

    /** 回写同步候选全集:syncable + completed——合集改选可把已完成任务重新激活
     *  (completed→paused→running),完成行也必须持续回写,否则界面永远停在旧完成态。 */
    @Query("SELECT * FROM downloadTask WHERE status IN ('fetching','creating','choosing','queued','downloading','seeding','paused','moving','error','completed') ORDER BY createdAt DESC")
    suspend fun syncCandidates(): List<DownloadTaskEntity>

    /** 全部行(清理文件已丢的 completed 幽灵用)。 */
    @Query("SELECT * FROM downloadTask")
    suspend fun all(): List<DownloadTaskEntity>

    /** 每部番剧已下载(完成/做种)的集数,封面胶囊"已下载n集"用。 */
    @Query("SELECT anidockId AS animeId, COUNT(*) AS cnt FROM downloadTask WHERE status IN ('completed','seeding') GROUP BY anidockId")
    suspend fun completedCounts(): List<DlCountRow>

    /** 每部番剧在途(排队/下载/暂停中,不含完成/做种/出错)集数 + 最高进度,首页卡片下载圆环用。 */
    @Query("SELECT anidockId AS animeId, COUNT(*) AS cnt, COALESCE(MAX(progress), 0) AS maxProgress FROM downloadTask WHERE status IN ('fetching','creating','queued','downloading','paused','moving') GROUP BY anidockId")
    suspend fun activeDownloads(): List<DlActiveRow>

    @Query("DELETE FROM downloadTask WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM downloadTask")
    suspend fun clear()
}

/** completedCounts() 的一行:番剧 anidockId + 已下载集数。 */
data class DlCountRow(val animeId: Long, val cnt: Int)

/** activeDownloads() 的一行:番剧 anidockId + 在途集数 + 在途最高进度(0-1)。 */
data class DlActiveRow(val animeId: Long, val cnt: Int, val maxProgress: Float)

/**
 * 侧边栏任务列表行:进行中任务实体 + 番剧名。
 * 携带实体以便按键动作(暂停/取消/删除)与详情页走同一套 repo 方法。
 */
data class DownloadTaskView(
    val task: DownloadTaskEntity,
    val title: String,
) {
    val status: String get() = task.status          // queued / choosing / downloading / seeding / paused
    val episode: Float get() = task.episode
    val progress: Float get() = task.progress
    val speed: Long get() = task.speed
    val size: Long get() = task.size                // 总大小(字节);0=引擎尚未探测到
    val downloaded: Long get() = task.downloaded    // 已下载(字节)
}
