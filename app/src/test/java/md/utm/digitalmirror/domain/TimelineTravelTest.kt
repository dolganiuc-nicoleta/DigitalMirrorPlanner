package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.TimelineTravel.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTravelTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private val HOME = 1L
    private val UNI = 2L
    private val GYM = 3L

    private val travel = TravelGaps.Travel { from, to ->
        when (setOf(from, to)) {
            setOf(UNI, HOME) -> 35
            setOf(UNI, GYM) -> 20
            setOf(HOME, GYM) -> 15
            else -> null
        }
    }

    @Test
    fun `ziua ta - drum spre facultate inainte de cursuri si spre casa dupa`() {
        val stops = listOf(Stop(h(15, 15), h(16, 45), UNI), Stop(h(17), h(18, 30), UNI))
        val legs = TimelineTravel.legs(stops, HOME, travel, margin = 10)
        assertEquals(2, legs.size)
        // plecarea: 15:15 - 35 - 10 = 14:30
        assertEquals(h(14, 30), legs[0].start)
        assertEquals(h(15, 15), legs[0].end)
        assertEquals(HOME, legs[0].fromPlaceId)
        // între cursuri, același loc: fără drum; după: 18:30 + 45 = 19:15
        assertEquals(h(18, 30), legs[1].start)
        assertEquals(h(19, 15), legs[1].end)
        assertEquals(HOME, legs[1].toPlaceId)
    }

    @Test
    fun `fara loc acasa nu presupunem drumuri spre casa`() {
        val stops = listOf(Stop(h(15), h(16), UNI))
        assertTrue(TimelineTravel.legs(stops, null, travel).isEmpty())
    }

    @Test
    fun `pauza scurta - drum direct, cat sa ajungi la timp`() {
        val stops = listOf(Stop(h(8), h(15), UNI), Stop(h(16), h(17), GYM))
        val legs = TimelineTravel.legs(stops, HOME, travel, margin = 10)
        val direct = legs.single { it.fromPlaceId == UNI && it.toPlaceId == GYM }
        assertEquals(h(15, 30), direct.start)      // 16:00 - 20 - 10
        assertFalse(direct.tight)
    }

    @Test
    fun `pauza lunga - treci pe acasa`() {
        val stops = listOf(Stop(h(8), h(12), UNI), Stop(h(18), h(19), GYM))
        val legs = TimelineTravel.legs(stops, HOME, travel, margin = 10)
        assertTrue(legs.any { it.fromPlaceId == UNI && it.toPlaceId == HOME && it.start == h(12) })
        assertTrue(legs.any { it.fromPlaceId == HOME && it.toPlaceId == GYM && it.end == h(18) })
        assertFalse(legs.any { it.fromPlaceId == UNI && it.toPlaceId == GYM })
    }

    @Test
    fun `nu ai timp de drum - semnalat`() {
        val stops = listOf(Stop(h(8), h(15), UNI), Stop(h(15, 10), h(16), GYM))
        val leg = TimelineTravel.legs(stops, HOME, travel, margin = 10)
            .single { it.fromPlaceId == UNI && it.toPlaceId == GYM }
        assertTrue(leg.tight)
        assertEquals(h(15), leg.start)              // nu înainte să se termine cursurile
    }

    @Test
    fun `drum necunoscut - fara drum pe linia de timp`() {
        val stops = listOf(Stop(h(10), h(11), 99L))
        assertTrue(TimelineTravel.legs(stops, HOME, travel).isEmpty())
    }

    @Test
    fun `lucrurile de acasa nu cer drum`() {
        val stops = listOf(Stop(h(10), h(11), HOME), Stop(h(12), h(13), HOME))
        assertTrue(TimelineTravel.legs(stops, HOME, travel).isEmpty())
    }

    @Test
    fun `perechile necesare`() {
        val stops = listOf(Stop(h(8), h(12), UNI), Stop(h(18), h(19), GYM))
        val pairs = TimelineTravel.pairsNeeded(stops, HOME)
        assertTrue(UNI to GYM in pairs)
        assertTrue(HOME to UNI in pairs)
        assertTrue(GYM to HOME in pairs)
    }
}
