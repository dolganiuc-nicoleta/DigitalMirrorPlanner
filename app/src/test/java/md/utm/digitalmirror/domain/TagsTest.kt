package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {

    @Test
    fun `curata si normalizeaza etichetele`() {
        assertEquals(listOf("curs", "urgent"), Tags.parse("#Curs, URGENT"))
        assertEquals(listOf("a", "b", "c"), Tags.parse("a;b c\nc"))
    }

    @Test
    fun `scoate duplicatele si golurile`() {
        assertEquals(listOf("curs"), Tags.parse("#curs  #Curs ,, #"))
        assertEquals(emptyList<String>(), Tags.parse("   "))
    }

    @Test
    fun `limiteaza numarul si lungimea`() {
        assertEquals(5, Tags.parse("a b c d e f g").size)
        assertEquals(20, Tags.parse("x".repeat(40)).single().length)
    }

    @Test
    fun `salvarea si citirea dau aceeasi lista`() {
        val stored = Tags.encode(listOf("curs", "urgent"))
        assertEquals("curs,urgent", stored)
        assertEquals(listOf("curs", "urgent"), Tags.decode(stored))
        assertEquals("#curs #urgent", Tags.toInput(stored))
        assertEquals(emptyList<String>(), Tags.decode(""))
    }
}