package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.CategoryGuess.Cat
import md.utm.digitalmirror.domain.CategoryGuess.Known
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryGuessTest {

    private val categories = listOf(Cat(1, "Studiu"), Cat(2, "Sport"), Cat(3, "Casă"))
    private val known = listOf(
        Known("laborator pad", 1), Known("laborator pad", 1), Known("laborator broker", 1),
        Known("alergat", 2), Known("curatenie generala", 3)
    )

    @Test
    fun `acelasi titlu da aceeasi categorie`() {
        assertEquals(1L, CategoryGuess.guess("Laborator PAD", categories, known))
        assertEquals(2L, CategoryGuess.guess("alergat", categories, known))
    }

    @Test
    fun `un cuvant comun cu titluri din istoric`() {
        assertEquals(1L, CategoryGuess.guess("laborator retele", categories, known))
        assertEquals(3L, CategoryGuess.guess("curățenie în bucătărie", categories, known))
    }

    @Test
    fun `numele categoriei ajuta cand nu exista istoric`() {
        assertEquals(1L, CategoryGuess.guess("studiez pentru examen", categories, emptyList()))
        assertEquals(2L, CategoryGuess.guess("sporturi de iarnă", categories, emptyList()))
    }

    @Test
    fun `fara indicii nu ghiceste`() {
        assertNull(CategoryGuess.guess("telefon bunica", categories, known))
        assertNull(CategoryGuess.guess("la", categories, known))
    }

    @Test
    fun `ignora categoriile care nu mai exista`() {
        assertNull(CategoryGuess.guess("laborator PAD", listOf(Cat(2, "Sport")), known))
    }
}