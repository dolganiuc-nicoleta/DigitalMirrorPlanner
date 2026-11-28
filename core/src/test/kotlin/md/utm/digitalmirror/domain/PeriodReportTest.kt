package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.PeriodReport.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodReportTest {

    // marți; săptămâna curentă începe luni, 12 oct
    private val today = LocalDate.of(2026, 10, 13)
    private val monday = LocalDate.of(2026, 10, 12)

    private fun item(
        date: LocalDate,
        state: String,
        minutes: Int? = null,
        category: Long? = null,
        title: String = "Activitate",
        key: String? = null,
        postponed: Int = 0
    ) = Item(date, state, minutes, category, title, key, postponed)

    private fun week(items: List<Item>) = PeriodReport.summarize(items, monday, monday.plusDays(6), today)

    @Test
    fun `saptamana incepe luni`() {
        assertEquals(monday, PeriodReport.weekStart(today))
        assertEquals(monday, PeriodReport.weekStart(monday))
        assertEquals(monday, PeriodReport.weekStart(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `numara starile si calculeaza rata de finalizare`() {
        val s = week(
            listOf(
                item(monday, "DONE"), item(monday, "DONE"),
                item(monday, "PARTIAL"), item(monday, "ABANDONED")
            )
        )
        assertEquals(4, s.due)
        assertEquals(2, s.done)
        assertEquals(1, s.partial)
        assertEquals(1, s.abandoned)
        assertEquals(0, s.notClosed)
        assertEquals(50, s.completionPercent)
    }

    @Test
    fun `ce e planificat pentru azi nu conteaza ca nefinalizat dar ce a ramas din zile trecute da`() {
        val s = week(listOf(item(today, "PLANNED"), item(monday, "PLANNED"), item(monday, "DONE")))
        assertEquals(2, s.due)
        assertEquals(1, s.notClosed)
        assertEquals(50, s.completionPercent)
    }

    @Test
    fun `zilele viitoare nu se numara`() {
        val s = week(listOf(item(monday, "DONE"), item(today.plusDays(1), "DONE", 60)))
        assertEquals(1, s.due)
        assertEquals(0, s.minutes)
    }

    @Test
    fun `timpul lucrat vine doar din activitati finalizate sau partiale`() {
        val s = week(
            listOf(
                item(monday, "DONE", 60, category = 1),
                item(monday, "PARTIAL", 30, category = 2),
                item(today, "DONE", 45, category = 1),
                item(monday, "ABANDONED", 20, category = 1),
                item(monday, "DONE", null)
            )
        )
        assertEquals(135, s.minutes)
        assertEquals(2, s.activeDays)
        assertEquals(105, s.minutesByCategory[1L])
        assertEquals(30, s.minutesByCategory[2L])
    }

    @Test
    fun `fara nimic scadent nu exista procent`() {
        assertNull(week(emptyList()).completionPercent)
        assertNull(week(listOf(item(today, "PLANNED"))).completionPercent)
    }

    @Test
    fun `evolutia da opt saptamani cea mai veche prima`() {
        val list = PeriodReport.weeks(listOf(item(monday, "DONE")), monday, 8, today)
        assertEquals(8, list.size)
        assertEquals(monday.minusWeeks(7), list.first().from)
        assertEquals(monday, list.last().from)
        assertEquals(1, list.last().done)
        assertEquals(0, list.first().due)
    }

    @Test
    fun `compara ultimele zile cu cele dinaintea lor`() {
        val items = listOf(
            // ultimele 7 zile: 6–13 oct
            item(today, "DONE", 60), item(today.minusDays(2), "DONE", 60), item(today.minusDays(3), "ABANDONED"),
            // cele 7 dinainte: 29 sept – 5 oct
            item(today.minusDays(10), "DONE", 60), item(today.minusDays(11), "ABANDONED"),
            item(today.minusDays(12), "ABANDONED"), item(today.minusDays(13), "ABANDONED")
        )
        val c = PeriodReport.compare(items, 7, today)
        assertEquals(2, c.current.done)
        assertEquals(1, c.previous.done)
        assertEquals(1, c.doneDelta)
        assertEquals(67 - 25, c.completionDelta)
        assertEquals(100, c.minutesChangePercent)
    }

    @Test
    fun `comparatia fara perioada anterioara nu da procente`() {
        val c = PeriodReport.compare(listOf(item(today, "DONE", 30)), 7, today)
        assertNull(c.completionDelta)
        assertNull(c.minutesChangePercent)
    }

    @Test
    fun `gaseste activitatile amanate repetat si inca nefacute`() {
        val items = listOf(
            item(monday.minusDays(5), "PLANNED", title = "Scrie capitolul 2", key = "scrie capitolul 2", postponed = 2),
            item(monday, "PLANNED", title = "Scrie capitolul 2", key = "scrie capitolul 2", postponed = 1),
            item(monday, "PLANNED", title = "Sport", key = "sport", postponed = 1),
            item(monday.minusDays(9), "PLANNED", title = "Curățenie", key = "curatenie", postponed = 2),
            item(monday, "DONE", title = "Curățenie", key = "curatenie", postponed = 1)
        )
        val result = PeriodReport.repeatedlyPostponed(items)
        assertEquals(1, result.size)
        assertEquals("Scrie capitolul 2", result[0].title)
        assertEquals(3, result[0].times)
        assertEquals(2, result[0].instances)
    }

    @Test
    fun `observatiile spun cate s-au facut si cum se compara`() {
        val current = week(listOf(item(monday, "DONE", 90), item(monday, "DONE", 30), item(monday, "ABANDONED")))
        val previous = PeriodReport.summarize(
            listOf(item(monday.minusDays(7), "DONE", 30), item(monday.minusDays(6), "ABANDONED")),
            monday.minusWeeks(1), monday.minusDays(1), today
        )
        val lines = PeriodReport.observations(current, previous, "săptămâna trecută", "Studiu", emptyList())
        assertEquals("Ai finalizat 2 din 3 activități (67%), cu 17 puncte mai mult decât săptămâna trecută.", lines[0])
        assertEquals("Ai lucrat 2 h în 1 zi, cu 300% mai mult decât săptămâna trecută.", lines[1])
        assertEquals("Cel mai mult timp a mers la Studiu.", lines[2])
    }

    @Test
    fun `observatii pentru perioada goala si pentru amanari repetate`() {
        val empty = PeriodReport.observations(week(emptyList()), null, "săptămâna trecută", null, emptyList())
        assertEquals(listOf("Nu ai activități scadente în această perioadă."), empty)

        val lines = PeriodReport.observations(
            week(listOf(item(monday, "DONE", 30), item(monday, "DONE", 30), item(monday, "DONE", 30))),
            null, "săptămâna trecută", null,
            listOf(PeriodReport.Postponed("Scrie capitolul 2", 4, 3))
        )
        assertTrue(lines.any { it == "Toate activitățile scadente au fost finalizate." })
        assertTrue(lines.last().startsWith("„Scrie capitolul 2” a fost amânată de 4 ori."))
    }
}