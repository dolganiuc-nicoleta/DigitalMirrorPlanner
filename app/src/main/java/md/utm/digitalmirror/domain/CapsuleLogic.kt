package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.GoalProgress.Momentum
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Regulile capsulei timpului, fără Android, ca să se poată testa simplu:
 *  - dacă e sigilată, gata de deschis sau deschisă;
 *  - când are sens să-i amintim utilizatorului de ea pentru că a încetinit;
 *  - cât s-a schimbat un obiectiv între sigilare și deschidere.
 */
object CapsuleLogic {

    const val MIN_AGE_DAYS = 3          // în primele zile nu întrebăm nimic
    const val NUDGE_EVERY_DAYS = 7      // cel mult o amintire pe săptămână

    enum class State { LOCKED, READY, OPENED }

    enum class Nudge { NONE, SLOWING, STAGNANT }

    /** Un obiectiv din comparația de la deschidere; percentNow e null dacă obiectivul a fost șters între timp. */
    data class GoalLine(val title: String, val percentStart: Int, val percentNow: Int?)

    fun state(unlockDate: LocalDate, opened: Boolean, today: LocalDate): State = when {
        opened -> State.OPENED
        !today.isBefore(unlockDate) -> State.READY
        else -> State.LOCKED
    }

    /**
     * Doar o capsulă încă sigilată, cu opțiunea activată de utilizator, poate fi amintită.
     * Se uită la ritmul obiectivelor active legate de ea: oprirea contează mai mult decât încetinirea.
     * lastNudge e null când vrem doar să aflăm dacă situația există (de exemplu pentru ecran).
     */
    fun nudge(
        state: State,
        openWhenSlowing: Boolean,
        createdOn: LocalDate,
        lastNudge: LocalDate?,
        momenta: List<Momentum>,
        today: LocalDate
    ): Nudge {
        if (state != State.LOCKED || !openWhenSlowing) return Nudge.NONE
        if (ChronoUnit.DAYS.between(createdOn, today) < MIN_AGE_DAYS) return Nudge.NONE
        if (lastNudge != null && ChronoUnit.DAYS.between(lastNudge, today) < NUDGE_EVERY_DAYS) return Nudge.NONE
        return when {
            Momentum.STAGNANT in momenta -> Nudge.STAGNANT
            Momentum.SLOWING in momenta -> Nudge.SLOWING
            else -> Nudge.NONE
        }
    }

    /** Zilele acoperite de capsulă, cu prima și ultima zi incluse. */
    fun daysCovered(start: LocalDate, end: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, end).toInt() + 1).coerceAtLeast(1)

    /** Câte puncte procentuale s-au adăugat; null dacă obiectivul nu mai există. */
    fun change(line: GoalLine): Int? = line.percentNow?.let { it - line.percentStart }
}