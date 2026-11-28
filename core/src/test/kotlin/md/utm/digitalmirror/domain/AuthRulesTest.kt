package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {

    @Test
    fun `e-mailul trebuie sa aiba forma nume@domeniu punct tld`() {
        assertTrue(AuthRules.isEmailValid("ana@exemplu.md"))
        assertTrue(AuthRules.isEmailValid("  ana@exemplu.md  "))
        assertFalse(AuthRules.isEmailValid("ana"))
        assertFalse(AuthRules.isEmailValid("ana@exemplu"))
        assertFalse(AuthRules.isEmailValid("@exemplu.md"))
        assertFalse(AuthRules.isEmailValid("ana @exemplu.md"))
    }

    @Test
    fun `parola are minimum 6 caractere`() {
        assertFalse(AuthRules.isPasswordValid("12345"))
        assertTrue(AuthRules.isPasswordValid("123456"))
    }

    @Test
    fun `problem spune primul lucru care lipseste`() {
        assertEquals("Completează adresa de e-mail.", AuthRules.problem("", "parola123", null))
        assertEquals(
            "Parola are nevoie de cel puțin 6 caractere.",
            AuthRules.problem("ana@exemplu.md", "abc", null)
        )
        assertEquals("Repetă aceeași parolă.", AuthRules.problem("ana@exemplu.md", "parola123", "parola124"))
        assertEquals("Repetă aceeași parolă.", AuthRules.problem("ana@exemplu.md", "parola123", ""))
    }

    @Test
    fun `formularul valid nu are probleme`() {
        assertNull(AuthRules.problem("ana@exemplu.md", "parola123", null))
        assertNull(AuthRules.problem("ana@exemplu.md", "parola123", "parola123"))
    }

    @Test
    fun `la autentificare parola repetata nu conteaza`() {
        assertNull(AuthRules.problem("ana@exemplu.md", "parola123", null))
    }

    @Test
    fun `dupa 5 esecuri la rand butonul se blocheaza 30 de secunde`() {
        val t = LoginThrottle()
        repeat(4) { t.recordFailure(1_000L) }
        assertFalse(t.isLocked(1_000L))
        t.recordFailure(1_000L)
        assertTrue(t.isLocked(1_000L))
        assertEquals(30_000L, t.remainingMillis(1_000L))
        assertEquals(10_000L, t.remainingMillis(21_000L))
        assertFalse(t.isLocked(31_000L))
    }

    @Test
    fun `dupa pauza numaratoarea reincepe de la zero`() {
        val t = LoginThrottle()
        repeat(5) { t.recordFailure(0L) }
        // pauza a trecut; 4 esecuri noi nu mai blocheaza
        repeat(4) { t.recordFailure(40_000L) }
        assertFalse(t.isLocked(40_000L))
        t.recordFailure(40_000L)
        assertTrue(t.isLocked(40_000L))
    }

    @Test
    fun `esecurile din timpul pauzei nu prelungesc pauza`() {
        val t = LoginThrottle()
        repeat(5) { t.recordFailure(0L) }
        t.recordFailure(10_000L)
        assertEquals(20_000L, t.remainingMillis(10_000L))
    }

    @Test
    fun `succesul sterge esecurile`() {
        val t = LoginThrottle()
        repeat(4) { t.recordFailure(0L) }
        t.recordSuccess()
        repeat(4) { t.recordFailure(1L) }
        assertFalse(t.isLocked(1L))
    }
}