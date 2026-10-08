package md.utm.digitalmirror.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import md.utm.digitalmirror.data.dao.*
import md.utm.digitalmirror.data.entity.*
import md.utm.digitalmirror.domain.TitleKey

@Database(
    entities = [
        Reflection::class,
        DailyActivity::class,
        AppUsage::class,
        Category::class,
        Activity::class,
        WorkSession::class
    ],
    version = 4,
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

        // v3 → v4: ghicitul utilizatorului, intervalul estimării, sursa duratei reale,
        // titlul normalizat și sursa somnului
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity ADD COLUMN userGuessMinutes INTEGER")
                db.execSQL("ALTER TABLE activity ADD COLUMN estimatedLowMinutes INTEGER")
                db.execSQL("ALTER TABLE activity ADD COLUMN estimatedHighMinutes INTEGER")
                db.execSQL("ALTER TABLE activity ADD COLUMN actualSource TEXT")
                db.execSQL("ALTER TABLE activity ADD COLUMN titleKey TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_titleKey` ON `activity` (`titleKey`)")
                db.execSQL("ALTER TABLE daily_activity ADD COLUMN sleepSource TEXT")

                // activitățile vechi cu durată reală: dacă au sesiuni de cronometru, sursa e TIMER, altfel MANUAL
                db.execSQL(
                    """
                    UPDATE activity SET actualSource = CASE
                        WHEN EXISTS (SELECT 1 FROM work_session ws WHERE ws.activityId = activity.id) THEN 'TIMER'
                        ELSE 'MANUAL'
                    END
                    WHERE actualMinutes IS NOT NULL
                    """.trimIndent()
                )

                // somnul existent a venit din Health Connect
                db.execSQL("UPDATE daily_activity SET sleepSource = 'HEALTH' WHERE sleepMinutes IS NOT NULL")

                // titlul normalizat pentru activitățile existente (normalizarea e în Kotlin, nu în SQL)
                db.query("SELECT id, title FROM activity").use { cursor ->
                    while (cursor.moveToNext()) {
                        val values = ContentValues().apply {
                            put("titleKey", TitleKey.of(cursor.getString(1)))
                        }
                        db.update(
                            "activity", SQLiteDatabase.CONFLICT_NONE, values,
                            "id = ?", arrayOf(cursor.getLong(0))
                        )
                    }
                }
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "digitalmirror.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}