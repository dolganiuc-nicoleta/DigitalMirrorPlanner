package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.PresenceRules.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceRulesTest {

    private val min = 60_000L

    @Test
    fun `doua vizite la acelasi loc cu pauza mica se unesc`() {
        // curs 09:00–10:00, iese 3 minute (GPS), revine 10:03–11:00
        val result = PresenceRules.clean(
            listOf(Visit(1, 0, 60 * min), Visit(1, 63 * min, 120 * min))
        )
        assertEquals(listOf(Visit(1, 0, 120 * min)), result)
    }

    @Test
    fun `pauza mai mare de 10 minute pastreaza vizitele separate`() {
        val result = PresenceRules.clean(
            listOf(Visit(1, 0, 60 * min), Visit(1, 75 * min, 120 * min))
        )
        assertEquals(2, result.size)
    }

    @Test
    fun `o trecere de sub 5 minute nu e prezenta`() {
        val result = PresenceRules.clean(listOf(Visit(1, 0, 4 * min)))
        assertTrue(result.isEmpty())
        assertEquals(1, PresenceRules.clean(listOf(Visit(1, 0, 5 * min))).size)
    }

    @Test
    fun `locuri diferite nu se unesc intre ele`() {
        val result = PresenceRules.clean(
            listOf(Visit(1, 0, 60 * min), Visit(2, 61 * min, 120 * min))
        )
        assertEquals(2, result.size)
        assertEquals(listOf(1L, 2L), result.map { it.placeId })
    }

    @Test
    fun `vizitele suprapuse la acelasi loc se unesc fara sa se dubleze`() {
        val result = PresenceRules.clean(
            listOf(Visit(1, 0, 60 * min), Visit(1, 30 * min, 50 * min))
        )
        assertEquals(listOf(Visit(1, 0, 60 * min)), result)
    }

    @Test
    fun `vizita peste miezul noptii se taie la ziua ceruta`() {
        // zi = [100, 200); vizita 150–260 → doar 150–200 ramane
        val clipped = PresenceRules.clipTo(listOf(Visit(1, 150 * min, 260 * min)), 100 * min, 200 * min)
        assertEquals(listOf(Visit(1, 150 * min, 200 * min)), clipped)
        assertTrue(PresenceRules.clipTo(listOf(Visit(1, 0, 50 * min)), 100 * min, 200 * min).isEmpty())
    }

    @Test
    fun `minutele se aduna pe locuri`() {
        val minutes = PresenceRules.minutesByPlace(
            listOf(Visit(1, 0, 60 * min), Visit(1, 100 * min, 130 * min), Visit(2, 0, 20 * min))
        )
        assertEquals(90, minutes[1L])
        assertEquals(20, minutes[2L])
    }
}