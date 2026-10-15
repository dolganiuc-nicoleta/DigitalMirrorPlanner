package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.PlanParser.DayPart
import md.utm.digitalmirror.domain.PlanParser.Draft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PlanParserTest {

    // marți
    private val today = LocalDate.of(2026, 10, 13)

    private fun parse(text: String) = PlanParser.parse(text, today)
    private fun one(text: String): Draft = parse(text).drafts.single()

    @Test
    fun `un plan complet cu mai multe activitati`() {
        val r = parse("mâine laborator PAD 2h, sport seara, cumpărături 30 min")
        assertEquals(LocalDate.of(2026, 10, 14), r.date)
        assertEquals(
            listOf(
                Draft("Laborator PAD", 120, null, null),
                Draft("Sport", null, null, DayPart.EVENING),
                Draft("Cumpărături", 30, null, null)
            ),
            r.drafts
        )
    }

    @Test
    fun `recunoaste duratele spuse in cuvinte si cifre`() {
        assertEquals(90, one("citit 1h30").durationMinutes)
        assertEquals(90, one("citit 1,5 ore").durationMinutes)
        assertEquals(60, one("citit o oră").durationMinutes)
        assertEquals(30, one("citit jumătate de oră").durationMinutes)
        assertEquals(45, one("citit 45 de minute").durationMinutes)
        assertEquals(120, one("citit două ore").durationMinutes)
        assertEquals(90, one("citit o oră și jumătate").durationMinutes)
        assertEquals(15, one("citit un sfert de oră").durationMinutes)
        assertNull(one("citit").durationMinutes)
    }

    @Test
    fun `duratia nu ramane in titlu`() {
        assertEquals("Citit", one("citit timp de 2 ore").title)
        assertEquals("Laborator PAD", one("laborator PAD de 2 ore").title)
    }

    @Test
    fun `recunoaste ora de start`() {
        assertEquals(14 * 60 + 30, one("curs de engleză la 14:30").startMinute)
        assertEquals("Curs de engleză", one("curs de engleză la 14:30").title)
        assertEquals(9 * 60, one("sport la 9 dimineața").startMinute)
        assertEquals(DayPart.MORNING, one("sport la 9 dimineața").dayPart)
        assertEquals(18 * 60, one("film la 6 seara").startMinute)
        assertEquals(17 * 60, one("sport la 5").startMinute)
        assertEquals(18 * 60, one("sport la ora 18").startMinute)
    }

    @Test
    fun `ora si durata pot fi spuse impreuna`() {
        val d = one("sport 2 ore la 18")
        assertEquals(120, d.durationMinutes)
        assertEquals(18 * 60, d.startMinute)
        assertEquals("Sport", d.title)
    }

    @Test
    fun `un interval orar da si ora si durata`() {
        val a = one("de la 10 la 12 laborator")
        assertEquals(10 * 60, a.startMinute)
        assertEquals(120, a.durationMinutes)
        assertEquals("Laborator", a.title)

        val b = one("10:00-11:30 ședință")
        assertEquals(10 * 60, b.startMinute)
        assertEquals(90, b.durationMinutes)
        assertEquals("Ședință", b.title)
    }

    @Test
    fun `o durata nu e luata drept ora`() {
        val d = one("sport la 2 ore")
        assertNull(d.startMinute)
    }

    @Test
    fun `recunoaste ziua`() {
        assertEquals(today, parse("azi sport").date)
        assertEquals(today.plusDays(1), parse("mâine sport").date)
        assertEquals(today.plusDays(2), parse("poimâine sport").date)
        assertEquals(LocalDate.of(2026, 10, 16), parse("vineri sport").date)
        assertEquals(LocalDate.of(2026, 10, 20), parse("marți sport").date)   // marți viitoare, nu azi
        assertNull(parse("sport").date)
    }

    @Test
    fun `diseara inseamna azi seara`() {
        val r = parse("diseară film")
        assertEquals(today, r.date)
        assertEquals(Draft("Film", null, null, DayPart.EVENING), r.drafts.single())
    }

    @Test
    fun `desparte la si doar dupa o activitate completa`() {
        val two = parse("laborator 2h și sport seara").drafts
        assertEquals(listOf("Laborator", "Sport"), two.map { it.title })

        val one = parse("mic dejun și cafea").drafts
        assertEquals(listOf("Mic dejun și cafea"), one.map { it.title })
    }

    @Test
    fun `scoate cuvintele de legatura`() {
        val drafts = parse("vreau să termin capitolul 2, apoi sport").drafts
        assertEquals(listOf("Termin capitolul 2", "Sport"), drafts.map { it.title })
    }

    @Test
    fun `text gol sau doar ziua nu da activitati`() {
        assertEquals(emptyList<Draft>(), parse("").drafts)
        val r = parse("mâine")
        assertEquals(today.plusDays(1), r.date)
        assertEquals(emptyList<Draft>(), r.drafts)
    }

    @Test
    fun `pastreaza diacriticele din titlu`() {
        assertEquals("Cumpărături", one("cumpărături 30 min").title)
        assertEquals("Ședință la facultate", one("ședință la facultate 1h").title)
    }
}