package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.MealPlanner.Kind
import md.utm.digitalmirror.domain.MealPlanner.Window
import md.utm.digitalmirror.domain.SlotRecommender.Busy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPlannerTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private val lunch = Window(Kind.LUNCH, h(12), h(15), 30, optional = true)
    private val dinner = Window(Kind.DINNER, h(18), h(21), 30, optional = false)

    @Test
    fun `zi libera - pranzul la mijlocul ferestrei`() {
        val r = MealPlanner.place(listOf(lunch), emptyList())
        assertEquals(h(13, 15), r.slots.single().start)     // mijlocul 12–15 pentru 30 min
    }

    @Test
    fun `cu curs la pranz - masa se muta in pauza`() {
        // cursuri 10:00–12:30 și 14:00–16:00: rămâne 12:30–14:00
        val r = MealPlanner.place(listOf(lunch), listOf(Busy(h(10), h(12, 30)), Busy(h(14), h(16))))
        val slot = r.slots.single()
        assertTrue(slot.start >= h(12, 30) && slot.end <= h(14))
    }

    @Test
    fun `fara loc - masa ramane fara loc`() {
        // ocupat și cu o oră înainte și după fereastră
        val r = MealPlanner.place(listOf(lunch), listOf(Busy(h(10, 30), h(16, 30))))
        assertTrue(r.slots.isEmpty())
        assertEquals(Kind.LUNCH, r.missing.single().kind)
    }

    @Test
    fun `pauza de 20 de minute - masa scurta in pauza`() {
        // cursuri 11:30–13:00 și 13:20–16:00: doar 20 min la prânz
        val r = MealPlanner.place(listOf(lunch), listOf(Busy(h(11, 30), h(13)), Busy(h(13, 20), h(16))))
        val slot = r.slots.single()
        assertEquals(MealPlanner.Fit.SHORT, slot.fit)
        assertEquals(h(13), slot.start)
        assertEquals(h(13, 20), slot.end)
    }

    @Test
    fun `pauza de la 11 - pranzul putin mai devreme`() {
        // cursuri 9:30–11:00 și 11:30–16:00: singura pauză e înainte de fereastra prânzului
        val r = MealPlanner.place(listOf(lunch), listOf(Busy(h(9, 30), h(11)), Busy(h(11, 30), h(16))))
        val slot = r.slots.single()
        assertEquals(MealPlanner.Fit.EARLY, slot.fit)
        assertEquals(h(11), slot.start)
    }

    @Test
    fun `pauza de 10 minute nu e masa`() {
        val r = MealPlanner.place(listOf(lunch), listOf(Busy(h(10, 30), h(13)), Busy(h(13, 10), h(16, 30))))
        assertTrue(r.slots.isEmpty())
    }

    @Test
    fun `masa oprita nu apare deloc`() {
        val r = MealPlanner.place(listOf(lunch.copy(enabled = false)), emptyList())
        assertTrue(r.slots.isEmpty())
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `ziua ta - pranzul inainte de plecare, cina dupa ce ajungi acasa`() {
        val HOME = 1L
        val UNI = 2L
        val travel = TravelGaps.Travel { a, b -> if (setOf(a, b) == setOf(HOME, UNI)) 19 else null }
        val skeleton = DaySkeleton.build(
            busy = listOf(Busy(h(15, 15), h(16, 45), UNI), Busy(h(17), h(18, 30), UNI)),
            home = HOME, travel = travel, prepMinutes = 15,
            meals = listOf(lunch, dinner)
        )
        // pregătirea începe la 14:31 (15:15 - 19 - 10 - 15): prânzul se termină până atunci
        val l = skeleton.meals.slots.first { it.kind == Kind.LUNCH }
        assertTrue(l.end <= h(14, 31))
        // acasă la 18:59: cina după
        val d = skeleton.meals.slots.first { it.kind == Kind.DINNER }
        assertTrue(d.start >= h(18, 59))
    }
}
