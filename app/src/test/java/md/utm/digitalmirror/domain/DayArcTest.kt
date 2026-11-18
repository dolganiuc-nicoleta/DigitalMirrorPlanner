package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.DayArc.Item
import md.utm.digitalmirror.domain.DayArc.Kind
import md.utm.digitalmirror.domain.DayArc.Leg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayArcTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    // ziua din machetă: trezire 9:00, culcare 0:15
    private val wake = h(9)
    private val bed = h(24, 15)
    private val items = listOf(
        Item(h(10, 45), h(12, 15), "Licență", Kind.DONE),
        Item(h(13, 15), h(13, 45), "Prânz", Kind.MEAL),
        Item(h(15, 15), h(16, 45), "C.PSI 3.3", Kind.EVENT),
        Item(h(17), h(18, 30), "C.PAM 3.3", Kind.EVENT),
        Item(h(19, 15), h(19, 45), "Cină", Kind.MEAL)
    )
    private val legs = listOf(
        Leg(h(14, 16), h(14, 46), h(15, 15), "Facultate"),
        Leg(h(18, 30), h(18, 30), h(18, 59), "Acasă")
    )

    @Test
    fun `dupa-amiaza - pana te pregatesti`() {
        val f = DayArc.focus(wake, bed, items, legs, now = h(12, 40))
        assertEquals("până te pregătești", f.label)
        assertEquals(96, f.minutes)                      // 1h 36
        assertEquals("pleci la 14:46 spre Facultate", f.sentence)
        assertEquals("1h 36", DayArc.duration(f.minutes))
    }

    @Test
    fun `in timpul cursului - acum`() {
        val f = DayArc.focus(wake, bed, items, legs, now = h(16))
        assertEquals("acum", f.label)
        assertEquals(45, f.minutes)
        assertEquals("C.PSI 3.3 · până la 16:45", f.sentence)
    }

    @Test
    fun `in timpul pregatirii - pleci in`() {
        val f = DayArc.focus(wake, bed, items, legs, now = h(14, 30))
        assertEquals("pleci în", f.label)
        assertEquals(16, f.minutes)
    }

    @Test
    fun `seara - liber pana la culcare`() {
        val f = DayArc.focus(wake, bed, items, legs, now = h(20, 30))
        assertEquals("liber până la culcare", f.label)
        assertEquals(h(3, 45), f.minutes)
        assertEquals("culcare 0:15", f.sentence)
    }

    @Test
    fun `alta zi - rezumatul zilei`() {
        val f = DayArc.focus(wake, bed, items, legs, now = null)
        assertEquals("libere în ziua asta", f.label)
        assertTrue(f.minutes > 0)
    }

    @Test
    fun `segmentele sunt fractii din zi si soarele e acum`() {
        val r = DayArc.build(wake, bed, items, legs, now = h(12, 40))
        val psi = r.segments.first { it.kind == Kind.EVENT }
        assertEquals((h(15, 15) - wake) / (bed - wake).toFloat(), psi.from, 0.001f)
        assertTrue(r.segments.any { it.kind == Kind.PREP })      // 14:16–14:46
        assertEquals(2, r.segments.count { it.kind == Kind.TRAVEL })
        assertEquals((h(12, 40) - wake) / (bed - wake).toFloat(), r.now!!, 0.001f)
    }

    @Test
    fun `fara pregatire nu apare segment de pregatire`() {
        val r = DayArc.build(wake, bed, emptyList(), listOf(Leg(h(18, 30), h(18, 30), h(18, 59), "Acasă")), now = null)
        assertTrue(r.segments.none { it.kind == Kind.PREP })
        assertNull(r.now)
    }
}
