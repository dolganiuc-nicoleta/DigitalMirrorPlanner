package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.PlaceGuess.Known
import md.utm.digitalmirror.domain.PlaceGuess.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceGuessTest {

    private val home = Named(1, "Acasă")
    private val uni = Named(2, "Facultate")
    private val gym = Named(3, "Sala")
    private val gymLong = Named(4, "Sala de sport")
    private val places = listOf(home, uni, gym, gymLong)

    @Test
    fun `numele locului din titlu alege locul, fara diacritice si fara litere mari`() {
        assertEquals(1L, PlaceGuess.guess("citit ACASA seara", places, emptyList()))
        assertEquals(2L, PlaceGuess.guess("laborator PAD la facultate", places, emptyList()))
    }

    @Test
    fun `numele mai lung castiga`() {
        assertEquals(4L, PlaceGuess.guess("antrenament la sala de sport", places, emptyList()))
        assertEquals(3L, PlaceGuess.guess("antrenament la sala", places, emptyList()))
    }

    @Test
    fun `un cuvant doar asemanator nu conteaza, trebuie cuvantul intreg`() {
        // „acasa” nu e inclus în „acasadeva”
        assertNull(PlaceGuess.guess("ma duc acasadeva", places, emptyList()))
    }

    @Test
    fun `fara nume in titlu se foloseste locul obisnuit din istoric`() {
        val history = listOf(Known("sport", 3), Known("sport", 3), Known("sport", 1), Known("curs", 2))
        assertEquals(3L, PlaceGuess.guess("Sport", places, history))
        assertEquals(2L, PlaceGuess.guess("curs", places, history))
    }

    @Test
    fun `la egalitate castiga locul cel mai recent, adica primul din lista`() {
        val history = listOf(Known("citit", 2), Known("citit", 1))
        assertEquals(2L, PlaceGuess.guess("citit", places, history))
    }

    @Test
    fun `un loc sters nu se mai propune`() {
        val history = listOf(Known("sport", 99), Known("sport", 99), Known("sport", 3))
        assertEquals(3L, PlaceGuess.guess("sport", places, history))
        assertNull(PlaceGuess.guess("sport", places, listOf(Known("sport", 99))))
    }

    @Test
    fun `numele din titlu are prioritate fata de istoric`() {
        val history = listOf(Known("sport acasa", 3), Known("sport acasa", 3))
        assertEquals(1L, PlaceGuess.guess("sport acasa", places, history))
    }

    @Test
    fun `fara locuri sau fara titlu nu propune nimic`() {
        assertNull(PlaceGuess.guess("sport", emptyList(), listOf(Known("sport", 3))))
        assertNull(PlaceGuess.guess("   ", places, emptyList()))
    }
}