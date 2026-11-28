package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.DayLoad.DayTotal
import md.utm.digitalmirror.domain.DayLoad.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DayLoadTest {

    // joi, 8 octombrie 2026
    private val thursday = LocalDate.of(2026, 10, 8)

    // zile lucrătoare din urmă, fiecare cu minutele date
    private fun weekdays(vararg minutes: Int): List<DayTotal> {
        val result = mutableListOf<DayTotal>()
        var d = thursday.minusDays(1)
        for (m in minutes) {
            while (DayLoad.isWeekend(d)) d = d.minusDays(1)
            result += DayTotal(d, m)
            d = d.minusDays(1)
        }
        return result
    }

    @Test
    fun `fara destule zile nu compara`() {
        val r = DayLoad.compute(300, weekdays(360, 400, 380), thursday)
        assertEquals(Status.NO_HISTORY, r.status)
        assertNull(r.typicalMinutes)
        assertEquals(3, r.daysUsed)
    }

    @Test
    fun `foloseste mediana zilelor recente`() {
        // o zi excepțională de 12 h nu trage valoarea în sus
        val r = DayLoad.compute(390, weekdays(360, 390, 400, 380, 720), thursday)
        assertEquals(390, r.typicalMinutes)
        assertEquals(Status.OK, r.status)
    }

    @Test
    fun `detecteaza ziua supraincarcata`() {
        val r = DayLoad.compute(470, weekdays(390, 390, 390, 390, 390), thursday)
        assertEquals(Status.OVERLOADED, r.status)
        assertEquals(80, r.difference)
    }

    @Test
    fun `detecteaza ziua usoara`() {
        val r = DayLoad.compute(120, weekdays(390, 390, 390, 390, 390), thursday)
        assertEquals(Status.LIGHT, r.status)
    }

    @Test
    fun `weekendul se compara cu weekendul cand sunt destule date`() {
        val saturday = LocalDate.of(2026, 10, 10)
        val weekendDays = (1..5).map { DayTotal(saturday.minusWeeks(it.toLong()), 120) }
        val r = DayLoad.compute(130, weekdays(400, 400, 400, 400, 400) + weekendDays, saturday)

        assertEquals(120, r.typicalMinutes)
        assertEquals(Status.OK, r.status)
    }

    @Test
    fun `zilele din viitor si ziua curenta nu se iau in calcul`() {
        val future = List(5) { DayTotal(thursday.plusDays(it.toLong()), 600) }
        val r = DayLoad.compute(300, future, thursday)
        assertEquals(Status.NO_HISTORY, r.status)
    }
}