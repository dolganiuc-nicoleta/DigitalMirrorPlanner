package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.HistoryEstimator.Level
import md.utm.digitalmirror.domain.HistoryEstimator.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryEstimatorTest {

    private fun sample(title: String, category: Long, base: Int?, actual: Int, age: Double = 0.0) =
        Sample(titleKey = title, categoryId = category, baseMinutes = base, actualMinutes = actual, ageDays = age)

    @Test
    fun `fara istoric pastreaza estimarea utilizatorului`() {
        val e = HistoryEstimator.estimate(emptyList(), "lectura", 1, userGuess = 60)

        assertEquals(60, e.minutes)
        assertEquals(Level.PRIOR, e.level)
        assertTrue(e.lowMinutes < 60 && e.highMinutes > 60)
    }

    @Test
    fun `fara istoric si fara estimare propune valoarea implicita`() {
        val e = HistoryEstimator.estimate(emptyList(), null, null, userGuess = null)
        assertEquals(HistoryEstimator.DEFAULT_MINUTES, e.minutes)
    }

    @Test
    fun `corecteaza subestimarea din categorie`() {
        // în categoria 1, activitățile durează constant de 1.5 ori mai mult decât estimează utilizatorul
        val history = List(20) { sample("altceva", 1, base = 40, actual = 60) }
        val e = HistoryEstimator.estimate(history, "lectura", 1, userGuess = 40)

        // 20 de activități → pondere 20/25 = 0.8 spre raportul 1.5, deci ~40 × 1.38 = 55
        assertEquals(55, e.minutes)
        assertEquals(Level.CATEGORY, e.level)
        assertEquals(1.5, e.categoryRatio!!, 0.001)
    }

    @Test
    fun `aceeasi activitate trage estimarea spre durata ei tipica`() {
        val history = List(10) { sample("laborator broker", 2, base = 60, actual = 120) }
        val e = HistoryEstimator.estimate(history, "laborator broker", 2, userGuess = 60)

        assertEquals(Level.ACTIVITY, e.level)
        assertEquals(10, e.activityCount)
        assertEquals(120, e.activityTypicalMinutes)
        assertTrue("estimarea ar trebui să fie aproape de 120, nu de 60", e.minutes in 100..120)
    }

    @Test
    fun `activitatile vechi conteaza mult mai putin`() {
        val recent = List(5) { sample("laborator broker", 2, base = 60, actual = 100, age = 0.0) }
        val veryOld = List(5) { sample("laborator broker", 2, base = 60, actual = 200, age = 365.0) }

        val onlyRecent = HistoryEstimator.estimate(recent, "laborator broker", 2, userGuess = 60)
        val withOld = HistoryEstimator.estimate(recent + veryOld, "laborator broker", 2, userGuess = 60)

        // activitățile de acum un an aproape nu mai contează
        assertEquals(onlyRecent.minutes, withOld.minutes)
    }

    @Test
    fun `o valoare absurda nu strica estimarea`() {
        // o activitate estimată la 10 min care a durat 300 (cronometru uitat) printre 9 normale
        val history = listOf(sample("x", 1, base = 10, actual = 300)) +
                List(9) { sample("x", 1, base = 40, actual = 40) }
        val e = HistoryEstimator.estimate(history, "altceva", 1, userGuess = 40)

        // raportul de 30 e tăiat la 4, deci estimarea rămâne aproape de 40
        assertTrue(e.minutes <= 50)
    }

    @Test
    fun `intervalul contine estimarea`() {
        val history = listOf(
            sample("y", 1, 30, 25), sample("y", 1, 30, 45), sample("y", 1, 30, 35),
            sample("y", 1, 30, 50), sample("y", 1, 30, 30)
        )
        val e = HistoryEstimator.estimate(history, "y", 1, userGuess = 30)
        assertTrue(e.lowMinutes <= e.minutes && e.minutes <= e.highMinutes)
        assertTrue(e.lowMinutes < e.highMinutes)
    }

    @Test
    fun `ponderea dupa vechime se injumatateste la 30 de zile`() {
        assertEquals(1.0, HistoryEstimator.recencyWeight(0.0), 1e-9)
        assertEquals(0.5, HistoryEstimator.recencyWeight(30.0), 1e-9)
        assertEquals(0.25, HistoryEstimator.recencyWeight(60.0), 1e-9)
    }
}