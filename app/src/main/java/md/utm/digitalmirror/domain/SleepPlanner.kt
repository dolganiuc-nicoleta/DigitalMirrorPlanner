package md.utm.digitalmirror.domain

import kotlin.math.roundToInt

/**
 * Propune ora de trezire și ora de culcare, din somnul tău real și din cum arată ziua următoare.
 *
 *  - cât dormi de obicei = mediana nopților recente (Health Connect sau introdus de tine);
 *  - te trezești la ora obișnuită, dar mai devreme dacă ziua începe devreme: înainte de primul lucru
 *    (sau de pregătirea de plecare) trebuie să încapă dimineața ta ([MORNING_MINUTES]) și micul dejun;
 *  - te culci cât să dormi cât dormi de obicei, plus timpul în care adormi ([FALL_ASLEEP_MINUTES]).
 *
 * Orele de după miezul nopții se scriu peste 24:00 (0:30 = 24 * 60 + 30), ca să se poată compara ușor.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object SleepPlanner {

    const val DEFAULT_SLEEP_MINUTES = 8 * 60
    const val DEFAULT_WAKE = 8 * 60
    const val MORNING_MINUTES = 20           // te trezești, baie, cafea: înainte de orice altceva
    const val FALL_ASLEEP_MINUTES = 15
    const val MIN_NIGHTS = 3                 // de la câte nopți avem încredere în obiceiul tău
    private const val DAY = 24 * 60

    /** O noapte din istoric. [bedtime] și [wake] în minute de la miezul nopții. */
    data class Night(val sleepMinutes: Int?, val bedtime: Int?, val wake: Int?)

    enum class Reason {
        USUAL,          // ora ta obișnuită de trezire încape
        EARLY_START,    // ziua începe devreme: te trezești mai devreme decât de obicei
        DEFAULT,        // nu avem destule nopți: valori implicite
        CHOSEN          // ai ales tu ora pentru ziua asta (excepție în scheletul săptămânii)
    }

    data class Plan(
        val wake: Int,                // ora de trezire în ziua respectivă
        val sleepMinutes: Int,        // cât dormi de obicei
        val reason: Reason,
        val nights: Int,              // pe câte nopți s-a calculat
        val usualWake: Int?,
        val usualBedtime: Int?        // peste 24:00 dacă e după miezul nopții
    ) {
        /** Ora de culcare din seara dinainte, ca să dormi [sleepMinutes]; poate fi peste 24:00 (seara de dinainte). */
        val bedtimeBefore: Int get() = wake - sleepMinutes - FALL_ASLEEP_MINUTES + DAY
    }

    /**
     * @param nights nopțile recente
     * @param firstStart primul lucru din zi (sau începutul pregătirii de plecare), în minute; null = zi liberă
     * @param breakfastMinutes micul dejun, dacă e pornit; se adaugă la dimineață
     * @param chosenWake ora de trezire aleasă de tine pentru ziua asta; are prioritate
     */
    /**
     * Ritmul spus de tine la configurarea inițială: de la el pornim până se adună destule nopți ([MIN_NIGHTS]).
     * [bed] poate fi peste 24:00 (0:30 = 24:30).
     */
    data class Habit(
        val wake: Int = DEFAULT_WAKE,
        val bed: Int = DEFAULT_BEDTIME,
        val sleepMinutes: Int = DEFAULT_SLEEP_MINUTES
    ) {
        companion object {
            /** Din orele spuse de tine; somnul e diferența dintre ele (fără adormirea), dacă e credibil. */
            fun of(wake: Int, bed: Int): Habit {
                val sleep = (((wake - bed - FALL_ASLEEP_MINUTES) % DAY) + DAY) % DAY
                return Habit(wake, if (bed < 12 * 60) bed + DAY else bed, sleep.takeIf { it in 4 * 60..12 * 60 } ?: DEFAULT_SLEEP_MINUTES)
            }
        }
    }

    fun plan(nights: List<Night>, firstStart: Int?, breakfastMinutes: Int = 0, chosenWake: Int? = null, habit: Habit = Habit()): Plan {
        val good = nights.filter(::plausible)
        val sleeps = good.mapNotNull { it.sleepMinutes }
        val wakes = good.mapNotNull { it.wake }
        val beds = good.mapNotNull { it.bedtime }.map { if (it < 12 * 60) it + DAY else it }   // 0:30 → 24:30

        val enough = sleeps.size >= MIN_NIGHTS
        val sleep = if (enough) median(sleeps) else habit.sleepMinutes
        val usualWake = if (wakes.size >= MIN_NIGHTS) median(wakes) else null
        val usualBed = if (beds.size >= MIN_NIGHTS) median(beds) else null

        if (chosenWake != null) return Plan(chosenWake, sleep, Reason.CHOSEN, sleeps.size, usualWake, usualBed)

        val preferred = usualWake ?: habit.wake
        val latest = firstStart?.let { it - MORNING_MINUTES - breakfastMinutes }

        return if (latest != null && latest < preferred) {
            Plan(roundDown(latest), sleep, Reason.EARLY_START, sleeps.size, usualWake, usualBed)
        } else {
            Plan(preferred, sleep, if (enough && usualWake != null) Reason.USUAL else Reason.DEFAULT, sleeps.size, usualWake, usualBed)
        }
    }

    /**
     * Intervalul în care se pot pune lucruri flexibile (sesiuni, activități noi): de la trezirea ta obișnuită
     * plus dimineața, până la o oră înainte de culcare (perna de seară). Așa o sarcină flexibilă nu te scoală
     * mai devreme și nu te ține treaz; doar lucrurile fixe (cursuri, programări) pot muta trezirea.
     * Fără destule nopți: 8:20–22:00.
     */
    fun activeWindow(nights: List<Night>, chosenWake: Int? = null, habit: Habit = Habit()): Pair<Int, Int> {
        val good = nights.filter(::plausible)
        val wakes = good.mapNotNull { it.wake }
        val beds = good.mapNotNull { it.bedtime }.map { if (it < 12 * 60) it + DAY else it }
        val wake = chosenWake ?: if (wakes.size >= MIN_NIGHTS) median(wakes) else habit.wake
        val bed = if (beds.size >= MIN_NIGHTS) median(beds) else habit.bed
        val start = wake + MORNING_MINUTES
        // perna de seară: nimic flexibil în ultima oră dinainte de culcare
        val end = (bed - WIND_DOWN_MINUTES).coerceAtMost(DAY)
        // un ritm ciudat (date greșite) nu are voie să lase o zi aproape fără loc
        return if (end - start >= 8 * 60) start to end
        else (DEFAULT_WAKE + MORNING_MINUTES) to (DEFAULT_BEDTIME - WIND_DOWN_MINUTES)
    }

    const val DEFAULT_BEDTIME = 23 * 60

    /** Perna de seară: în ultima oră dinainte de culcare nu punem sesiuni sau activități flexibile. */
    const val WIND_DOWN_MINUTES = 60

    /** Cât ai dormit între [bedtime] și [wake] (minute de la miezul nopții), și peste miezul nopții. */
    fun duration(bedtime: Int, wake: Int): Int = ((wake - bedtime) % DAY + DAY) % DAY

    /** Ce propunem la confirmarea de dimineață. */
    data class Check(
        val bedtime: Int,                   // minute de la miezul nopții (0–1439)
        val wake: Int,
        val fromHealth: Boolean,            // orele vin din aplicația de somn
        val partialHealth: Night? = null    // aplicația a salvat doar o bucată din noapte
    ) {
        val minutes: Int get() = duration(bedtime, wake)
    }

    /**
     * Noaptea de confirmat: cea salvată de aplicația de somn, dacă e credibilă;
     * altfel ritmul tău obișnuit (sau 23:00–7:00), cu bucata salvată arătată separat.
     */
    fun check(saved: Night?, recent: List<Night>, habit: Habit = Habit()): Check {
        if (saved != null && plausible(saved) && saved.bedtime != null && saved.wake != null) {
            return Check(saved.bedtime, saved.wake, fromHealth = true)
        }
        val good = recent.filter(::plausible)
        val beds = good.mapNotNull { it.bedtime }.map { if (it < 12 * 60) it + DAY else it }
        val wakes = good.mapNotNull { it.wake }
        val bed = (if (beds.size >= MIN_NIGHTS) median(beds) else habit.bed) % DAY
        val wake = if (wakes.size >= MIN_NIGHTS) median(wakes) else habit.wake
        val partial = saved?.takeIf { it.sleepMinutes != null }
        return Check(bed, wake, fromHealth = false, partialHealth = partial)
    }

    /** Ora scrisă ca pe ceas, și pentru valorile de peste 24:00. */
    fun clock(minute: Int): String {
        val m = ((minute % DAY) + DAY) % DAY
        return "%d:%02d".format(m / 60, m % 60)
    }

    /**
     * O noapte credibilă: între 4 și 14 ore. Aplicațiile de somn salvează uneori doar o bucată din noapte
     * (de ex. 4:10–7:30, până confirmi în ele), iar o astfel de noapte ar strica ritmul tău obișnuit.
     */
    fun plausible(night: Night): Boolean = night.sleepMinutes?.let { it in 4 * 60..14 * 60 } ?: false

    // la 5 minute, în jos: 6:47 → 6:45
    private fun roundDown(minute: Int): Int = (minute / 5) * 5

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToInt()
    }
}
