package md.utm.digitalmirror.domain

import kotlin.math.roundToInt

/**
 * Observația pentru o activitate care se repetă („Laborator broker”):
 * cât durează de obicei și cu cât depășește, de obicei, durata planificată.
 *
 * Folosește mediana, nu media, ca o sesiune neobișnuită (cronometru uitat pornit)
 * să nu schimbe concluzia. Se iau în calcul doar duratele sigure (cronometru sau corectate manual).
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object ActivityInsight {

    const val MIN_SESSIONS = 3

    data class Session(
        val plannedMinutes: Int?,
        val actualMinutes: Int,
        val source: String?        // TIMER, MANUAL, AS_PLANNED…
    )

    data class Result(
        val reliableCount: Int,            // sesiuni cu durată sigură
        val timerCount: Int,
        val manualCount: Int,
        val typicalMinutes: Int?,          // mediana duratelor reale
        val overrunPercent: Int?           // mediana depășirii față de plan, în procente (negativ = mai repede)
    ) {
        val enoughData: Boolean get() = reliableCount >= MIN_SESSIONS
    }

    fun compute(sessions: List<Session>): Result {
        val reliable = sessions.filter { it.actualMinutes > 0 && it.source in setOf("TIMER", "MANUAL") }
        val withPlan = reliable.filter { (it.plannedMinutes ?: 0) > 0 }

        return Result(
            reliableCount = reliable.size,
            timerCount = reliable.count { it.source == "TIMER" },
            manualCount = reliable.count { it.source == "MANUAL" },
            typicalMinutes = if (reliable.isEmpty()) null else median(reliable.map { it.actualMinutes.toDouble() }).roundToInt(),
            overrunPercent = if (withPlan.size < MIN_SESSIONS) null
            else ((median(withPlan.map { it.actualMinutes.toDouble() / it.plannedMinutes!! }) - 1) * 100).roundToInt()
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}