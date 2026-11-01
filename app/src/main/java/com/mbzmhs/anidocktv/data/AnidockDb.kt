package com.mbzmhs.anidocktv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** version 6:v6 新增播放进度表(续播)。存量设备数据重要,迁移必须显式给出,禁用破坏性迁移。 */
@Database(
    entities = [AnimeEntity::class, SettingEntity::class, DownloadTaskEntity::class, PlaybackPosEntity::class],
    version = 6,
    exportSchema = false,
)
abstract class AnidockDb : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun settingDao(): SettingDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun playbackPosDao(): PlaybackPosDao

    companion object {
        @Volatile private var instance: AnidockDb? = null

        fun get(context: Context): AnidockDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnidockDb::class.java,
                    "anidock_tv.db",
                ).addMigrations(MIGRATION_5_6).build().also { instance = it }
            }

        /** v6:播放进度表(每番剧每集一行,主键 anidockId+episode)。 */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playbackPos` (" +
                        "`anidockId` INTEGER NOT NULL, `episode` REAL NOT NULL, `fileRelPath` TEXT NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, `lengthMs` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`anidockId`, `episode`))"
                )
            }
        }
    }
}
