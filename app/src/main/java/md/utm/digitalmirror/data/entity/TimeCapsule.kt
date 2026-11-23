package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * O capsulă a timpului: un mesaj pe care utilizatorul și-l scrie singur, sigilat până la o dată aleasă.
 * Odată creată nu se mai editează. Videoul (opțional) e un fișier din spațiul aplicației; aici ținem doar numele lui.
 */
@Entity(tableName = "time_capsule")
data class TimeCapsule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val message: String,
    val expectations: String,          // ce speră utilizatorul să realizeze; poate fi gol
    val startDate: String,             // yyyy-MM-dd, ziua sigilării
    val unlockDate: String,            // yyyy-MM-dd, ziua deschiderii
    val createdAt: Long,
    val openedAt: Long?,               // null = încă sigilată
    val videoFile: String?,            // numele fișierului din filesDir/capsules
    val openWhenSlowing: Boolean,      // permite aplicației să întrebe dacă o deschizi mai devreme
    val lastNudgeDate: String?,        // ultima dată când i-am amintit de capsulă
    val readyNotified: Boolean,        // true după notificarea „se poate deschide”
    // v20 · cât de bine îți estimai timpul la sigilare (CapsuleBets.estimateError); null = prea puține date
    val estimateErrorAtStart: Int? = null
)