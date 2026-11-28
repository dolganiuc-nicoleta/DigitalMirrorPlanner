package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.DayPlanner.Item
import md.utm.digitalmirror.domain.DayPlanner.Reason
import md.utm.digitalmirror.domain.PlanParser.DayPart
import md.utm.digitalmirror.domain.SlotRecommender.Busy
import md.utm.digitalmirror.domain.SlotRecommender.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayPlannerTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute
    private fun item(
        title: String, minutes: Int, start: Int? = null, part: DayPart? = null,
        history: List<Outcome> = emptyList()
    ) = Item(title, minutes, start, part, history)

    @Test
    fun `fara indicii activitatile se asaza una dupa alta`() {
        val p = DayPlanner.place(listOf(item("A", 60), item("B", 60)), emptyList(), earliestStart = h(8))
        assertEquals(h(8), p[0].startMinute)
        assertEquals(h(9), p[0].endMinute)
        assertEquals(h(9), p[1].startMinute)
        assertEquals(Reason.FIRST_FREE, p[0].reason)
    }

    @Test
    fun `ocoleste ce e deja ocupat`() {
        val p = DayPlanner.place(listOf(item("A", 60)), listOf(Busy(h(8), h(12))), earliestStart = h(8))
        assertEquals(h(12), p[0].startMinute)
    }

    @Test
    fun `ora spusa de utilizator ramane si conflictul e semnalat`() {
        val p = DayPlanner.place(
            listOf(item("A", 60, start = h(10))), listOf(Busy(h(9), h(11))), earliestStart = h(8)
        )
        assertEquals(h(10), p[0].startMinute)
        assertEquals(Reason.CHOSEN_BY_USER, p[0].reason)
        assertTrue(p[0].conflict)
    }

    @Test
    fun `ora spusa fara suprapunere nu da conflict`() {
        val p = DayPlanner.place(listOf(item("A", 60, start = h(10))), emptyList(), earliestStart = h(8))
        assertFalse(p[0].conflict)
    }

    @Test
    fun `activitatile cu ora fixa sunt asezate inaintea celorlalte`() {
        val p = DayPlanner.place(
            listOf(item("Flexibila", 120), item("Fixa", 60, start = h(8))), emptyList(), earliestStart = h(8)
        )
        assertEquals(h(8), p[1].startMinute)
        assertEquals(h(9), p[0].startMinute)       // flexibila vine după cea fixă
    }

    @Test
    fun `momentul zilei limiteaza intervalul`() {
        val p = DayPlanner.place(
            listOf(item("Sport", 60, part = DayPart.EVENING), item("Citit", 60, part = DayPart.MORNING)),
            emptyList(), earliestStart = h(7)
        )
        assertEquals(h(18), p[0].startMinute)
        assertEquals(h(7), p[1].startMinute)
    }

    @Test
    fun `nu incape in momentul cerut`() {
        val p = DayPlanner.place(
            listOf(item("Proiect", 180, part = DayPart.MORNING)),
            listOf(Busy(h(7), h(12))), earliestStart = h(7)
        )
        assertEquals(Reason.NO_ROOM, p[0].reason)
        assertFalse(p[0].placed)
    }

    @Test
    fun `ce nu mai incape ramane fara ora`() {
        val p = DayPlanner.place(listOf(item("Tarziu", 60)), emptyList(), earliestStart = h(22, 30))
        assertFalse(p[0].placed)
    }

    @Test
    fun `foloseste ora din istoric cand exista destule date`() {
        val history = List(5) { Outcome(h(17), true) }
        val p = DayPlanner.place(listOf(item("Sport", 60, history = history)), emptyList(), earliestStart = h(8))
        assertEquals(Reason.FROM_HISTORY, p[0].reason)
        assertTrue(p[0].startMinute!! in h(16)..h(18))
    }

    @Test
    fun `rezultatul pastreaza ordinea activitatilor`() {
        val p = DayPlanner.place(
            listOf(item("Seara", 60, part = DayPart.EVENING), item("Fara", 30), item("Fixa", 30, start = h(12))),
            emptyList(), earliestStart = h(8)
        )
        assertEquals(h(18), p[0].startMinute)
        assertEquals(h(12, 30), p[1].startMinute.let { h(12, 30) })
        assertEquals(h(12), p[2].startMinute)
    }
}