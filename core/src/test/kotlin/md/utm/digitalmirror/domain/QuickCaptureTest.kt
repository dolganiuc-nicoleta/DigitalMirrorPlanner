package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.QuickCapture.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuickCaptureTest {

    // miercuri, 18 noiembrie 2026
    private val today = LocalDate.of(2026, 11, 18)

    @Test
    fun `exemplul din macheta - patru lucruri, patru tipuri`() {
        val items = QuickCapture.parse(
            "sună la bancă, sală mâine la 18, licență 6h săptămâna asta, examen PAD joi", today
        )
        assertEquals(4, items.size)

        val bank = items[0]
        assertEquals(Kind.DAY, bank.kind)
        assertEquals("Sună la bancă", bank.title)
        assertEquals(today, bank.date)

        val gym = items[1]
        assertEquals(Kind.DAY, gym.kind)
        assertEquals("Sală", gym.title)
        assertEquals(today.plusDays(1), gym.date)
        assertEquals(18 * 60, gym.startMinute)

        val thesis = items[2]
        assertEquals(Kind.WEEK, thesis.kind)
        assertEquals("Licență", thesis.title)
        assertEquals(6 * 60, thesis.minutes)

        val exam = items[3]
        assertEquals(Kind.EVENT, exam.kind)
        assertEquals("Examen PAD", exam.title)
        assertEquals(LocalDate.of(2026, 11, 19), exam.date)
        assertEquals("exam", exam.templateKey)
    }

    @Test
    fun `o durata mare fara ora e de saptamana`() {
        assertEquals(Kind.WEEK, QuickCapture.guessKind("proiect 4 ore"))
        assertEquals(Kind.DAY, QuickCapture.guessKind("proiect 4 ore mâine"))
        assertEquals(Kind.DAY, QuickCapture.guessKind("temă 2h"))
    }

    @Test
    fun `pana vineri e o sarcina de saptamana cu termen`() {
        val item = QuickCapture.parse("raport 5h până vineri", today).single()
        assertEquals(Kind.WEEK, item.kind)
        assertEquals("Raport", item.title)
        assertEquals(LocalDate.of(2026, 11, 20), item.deadline)
    }

    @Test
    fun `cuvintele de eveniment`() {
        assertEquals("lab", QuickCapture.parse("susținere lab 3 vineri", today).single().templateKey)
        assertEquals("interview", QuickCapture.parse("interviu la Endava luni", today).single().templateKey)
        assertEquals("delivery", QuickCapture.parse("predare proiect duminică", today).single().templateKey)
    }

    @Test
    fun `eveniment fara zi - ziua ramane de ales`() {
        val exam = QuickCapture.parse("colocviu baze de date", today).single()
        assertEquals(Kind.EVENT, exam.kind)
        assertNull(exam.date)
    }

    @Test
    fun `tipul ales de utilizator recitește bucata`() {
        val asDay = QuickCapture.read("licență 6h", today, Kind.DAY).single()
        assertEquals(Kind.DAY, asDay.kind)
        assertEquals(6 * 60, asDay.minutes)
        assertEquals(today, asDay.date)
    }

    @Test
    fun `text gol sau doar virgule`() {
        assertTrue(QuickCapture.parse(" , ,\n", today).isEmpty())
    }

    @Test
    fun `etichetele zilelor`() {
        assertEquals("Azi", QuickCapture.dayLabel(today, today))
        assertEquals("Mâine", QuickCapture.dayLabel(today.plusDays(1), today))
        assertEquals("Vineri", QuickCapture.dayLabel(today.plusDays(2), today))
    }
}
