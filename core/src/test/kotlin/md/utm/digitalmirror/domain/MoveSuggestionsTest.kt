package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.MoveSuggestions.Day
import md.utm.digitalmirror.domain.MoveSuggestions.Note
import md.utm.digitalmirror.domain.SlotRecommender.Busy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoveSuggestionsTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute
    private val sunday = LocalDate.of(2026, 11, 22)
    private val monday = sunday.plusDays(1)

    @Test
    fun `seara de duminica - cel mai devreme azi, apoi a doua zi dimineata si dupa-amiaza`() {
        val days = listOf(
            // e 20:55: din 21:05 până la perna de seară (22:35)
            Day(sunday, emptyList(), h(21, 5), h(22, 35)),
            // luni: curs 11:30–13:00, restul liber, de la 9:20 la 22:35
            Day(monday, listOf(Busy(h(11, 30), h(13))), h(9, 20), h(22, 35))
        )
        // o oră: încape azi 21:15–22:15, înainte de perna de seară
        val s = MoveSuggestions.suggest(60, days)
        assertEquals(sunday, s[0].date)
        assertEquals(h(21, 15), s[0].start)
        assertEquals(Note.EARLIEST, s[0].note)
        assertEquals(monday, s[1].date)
        assertEquals(Note.MORNING, s[1].note)
        assertTrue(s.any { it.date == monday && it.note != Note.MORNING })
    }

    @Test
    fun `o sesiune de 1h30 nu mai incape duminica seara - prima propunere e luni`() {
        val days = listOf(
            Day(sunday, emptyList(), h(21, 5), h(22, 35)),
            Day(monday, emptyList(), h(9, 20), h(22, 35))
        )
        val s = MoveSuggestions.suggest(90, days)
        assertEquals(monday, s.first().date)
        assertEquals(h(9, 30), s.first().start)
    }

    @Test
    fun `nu propune exact locul in care e deja`() {
        val day = Day(monday, emptyList(), h(9), h(12))
        val s = MoveSuggestions.suggest(60, listOf(day), current = monday to h(9))
        assertEquals(h(9, 15), s.first().start)
    }

    @Test
    fun `zilele pline sunt sarite`() {
        val full = Day(sunday, listOf(Busy(0, h(24))), h(9), h(22))
        val free = Day(monday, emptyList(), h(9), h(22))
        assertEquals(monday, MoveSuggestions.suggest(60, listOf(full, free)).first().date)
    }

    @Test
    fun `cel mult patru propuneri`() {
        val days = (0..5).map { Day(monday.plusDays(it.toLong()), emptyList(), h(9), h(22)) }
        assertEquals(4, MoveSuggestions.suggest(60, days).size)
    }

    @Test
    fun `respecta perna de seara - nu trece de capatul zilei`() {
        val day = Day(sunday, emptyList(), h(21), h(22, 35))
        assertTrue(MoveSuggestions.suggest(90, listOf(day)).all { it.end <= h(22, 35) })
    }
}
