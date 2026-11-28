package md.utm.digitalmirror.data.entity

import androidx.room.Entity

/**
 * v23 · un rând șters, ca ștergerea să ajungă și pe celelalte dispozitive („piatră funerară”).
 * Se scrie singur, din triggerul de ștergere (vezi SyncSchema); dispare după ce serverul a primit-o.
 */
@Entity(tableName = "sync_tombstone", primaryKeys = ["kind", "uid"])
data class SyncTombstone(
    val kind: String,          // tabela: activity, category…
    val uid: String,
    val deletedAt: Long,
    val baseVersion: Long      // versiunea de pe server a rândului când a fost șters
)
