package md.utm.digitalmirror.domain

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Transformă un plan scris liber în română („mâine laborator PAD 2h, sport seara, cumpărături 30 min”)
 * în activități separate, cu durată, oră și moment al zilei, când au fost spuse.
 *
 * Funcționează pe reguli, local, fără server. Recunoaște:
 *  - ziua: azi, mâine, poimâine, diseară, luni … duminică;
 *  - durata: 2h, 1h30, 2 ore, o oră, jumătate de oră, 45 min, 1,5 ore;
 *  - ora: la 14, la 14:30, la ora 9, de la 10 la 12, 10:00-12:00;
 *  - momentul zilei: dimineața, la prânz, după-amiaza, seara.
 * Activitățile se despart prin virgulă, punct și virgulă, rând nou, „apoi” sau „și”
 * (acesta din urmă doar după o activitate care are deja durată, oră sau moment al zilei).
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object PlanParser {

    enum class DayPart(val from: Int, val to: Int, val label: String) {
        MORNING(7 * 60, 12 * 60, "dimineața"),
        NOON(12 * 60, 14 * 60, "la prânz"),
        AFTERNOON(13 * 60, 18 * 60, "după-amiaza"),
        EVENING(18 * 60, 23 * 60, "seara")
    }

    data class Draft(
        val title: String,
        val durationMinutes: Int?,     // spusă explicit; null = o estimează aplicația
        val startMinute: Int?,         // oră explicită
        val dayPart: DayPart?
    )

    data class Result(val date: LocalDate?, val drafts: List<Draft>)

    // ---- expresii (pe textul fără diacritice, cu litere mici) ----

    private val DAY_AFTER = Regex("""\bpoimaine\b""")
    private val TOMORROW = Regex("""\bmaine\b""")
    private val TODAY = Regex("""\b(?:azi|astazi|diseara|deseara)\b""")
    private val WEEKDAY = Regex("""\b(luni|marti|miercuri|joi|vineri|sambata|duminica)\b""")

    // virgula dintre două cifre e zecimală („1,5 ore”), nu separator
    private val SEPARATOR = Regex("""(?<!\d),|,(?!\d)|[;\n]+|\bapoi\b|\bpe urma\b|\bdupa aceea\b|\bsi apoi\b""")
    private val AND = Regex("""\bsi\b""")

    private val PART_MORNING = Regex("""\b(?:dimineata|dimineti)\b""")
    private val PART_NOON = Regex("""\bpranz\b""")
    private val PART_AFTERNOON = Regex("""\bdupa[ -]?amiaza\b|\bdupamiaza\b""")
    private val PART_EVENING = Regex("""\b(?:seara|diseara|deseara|noaptea)\b""")

    private val RANGE_WORDS = Regex(
        """(?:\bde la|\bintre)\s+(?:ora\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:pana la|pana|la|si|-)\s*(?:ora\s+)?(\d{1,2})(?:[:.](\d{2}))?"""
    )
    private val RANGE_CLOCK = Regex("""(\d{1,2})[:.](\d{2})\s*-\s*(\d{1,2})[:.](\d{2})""")
    private val SINGLE_TIME = Regex(
        """(?:\b(?:cam\s+)?la\s+(?:ora\s+)?|\bora\s+)(\d{1,2})(?:[:.](\d{2}))?(?![\d:.]|\s*(?:ore\b|ora\b|min|h\b|minute))"""
    )

    private const val LEAD = """(?:(?:timp\s+de|pentru|vreo|cam|aproximativ|circa|de)\s+)?"""
    private val DUR_HOURS_MIN = Regex("""$LEAD(\d+)\s*h\s*(\d{1,2})(?:\s*m(?:in\w*)?)?\b""")
    private val DUR_WORD_HOURS = Regex(
        """$LEAD\b(o|un|doua|trei|patru|cinci|sase)\s+(?:ora|ore)\b(\s+si\s+jumatate)?"""
    )
    private val DUR_HOURS = Regex("""$LEAD(\d+(?:[.,]\d+)?)\s*(?:h\b|ore\b|ora\b)""")
    private val DUR_MINUTES = Regex("""$LEAD(\d+)\s*(?:de\s+)?(?:min\w*|m\b)""")
    private val DUR_HALF = Regex("""$LEAD(?:o\s+)?\bjumatate\s+de\s+ora\b""")
    private val DUR_QUARTER = Regex("""$LEAD(?:un\s+)?\bsfert\s+de\s+ora\b""")

    private val WORD_NUMBERS = mapOf("o" to 1, "un" to 1, "doua" to 2, "trei" to 3, "patru" to 4, "cinci" to 5, "sase" to 6)

    private val LEADING_FILLERS = listOf(
        "vreau sa", "trebuie sa", "as vrea sa", "sa", "si", "apoi", "plus", "iar", "pe urma", "dupa aceea"
    )
    private val TRAILING_FILLERS = setOf("de", "la", "pe", "in", "cu", "pentru", "timp", "cam", "vreo", "circa")

    /** Același text, litere mici și fără diacritice, cu exact aceeași lungime (poziție cu poziție). */
    internal fun fold(text: String): String = buildString(text.length) {
        for (ch in text) {
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
            append(base.lowercaseChar())
        }
    }

    fun parse(text: String, today: LocalDate): Result {
        val original = StringBuilder(text)
        val folded = StringBuilder(fold(text))

        // 1. ziua, o singură dată pentru tot textul
        val date = extractDate(folded, original, today)

        // 2. despărțirea în activități
        val segments = split(original.toString(), folded.toString())

        // 3. fiecare activitate
        val drafts = segments.mapNotNull { (orig, fold) -> parseSegment(orig, fold) }
        return Result(date, drafts)
    }

    // ---- ziua ----

    private fun extractDate(folded: StringBuilder, original: StringBuilder, today: LocalDate): LocalDate? {
        val text = folded.toString()
        fun blank(range: IntRange) {
            for (i in range) { folded.setCharAt(i, ' '); original.setCharAt(i, ' ') }
        }

        DAY_AFTER.find(text)?.let { blank(it.range); return today.plusDays(2) }
        TOMORROW.find(text)?.let { blank(it.range); return today.plusDays(1) }
        WEEKDAY.find(text)?.let { match ->
            blank(match.range)
            val target = when (match.value) {
                "luni" -> DayOfWeek.MONDAY
                "marti" -> DayOfWeek.TUESDAY
                "miercuri" -> DayOfWeek.WEDNESDAY
                "joi" -> DayOfWeek.THURSDAY
                "vineri" -> DayOfWeek.FRIDAY
                "sambata" -> DayOfWeek.SATURDAY
                else -> DayOfWeek.SUNDAY
            }
            var days = (target.value - today.dayOfWeek.value + 7) % 7
            if (days == 0) days = 7
            return today.plusDays(days.toLong())
        }
        TODAY.find(text)?.let { match ->
            // „azi” dispare din text; „diseara” rămâne, fiindcă spune și momentul zilei
            if (match.value == "azi" || match.value == "astazi") blank(match.range)
            return today
        }
        return null
    }

    // ---- despărțirea ----

    private fun split(original: String, folded: String): List<Pair<String, String>> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        for (m in SEPARATOR.findAll(folded)) {
            ranges += start until m.range.first
            start = m.range.last + 1
        }
        ranges += start until folded.length

        return ranges.flatMap { splitAnd(it, original, folded) }
            .map { original.substring(it.first, it.last + 1) to folded.substring(it.first, it.last + 1) }
            .filter { it.first.isNotBlank() }
    }

    // „sport 1h și citit seara”: despărțim la „și” doar dacă ce e înainte e o activitate completă
    private fun splitAnd(range: IntRange, original: String, folded: String): List<IntRange> {
        if (range.isEmpty()) return emptyList()
        val piece = folded.substring(range.first, range.last + 1)
        for (m in AND.findAll(piece)) {
            val left = piece.substring(0, m.range.first)
            val right = piece.substring(m.range.last + 1)
            // „o oră și jumătate” e o singură durată, nu două activități
            if (hasCue(left) && right.isNotBlank() && !right.trimStart().startsWith("jumatate")) {
                val leftRange = range.first until (range.first + m.range.first)
                val rightRange = (range.first + m.range.last + 1)..range.last
                return listOf(leftRange) + splitAnd(rightRange, original, folded)
            }
        }
        return listOf(range)
    }

    private fun hasCue(foldedPiece: String): Boolean =
        RANGE_WORDS.containsMatchIn(foldedPiece) || RANGE_CLOCK.containsMatchIn(foldedPiece) ||
                SINGLE_TIME.containsMatchIn(foldedPiece) || findDuration(foldedPiece) != null ||
                findDayPart(foldedPiece) != null

    // ---- o activitate ----

    private fun parseSegment(original: String, folded: String): Draft? {
        val orig = StringBuilder(original)
        val fold = StringBuilder(folded)
        fun blank(range: IntRange) {
            for (i in range) { fold.setCharAt(i, ' '); orig.setCharAt(i, ' ') }
        }

        // momentul zilei
        val part = findDayPart(fold.toString())
        part?.let { blank(it.second) }
        val dayPart = part?.first

        // ora sau intervalul orar
        var start: Int? = null
        var duration: Int? = null

        fun clock(hour: String, minute: String?): Int? {
            val h = hour.toInt()
            val m = minute?.toIntOrNull() ?: 0
            if (h > 23 || m > 59) return null
            return resolveHour(h, dayPart) * 60 + m
        }

        for (regex in listOf(RANGE_WORDS, RANGE_CLOCK)) {
            val m = regex.find(fold.toString()) ?: continue
            val g = m.groupValues
            val from = clock(g[1], g[2].ifEmpty { null })
            val to = clock(g[3], g[4].ifEmpty { null })
            if (from != null && to != null) {
                start = from
                if (to > from) duration = to - from
                blank(m.range)
                break
            }
        }
        if (start == null) {
            for (m in SINGLE_TIME.findAll(fold.toString())) {
                val at = clock(m.groupValues[1], m.groupValues[2].ifEmpty { null }) ?: continue
                start = at
                blank(m.range)
                break
            }
        }

        // durata
        if (duration == null) {
            findDuration(fold.toString())?.let { (minutes, range) ->
                duration = minutes
                blank(range)
            }
        }

        val title = cleanTitle(orig.toString()) ?: return null
        return Draft(title, duration, start, dayPart)
    }

    // „la 6 seara” = 18:00; „la 5” fără alte indicii = 17:00; de la 7 la 11 dimineața rămân ca atare
    private fun resolveHour(hour: Int, part: DayPart?): Int = when {
        hour in 1..11 && (part == DayPart.AFTERNOON || part == DayPart.EVENING || part == DayPart.NOON) -> hour + 12
        hour in 1..6 && part == null -> hour + 12
        else -> hour
    }

    private fun findDayPart(folded: String): Pair<DayPart, IntRange>? {
        val candidates = listOf(
            DayPart.AFTERNOON to PART_AFTERNOON,    // înaintea „seara”/„pranz”, ca să nu fie confundate
            DayPart.MORNING to PART_MORNING,
            DayPart.NOON to PART_NOON,
            DayPart.EVENING to PART_EVENING
        )
        for ((part, regex) in candidates) {
            regex.find(folded)?.let { return part to it.range }
        }
        return null
    }

    internal fun findDuration(folded: String): Pair<Int, IntRange>? {
        DUR_HOURS_MIN.find(folded)?.let {
            val minutes = it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
            if (minutes > 0) return minutes to it.range
        }
        DUR_WORD_HOURS.find(folded)?.let {
            val hours = WORD_NUMBERS[it.groupValues[1]] ?: return@let
            return (hours * 60 + if (it.groupValues[2].isNotEmpty()) 30 else 0) to it.range
        }
        DUR_HOURS.find(folded)?.let {
            val hours = it.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val minutes = Math.round(hours * 60).toInt()
            if (minutes > 0) return minutes to it.range
        }
        DUR_MINUTES.find(folded)?.let {
            val minutes = it.groupValues[1].toInt()
            if (minutes > 0) return minutes to it.range
        }
        DUR_HALF.find(folded)?.let { return 30 to it.range }
        DUR_QUARTER.find(folded)?.let { return 15 to it.range }
        return null
    }

    // ---- titlul ----

    internal fun cleanTitle(raw: String): String? {
        var words = raw.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', ':', '.', ',', ';', '!', '?', ' ')
            .split(' ').filter { it.isNotEmpty() }.toMutableList()

        // cuvinte de legătură la început: „vreau să”, „și”, „apoi”…
        var changed = true
        while (changed && words.isNotEmpty()) {
            changed = false
            for (filler in LEADING_FILLERS) {
                val parts = filler.split(' ')
                if (words.size > parts.size && parts.indices.all { fold(words[it]) == parts[it] }) {
                    words = words.drop(parts.size).toMutableList()
                    changed = true
                    break
                }
            }
        }
        // prepoziții rămase în coadă după scoaterea duratei: „laborator PAD de”
        while (words.isNotEmpty() && fold(words.last().trim('.', ',', ';', ':')) in TRAILING_FILLERS) {
            words.removeAt(words.lastIndex)
        }
        val title = words.joinToString(" ").trim('-', '–', '—', ':', '.', ',', ';', '!', '?', ' ')
        if (title.length < 2) return null
        return title.replaceFirstChar { it.uppercase() }
    }
}
