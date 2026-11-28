package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy
import md.utm.digitalmirror.domain.SlotRecommender.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotRecommenderTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `nu propune nimic cu mai putin de 3 activitati`() {
        val history = listOf(Outcome(h(9), true), Outcome(h(9), true))
        assertNull(SlotRecommender.suggest(history, 60, busy = emptyList()))
    }

    @Test
    fun `alege ora la care activitatile au fost finalizate`() {
        // dimineața reușește, seara nu
        val history = listOf(
            Outcome(h(9), true), Outcome(h(9, 30), true), Outcome(h(10), true),
            Outcome(h(20), false), Outcome(h(20, 30), false), Outcome(h(21), false)
        )
        val s = SlotRecommender.suggest(history, 60, busy = emptyList())

        assertNotNull(s)
        assertTrue("propunerea ar trebui să fie dimineața", s!!.startMinute in h(8)..h(11))
        assertTrue(s.successPercent > 50)
    }

    @Test
    fun `ocoleste intervalele ocupate`() {
        val history = listOf(Outcome(h(9), true), Outcome(h(9), true), Outcome(h(9), true))
        val busy = listOf(Busy(h(8, 30), h(10, 30)))
        val s = SlotRecommender.suggest(history, 60, busy)!!

        assertTrue(SlotRecommender.isFree(s.startMinute, s.endMinute, busy))
    }

    @Test
    fun `nu propune ore fara date in jur`() {
        // toate activitățile sunt seara, deci dimineața nu are destule date
        val history = listOf(Outcome(h(20), true), Outcome(h(20), true), Outcome(h(20), true))
        val s = SlotRecommender.suggest(history, 30, busy = emptyList(), earliestStart = h(7))!!

        assertTrue(s.startMinute >= h(18))
    }

    @Test
    fun `intervalul liber se verifica corect`() {
        val busy = listOf(Busy(h(10), h(11)))
        assertTrue(SlotRecommender.isFree(h(9), h(10), busy))     // se termină exact când începe
        assertTrue(SlotRecommender.isFree(h(11), h(12), busy))    // începe exact când se termină
        assertEquals(false, SlotRecommender.isFree(h(10, 30), h(11, 30), busy))
    }

    @Test
    fun `fara istoric propune primul interval liber`() {
        // ocupat 11:30–13:00 și 13:30–15:00; o activitate de 30 min încape la 13:00
        val busy = listOf(Busy(h(11, 30), h(13)), Busy(h(13, 30), h(15)))
        val slot = SlotRecommender.firstFree(30, busy, earliestStart = h(12, 46))!!
        assertEquals(h(13), slot.startMinute)
        assertEquals(false, slot.fromHistory)
    }

    @Test
    fun `fara istoric sare peste golurile prea mici`() {
        val busy = listOf(Busy(h(11, 30), h(13)), Busy(h(13, 30), h(15)))
        val slot = SlotRecommender.firstFree(60, busy, earliestStart = h(12, 46))!!
        assertEquals(h(15), slot.startMinute)
    }
}