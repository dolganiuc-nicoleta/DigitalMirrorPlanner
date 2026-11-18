package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy

/**
 * Privirea de seară asupra zilei de mâine: rezumatul și întrebările, doar la excepții.
 * Ca ritualul să rămână sub 30 de secunde, nu întrebăm nimic când totul arată normal.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object EveningReview {

    /** Cât de mai devreme decât de obicei merită să întrebăm (sub atât, nu deranjăm). */
    const val EARLY_WAKE_MINUTES = 30

    /** Un lucru din ziua de mâine. [flexible] = pus de aplicație (sesiune din plan), deci se poate muta. */
    data class Item(
        val start: Int,
        val end: Int,
        val title: String,
        val flexible: Boolean,
        val activityId: Long? = null,
        val placeId: Long? = null
    )

    sealed interface Issue {
        /** Ceva flexibil te-ar scula mai devreme decât de obicei; [moveTo] = unde încape după trezirea obișnuită. */
        data class EarlyBecauseFlexible(val item: Item, val wake: Int, val usualWake: Int, val moveTo: Int?) : Issue
        /** Un eveniment din calendar fără loc: fără el nu putem calcula drumul. */
        data class NoPlace(val title: String) : Issue
        /** Nu e loc pentru o masă în fereastra ei. */
        data class NoMeal(val window: MealPlanner.Window) : Issue
        /** Ziua e mult mai plină decât reușești de obicei. */
        data class Overloaded(val planned: Int, val typical: Int) : Issue
    }

    /**
     * @param items ce e mâine, cu oră (activități, calendar, blocuri)
     * @param sleep trezirea propusă pentru mâine
     * @param window intervalul pentru lucruri flexibile (după trezirea obișnuită, până la culcare)
     * @param mealBusy locul păstrat pentru mese (ca mutarea să nu cadă peste ele)
     * @param eventsWithoutPlace titlurile evenimentelor din calendar fără loc (doar dacă ai locuri salvate)
     */
    fun issues(
        items: List<Item>,
        sleep: SleepPlanner.Plan,
        window: Pair<Int, Int>,
        mealBusy: List<Busy> = emptyList(),
        missingMeals: List<MealPlanner.Window> = emptyList(),
        eventsWithoutPlace: List<String> = emptyList(),
        load: DayLoad.Result? = null
    ): List<Issue> {
        val result = mutableListOf<Issue>()

        // 1. trezirea mai devreme din cauza unui lucru care se poate muta
        val first = items.minByOrNull { it.start }
        val usual = sleep.usualWake
        if (first != null && first.flexible && sleep.reason == SleepPlanner.Reason.EARLY_START &&
            usual != null && usual - sleep.wake >= EARLY_WAKE_MINUTES
        ) {
            val others = items.filter { it !== first }.map { Busy(it.start, it.end) } + mealBusy
            val moveTo = SlotRecommender.firstFree(first.end - first.start, others, window.first, window.second)
                ?.startMinute?.takeIf { it != first.start }
            result += Issue.EarlyBecauseFlexible(first, sleep.wake, usual, moveTo)
        }

        // 2. evenimente fără loc (o singură dată pe titlu)
        eventsWithoutPlace.distinct().forEach { result += Issue.NoPlace(it) }

        // 3. mesele fără loc
        missingMeals.forEach { result += Issue.NoMeal(it) }

        // 4. prea mult pentru o zi
        if (load != null && load.status == DayLoad.Status.OVERLOADED && load.typicalMinutes != null) {
            result += Issue.Overloaded(load.plannedMinutes, load.typicalMinutes)
        }
        return result
    }
}
