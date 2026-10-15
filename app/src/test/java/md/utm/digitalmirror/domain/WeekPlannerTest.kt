package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy
import md.utm.digitalmirror.domain.WeekPlanner.DayInput
import md.utm.digitalmirror.domain.WeekPlanner.Risk
import md.utm.digitalmirror.domain.WeekPlanner.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeekPlannerTest {

    private val monday = LocalDate.of(2026, 10, 12)
    private fun day(i: Int, busy: List<Busy> = emptyList(), typical: Int? = 300, planned: Int = 0) =
        DayInput(monday.plusDays(i.toLong()), busy, planned, typical)
    private fun week(typical: Int? = 300) = (0..6).map { day(it, typical = typical) }

    @Test
    fun `imparte in sesiuni si lipeste ramasita mica`() {
        assertEquals(listOf(90, 90, 90), WeekPlanner.split(270, 90))
        assertEquals(listOf(90, 100), WeekPlanner.split(190, 90))
        assertEquals(listOf(60), WeekPlanner.split(60, 90))
        assertEquals(emptyList<Int>(), WeekPlanner.split(0, 90))
    }

    @Test
    fun `sesiunile unei sarcini se raspandesc pe zile diferite`() {
        val plan = WeekPlanner.plan(listOf(Task(1, "Licenta", 360, 90)), week())
        assertEquals(4, plan.sessions.size)
        assertEquals(4, plan.sessions.map { it.date }.distinct().size)
        assertTrue(plan.unplaced.isEmpty())
        assertEquals(360, plan.tasks.single().plannedMinutes)
    }

    @Test
    fun `nicio sesiune nu trece de termen`() {
        val deadline = monday.plusDays(2)
        val plan = WeekPlanner.plan(listOf(Task(1, "Raport", 180, 60, deadline = deadline)), week())
        assertTrue(plan.sessions.all { !it.date.isAfter(deadline) })
        assertEquals(3, plan.sessions.size)
    }

    @Test
    fun `sarcina fixata ramane pe ziua ei`() {
        val thursday = monday.plusDays(3)
        val plan = WeekPlanner.plan(
            listOf(Task(1, "Laborator", 120, 120, pinnedDate = thursday), Task(2, "Citit", 120, 60)), week()
        )
        val lab = plan.sessions.single { it.taskId == 1L }
        assertEquals(thursday, lab.date)
        assertTrue(lab.pinned)
    }

    @Test
    fun `ocoleste intervalele ocupate`() {
        val days = listOf(day(0, busy = listOf(Busy(8 * 60, 12 * 60))), day(1, typical = 0))
        val plan = WeekPlanner.plan(listOf(Task(1, "A", 60, 60)), days)
        val s = plan.sessions.single()
        assertEquals(monday, s.date)
        assertEquals(12 * 60, s.startMinute)
    }

    @Test
    fun `ce nu incape ramane neasezat si riscul e mare`() {
        val days = listOf(day(0, busy = listOf(Busy(0, 24 * 60))))
        val plan = WeekPlanner.plan(listOf(Task(1, "A", 60, 60)), days)
        assertEquals(1, plan.unplaced.size)
        assertEquals(Risk.HIGH, plan.tasks.single().risk)
        assertEquals(0, plan.chancePercent)
    }

    @Test
    fun `ziua peste buget e folosita doar la nevoie si e marcata riscanta`() {
        val days = listOf(day(0, typical = 60))
        val plan = WeekPlanner.plan(listOf(Task(1, "A", 120, 60)), days)
        assertEquals(2, plan.sessions.size)
        assertEquals(monday, plan.riskyDay)
        assertEquals(Risk.HIGH, plan.tasks.single().risk)
    }

    @Test
    fun `sarcinile cu termen apropiat se aseaza primele`() {
        val days = listOf(day(0, typical = 60), day(1, typical = 60))
        val plan = WeekPlanner.plan(
            listOf(Task(1, "Tarziu", 60, 60, deadline = monday.plusDays(1)), Task(2, "Urgent", 60, 60, deadline = monday)),
            days
        )
        assertEquals(monday, plan.sessions.single { it.taskId == 2L }.date)
        assertEquals(monday.plusDays(1), plan.sessions.single { it.taskId == 1L }.date)
    }

    @Test
    fun `sansele scad cand planul e incarcat`() {
        val light = WeekPlanner.plan(listOf(Task(1, "A", 120, 60)), week())
        val heavy = WeekPlanner.plan(listOf(Task(1, "A", 120, 60)), listOf(day(0, typical = 60)))
        assertTrue(light.chancePercent!! > heavy.chancePercent!!)
        assertEquals(75, light.chancePercent)
    }

    @Test
    fun `rata de reusita din istoric conteaza`() {
        val plan = WeekPlanner.plan(listOf(Task(1, "A", 60, 60)), week(), successRate = 0.9)
        assertEquals(90, plan.chancePercent)
    }

    @Test
    fun `fara sarcini nu exista procent`() {
        assertNull(WeekPlanner.plan(emptyList(), week()).chancePercent)
    }

    @Test
    fun `azi nu se aseaza in trecut`() {
        val plan = WeekPlanner.plan(
            listOf(Task(1, "A", 60, 60)), listOf(day(0)), earliestToday = 15 * 60, today = monday
        )
        assertEquals(15 * 60, plan.sessions.single().startMinute)
    }

    @Test
    fun `zi liberata de scenariu nu primeste sesiuni`() {
        val thursday = 3
        val days = (0..6).map { if (it == thursday) day(it, busy = listOf(Busy(0, 24 * 60))) else day(it) }
        val plan = WeekPlanner.plan(listOf(Task(1, "A", 600, 60)), days)
        assertTrue(plan.sessions.none { it.date == monday.plusDays(3) })
    }

    @Test
    fun `scenariul vreau liber joi dupa 14`() {
        val r = WeekScenario.parse("Vreau liber joi după 14", monday, monday)
        val b = r.blocks.single()
        assertEquals(monday.plusDays(3), b.date)
        assertEquals(14 * 60, b.fromMinute)
        assertEquals(24 * 60, b.toMinute)
    }

    @Test
    fun `scenarii cu zi intreaga, interval si moment al zilei`() {
        val r = WeekScenario.parse("liber miercuri; ocupat marți 10-12, nu pot vineri dimineața", monday, monday)
        assertEquals(3, r.blocks.size)
        assertTrue(r.blocks[0].wholeDay)
        assertEquals(monday.plusDays(1), r.blocks[1].date)
        assertEquals(10 * 60, r.blocks[1].fromMinute)
        assertEquals(12 * 60, r.blocks[1].toMinute)
        assertEquals(PlanParser.DayPart.MORNING.from, r.blocks[2].fromMinute)
    }

    @Test
    fun `inainte de si dupa-amiaza`() {
        val a = WeekScenario.parse("liber luni înainte de 12", monday, monday).blocks.single()
        assertEquals(0, a.fromMinute)
        assertEquals(12 * 60, a.toMinute)
        val b = WeekScenario.parse("ocupat sâmbătă după-amiaza", monday, monday).blocks.single()
        assertEquals(PlanParser.DayPart.AFTERNOON.from, b.fromMinute)
    }

    @Test
    fun `propozitiile necunoscute sunt returnate`() {
        val r = WeekScenario.parse("liber joi, mă gândesc la altceva", monday, monday)
        assertEquals(1, r.blocks.size)
        assertEquals(1, r.ignored.size)
        assertNotNull(r.ignored.first())
    }
}