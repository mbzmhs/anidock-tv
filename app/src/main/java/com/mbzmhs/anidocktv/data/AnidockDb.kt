package com.mbzmhs.anidocktv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** version 固定 5:各版本迁移已在存量设备上执行完毕,新安装由 Room 按实体直接建表,无需迁移代码。 */
@Database(entities = [AnimeEntity::class, SettingEntity::class, DownloadTaskEntity::class], version = 5, exportSchema = false)
abstract class AnidockDb : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun settingDao(): SettingDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        @Volatile private var instance: AnidockDb? = null

        fun get(context: Context): AnidockDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnidockDb::class.java,
                    "anidock_tv.db",
                ).build().also { instance = it }
            }
    }
}
