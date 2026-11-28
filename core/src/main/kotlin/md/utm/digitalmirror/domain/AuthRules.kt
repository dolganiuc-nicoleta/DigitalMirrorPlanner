package md.utm.digitalmirror.domain

/**
 * Regulile formularului de autentificare (CF-01, CF-02): ce e valid și ce lipsește.
 * Sunt separate de interfață și de Firebase, ca să se poată testa direct.
 */
object AuthRules {

    const val MIN_PASSWORD = 6

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isEmailValid(email: String): Boolean = EMAIL.matches(email.trim())

    fun isPasswordValid(password: String): Boolean = password.length >= MIN_PASSWORD

    /**
     * Primul lucru care lipsește din formular, sau null dacă se poate trimite.
     * [repeat] contează doar la crearea contului (null = autentificare).
     */
    fun problem(email: String, password: String, repeat: String?): String? = when {
        !isEmailValid(email) -> "Completează adresa de e-mail."
        !isPasswordValid(password) -> "Parola are nevoie de cel puțin $MIN_PASSWORD caractere."
        repeat != null && repeat != password -> "Repetă aceeași parolă."
        else -> null
    }
}

/**
 * Pauză după prea multe încercări greșite la rând (CF-02): după [maxFailures] eșecuri
 * butonul rămâne blocat [lockMillis]. Timpul se dă din exterior, ca să putem testa.
 */
class LoginThrottle(
    private val maxFailures: Int = 5,
    private val lockMillis: Long = 30_000L
) {
    private var failures = 0
    private var lockedUntil = 0L

    fun remainingMillis(now: Long): Long = (lockedUntil - now).coerceAtLeast(0L)

    fun isLocked(now: Long): Boolean = remainingMillis(now) > 0L

    fun recordFailure(now: Long) {
        if (isLocked(now)) return
        failures++
        if (failures >= maxFailures) {
            lockedUntil = now + lockMillis
            failures = 0
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntil = 0L
    }
}