package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.Replan.Item
import md.utm.digitalmirror.domain.Replan.Outcome
import md.utm.digitalmirror.domain.SlotRecommender.Busy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplanTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    // e 13:00 și apare ceva pentru o oră: până la 14:00
    private val classes = listOf(Busy(h(15, 15), h(16, 45)), Busy(h(17), h(18, 30)))

    @Test
    fun `ce era in intrerupere se muta dupa ea`() {
        val moves = Replan.plan(
            items = listOf(Item(1, "Licență", h(13), 60)),
            fixed = classes, from = h(13), until = h(14), dayEnd = h(22, 30)
        )
        val m = moves.single()
        assertEquals(Outcome.MOVED, m.outcome)
        assertEquals(h(14), m.newStart)
    }

    @Test
    fun `ce era dupa intrerupere si inca incape ramane pe loc`() {
        val moves = Replan.plan(
            items = listOf(Item(1, "Licență", h(13), 60), Item(2, "Sală", h(19), 60)),
            fixed = classes, from = h(13), until = h(14), dayEnd = h(22, 30)
        )
        val gym = moves.first { it.item.id == 2L }
        assertEquals(Outcome.STAYS, gym.outcome)
        assertEquals(h(19), gym.newStart)
    }

    @Test
    fun `cursurile nu se muta si nici nu se suprapun`() {
        // 1h 30 de la 14:00 nu încape până la 15:15 → după cursuri
        val moves = Replan.plan(
            items = listOf(Item(1, "Licență", h(13), 90)),
            fixed = classes, from = h(13), until = h(14), dayEnd = h(22, 30)
        )
        assertEquals(h(18, 30), moves.single().newStart)
    }

    @Test
    fun `ce nu mai incape azi trece pe maine`() {
        val moves = Replan.plan(
            items = listOf(Item(1, "Proiect", h(20), 120)),
            fixed = emptyList(), from = h(20), until = h(21, 30), dayEnd = h(22, 30)
        )
        assertEquals(Outcome.TOMORROW, moves.single().outcome)
        assertNull(moves.single().newStart)
    }

    @Test
    fun `mutarile nu se suprapun intre ele si pastreaza ordinea`() {
        val moves = Replan.plan(
            items = listOf(Item(1, "A", h(13), 30), Item(2, "B", h(13, 30), 30)),
            fixed = emptyList(), from = h(13), until = h(14), dayEnd = h(22)
        )
        val a = moves.first { it.item.id == 1L }.newStart!!
        val b = moves.first { it.item.id == 2L }.newStart!!
        assertTrue(a < b)
        assertTrue(b >= a + 30)
    }
}
