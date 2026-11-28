package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.ForegroundTime.Event
import md.utm.digitalmirror.domain.ForegroundTime.PAUSED
import md.utm.digitalmirror.domain.ForegroundTime.RESUMED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundTimeTest {

    private val min = 60_000L

    @Test
    fun `aduna timpul pe aplicatie`() {
        val r = ForegroundTime.compute(
            listOf(
                Event("chrome", RESUMED, 10 * min), Event("chrome", PAUSED, 15 * min),
                Event("insta", RESUMED, 15 * min), Event("insta", PAUSED, 18 * min),
                Event("chrome", RESUMED, 20 * min), Event("chrome", PAUSED, 22 * min)
            ),
            from = 0, to = 60 * min
        )
        assertEquals(7 * min, r["chrome"])
        assertEquals(3 * min, r["insta"])
    }

    @Test
    fun `taie partea din afara intervalului`() {
        // deschisă de la minutul 5, dar sesiunea începe la minutul 10
        val r = ForegroundTime.compute(
            listOf(Event("chrome", RESUMED, 5 * min), Event("chrome", PAUSED, 14 * min)),
            from = 10 * min, to = 60 * min
        )
        assertEquals(4 * min, r["chrome"])
    }

    @Test
    fun `aplicatia ramasa deschisa se numara pana la sfarsitul intervalului`() {
        val r = ForegroundTime.compute(
            listOf(Event("chrome", RESUMED, 50 * min)),
            from = 0, to = 60 * min
        )
        assertEquals(10 * min, r["chrome"])
    }

    @Test
    fun `ignora aplicatiile folosite doar inainte de interval`() {
        val r = ForegroundTime.compute(
            listOf(Event("insta", RESUMED, 1 * min), Event("insta", PAUSED, 3 * min)),
            from = 10 * min, to = 60 * min
        )
        assertNull(r["insta"])
    }
}