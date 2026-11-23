package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.DayPlanner.Item
import md.utm.digitalmirror.domain.SlotRecommender.Busy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelGapsTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private val HOME = 1L
    private val UNI = 2L
    private val GYM = 3L

    // drumurile dintre locuri, în ambele sensuri
    private val travel = TravelGaps.Travel { from, to ->
        val pair = setOf(from, to)
        when (pair) {
            setOf(UNI, GYM) -> 20
            setOf(UNI, HOME) -> 35
            setOf(HOME, GYM) -> 15
            else -> null
        }
    }

    // cursurile 8–15 la Facultate, cu drumul fix de 40 min pus în Setări
    private val courses = Busy(h(8), h(15), UNI, travelBefore = 40, travelAfter = 40)

    @Test
    fun `dupa cursuri, la sala, se adauga drumul pana acolo si marja`() {
        val view = TravelGaps.expand(listOf(courses), GYM, travel, margin = 10)
        assertEquals(h(15, 30), view[0].end)          // 20 min drum + 10 min marjă
        assertEquals(h(7, 30), view[0].start)         // și dimineața, drumul de la sală la facultate
    }

    @Test
    fun `acasa drumul e altul decat la sala`() {
        val view = TravelGaps.expand(listOf(courses), HOME, travel, margin = 10)
        assertEquals(h(15, 45), view[0].end)          // 35 + 10
    }

    @Test
    fun `in acelasi loc nu e nevoie de drum`() {
        val view = TravelGaps.expand(listOf(courses), UNI, travel, margin = 10)
        assertEquals(h(8), view[0].start)
        assertEquals(h(15), view[0].end)
    }

    @Test
    fun `fara loc se foloseste drumul fix al blocului`() {
        val view = TravelGaps.expand(listOf(courses), null, travel, margin = 10)
        assertEquals(h(7, 20), view[0].start)
        assertEquals(h(15, 40), view[0].end)
    }

    @Test
    fun `drum necunoscut intre locuri foloseste drumul fix`() {
        val view = TravelGaps.expand(listOf(courses), 99L, travel, margin = 10)
        assertEquals(h(15, 40), view[0].end)
    }

    @Test
    fun `activitatile fara loc nu cer drum`() {
        val view = TravelGaps.expand(listOf(Busy(h(10), h(11))), GYM, travel)
        assertEquals(h(10), view[0].start)
        assertEquals(h(11), view[0].end)
    }

    @Test
    fun `cea mai devreme ora dupa cursuri tine cont de drum`() {
        val start = TravelGaps.earliestFit(h(15), 60, listOf(courses), GYM, travel, margin = 10)
        assertEquals(h(15, 30), start)
    }

    @Test
    fun `cea mai devreme ora tine cont si de drumul spre ce urmeaza`() {
        // la 17:00 ceva la facultate: sala 60 min de la 15:30 se termină la 16:30, plus 30 min drum = 17:00, încape
        val later = Busy(h(17), h(18), UNI)
        assertEquals(h(15, 30), TravelGaps.earliestFit(h(15), 60, listOf(courses, later), GYM, travel, margin = 10))
        // 90 min nu mai încap între ele; următorul loc e după 18:00 + 30
        assertEquals(h(18, 30), TravelGaps.earliestFit(h(15), 90, listOf(courses, later), GYM, travel, margin = 10))
    }

    @Test
    fun `nu incape pana la sfarsitul zilei`() {
        assertNull(TravelGaps.earliestFit(h(23), 120, emptyList(), GYM, travel))
    }

    @Test
    fun `planificatorul pune sala dupa drum, nu imediat dupa cursuri`() {
        val p = DayPlanner.place(
            listOf(Item("Sală", 60, null, null, placeId = GYM)),
            listOf(courses), earliestStart = h(7), travel = travel, margin = 10
        )
        assertEquals(h(15, 30), p[0].startMinute)
    }

    @Test
    fun `planificatorul tine cont de drumul dintre activitatile noi`() {
        // întâi sala (ora spusă), apoi ceva acasă: între ele 15 min drum + 10 marjă
        val p = DayPlanner.place(
            listOf(
                Item("Sală", 60, h(16), null, placeId = GYM),
                Item("Temă", 60, null, null, placeId = HOME)
            ),
            listOf(courses), earliestStart = h(16), travel = travel, margin = 10
        )
        assertEquals(h(17, 30), p[1].startMinute)    // 17:00 + 25 min, rotunjit la sfertul de oră
    }

    @Test
    fun `ora spusa fara timp de drum e semnalata ca conflict`() {
        val p = DayPlanner.place(
            listOf(Item("Sală", 60, h(15), null, placeId = GYM)),
            listOf(courses), earliestStart = h(7), travel = travel, margin = 10
        )
        assertTrue(p[0].conflict)
        val ok = DayPlanner.place(
            listOf(Item("Sală", 60, h(15, 30), null, placeId = GYM)),
            listOf(courses), earliestStart = h(7), travel = travel, margin = 10
        )
        assertFalse(ok[0].conflict)
    }

    @Test
    fun `planul pe saptamana lasa timp de drum dupa cursuri`() {
        val day = java.time.LocalDate.of(2026, 11, 18)
        // cursurile 15:15–18:30 la facultate; ziua până la 22:00, deci locul e doar după cursuri
        val busy = listOf(Busy(h(8), h(15, 15)), Busy(h(15, 15), h(18, 30), UNI))
        val plan = WeekPlanner.plan(
            tasks = listOf(WeekPlanner.Task(1, "Licență", 90, 90, placeId = HOME)),
            days = listOf(WeekPlanner.DayInput(day, busy)),
            travel = travel,
            margin = 10
        )
        // 18:30 + 35 drum + 10 marjă = 19:15
        assertEquals(h(19, 15), plan.sessions.single().startMinute)
    }

    @Test
    fun `doua sesiuni in aceeasi zi au o pauza intre ele`() {
        val day = java.time.LocalDate.of(2026, 11, 23)
        val plan = WeekPlanner.plan(
            tasks = listOf(
                WeekPlanner.Task(1, "Învăț materia", 90, 90, deadline = day),
                WeekPlanner.Task(2, "Exerciții", 90, 90, deadline = day)
            ),
            days = listOf(WeekPlanner.DayInput(day, emptyList(), dayStart = h(9, 30), dayEnd = h(22))),
            today = null
        )
        val (first, second) = plan.sessions.sortedBy { it.startMinute }
        assertTrue(second.startMinute - first.endMinute >= WeekPlanner.SESSION_BREAK)
    }

    @Test
    fun `perechile necesare includ ambele sensuri`() {
        val pairs = TravelGaps.pairsNeeded(listOf(GYM), listOf(UNI))
        assertEquals(setOf(GYM to UNI, UNI to GYM), pairs)
    }
}
