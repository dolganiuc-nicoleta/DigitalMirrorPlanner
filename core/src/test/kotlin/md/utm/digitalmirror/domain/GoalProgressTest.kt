package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.GoalProgress.Contribution
import md.utm.digitalmirror.domain.GoalProgress.Goal
import md.utm.digitalmirror.domain.GoalProgress.Momentum
import md.utm.digitalmirror.domain.GoalProgress.Pace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GoalProgressTest {

    private val today = LocalDate.of(2026, 10, 13)
    private fun ago(days: Int) = today.minusDays(days.toLong())

    // 12 zile din 24 au trecut: ritmul liniar e la 50%
    private val goal = Goal(
        startDate = LocalDate.of(2026, 10, 1),
        deadline = LocalDate.of(2026, 10, 25),
        targetMinutes = 600,
        targetActivities = null
    )

    private fun minutes(vararg values: Int) =
        values.mapIndexed { i, m -> Contribution(ago(i + 1), m) }

    @Test
    fun `calculeaza procentul din minute`() {
        val r = GoalProgress.compute(goal, minutes(300), today)
        assertEquals(50, r.percent)
        assertEquals(300, r.minutesDone)
        assertEquals(1, r.activitiesDone)
    }

    @Test
    fun `cu doua tinte progresul e media lor`() {
        val both = goal.copy(targetActivities = 10)
        // 3 activități de 100 min: minute 300/600 = 50%, activități 3/10 = 30%
        val r = GoalProgress.compute(both, minutes(100, 100, 100), today)
        assertEquals(40, r.percent)
    }

    @Test
    fun `compara cu ritmul liniar pana la termen`() {
        assertEquals(50, GoalProgress.compute(goal, minutes(300), today).expectedPercent)
        assertEquals(Pace.BEHIND, GoalProgress.compute(goal, minutes(120), today).pace)    // 20%
        assertEquals(Pace.ON_TRACK, GoalProgress.compute(goal, minutes(300), today).pace)  // 50%
        assertEquals(Pace.AHEAD, GoalProgress.compute(goal, minutes(390), today).pace)     // 65%
    }

    @Test
    fun `obiectivul atins sau cu termenul depasit`() {
        assertEquals(Pace.DONE, GoalProgress.compute(goal, minutes(700), today).pace)
        assertEquals(100, GoalProgress.compute(goal, minutes(700), today).percent)

        val late = LocalDate.of(2026, 10, 30)
        assertEquals(Pace.OVERDUE, GoalProgress.compute(goal, minutes(300), late).pace)
    }

    @Test
    fun `zilele ramase pana la termen`() {
        assertEquals(12, GoalProgress.compute(goal, emptyList(), today).daysLeft)
    }

    @Test
    fun `fara termen nu compara cu un ritm`() {
        val open = goal.copy(deadline = null)
        val r = GoalProgress.compute(open, minutes(100), today)
        assertEquals(Pace.NO_DEADLINE, r.pace)
        assertNull(r.expectedPercent)
        assertNull(r.daysLeft)
    }

    @Test
    fun `estimeaza data de final dupa ritmul recent`() {
        // 5 zile × 100 min din 1000 = 50% în 13 zile → încă 50% cere 13 zile
        val g = goal.copy(targetMinutes = 1000)
        val r = GoalProgress.compute(g, minutes(100, 100, 100, 100, 100), today)
        assertEquals(LocalDate.of(2026, 10, 26), r.forecast)
    }

    @Test
    fun `fara activitati recente nu estimeaza nicio data`() {
        assertNull(GoalProgress.compute(goal, emptyList(), today).forecast)
    }

    @Test
    fun `ignora activitatile de dinainte de inceputul obiectivului`() {
        val r = GoalProgress.compute(goal, listOf(Contribution(LocalDate.of(2026, 9, 20), 500)), today)
        assertEquals(0, r.percent)
    }

    @Test
    fun `stagnare dupa 7 zile fara activitate`() {
        val r = GoalProgress.compute(goal, listOf(Contribution(ago(10), 60)), today)
        assertEquals(Momentum.STAGNANT, r.momentum)
        assertEquals(10, r.daysSinceLast)
    }

    @Test
    fun `activitate recenta inseamna ritm normal`() {
        val r = GoalProgress.compute(goal, listOf(Contribution(ago(3), 60)), today)
        assertEquals(Momentum.OK, r.momentum)
    }

    @Test
    fun `prea devreme pentru concluzii`() {
        val fresh = goal.copy(startDate = ago(3))
        assertEquals(Momentum.TOO_EARLY, GoalProgress.compute(fresh, emptyList(), today).momentum)
    }

    @Test
    fun `ritm in scadere fata de saptamanile dinainte`() {
        val long = Goal(ago(42), null, targetMinutes = 10_000, targetActivities = null)
        // înainte: 6 activități de 100 min în zilele 8–27; ultima săptămână: o activitate de 50 min
        val before = listOf(8, 10, 13, 17, 21, 26).map { Contribution(ago(it), 100) }
        val r = GoalProgress.compute(long, before + Contribution(ago(2), 50), today)
        assertEquals(Momentum.SLOWING, r.momentum)
    }
}