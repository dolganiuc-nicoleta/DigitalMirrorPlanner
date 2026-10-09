package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.ProductivityMap.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductivityMapTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `asaza activitatile in intervalele corecte`() {
        assertEquals(0, ProductivityMap.slotOf(h(6)))
        assertEquals(0, ProductivityMap.slotOf(h(8, 59)))
        assertEquals(3, ProductivityMap.slotOf(h(15, 30)))
        assertEquals(5, ProductivityMap.slotOf(h(23, 50)))
        assertNull(ProductivityMap.slotOf(h(2)))    // noaptea nu intră în hartă
    }

    @Test
    fun `numara activitatile si reusitele pe celula`() {
        val r = ProductivityMap.compute(listOf(
            Outcome(1, h(9, 30), true),
            Outcome(1, h(10), false),
            Outcome(2, h(9, 30), true)
        ))
        val mondayMorning = r.cell(1, 1)
        assertEquals(2, mondayMorning.total)
        assertEquals(1, mondayMorning.successes)
        assertEquals(50, mondayMorning.percent)
        assertNull(r.cell(3, 1).rate)
    }

    @Test
    fun `gaseste cel mai bun interval si cea mai buna zi`() {
        val afternoon = List(4) { Outcome(it % 4 + 1, h(16), true) }      // 15–18, toate reușite
        val evening = List(4) { Outcome(it % 4 + 1, h(21), false) }       // 21–24, toate eșuate
        val r = ProductivityMap.compute(afternoon + evening)

        assertEquals("15–18", r.bestSlot!!.label)
        assertEquals(100, r.bestSlot!!.percent)
        assertEquals(4, r.bestSlot!!.total)
    }

    @Test
    fun `fara destule date nu trage concluzii`() {
        val r = ProductivityMap.compute(listOf(Outcome(1, h(9), true), Outcome(1, h(9), true)))
        assertNull(r.bestSlot)
        assertNull(r.bestDay)
    }

    @Test
    fun `o serie scurta nu bate o serie lunga aproape perfecta`() {
        // marți: 3 din 3; luni: 9 din 10 → luni are mai multe date, deci e mai sigură
        val r = ProductivityMap.compute(
            List(9) { Outcome(1, h(10), true) } + Outcome(1, h(10), false) +
                    listOf(Outcome(2, h(10), true), Outcome(2, h(10), true), Outcome(2, h(10), true))
        )
        assertEquals("luni", r.bestDay!!.label)
    }
}