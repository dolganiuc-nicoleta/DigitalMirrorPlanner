package md.utm.digitalmirror.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Scenarii scrise liber pentru o săptămână: „vreau liber joi după 14”, „liber miercuri”,
 * „ocupat marți 10-12”, „nu pot vineri dimineața”. Fiecare propoziție devine un interval
 * ocupat într-o zi a săptămânii, pe care planificatorul îl ocolește.
 *
 * Ziua se caută în săptămâna planificată (nu „următoarea joi”). Propozițiile fără cuvânt
 * de tipul „liber / ocupat / nu pot” sau fără zi sunt returnate separat, ca aplicația să le arate.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object WeekScenario {

    data class Block(val date: LocalDate, val fromMinute: Int, val toMinute: Int) {
        val wholeDay: Boolean get() = fromMinute <= 0 && toMinute >= 24 * 60
    }

    data class Result(val blocks: List<Block>, val ignored: List<String>)

    private val CUE = Regex("""\b(?:liber|libera|libere|ocupat|ocupata|indisponibil\w*|nu pot|nu sunt disponibil\w*|nu sunt acasa|plec|lipsesc|fara)\b""")
    private val CLAUSE_SPLIT = Regex("""(?<!\d),|,(?!\d)|[;\n]+|\bsi\b(?=\s+(?:liber|ocupat|nu\b))""")

    private val DAYS = mapOf(
        "luni" to DayOfWeek.MONDAY, "marti" to DayOfWeek.TUESDAY, "miercuri" to DayOfWeek.WEDNESDAY,
        "joi" to DayOfWeek.THURSDAY, "vineri" to DayOfWeek.FRIDAY, "sambata" to DayOfWeek.SATURDAY,
        "duminica" to DayOfWeek.SUNDAY
    )
    private val WEEKDAY = Regex("""\b(luni|marti|miercuri|joi|vineri|sambata|duminica)\b""")
    private val DAY_AFTER = Regex("""\bpoimaine\b""")
    private val TOMORROW = Regex("""\bmaine\b""")
    private val TODAY = Regex("""\b(?:azi|astazi|diseara)\b""")

    private val RANGE = Regex(
        """(?:\bde la\s+|\bintre\s+)?(?:ora\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(?:pana la|pana|si|-)\s*(?:ora\s+)?(\d{1,2})(?:[:.](\d{2}))?"""
    )
    private val AFTER = Regex("""\b(?:dupa|de la)\s+(?:ora\s+)?(\d{1,2})(?:[:.](\d{2}))?(?![\d]|\s*-)""")
    private val BEFORE = Regex("""\b(?:inainte de|pana la|pana in)\s+(?:ora\s+)?(\d{1,2})(?:[:.](\d{2}))?""")

    private val PARTS = listOf(
        Regex("""\b(?:dimineata|dimineti)\b""") to PlanParser.DayPart.MORNING,
        Regex("""\bpranz\b""") to PlanParser.DayPart.NOON,
        Regex("""\bdupa[ -]?amiaza\b|\bdupamiaza\b""") to PlanParser.DayPart.AFTERNOON,
        Regex("""\b(?:seara|diseara|noaptea)\b""") to PlanParser.DayPart.EVENING
    )

    /** @param weekStart luni; @param today folosit pentru „azi”, „mâine”, „poimâine” */
    fun parse(text: String, weekStart: LocalDate, today: LocalDate = LocalDate.now()): Result {
        val blocks = mutableListOf<Block>()
        val ignored = mutableListOf<String>()
        val originalText = text
        val folded = PlanParser.fold(text)

        // despărțim pe textul fără diacritice, dar păstrăm propoziția originală pentru afișare
        var cursor = 0
        val pieces = mutableListOf<Pair<String, String>>()
        for (m in CLAUSE_SPLIT.findAll(folded)) {
            pieces += originalText.substring(cursor, m.range.first) to folded.substring(cursor, m.range.first)
            cursor = m.range.last + 1
        }
        pieces += originalText.substring(cursor) to folded.substring(cursor)

        for ((original, clause) in pieces) {
            if (clause.isBlank()) continue
            val block = parseClause(clause, weekStart, today)
            if (block == null) ignored += original.trim() else blocks += block
        }
        return Result(blocks, ignored)
    }

    private fun parseClause(clauseText: String, weekStart: LocalDate, today: LocalDate): Block? {
        var clause = clauseText
        if (!CUE.containsMatchIn(clause)) return null

        // 1. ziua
        val date = findDate(clause, weekStart, today) ?: return null
        clause = clause.replace(WEEKDAY, " ").replace(DAY_AFTER, " ").replace(TOMORROW, " ")
            .replace(Regex("""\b(?:azi|astazi)\b"""), " ")

        // 2. intervalul
        for ((regex, part) in PARTS) {
            if (regex.containsMatchIn(clause)) return Block(date, part.from, part.to)
        }
        RANGE.find(clause)?.let { m ->
            val a = hour(m.groupValues[1], m.groupValues[2])
            val b = hour(m.groupValues[3], m.groupValues[4])
            if (a != null && b != null && b > a) return Block(date, a, b)
        }
        BEFORE.find(clause)?.let { m ->
            hour(m.groupValues[1], m.groupValues[2])?.let { return Block(date, 0, it) }
        }
        AFTER.find(clause)?.let { m ->
            hour(m.groupValues[1], m.groupValues[2])?.let { return Block(date, it, 24 * 60) }
        }
        return Block(date, 0, 24 * 60)          // fără oră: toată ziua
    }

    private fun findDate(clause: String, weekStart: LocalDate, today: LocalDate): LocalDate? {
        DAY_AFTER.find(clause)?.let { return today.plusDays(2) }
        TOMORROW.find(clause)?.let { return today.plusDays(1) }
        WEEKDAY.find(clause)?.let { return weekStart.plusDays((DAYS.getValue(it.value).value - 1).toLong()) }
        TODAY.find(clause)?.let { return today }
        return null
    }

    private fun hour(h: String, m: String): Int? {
        val hour = h.toIntOrNull() ?: return null
        val minute = if (m.isEmpty()) 0 else m.toIntOrNull() ?: return null
        if (hour !in 0..24 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}