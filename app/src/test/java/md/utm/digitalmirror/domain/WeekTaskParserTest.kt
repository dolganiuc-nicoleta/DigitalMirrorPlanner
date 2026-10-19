package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekTaskParserTest {

    @Test
    fun `titlu, total si termen`() {
        val r = WeekTaskParser.parse("licență 6h, până vineri")
        assertEquals("Licență", r.title)
        assertEquals(360, r.totalMinutes)
        assertNull(r.sessionMinutes)
        assertEquals(4, r.deadlineDay)
    }

    @Test
    fun `tot ce se poate spune deodata`() {
        val r = WeekTaskParser.parse("Lucru la licență, 6 ore, sesiuni de 90 min, până vineri")
        assertEquals("Lucru la licență", r.title)
        assertEquals(360, r.totalMinutes)
        assertEquals(90, r.sessionMinutes)
        assertEquals(4, r.deadlineDay)
    }

    @Test
    fun `fara termen si fara sesiune`() {
        val r = WeekTaskParser.parse("proiect PAD 1h30")
        assertEquals("Proiect PAD", r.title)
        assertEquals(90, r.totalMinutes)
        assertNull(r.sessionMinutes)
        assertNull(r.deadlineDay)
    }

    @Test
    fun `ore cu zecimala si termen simplu`() {
        val r = WeekTaskParser.parse("citit 1,5 ore joi")
        assertEquals("Citit", r.title)
        assertEquals(90, r.totalMinutes)
        assertEquals(3, r.deadlineDay)
    }

    @Test
    fun `sesiunea nu se confunda cu totalul, indiferent de ordine`() {
        val r = WeekTaskParser.parse("sesiuni de 1h licență 6 ore")
        assertEquals(60, r.sessionMinutes)
        assertEquals(360, r.totalMinutes)
        assertEquals("Licență", r.title)
    }

    @Test
    fun `in sesiuni de la sfarsit`() {
        val r = WeekTaskParser.parse("Pregătire examen 4 ore în sesiuni de 45 min")
        assertEquals("Pregătire examen", r.title)
        assertEquals(240, r.totalMinutes)
        assertEquals(45, r.sessionMinutes)
    }

    @Test
    fun `in total si termen cu cuvant`() {
        val r = WeekTaskParser.parse("raport laborator 3 ore în total, termen miercuri")
        assertEquals("Raport laborator", r.title)
        assertEquals(180, r.totalMinutes)
        assertEquals(2, r.deadlineDay)
    }

    @Test
    fun `fara durata se vede ca lipseste`() {
        val r = WeekTaskParser.parse("licență")
        assertEquals("Licență", r.title)
        assertNull(r.totalMinutes)
    }

    @Test
    fun `text gol`() {
        val r = WeekTaskParser.parse("   ")
        assertNull(r.title)
        assertNull(r.totalMinutes)
        assertNull(r.deadlineDay)
    }
}
