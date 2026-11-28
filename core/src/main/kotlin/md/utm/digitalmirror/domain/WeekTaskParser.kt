package md.utm.digitalmirror.domain

/**
 * Citește o sarcină a săptămânii scrisă liber, în română:
 * „Lucru la licență 6 ore, sesiuni de 90 min, până vineri”.
 *  - totalul: 6h, 6 ore, 1h30, 90 min, 1,5 ore;
 *  - sesiunea (opțional): „sesiuni de 90 min”, „în sesiuni de 1h”;
 *  - termenul (opțional): „până vineri”, „termen joi”, sau doar „vineri”.
 * Ce rămâne după ce scoatem acestea este titlul.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object WeekTaskParser {

    data class Result(
        val title: String?,            // null = nu s-a înțeles titlul
        val totalMinutes: Int?,        // null = nu s-a spus cât durează
        val sessionMinutes: Int?,      // null = nespus; ecranul folosește o valoare implicită
        val deadlineDay: Int?          // 0 = luni … 6 = duminică; null = fără termen
    )

    private val DAYS = listOf("luni", "marti", "miercuri", "joi", "vineri", "sambata", "duminica")

    private val SESSION = Regex("""(?:\bin\s+)?\bsesi\w*""")
    private val DEADLINE = Regex(
        """(?:\b(?:pana|termen|deadline)(?:\s+(?:la|in|pe))?\s+)?\b(luni|marti|miercuri|joi|vineri|sambata|duminica)\b"""
    )
    private val TOTAL_WORDS = Regex("""\b(?:in\s+)?total\b|\bcate\b""")

    fun parse(text: String): Result {
        val original = StringBuilder(text)
        val folded = StringBuilder(PlanParser.fold(text))
        fun blank(range: IntRange) {
            for (i in range) if (i < folded.length) { folded.setCharAt(i, ' '); original.setCharAt(i, ' ') }
        }

        // 1. sesiunea: „sesiuni de 90 min” (durata care urmează imediat după cuvânt)
        var session: Int? = null
        SESSION.find(folded.toString())?.let { match ->
            val after = folded.substring(match.range.last + 1)
            val found = PlanParser.findDuration(after)
            if (found != null && found.second.first <= 4) {
                session = found.first
                val offset = match.range.last + 1
                blank(match.range.first..(offset + found.second.last))
            } else {
                blank(match.range)            // cuvântul „sesiuni” fără durată dispare din titlu
            }
        }

        // 2. termenul
        var deadline: Int? = null
        DEADLINE.find(folded.toString())?.let { match ->
            deadline = DAYS.indexOf(match.groupValues[1]).takeIf { it >= 0 }
            blank(match.range)
        }

        // 3. cuvinte care nu fac parte din titlu: „în total”
        TOTAL_WORDS.findAll(folded.toString()).toList().forEach { blank(it.range) }

        // 4. totalul: prima durată rămasă
        var total: Int? = null
        PlanParser.findDuration(folded.toString())?.let { (minutes, range) ->
            total = minutes
            blank(range)
        }

        // 5. titlul: ce a rămas, fără semne de punctuație rătăcite
        val rest = original.toString().replace(Regex("""\s*[,;]\s*(?=[,;]|$)"""), " ")
        val title = PlanParser.cleanTitle(rest)
        return Result(title, total, session, deadline)
    }
}
