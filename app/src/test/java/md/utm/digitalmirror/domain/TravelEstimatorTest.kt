package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.TravelEstimator.Mode
import md.utm.digitalmirror.domain.TravelEstimator.Source
import md.utm.digitalmirror.domain.TravelPlanner.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelEstimatorTest {

    @Test
    fun `pe jos foloseste durata din serviciul de rute, cu semafoarele din oras`() {
        // 1,2 km pe jos, 15 min 10 s; în oraș × 1,2 = 18 min 12 s → 19
        val e = TravelEstimator.base(Mode.WALK, 1200.0, 15 * 60 + 10.0)
        assertEquals(19, e.minutes)
        assertEquals(Source.ROUTE, e.source)
    }

    @Test
    fun `transportul public se aproximeaza din distanta rutiera`() {
        // 5,1 km: 5,1 / 17 * 60 = 18 min de mers + 8 min de asteptare = 26
        val e = TravelEstimator.base(Mode.TRANSIT, 5100.0, 0.0)
        assertEquals(26, e.minutes)
        assertEquals(Source.APPROXIMATION, e.source)
    }

    @Test
    fun `fara drumuri observate ramane estimarea de baza`() {
        val base = TravelEstimator.Estimate(26, Source.APPROXIMATION)
        assertEquals(base, TravelEstimator.learn(base, emptyList()))
    }

    @Test
    fun `cu doua drumuri observate estimarea e la jumatate intre baza si mediana`() {
        // baza 26, observate 40 si 40 → greutate 2/4 = 0,5 → (40 + 26) / 2 = 33
        val e = TravelEstimator.learn(TravelEstimator.Estimate(26, Source.APPROXIMATION), listOf(40, 40))
        assertEquals(33, e.minutes)
        assertEquals(Source.LEARNED, e.source)
    }

    @Test
    fun `cu multe drumuri observate estimarea se apropie de ele`() {
        val base = TravelEstimator.Estimate(26, Source.APPROXIMATION)
        val e = TravelEstimator.learn(base, List(8) { 40 })   // greutate 8/10 → 0,8*40 + 0,2*26 = 37,2
        assertEquals(37, e.minutes)
    }

    @Test
    fun `un drum ie sit din comun nu strica mediana`() {
        val base = TravelEstimator.Estimate(20, Source.ROUTE)
        // observate: 20, 22, 21, 120 → mediana 21,5, nu media 45,75
        val e = TravelEstimator.learn(base, listOf(20, 22, 21, 120))
        // greutate 4/6; 0,667*21,5 + 0,333*20 = 21,0
        assertEquals(21, e.minutes)
    }

    @Test
    fun `un drum observat e intre iesirea din A si intrarea in B, cel mult 3 ore`() {
        val min = 60_000L
        assertEquals(25, TravelEstimator.observedTrip(0, 25 * min))
        assertNull(TravelEstimator.observedTrip(0, 0))
        assertNull(TravelEstimator.observedTrip(0, 200 * min))
        assertNull(TravelEstimator.observedTrip(100 * min, 50 * min))
    }

    @Test
    fun `drumurile reale se iau din vizite consecutive la locuri diferite`() {
        val min = 60_000L
        val visits = listOf(
            PresenceRules.Visit(1, 0, 60 * min),            // acasă
            PresenceRules.Visit(2, 85 * min, 200 * min),    // facultate: drum 25 min
            PresenceRules.Visit(2, 205 * min, 260 * min),   // același loc, nu e drum
            PresenceRules.Visit(1, 290 * min, 400 * min),   // înapoi acasă: drum 30 min
            PresenceRules.Visit(2, 900 * min, 960 * min)    // a doua zi: peste 3 ore, nu e drum
        )
        val trips = TravelEstimator.observedTrips(visits)
        assertEquals(
            listOf(TravelEstimator.Trip(1, 2, 25), TravelEstimator.Trip(2, 1, 30)).map { Triple(it.fromId, it.toId, it.minutes) },
            trips.map { Triple(it.fromId, it.toId, it.minutes) }
        )
    }

    // ---------- planificatorul de drumuri ----------

    private fun stop(id: Long, place: Long?, start: Int?, duration: Int = 60) =
        Stop(id, "A$id", place, start, duration)

    @Test
    fun `intre doua activitati la locuri diferite apare un drum`() {
        // curs 09:00–10:30 la locul 1, apoi sala 11:00–12:00 la locul 2
        val legs = TravelPlanner.legs(listOf(stop(1, 1, 9 * 60, 90), stop(2, 2, 11 * 60)))
        assertEquals(1, legs.size)
        assertEquals(30, legs[0].availableMinutes)
        assertTrue(legs[0].fits(25))
        assertFalse(legs[0].fits(35))
        assertEquals(11 * 60 - 25, legs[0].departMinute(25))
    }

    @Test
    fun `la acelasi loc nu e nevoie de drum`() {
        assertTrue(TravelPlanner.legs(listOf(stop(1, 1, 9 * 60), stop(2, 1, 11 * 60))).isEmpty())
    }

    @Test
    fun `activitatile fara loc sau fara ora sunt ignorate`() {
        val legs = TravelPlanner.legs(
            listOf(stop(1, 1, 9 * 60), stop(2, null, 10 * 60), stop(3, 2, null), stop(4, 2, 12 * 60))
        )
        // raman doar 1 (09:00, loc 1) și 4 (12:00, loc 2)
        assertEquals(1, legs.size)
        assertEquals(1L, legs[0].from.id)
        assertEquals(4L, legs[0].to.id)
    }

    @Test
    fun `activitatile se ordoneaza dupa ora, nu dupa lista`() {
        val legs = TravelPlanner.legs(listOf(stop(2, 2, 12 * 60), stop(1, 1, 9 * 60)))
        assertEquals(1L, legs[0].from.id)
    }

    @Test
    fun `timpul dintre activitati poate fi negativ cand se suprapun`() {
        val legs = TravelPlanner.legs(listOf(stop(1, 1, 9 * 60, 90), stop(2, 2, 10 * 60)))
        assertEquals(-30, legs[0].availableMinutes)
        assertFalse(legs[0].fits(10))
    }

    @Test
    fun `drumul incape sau depaseste timpul liber cu cateva minute`() {
        val leg = TravelPlanner.legs(listOf(stop(1, 1, 9 * 60, 60), stop(2, 2, 10 * 60 + 30))).single()
        assertEquals(30, leg.availableMinutes)
        assertTrue(leg.fits(20))
        assertEquals(0, leg.shortfall(20))
        assertFalse(leg.fits(45))
        assertEquals(15, leg.shortfall(45))
        assertEquals(10 * 60 + 30 - 45, leg.departMinute(45))
    }

    @Test
    fun `titlul drumului contine ambele locuri`() {
        assertEquals("Drum: Facultate → Sală", TravelPlanner.tripTitle("Facultate", "Sală"))
    }
}