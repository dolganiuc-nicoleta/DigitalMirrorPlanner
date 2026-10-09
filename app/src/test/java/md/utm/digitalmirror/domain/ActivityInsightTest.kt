package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityInsightTest {

    @Test
    fun `calculeaza durata tipica si depasirea`() {
        val r = ActivityInsight.compute(listOf(
            s(planned = 120, actual = 150, "TIMER"),     // +25%
            s(planned = 120, actual = 170, "TIMER"),     // +42%
            s(planned = 100, actual = 135, "MANUAL")     // +35%
        ))
        assertTrue(r.enoughData)
        assertEquals(150, r.typicalMinutes)
        assertEquals(35, r.overrunPercent)
        assertEquals(2, r.timerCount)
        assertEquals(1, r.manualCount)
    }

    @Test
    fun `ignora duratele copiate din plan`() {
        val r = ActivityInsight.compute(listOf(
            s(60, 60, "AS_PLANNED"),
            s(60, 60, "AS_PLANNED"),
            s(60, 90, "TIMER")
        ))
        assertEquals(1, r.reliableCount)
        assertFalse(r.enoughData)
        assertNull(r.overrunPercent)
    }

    @Test
    fun `o sesiune neobisnuita nu schimba concluzia`() {
        // cronometrul uitat pornit o dată: 600 min în loc de ~60
        val r = ActivityInsight.compute(listOf(
            s(60, 62, "TIMER"), s(60, 58, "TIMER"),
            s(60, 65, "TIMER"), s(60, 600, "TIMER")
        ))
        assertEquals(64, r.typicalMinutes)   // mediana dintre 62 și 65 = 63.5 → 64
    }

    @Test
    fun `depasire negativa cand termina mai repede`() {
        val r = ActivityInsight.compute(List(3) { s(60, 45, "TIMER") })
        assertEquals(-25, r.overrunPercent)
    }

    private fun s(planned: Int?, actual: Int, source: String?) =
        ActivityInsight.Session(planned, actual, source)
}