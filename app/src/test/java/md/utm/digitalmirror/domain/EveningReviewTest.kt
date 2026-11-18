package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.EveningReview.Issue
import md.utm.digitalmirror.domain.EveningReview.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EveningReviewTest {

    private fun h(hour: Int, minute: Int = 0) = hour * 60 + minute

    private val usualNights = List(5) { SleepPlanner.Night(460, h(0, 30), h(9)) }
    private val window = h(9, 20) to h(24)

    private fun sleepFor(items: List<Item>) =
        SleepPlanner.plan(usualNights, items.minOfOrNull { it.start })

    @Test
    fun `zi obisnuita - nicio intrebare`() {
        val items = listOf(Item(h(15, 15), h(16, 45), "C.PSI", flexible = false))
        assertTrue(EveningReview.issues(items, sleepFor(items), window).isEmpty())
    }

    @Test
    fun `o sesiune flexibila la 8 te-ar scula devreme - propunem s-o mutam`() {
        val items = listOf(
            Item(h(8), h(9, 30), "Licență", flexible = true, activityId = 7),
            Item(h(11, 30), h(13), "C.PAD", flexible = false)
        )
        val issue = EveningReview.issues(items, sleepFor(items), window).single() as Issue.EarlyBecauseFlexible
        assertEquals("Licență", issue.item.title)
        assertEquals(h(9), issue.usualWake)
        // după trezirea obișnuită și dimineață (9:20), la primul sfert de oră liber: 9:30–11:00, înaintea cursului
        assertEquals(h(9, 30), issue.moveTo)
    }

    @Test
    fun `un curs devreme nu e o exceptie - nu se poate muta`() {
        val items = listOf(Item(h(8), h(9, 30), "C.PAD", flexible = false))
        assertTrue(EveningReview.issues(items, sleepFor(items), window).isEmpty())
    }

    @Test
    fun `evenimentele fara loc se intreaba o singura data`() {
        val items = listOf(Item(h(15), h(16), "C.PAD", flexible = false))
        val issues = EveningReview.issues(items, sleepFor(items), window, eventsWithoutPlace = listOf("C.PAD", "C.PAD"))
        assertEquals(1, issues.count { it is Issue.NoPlace })
    }

    @Test
    fun `ziua prea plina e semnalata`() {
        val load = DayLoad.Result(plannedMinutes = 420, typicalMinutes = 240, daysUsed = 10, status = DayLoad.Status.OVERLOADED)
        val issues = EveningReview.issues(emptyList(), sleepFor(emptyList()), window, load = load)
        assertTrue(issues.single() is Issue.Overloaded)
    }
}
