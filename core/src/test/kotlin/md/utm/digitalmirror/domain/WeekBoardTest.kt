package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.WeekBoard.Load
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekBoardTest {

    @Test
    fun `zi libera - capacitatea e cat reusesti de obicei`() {
        // treaz 15 h, nimic fix: încap 5 h cât reușești de obicei
        assertEquals(300, WeekBoard.capacity(budget = 300, awakeMinutes = 900, fixedMinutes = 0))
    }

    @Test
    fun `zi cu multe cursuri - capacitatea scade`() {
        // treaz 15 h, 11 h de cursuri și drumuri: rămân 4 h minus mese și pauze = 2 h 30
        assertEquals(150, WeekBoard.capacity(budget = 300, awakeMinutes = 900, fixedMinutes = 660))
    }

    @Test
    fun `incarcarea - goala, ok, plina, prea mult`() {
        assertEquals(Load.EMPTY, WeekBoard.load(0, 300))
        assertEquals(Load.OK, WeekBoard.load(120, 300))
        assertEquals(Load.FULL, WeekBoard.load(290, 300))
        assertEquals(Load.OVER, WeekBoard.load(400, 300))
        assertEquals(Load.OVER, WeekBoard.load(30, 0))
    }

    @Test
    fun `lucrurile fixe suprapuse nu se numara de doua ori`() {
        // curs 9–11, eveniment 10–12, curs 14–15 → 3 h + 1 h = 4 h
        assertEquals(240, WeekBoard.fixedMinutes(listOf(540 to 660, 600 to 720, 840 to 900)))
    }
}
