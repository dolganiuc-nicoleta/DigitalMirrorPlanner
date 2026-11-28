package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.WeekPlanner.DayInput
import md.utm.digitalmirror.domain.WeekPlanner.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrepPlannerTest {

    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun `zilele sunt de azi pana in ajunul evenimentului`() {
        val days = PrepPlanner.daysBefore(today.plusDays(7), today)
        assertEquals(7, days.size)
        assertEquals(today, days.first())
        assertEquals(today.plusDays(6), days.last())
    }

    @Test
    fun `evenimentul de azi sau din trecut nu are zile`() {
        assertTrue(PrepPlanner.daysBefore(today, today).isEmpty())
        assertTrue(PrepPlanner.daysBefore(today.minusDays(1), today).isEmpty())
    }

    @Test
    fun `ferestrele urmeaza ordinea pasilor si ultimul pas ajunge in ultima zi`() {
        val days = PrepPlanner.daysBefore(today.plusDays(10), today)
        val w = PrepPlanner.windows(days, listOf(120, 120, 60, 60))
        assertEquals(4, w.size)
        assertEquals(days.first(), w.first().from)
        assertEquals(days.last(), w.last().to)
        for (i in 1 until w.size) assertTrue(!w[i].from.isBefore(w[i - 1].from))
        for (x in w) assertTrue(!x.to.isBefore(x.from))
    }

    @Test
    fun `cu o singura zi toti pasii cad in ea`() {
        val days = PrepPlanner.daysBefore(today.plusDays(1), today)
        val w = PrepPlanner.windows(days, listOf(60, 60, 30))
        assertTrue(w.all { it.from == today && it.to == today })
    }

    @Test
    fun `procentul de pregatire nu depaseste 100 si nu numara peste durata pasului`() {
        assertEquals(0, PrepPlanner.readyPercent(listOf(60, 60), listOf(0, 0)))
        assertEquals(50, PrepPlanner.readyPercent(listOf(60, 60), listOf(60, 0)))
        assertEquals(50, PrepPlanner.readyPercent(listOf(60, 60), listOf(200, 0)))   // peste durata pasului nu se numără
        assertEquals(100, PrepPlanner.readyPercent(listOf(60, 60), listOf(60, 60)))
        assertEquals(0, PrepPlanner.readyPercent(emptyList(), emptyList()))
    }

    @Test
    fun `sabloanele pastreaza totalul, in sferturi de ora`() {
        for (t in PrepTemplates.ALL) {
            assertEquals(100, t.steps.sumOf { it.second })
            for (total in listOf(120, 240, 360, 600, 725)) {
                val steps = PrepTemplates.build(t, total)
                assertEquals(t.steps.size, steps.size)
                assertEquals(total / 15 * 15, steps.sumOf { it.minutes })
                assertTrue(steps.all { it.minutes >= 15 && it.minutes % 15 == 0 })
            }
        }
    }

    @Test
    fun `planul respecta ferestrele si pasii se fac in ordine`() {
        val event = today.plusDays(8)
        val days = PrepPlanner.daysBefore(event, today)
        val steps = PrepTemplates.build(PrepTemplates.byKey("lab"), 360)
        val windows = PrepPlanner.windows(days, steps.map { it.minutes })
        val tasks = steps.mapIndexed { i, s ->
            Task(i + 1L, s.title, s.minutes, 90, deadline = windows[i].to, notBefore = windows[i].from)
        }
        val plan = WeekPlanner.plan(tasks, days.map { DayInput(it, emptyList(), 0, 300) }, today = null)
        assertTrue(plan.unplaced.isEmpty())
        assertEquals(360, plan.sessions.sumOf { it.minutes })
        // primul pas nu vine după ultimul
        val firstEnd = plan.sessions.filter { it.taskId == 1L }.maxOf { it.date }
        val lastStart = plan.sessions.filter { it.taskId == steps.size.toLong() }.minOf { it.date }
        assertTrue(!firstEnd.isAfter(lastStart))
        // nimic nu trece de ajunul evenimentului
        assertTrue(plan.sessions.all { it.date.isBefore(event) })
    }

    @Test
    fun `daca fereastra e plina, sesiunea merge mai devreme in loc sa se piarda`() {
        val d1 = today; val d2 = today.plusDays(1)
        val days = listOf(DayInput(d1, emptyList(), 0, 300), DayInput(d2, listOf(SlotRecommender.Busy(8 * 60, 22 * 60)), 0, 300))
        val plan = WeekPlanner.plan(listOf(Task(1, "Pas", 60, 60, notBefore = d2)), days)
        assertTrue(plan.unplaced.isEmpty())
        assertEquals(d1, plan.sessions.single().date)
    }
}
