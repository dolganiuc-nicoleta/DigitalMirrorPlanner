package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.WidgetPick.Candidate
import md.utm.digitalmirror.domain.WidgetPick.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPickTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private val day = listOf(
        Candidate(1, "Licență", h(10, 45), 90, "DONE"),
        Candidate(2, "Exerciții", h(13), 60, "PLANNED"),
        Candidate(3, "Temă", h(19), 60, "PLANNED"),
        Candidate(4, "Sună la bancă", null, 10, "PLANNED")
    )

    @Test
    fun `in timpul unei activitati - porneste chiar pe ea`() {
        assertEquals(2L, WidgetPick.timerTarget(day, h(13, 20))?.id)
    }

    @Test
    fun `intre activitati - urmatoarea cu ora`() {
        assertEquals(3L, WidgetPick.timerTarget(day, h(15))?.id)
    }

    @Test
    fun `seara tarziu - una fara ora, daca mai e`() {
        assertEquals(4L, WidgetPick.timerTarget(day, h(22))?.id)
    }

    @Test
    fun `cele terminate nu se pornesc`() {
        assertNull(WidgetPick.timerTarget(day.filter { it.id == 1L }, h(11)))
    }

    @Test
    fun `urmeaza - doar ce nu s-a terminat, in ordine, cel mult trei`() {
        val lines = listOf(
            Line(h(15, 15), h(16, 45), "C.PSI"),
            Line(h(10), h(11), "Dimineața"),
            Line(h(13, 15), h(13, 45), "Prânz"),
            Line(h(17), h(18, 30), "C.PAM"),
            Line(h(19, 15), h(19, 45), "Cină")
        )
        val next = WidgetPick.upcoming(lines, h(12))
        assertEquals(listOf("Prânz", "C.PSI", "C.PAM"), next.map { it.title })
    }
}
