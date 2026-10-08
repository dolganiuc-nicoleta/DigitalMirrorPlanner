package md.utm.digitalmirror.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import md.utm.digitalmirror.data.dao.*
import md.utm.digitalmirror.data.entity.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
@Database(
    entities = [
        Reflection::class,
        DailyActivity::class,
        AppUsage::class,
        Category::class,
        Activity::class,
        WorkSession::class
    ],
    version = 3,
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
        // v1 → v2: contorul de amânări pentru activități
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity ADD COLUMN postponedCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v2 → v3: somnul din Health Connect
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_activity ADD COLUMN sleepMinutes INTEGER")
                db.execSQL("ALTER TABLE daily_activity ADD COLUMN bedtimeMinute INTEGER")
                db.execSQL("ALTER TABLE daily_activity ADD COLUMN wakeMinute INTEGER")
            }
        }
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "digitalmirror.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}