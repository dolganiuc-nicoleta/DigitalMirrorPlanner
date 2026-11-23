package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.HistoryEstimator.Estimate
import md.utm.digitalmirror.domain.HistoryEstimator.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardMirrorTest {

    private fun estimate(minutes: Int, level: Level, same: Int = 0, category: Int = 0) =
        Estimate(minutes, minutes - 15, minutes + 15, level, same, category, null, null)

    @Test
    fun `de obicei iti ia mai mult - din activitati la fel`() {
        val u = CardMirror.usually(90, estimate(110, Level.ACTIVITY, same = 6))!!
        assertEquals(110, u.minutes)
        assertEquals(6, u.samples)
        assertTrue(u.sameActivity)
    }

    @Test
    fun `diferenta mica nu merita spusa`() {
        assertNull(CardMirror.usually(90, estimate(100, Level.ACTIVITY, same = 6)))
    }

    @Test
    fun `prea putine date - nu spunem nimic`() {
        assertNull(CardMirror.usually(90, estimate(130, Level.ACTIVITY, same = 2)))
        assertNull(CardMirror.usually(90, estimate(130, Level.PRIOR)))
    }

    @Test
    fun `din categorie, cu destule activitati`() {
        val u = CardMirror.usually(60, estimate(85, Level.CATEGORY, category = 8))!!
        assertEquals(false, u.sameActivity)
        assertEquals(8, u.samples)
    }

    @Test
    fun `dupa - peste, sub sau cat ai planuit`() {
        assertEquals(CardMirror.Versus.OVER, CardMirror.took(90, 110)?.versus)
        assertEquals(20, CardMirror.took(90, 110)?.difference)
        assertEquals(CardMirror.Versus.UNDER, CardMirror.took(90, 60)?.versus)
        assertEquals(CardMirror.Versus.ON_PLAN, CardMirror.took(90, 95)?.versus)
        assertNull(CardMirror.took(null, 95))
    }
}
