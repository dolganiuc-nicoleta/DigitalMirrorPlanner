package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SleepPlanner.Night
import md.utm.digitalmirror.domain.SleepPlanner.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepPlannerTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    // de obicei: culcare 0:30, trezire 9:00, ~7h 40 somn
    private val usual = List(5) { Night(460, h(0, 30), h(9)) }

    @Test
    fun `zi care incepe tarziu - te trezesti la ora obisnuita`() {
        val p = SleepPlanner.plan(usual, firstStart = h(14, 16))
        assertEquals(h(9), p.wake)
        assertEquals(Reason.USUAL, p.reason)
        assertEquals(460, p.sleepMinutes)
    }

    @Test
    fun `zi care incepe devreme - te trezesti mai devreme`() {
        // pregătirea de plecare la 7:30, mic dejun 20 min: 7:30 - 20 - 20 = 6:50
        val p = SleepPlanner.plan(usual, firstStart = h(7, 30), breakfastMinutes = 20)
        assertEquals(h(6, 50), p.wake)
        assertEquals(Reason.EARLY_START, p.reason)
    }

    @Test
    fun `ora de culcare - cat dormi de obicei inainte de trezire`() {
        val p = SleepPlanner.plan(usual, firstStart = h(7, 30), breakfastMinutes = 20)
        // 6:50 - 7h 40 - 15 min = 22:55 (seara dinainte)
        assertEquals("22:55", SleepPlanner.clock(p.bedtimeBefore))
    }

    @Test
    fun `fara istoric - valori implicite`() {
        val p = SleepPlanner.plan(emptyList(), firstStart = null)
        assertEquals(SleepPlanner.DEFAULT_WAKE, p.wake)
        assertEquals(SleepPlanner.DEFAULT_SLEEP_MINUTES, p.sleepMinutes)
        assertEquals(Reason.DEFAULT, p.reason)
    }

    @Test
    fun `ora obisnuita de culcare dupa miezul noptii se numara peste 24`() {
        val p = SleepPlanner.plan(usual, firstStart = null)
        assertEquals(h(24, 30), p.usualBedtime)
    }

    @Test
    fun `lucrurile flexibile incep dupa trezirea obisnuita si dimineata`() {
        val (start, end) = SleepPlanner.activeWindow(usual)
        assertEquals(h(9, 20), start)          // 9:00 + 20 min
        assertEquals(h(23, 30), end)           // culcarea la 0:30, minus perna de seară de o oră
    }

    @Test
    fun `fara istoric - interval implicit`() {
        val (start, end) = SleepPlanner.activeWindow(emptyList())
        assertEquals(h(8, 20), start)
        assertEquals(h(22), end)               // 23:00 minus perna de seară
    }

    @Test
    fun `perna de seara - nimic flexibil in ultima ora inainte de culcare`() {
        // culcare 23:35 → lucrurile flexibile se termină până la 22:35
        val nights = List(5) { Night(450, h(23, 35), h(7, 5)) }
        assertEquals(h(22, 35), SleepPlanner.activeWindow(nights).second)
    }

    @Test
    fun `o noapte salvata doar pe jumatate nu muta ora obisnuita de trezire`() {
        // ca în Health, până confirmi: 3h 20, 4:10–7:30
        val nights = usual + List(3) { Night(200, h(4, 10), h(7, 30)) }
        val p = SleepPlanner.plan(nights, firstStart = null)
        assertEquals(h(9), p.wake)
    }

    @Test
    fun `confirmarea propune noaptea din health cand e intreaga`() {
        val c = SleepPlanner.check(Night(450, h(0, 15), h(7, 45)), usual)
        assertEquals(h(0, 15), c.bedtime)
        assertEquals(h(7, 45), c.wake)
        assertEquals(true, c.fromHealth)
        assertEquals(450, c.minutes)
    }

    @Test
    fun `confirmarea ignora noaptea salvata pe jumatate si propune ritmul obisnuit`() {
        val c = SleepPlanner.check(Night(200, h(4, 10), h(7, 30)), usual)
        assertEquals(h(0, 30), c.bedtime)
        assertEquals(h(9), c.wake)
        assertEquals(false, c.fromHealth)
        assertEquals(200, c.partialHealth?.sleepMinutes)
    }

    @Test
    fun `durata peste miezul noptii`() {
        assertEquals(h(8, 30), SleepPlanner.duration(h(23, 30), h(8)))
        assertEquals(h(7), SleepPlanner.duration(h(1), h(8)))
    }

    @Test
    fun `ora aleasa pentru o zi are prioritate`() {
        // vineri mă trezesc la 10:00, chiar dacă de obicei la 9:00
        val p = SleepPlanner.plan(usual, firstStart = h(14), chosenWake = h(10))
        assertEquals(h(10), p.wake)
        assertEquals(Reason.CHOSEN, p.reason)
        // iar lucrurile flexibile încep după ora aleasă
        assertEquals(h(10, 20), SleepPlanner.activeWindow(usual, chosenWake = h(10)).first)
    }

    @Test
    fun `noptile ciudate nu strica media`() {
        val nights = usual + Night(60, h(4), h(5)) + Night(900, h(20), h(11))
        val p = SleepPlanner.plan(nights, firstStart = null)
        assertEquals(460, p.sleepMinutes)
    }
}
