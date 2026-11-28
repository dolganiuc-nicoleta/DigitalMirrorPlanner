package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.CapsuleBets.Bet
import md.utm.digitalmirror.domain.CapsuleBets.Kind
import md.utm.digitalmirror.domain.CapsuleBets.Sample
import md.utm.digitalmirror.domain.CapsuleBets.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleBetsTest {

    @Test
    fun `40h crezute, 31h lucrate - cu 9h mai putin`() {
        val r = CapsuleBets.result(Bet(Kind.HOURS, 40 * 60, categoryId = 1), 31 * 60)
        assertEquals(Verdict.BELOW, r.verdict)
        assertEquals(-9 * 60, r.difference)
    }

    @Test
    fun `somnul la 25 de minute e aproape`() {
        // 7h 30 crezute, 7h 05 dormite: în 10% (45 min) → aproape
        assertEquals(Verdict.NEAR, CapsuleBets.result(Bet(Kind.SLEEP, 450), 425).verdict)
    }

    @Test
    fun `procentul terminat - mai mult decat ai crezut`() {
        assertEquals(Verdict.ABOVE, CapsuleBets.result(Bet(Kind.COMPLETION, 70), 82).verdict)
        assertEquals(Verdict.NEAR, CapsuleBets.result(Bet(Kind.COMPLETION, 80), 76).verdict)
    }

    @Test
    fun `fara date nu spunem nimic`() {
        val r = CapsuleBets.result(Bet(Kind.SLEEP, 450), null)
        assertNull(r.verdict)
    }

    @Test
    fun `subestimarea - mediana, ca o zi ciudata sa nu strice`() {
        // planificat 60, real: 78, 80, 75, 90, 300 → +30%, +33%, +25%, +50%, +400% → mediana +33%
        val samples = listOf(78, 80, 75, 90, 300).map { Sample(60, it) }
        assertEquals(33, CapsuleBets.estimateError(samples))
    }

    @Test
    fun `prea putine activitati - fara estimare`() {
        assertNull(CapsuleBets.estimateError(List(4) { Sample(60, 80) }))
    }

    @Test
    fun `propunerea de ore din ritmul real`() {
        // 25h în ultimele 30 de zile → pentru 30 de zile: 25h
        assertEquals(25 * 60, CapsuleBets.suggestHours(25 * 60, 30, 30))
        // pentru 14 zile: ~12h
        assertEquals(12 * 60, CapsuleBets.suggestHours(25 * 60, 30, 14))
    }

    @Test
    fun `propunerea de somn si de terminat`() {
        val nights = List(5) { SleepPlanner.Night(430, 30, 480) }
        assertEquals(435, CapsuleBets.suggestSleep(nights))        // 7h 10 → la sfert: 7h 15
        assertEquals(70, CapsuleBets.suggestCompletion(CapsuleBets.completionRate(20, 13, 2)))
        assertEquals(75, CapsuleBets.suggestCompletion(null))
    }
}
