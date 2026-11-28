package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.CapsuleLogic.GoalLine
import md.utm.digitalmirror.domain.CapsuleLogic.Nudge
import md.utm.digitalmirror.domain.CapsuleLogic.State
import md.utm.digitalmirror.domain.GoalProgress.Momentum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CapsuleLogicTest {

    private val today = LocalDate.of(2026, 10, 13)
    private val created = LocalDate.of(2026, 10, 1)       // 12 zile în urmă
    private val unlock = LocalDate.of(2026, 11, 13)

    private fun nudge(
        momenta: List<Momentum>,
        state: State = State.LOCKED,
        enabled: Boolean = true,
        createdOn: LocalDate = created,
        last: LocalDate? = null
    ) = CapsuleLogic.nudge(state, enabled, createdOn, last, momenta, today)

    @Test
    fun `inainte de data e sigilata`() {
        assertEquals(State.LOCKED, CapsuleLogic.state(unlock, opened = false, today = today))
    }

    @Test
    fun `in ziua stabilita se poate deschide`() {
        assertEquals(State.READY, CapsuleLogic.state(today, opened = false, today = today))
        assertEquals(State.READY, CapsuleLogic.state(today.minusDays(5), opened = false, today = today))
    }

    @Test
    fun `deschisa ramane deschisa chiar daca data nu a venit`() {
        assertEquals(State.OPENED, CapsuleLogic.state(unlock, opened = true, today = today))
    }

    @Test
    fun `fara incetinire nu apare nicio amintire`() {
        assertEquals(Nudge.NONE, nudge(listOf(Momentum.OK, Momentum.TOO_EARLY)))
    }

    @Test
    fun `stagnarea declanseaza amintirea`() {
        assertEquals(Nudge.STAGNANT, nudge(listOf(Momentum.OK, Momentum.STAGNANT)))
    }

    @Test
    fun `incetinirea declanseaza amintirea`() {
        assertEquals(Nudge.SLOWING, nudge(listOf(Momentum.SLOWING)))
    }

    @Test
    fun `stagnarea conteaza mai mult decat incetinirea`() {
        assertEquals(Nudge.STAGNANT, nudge(listOf(Momentum.SLOWING, Momentum.STAGNANT)))
    }

    @Test
    fun `fara optiunea activata nu se spune nimic`() {
        assertEquals(Nudge.NONE, nudge(listOf(Momentum.STAGNANT), enabled = false))
    }

    @Test
    fun `fara obiective legate nu se spune nimic`() {
        assertEquals(Nudge.NONE, nudge(emptyList()))
    }

    @Test
    fun `capsula prea noua nu intreaba`() {
        assertEquals(Nudge.NONE, nudge(listOf(Momentum.STAGNANT), createdOn = today.minusDays(2)))
    }

    @Test
    fun `cel mult o amintire pe saptamana`() {
        assertEquals(Nudge.NONE, nudge(listOf(Momentum.STAGNANT), last = today.minusDays(3)))
        assertEquals(Nudge.STAGNANT, nudge(listOf(Momentum.STAGNANT), last = today.minusDays(7)))
    }

    @Test
    fun `o capsula gata de deschis sau deschisa nu mai e amintita`() {
        assertEquals(Nudge.NONE, nudge(listOf(Momentum.STAGNANT), state = State.READY))
        assertEquals(Nudge.NONE, nudge(listOf(Momentum.STAGNANT), state = State.OPENED))
    }

    @Test
    fun `zilele acoperite includ prima si ultima zi`() {
        assertEquals(1, CapsuleLogic.daysCovered(today, today))
        assertEquals(32, CapsuleLogic.daysCovered(created.plusDays(0), created.plusDays(31)))
    }

    @Test
    fun `schimbarea unui obiectiv`() {
        assertEquals(62, CapsuleLogic.change(GoalLine("Teza", 0, 62)))
        assertEquals(-5, CapsuleLogic.change(GoalLine("Teza", 40, 35)))
        assertNull(CapsuleLogic.change(GoalLine("Teza", 10, null)))
    }
}