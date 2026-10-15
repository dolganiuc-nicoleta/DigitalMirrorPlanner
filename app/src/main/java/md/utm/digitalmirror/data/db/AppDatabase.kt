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
        WorkSession::class,
        SessionUsage::class,
        AvailabilityRule::class,
        DayOff::class,
        Goal::class,
        GoalCategory::class,
        GoalStep::class,
        TimeCapsule::class,
        CapsuleGoal::class,
        WeekTask::class,
        Place::class
    ],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reflectionDao(): ReflectionDao
    abstract fun dailyActivityDao(): DailyActivityDao
    abstract fun appUsageDao(): AppUsageDao
    abstract fun categoryDao(): CategoryDao
    abstract fun activityDao(): ActivityDao
    abstract fun workSessionDao(): WorkSessionDao
    abstract fun sessionUsageDao(): SessionUsageDao
    abstract fun availabilityDao(): AvailabilityDao
    abstract fun goalDao(): GoalDao
    abstract fun capsuleDao(): CapsuleDao
    abstract fun weekTaskDao(): WeekTaskDao
    abstract fun placeDao(): PlaceDao

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

        // v4 → v5: timpul pe telefon pentru fiecare sesiune de cronometru
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `session_usage` (
                        `startedAt` INTEGER NOT NULL,
                        `activityId` INTEGER NOT NULL,
                        `phoneMillis` INTEGER NOT NULL,
                        `topApps` TEXT NOT NULL,
                        `collectedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`startedAt`)
                    )
                    """.trimIndent()
                )
            }
        }

        // v5 → v6: blocurile fixe (cursuri, masă, somn) și zilele libere
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `availability_rule` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `daysMask` INTEGER NOT NULL,
                        `startMinute` INTEGER NOT NULL,
                        `endMinute` INTEGER NOT NULL,
                        `travelBeforeMinutes` INTEGER NOT NULL,
                        `travelAfterMinutes` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `day_off` (`date` TEXT NOT NULL, PRIMARY KEY(`date`))")
            }
        }

        // v6 → v7: obiective, categoriile lor și etapele
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `goal` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `deadline` TEXT,
                        `targetMinutes` INTEGER,
                        `targetActivities` INTEGER,
                        `startDate` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `note` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goal_category` (`goalId` INTEGER NOT NULL, " +
                            "`categoryId` INTEGER NOT NULL, PRIMARY KEY(`goalId`, `categoryId`))"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `goal_step` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `goalId` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `done` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // v7 → v8: capsulele timpului și obiectivele legate de ele
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `time_capsule` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `expectations` TEXT NOT NULL,
                        `startDate` TEXT NOT NULL,
                        `unlockDate` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `openedAt` INTEGER,
                        `videoFile` TEXT,
                        `openWhenSlowing` INTEGER NOT NULL,
                        `lastNudgeDate` TEXT,
                        `readyNotified` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `capsule_goal` (`capsuleId` INTEGER NOT NULL, " +
                            "`goalId` INTEGER NOT NULL, `percentAtStart` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`capsuleId`, `goalId`))"
                )
            }
        }

        // v8 → v9: etichetele activităților
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        // v9 → v10: planul pe săptămână (sarcini + legătura activităților de ele)
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `week_task` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                            "`categoryId` INTEGER, `weekStart` TEXT NOT NULL, `totalMinutes` INTEGER NOT NULL, " +
                            "`sessionMinutes` INTEGER NOT NULL, `deadline` TEXT, `pinnedDate` TEXT, " +
                            "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_week_task_weekStart` ON `week_task` (`weekStart`)")
                db.execSQL("ALTER TABLE activity ADD COLUMN weekTaskId INTEGER")
            }
        }

        // v10 → v11: locurile (Facultate, Sală…) alese pe hartă
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                            "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                            "`radiusMeters` INTEGER NOT NULL, `address` TEXT NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "digitalmirror.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}