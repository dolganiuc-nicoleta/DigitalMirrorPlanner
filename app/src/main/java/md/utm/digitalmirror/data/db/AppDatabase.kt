package md.utm.digitalmirror.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import md.utm.digitalmirror.data.dao.*
import md.utm.digitalmirror.data.entity.*

@Database(
    entities = [
        Reflection::class,
        DailyActivity::class,
        AppUsage::class,
        Category::class,
        Activity::class,
        WorkSession::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reflectionDao(): ReflectionDao
    abstract fun dailyActivityDao(): DailyActivityDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun categoryDao(): CategoryDao
    abstract fun activityDao(): ActivityDao
    abstract fun workSessionDao(): WorkSessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "digitalmirror.db"
                ).build().also { INSTANCE = it }
            }
    }
}