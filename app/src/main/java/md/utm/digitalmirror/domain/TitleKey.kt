package md.utm.digitalmirror.domain

import java.text.Normalizer

/**
 * Transformă titlul unei activități într-o cheie, ca „Laborator broker”,
 * „laborator  broker” și „Laborator Broker!” să fie considerate aceeași activitate.
 */
object TitleKey {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NOT_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")

    fun of(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")              // ă → a, ț → t, ș → s
            .lowercase()
            .replace(NOT_LETTER_OR_DIGIT, " ")    // semnele de punctuație devin spații
            .trim()
            .replace(Regex("\\s+"), " ")          // mai multe spații devin unul singur
}