package com.mbzmhs.anidocktv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 播放进度(续播):每番剧每集一行,记录退出时的位置与所在文件。
 * episode 为剧集键:单集=集号,合集=服务器的负键(-(起点*1000+终点));
 * fileRelPath 合集=种子内相对路径,单文件=成片文件名(仅展示用)。
 */
@Entity(tableName = "playbackPos", primaryKeys = ["anidockId", "episode"])
data class PlaybackPosEntity(
    val anidockId: Long,
    val episode: Float,
    val fileRelPath: String,
    val positionMs: Long,
    val lengthMs: Long,
    val updatedAt: Long,
)

@Dao
interface PlaybackPosDao {
    @Query("SELECT * FROM playbackPos WHERE anidockId = :animeId AND episode = :episode LIMIT 1")
    suspend fun byKey(animeId: Long, episode: Float): PlaybackPosEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pos: PlaybackPosEntity)

    @Query("DELETE FROM playbackPos WHERE anidockId = :animeId AND episode = :episode")
    suspend fun delete(animeId: Long, episode: Float)
}
