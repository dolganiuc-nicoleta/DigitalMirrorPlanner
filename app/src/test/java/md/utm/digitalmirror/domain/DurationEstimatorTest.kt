package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.DurationEstimator.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationEstimatorTest {

    @Test
    fun `nu propune nimic cu mai putin de 3 activitati`() {
        val history = listOf(Sample(30, 40), Sample(20, 25))
        assertNull(DurationEstimator.suggest(history, userEstimate = 30))
    }

    @Test
    fun `aplica raportul real pe estimat la estimarea noua`() {
        // în total: estimat 100, real 125 → raport 1.25
        val history = listOf(Sample(40, 50), Sample(30, 40), Sample(30, 35))
        val suggestion = DurationEstimator.suggest(history, userEstimate = 40)!!

        assertEquals(50, suggestion.minutes)          // 40 × 1.25
        assertEquals(25, suggestion.deviationPercent)
        assertEquals(3, suggestion.sampleSize)
    }

    @Test
    fun `fara estimare propune durata reala medie`() {
        val history = listOf(Sample(10, 12), Sample(10, 14), Sample(10, 16))
        val suggestion = DurationEstimator.suggest(history, userEstimate = null)!!

        assertEquals(14, suggestion.minutes)
    }

    @Test
    fun `ignora activitatile fara estimare`() {
        val history = listOf(Sample(0, 30), Sample(10, 10), Sample(10, 10))
        assertNull(DurationEstimator.suggest(history, userEstimate = 10))
    }

    @Test
    fun `rotunjeste la multiplu de 5 peste 15 minute`() {
        assertEquals(35, DurationEstimator.roundNicely(37.2))
        assertEquals(40, DurationEstimator.roundNicely(38.0))
        assertEquals(12, DurationEstimator.roundNicely(12.4))
        assertEquals(1, DurationEstimator.roundNicely(0.2))
    }
}