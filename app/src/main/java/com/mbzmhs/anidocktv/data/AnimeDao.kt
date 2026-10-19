package com.mbzmhs.anidocktv.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AnimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(anime: AnimeEntity): Long

    // 本地筛选与番剧列表共享:year/quarter 为 0 表示不限;hasEps 传 1/0。
    // 两种排序各一个查询(Room 不能绑定 ORDER BY 文本):
    // latest = 最新一集 RSS 发布时间在前(与服务端 MAX(published_at) 同口径,未知=0 排最后);season = 季度标签降序
    @Query("SELECT * FROM anime WHERE followed = 1 AND (:year = 0 OR year = :year) AND (:quarter = 0 OR quarter = :quarter) AND (:hasEps = 0 OR episodeCount > 0) ORDER BY latestUpdatedAt DESC, updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun followedLatest(limit: Int, offset: Int, year: Int, quarter: Int, hasEps: Int): List<AnimeEntity>

    @Query("SELECT * FROM anime WHERE followed = 1 AND (:year = 0 OR year = :year) AND (:quarter = 0 OR quarter = :quarter) AND (:hasEps = 0 OR episodeCount > 0) ORDER BY seasonLabel DESC, title ASC LIMIT :limit OFFSET :offset")
    suspend fun followedSeason(limit: Int, offset: Int, year: Int, quarter: Int, hasEps: Int): List<AnimeEntity>

    @Query("SELECT COUNT(*) FROM anime WHERE followed = 1 AND (:year = 0 OR year = :year) AND (:quarter = 0 OR quarter = :quarter) AND (:hasEps = 0 OR episodeCount > 0)")
    suspend fun followedCount(year: Int, quarter: Int, hasEps: Int): Int

    @Query("SELECT anidockId FROM anime WHERE followed = 1")
    suspend fun followedIds(): List<Long>

    @Query("SELECT * FROM anime WHERE anidockId = :id LIMIT 1")
    suspend fun byAnidockId(id: Long): AnimeEntity?

    @Query("SELECT * FROM anime WHERE anidockId IN (:ids)")
    suspend fun byAnidockIds(ids: List<Long>): List<AnimeEntity>

    @Query("UPDATE anime SET lastOpenedEpisode = :ep, updatedAt = :ts WHERE anidockId = :id")
    suspend fun setLastOpened(id: Long, ep: Float, ts: Long = System.currentTimeMillis())

    @Query("UPDATE anime SET followed = :followed, updatedAt = :ts WHERE anidockId = :id")
    suspend fun setFollowed(id: Long, followed: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE anime SET lastSeenEpisode = :ep WHERE anidockId = :id")
    suspend fun setLastSeen(id: Long, ep: Float)

    /** 一次性归一:旧 schema 默认值 0 会被误读成"打开时 0 集",统一改为 -1(=从未打开;0 是合法集数)。 */
    @Query("UPDATE anime SET lastSeenEpisode = -1 WHERE lastSeenEpisode = 0")
    suspend fun resetSeenBaseline()

    @Query("DELETE FROM anime")
    suspend fun clear()
}
