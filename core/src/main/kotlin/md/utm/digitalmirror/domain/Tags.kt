package md.utm.digitalmirror.domain

/**
 * Etichetele unei activități („#curs #urgent”). Se salvează ca text, separate prin virgulă și fără #,
 * în litere mici, ca „Curs” și „curs” să fie aceeași etichetă. Nu depinde de Android.
 */
object Tags {

    const val MAX_TAGS = 5
    const val MAX_LENGTH = 20

    /** Ce a scris utilizatorul în câmp („#Curs, urgent”) → lista curată de etichete. */
    fun parse(input: String): List<String> =
        input.split(',', ';', ' ', '\n', '\t')
            .map { it.trim().trimStart('#').lowercase() }
            .filter { it.isNotEmpty() }
            .map { it.take(MAX_LENGTH) }
            .distinct()
            .take(MAX_TAGS)

    /** Forma salvată în baza de date. */
    fun encode(tags: List<String>): String = tags.joinToString(",")

    fun decode(stored: String): List<String> = stored.split(',').filter { it.isNotBlank() }

    /** Forma arătată în câmpul de editare. */
    fun toInput(stored: String): String = decode(stored).joinToString(" ") { "#$it" }
}