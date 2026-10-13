package md.utm.digitalmirror.domain

/**
 * Scrierea unui fișier CSV. Separatorul e „;”, ca fișierul să se deschidă corect în Excel cu setările
 * regionale românești; în Python se citește cu sep=";".
 * Câmpurile cu separator, ghilimele sau rând nou se pun între ghilimele. Textele care ar putea fi luate
 * drept formulă („=…”, „+…”, „@…”, „-…”) primesc un apostrof în față, ca Excel să nu le execute.
 */
object Csv {

    const val SEPARATOR = ';'

    fun field(value: Any?): String {
        if (value == null) return ""
        var text = value.toString()
        if (value is String && text.isNotEmpty() && text[0] in "=+-@") text = "'$text"
        val needsQuotes = text.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    fun row(values: List<Any?>): String = values.joinToString(SEPARATOR.toString()) { field(it) }
}