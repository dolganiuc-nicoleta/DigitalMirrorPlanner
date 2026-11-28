package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.Availability.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AvailabilityTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private val monday = LocalDate.of(2026, 10, 12)
    private val saturday = LocalDate.of(2026, 10, 17)

    private val classes = Rule(1, "Cursuri", Availability.WEEKDAYS, h(8), h(14), 40, 40)

    @Test
    fun `blocul apare doar in zilele alese`() {
        assertEquals(1, Availability.blocksFor(monday, listOf(classes), emptySet()).size)
        assertTrue(Availability.blocksFor(saturday, listOf(classes), emptySet()).isEmpty())
    }

    @Test
    fun `drumul extinde intervalul ocupat`() {
        val block = Availability.blocksFor(monday, listOf(classes), emptySet()).single()
        assertEquals(h(7, 20), block.busyStart)
        assertEquals(h(14, 40), block.busyEnd)
        // orele blocului rămân cele introduse
        assertEquals(h(8), block.start)
        assertEquals(h(14), block.end)
    }

    @Test
    fun `drumul nu iese din zi`() {
        val night = Rule(2, "Tură", Availability.ALL_DAYS, h(0, 10), h(23, 50), 60, 60)
        val block = Availability.blocksFor(monday, listOf(night), emptySet()).single()
        assertEquals(0, block.busyStart)
        assertEquals(24 * 60, block.busyEnd)
    }

    @Test
    fun `intr-o zi libera blocurile nu se aplica`() {
        val off = setOf(monday.toString())
        assertTrue(Availability.blocksFor(monday, listOf(classes), off).isEmpty())
    }

    @Test
    fun `blocurile sunt in ordinea in care incep`() {
        val breakfast = Rule(3, "Mic dejun", Availability.ALL_DAYS, h(6, 30), h(7), 0, 0)
        val result = Availability.blocksFor(monday, listOf(classes, breakfast), emptySet())
        assertEquals(listOf("Mic dejun", "Cursuri"), result.map { it.title })
    }

    @Test
    fun `ignora blocurile cu sfarsitul inainte de inceput`() {
        val broken = Rule(4, "Greșit", Availability.ALL_DAYS, h(10), h(9), 0, 0)
        assertTrue(Availability.blocksFor(monday, listOf(broken), emptySet()).isEmpty())
    }

    @Test
    fun `scrie zilele pe scurt`() {
        assertEquals("Zilnic", Availability.daysLabel(Availability.ALL_DAYS))
        assertEquals("Lu–Vi", Availability.daysLabel(Availability.WEEKDAYS))
        assertEquals("Sâ, Du", Availability.daysLabel(Availability.WEEKEND))
        assertEquals("Lu, Mi", Availability.daysLabel(0b101))
    }
}