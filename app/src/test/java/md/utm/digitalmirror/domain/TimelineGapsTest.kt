package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy
import md.utm.digitalmirror.domain.TimelineGaps.Gap
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGapsTest {

    @Test
    fun `zi goala este un singur spatiu`() {
        assertEquals(listOf(Gap(480, 1320)), TimelineGaps.compute(emptyList(), 480, 1320))
    }

    @Test
    fun `spatii intre activitati`() {
        val busy = listOf(Busy(600, 660), Busy(720, 780))   // 10-11 si 12-13
        assertEquals(
            listOf(Gap(480, 600), Gap(660, 720), Gap(780, 1320)),
            TimelineGaps.compute(busy, 480, 1320)
        )
    }

    @Test
    fun `intervale suprapuse sau neordonate se unesc`() {
        val busy = listOf(Busy(720, 840), Busy(600, 780), Busy(630, 650))
        assertEquals(listOf(Gap(480, 600), Gap(840, 1320)), TimelineGaps.compute(busy, 480, 1320))
    }

    @Test
    fun `spatiile prea scurte nu se arata`() {
        val busy = listOf(Busy(480, 600), Busy(620, 700))    // intre ele doar 20 min
        assertEquals(listOf(Gap(700, 1320)), TimelineGaps.compute(busy, 480, 1320, 30))
    }

    @Test
    fun `ce e in afara ferestrei nu conteaza, ce o taie se taie`() {
        val busy = listOf(Busy(0, 420), Busy(1300, 1400), Busy(400, 540))
        assertEquals(listOf(Gap(540, 1300)), TimelineGaps.compute(busy, 480, 1320))
    }

    @Test
    fun `fereastra goala sau inversata`() {
        assertEquals(emptyList<Gap>(), TimelineGaps.compute(emptyList(), 600, 600))
        assertEquals(emptyList<Gap>(), TimelineGaps.compute(emptyList(), 700, 600))
    }

    @Test
    fun `ziua plina nu are spatii`() {
        assertEquals(emptyList<Gap>(), TimelineGaps.compute(listOf(Busy(0, 1440)), 480, 1320))
    }
}
