package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.TravelEstimator.Estimate
import md.utm.digitalmirror.domain.TravelEstimator.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelSpreadTest {

    private val base = Estimate(20, Source.ROUTE)

    @Test
    fun `fara drumuri reale planificam cu traseul`() {
        val s = TravelEstimator.spread(base, emptyList())
        assertEquals(20, s.typical)
        assertEquals(20, s.planned)
        assertNull(s.low)
        assertNull(s.high)
    }

    @Test
    fun `prea putine drumuri - fara interval, dar invatam`() {
        val s = TravelEstimator.spread(base, listOf(30, 30))
        assertEquals(25, s.typical)        // jumătate traseu, jumătate drumurile reale
        assertEquals(s.typical, s.planned)
        assertNull(s.high)
    }

    @Test
    fun `drum care variaza - planificam spre capatul de sus`() {
        // de obicei ~25, dar în zilele cu ambuteiaj 40
        val s = TravelEstimator.spread(Estimate(25, Source.ROUTE), listOf(22, 24, 25, 25, 26, 38, 40, 24))
        assertEquals(25, s.typical)
        assertTrue(s.high!! >= 38)
        assertTrue(s.planned in 30..40)
        assertTrue(s.low!! <= 24)
    }

    @Test
    fun `drum real care nu se potriveste cu modul nu conteaza`() {
        val car = Estimate(5, Source.ROUTE)
        assertTrue(!TravelEstimator.plausible(car, 33))        // 33 min nu e cu mașina pe un drum de 5
        assertTrue(TravelEstimator.plausible(car, 12))         // ambuteiaj: da
        val walk = Estimate(21, Source.ROUTE)
        assertTrue(TravelEstimator.plausible(walk, 33))        // pe jos, mai încet: da
        assertTrue(!TravelEstimator.plausible(walk, 7))        // 7 min pe jos pe un drum de 21: nu
    }

    @Test
    fun `drum constant - fara rezerva in plus`() {
        val s = TravelEstimator.spread(Estimate(25, Source.ROUTE), listOf(25, 25, 25, 25, 25))
        assertEquals(s.typical, s.planned)
    }
}
