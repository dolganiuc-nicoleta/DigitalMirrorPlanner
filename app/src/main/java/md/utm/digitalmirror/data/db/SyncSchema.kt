package md.utm.digitalmirror.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Evidența schimbărilor pentru sincronizare, ținută de baza de date însăși (triggere SQLite),
 * ca nicio modificare să nu scape, oricare ecran ar face-o:
 *  - la inserare, rândul primește un UUID și ora modificării;
 *  - la orice modificare, ora se actualizează (rândul devine „de trimis”: updatedAt ≠ syncedAt);
 *  - la ștergere rămâne o piatră funerară (sync_tombstone).
 * Cât timp se aplică ce vine de pe server (sync_meta.applying = 1), modificările și ștergerile nu se mai notează,
 * ca să nu fie trimise înapoi.
 */
object SyncSchema : RoomDatabase.Callback() {

    /** Tabelele care se sincronizează; restul (locuri, somn, prezențe) rămân pe telefon. */
    val TABLES = listOf("category", "week_task", "activity", "work_session", "availability_rule", "day_off")

    /** Ora de acum, în milisecunde, calculată de SQLite. */
    private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val APPLYING = "(SELECT applying FROM sync_meta WHERE id = 1)"

    private fun uidFor(table: String) = if (table == "day_off") "NEW.date" else "lower(hex(randomblob(16)))"

    /** v22 → v23: coloanele noi, UUID-uri pentru rândurile vechi (toate „de trimis”) și tabela de pietre funerare. */
    fun migrate(db: SupportSQLiteDatabase) {
        for (t in TABLES) {
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `uid` TEXT")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `syncedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$t` ADD COLUMN `syncVersion` INTEGER NOT NULL DEFAULT 0")
            val uid = if (t == "day_off") "`date`" else "lower(hex(randomblob(16)))"
            db.execSQL("UPDATE `$t` SET `uid` = $uid, `updatedAt` = $NOW")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_uid` ON `$t` (`uid`)")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_tombstone` (`kind` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                    "`deletedAt` INTEGER NOT NULL, `baseVersion` INTEGER NOT NULL, PRIMARY KEY(`kind`, `uid`))"
        )
    }

    /** La fiecare deschidere (și după instalare): tabela de stare și triggerele, dacă lipsesc. */
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta (id INTEGER PRIMARY KEY CHECK (id = 1), applying INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO sync_meta (id, applying) VALUES (1, 0)")
        // dacă aplicația s-a oprit în mijlocul unei aplicări, nu rămânem „surzi” la schimbări
        db.execSQL("UPDATE sync_meta SET applying = 0")
        for (t in TABLES) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_${t}_insert AFTER INSERT ON `$t`
                WHEN NEW.uid IS NULL OR NEW.updatedAt = 0
                BEGIN
                    UPDATE `$t` SET
                        uid = COALESCE(NEW.uid, ${uidFor(t)}),
                        updatedAt = CASE WHEN NEW.updatedAt = 0 THEN $NOW ELSE NEW.updatedAt END
                    WHERE rowid = NEW.rowid;
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_${t}_update AFTER UPDATE ON `$t`
                WHEN NEW.updatedAt = OLD.updatedAt AND $APPLYING = 0
                BEGIN
                    UPDATE `$t` SET updatedAt = MAX($NOW, OLD.updatedAt + 1) WHERE rowid = NEW.rowid;
                END
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS sync_${t}_delete AFTER DELETE ON `$t`
                WHEN OLD.uid IS NOT NULL AND $APPLYING = 0
                BEGIN
                    INSERT OR REPLACE INTO sync_tombstone (kind, uid, deletedAt, baseVersion)
                    VALUES ('$t', OLD.uid, $NOW, OLD.syncVersion);
                END
                """.trimIndent()
            )
        }
    }

    /**
     * Rulează [block] fără să noteze schimbări (aplicarea datelor de pe server, restaurarea unei copii,
     * golirea bazei la ieșirea din cont).
     */
    fun <T> quietly(db: SupportSQLiteDatabase, block: () -> T): T {
        db.execSQL("UPDATE sync_meta SET applying = 1")
        try {
            return block()
        } finally {
            db.execSQL("UPDATE sync_meta SET applying = 0")
        }
    }
}
