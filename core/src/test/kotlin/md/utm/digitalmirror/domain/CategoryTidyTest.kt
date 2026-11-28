package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.CategoryTidy.Named
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryTidyTest {

    @Test
    fun `pluralul si diacriticele sunt aceeasi categorie`() {
        val pairs = CategoryTidy.duplicates(
            listOf(Named(1, "Laborator"), Named(2, "Laboratoare"), Named(3, "Proiecte"), Named(4, "proiect")),
            usage = mapOf(1L to 2, 2L to 10, 3L to 5, 4L to 1)
        )
        assertEquals(2, pairs.size)
        // se păstrează cea cu mai multe activități
        assertEquals(2L, pairs.first { it.drop.id == 1L || it.keep.id == 1L }.keep.id)
        assertEquals(3L, pairs.first { it.drop.id == 4L || it.keep.id == 4L }.keep.id)
    }

    @Test
    fun `categorii diferite nu sunt dubluri`() {
        val pairs = CategoryTidy.duplicates(
            listOf(Named(1, "Sport"), Named(2, "Studiu"), Named(3, "Casă"), Named(4, "Lectură")),
            usage = emptyMap()
        )
        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `iconita ghicita din nume`() {
        assertEquals("school", CategoryTidy.suggestIcon("Studiu"))
        assertEquals("science", CategoryTidy.suggestIcon("Laboratoare"))
        assertEquals("fitness", CategoryTidy.suggestIcon("Sală"))
        assertEquals("home", CategoryTidy.suggestIcon("Casă"))
        assertNull(CategoryTidy.suggestIcon("Xyz"))
    }
}
