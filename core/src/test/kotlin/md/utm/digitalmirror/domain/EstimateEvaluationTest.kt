package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.EstimateEvaluation.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateEvaluationTest {

    private fun r(actual: Int, guess: Int?, estimate: Int?, low: Int? = null, high: Int? = null, planned: Int? = null) =
        Record(actual, guess, estimate, low, high, planned)

    @Test
    fun `fara date nu calculeaza nimic`() {
        val e = EstimateEvaluation.evaluate(emptyList())
        assertEquals(0, e.comparedCount)
        assertNull(e.userMae)
        assertFalse(e.enoughData)
    }

    @Test
    fun `calculeaza eroarea medie pentru utilizator si aplicatie`() {
        // utilizatorul greșește cu 30 și 10, aplicația cu 10 și 0
        val e = EstimateEvaluation.evaluate(listOf(
            r(actual = 90, guess = 60, estimate = 80),
            r(actual = 40, guess = 30, estimate = 40)
        ))
        assertEquals(20, e.userMae)
        assertEquals(5, e.appMae)
        assertEquals(100, e.appCloserPercent)
        assertEquals(75, e.improvementPercent)
    }

    @Test
    fun `compara doar activitatile cu ambele estimari`() {
        val e = EstimateEvaluation.evaluate(listOf(
            r(actual = 60, guess = 60, estimate = null),
            r(actual = 60, guess = null, estimate = 60),
            r(actual = 60, guess = 50, estimate = 70)
        ))
        assertEquals(1, e.comparedCount)
        assertEquals(50, e.appCloserPercent)   // egalitate: se împarte la jumătate
    }

    @Test
    fun `calculeaza acoperirea intervalului`() {
        val e = EstimateEvaluation.evaluate(listOf(
            r(actual = 50, guess = 40, estimate = 50, low = 40, high = 60),
            r(actual = 70, guess = 40, estimate = 50, low = 40, high = 60),
            r(actual = 60, guess = 40, estimate = 50, low = 40, high = 60),   // limita intră în interval
            r(actual = 45, guess = 40, estimate = 50, low = 40, high = 60)
        ))
        assertEquals(4, e.intervalCount)
        assertEquals(75, e.coveragePercent)
    }

    @Test
    fun `calculeaza acceptarea doar unde propunerea a fost diferita`() {
        val e = EstimateEvaluation.evaluate(listOf(
            r(actual = 60, guess = 40, estimate = 55, planned = 55),   // a acceptat
            r(actual = 60, guess = 40, estimate = 55, planned = 40),   // și-a păstrat estimarea
            r(actual = 60, guess = 50, estimate = 50, planned = 50)    // identice, nu contează
        ))
        assertEquals(2, e.offeredCount)
        assertEquals(50, e.acceptedPercent)
    }

    @Test
    fun `are destule date de la 5 activitati comparate`() {
        val records = List(5) { r(actual = 60, guess = 50, estimate = 58) }
        assertTrue(EstimateEvaluation.evaluate(records).enoughData)
    }
}