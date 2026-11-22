package md.utm.digitalmirror.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * „Ce ai de făcut?”: un singur câmp pentru tot. Textul scris liber se împarte în bucăți, iar pentru
 * fiecare bucată hotărâm ce este:
 *  - un EVENIMENT, pentru care te pregătești („examen PAD joi”, „susținere vineri”);
 *  - o sarcină a SĂPTĂMÂNII, împărțită pe zile („licență 6h săptămâna asta”, „proiect 4 ore până vineri”);
 *  - o activitate într-o ZI anume („sală mâine la 18”, „sună la bancă”), azi dacă nu spui altfel.
 * Apoi fiecare bucată e citită de cititorul potrivit, care exista deja: PlanParser, WeekTaskParser.
 *
 * Funcționează pe reguli, local. Utilizatorul vede ce am înțeles și poate schimba tipul înainte să confirme.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object QuickCapture {

    enum class Kind { DAY, WEEK, EVENT }

    /** Ce am înțeles dintr-o bucată de text. */
    data class Item(
        val kind: Kind,
        val title: String,
        val raw: String,                          // bucata de text, ca s-o putem reciti cu alt tip
        val date: LocalDate?,                     // ziua (DAY) sau ziua evenimentului (EVENT); null = nespusă
        val minutes: Int? = null,                 // durata spusă (DAY) sau totalul (WEEK)
        val startMinute: Int? = null,             // ora spusă (DAY)
        val dayPart: PlanParser.DayPart? = null,
        val sessionMinutes: Int? = null,          // WEEK
        val deadline: LocalDate? = null,          // WEEK
        val templateKey: String? = null           // EVENT: ce fel de pregătire
    )

    // ---- cuvinte care spun tipul (pe text fără diacritice, litere mici) ----

    private val EVENT_WORDS = listOf(
        Regex("""\b(?:examen\w*|colocviu\w*|testul|test\b|parțial|partial\w*|sesiune de examen)""") to "exam",
        Regex("""\b(?:sustiner\w*|prezentar\w*|aparar\w*|lab\b|laborator\w* de predat)""") to "lab",
        Regex("""\binterviu\w*""") to "interview",
        Regex("""\b(?:predare\w*|predau|deadline)""") to "delivery"
    )
    private val WEEK_WORDS = Regex("""\bsaptaman\w*|\bpana (?:luni|marti|miercuri|joi|vineri|sambata|duminica)\b|\bin total\b|\bsesiuni\b""")
    private val DAY_WORDS = Regex("""\b(?:azi|astazi|maine|poimaine|diseara|deseara|luni|marti|miercuri|joi|vineri|sambata|duminica)\b""")
    private val CLOCK = Regex("""\bla\s+(?:ora\s+)?\d{1,2}\b|\d{1,2}[:.]\d{2}""")

    // virgula dintre două cifre e zecimală („1,5 ore”); „și” rămâne în bucată (PlanParser îl desparte când are sens)
    private val SEPARATOR = Regex("""(?<!\d),|,(?!\d)|[;\n]+|\bapoi\b|\bpe urma\b""")

    /** Din cât timp în sus o sarcină fără oră și fără zi pare de „săptămână”, nu de o singură zi. */
    const val WEEK_MINUTES = 3 * 60

    fun parse(text: String, today: LocalDate): List<Item> {
        val folded = PlanParser.fold(text)
        val pieces = mutableListOf<String>()
        var start = 0
        for (m in SEPARATOR.findAll(folded)) {
            pieces += text.substring(start, m.range.first)
            start = m.range.last + 1
        }
        pieces += text.substring(start)
        return pieces.map { it.trim() }.filter { it.length >= 2 }.flatMap { read(it, today) }
    }

    /** O bucată, cu tipul hotărât de noi. */
    fun read(piece: String, today: LocalDate): List<Item> = read(piece, today, guessKind(piece))

    /** O bucată, cu tipul ales de utilizator (când schimbă eticheta). */
    fun read(piece: String, today: LocalDate, kind: Kind): List<Item> = when (kind) {
        Kind.EVENT -> listOfNotNull(readEvent(piece, today))
        Kind.WEEK -> listOfNotNull(readWeek(piece, today))
        Kind.DAY -> readDay(piece, today)
    }

    /** Ce pare bucata: întâi evenimentele (au cuvinte clare), apoi săptămâna, altfel o zi. */
    fun guessKind(piece: String): Kind {
        val f = PlanParser.fold(piece)
        if (EVENT_WORDS.any { (regex, _) -> regex.containsMatchIn(f) }) return Kind.EVENT
        if (WEEK_WORDS.containsMatchIn(f)) return Kind.WEEK
        // „licență 6h”, fără oră și fără zi: e prea mult pentru o singură zi, se împarte pe săptămână
        val duration = PlanParser.findDuration(f)?.first
        if (duration != null && duration >= WEEK_MINUTES && !CLOCK.containsMatchIn(f) && !DAY_WORDS.containsMatchIn(f)) {
            return Kind.WEEK
        }
        return Kind.DAY
    }

    // ---- fiecare tip ----

    private fun readDay(piece: String, today: LocalDate): List<Item> {
        val parsed = PlanParser.parse(piece, today)
        val date = parsed.date ?: today
        return parsed.drafts.map {
            Item(Kind.DAY, it.title, piece, date, it.durationMinutes, it.startMinute, it.dayPart)
        }
    }

    private fun readWeek(piece: String, today: LocalDate): Item? {
        // „săptămâna asta/viitoare” nu face parte din titlu
        val cleaned = piece.replace(Regex("""(?i)\b(?:pe|in|în)?\s*s[ăa]pt[ăa]m[âa]n\w*(?:\s+(?:asta|aceasta|viitoare))?"""), " ")
        val parsed = WeekTaskParser.parse(cleaned)
        val title = parsed.title ?: return null
        val deadline = parsed.deadlineDay?.let { day ->
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            monday.plusDays(day.toLong()).takeIf { !it.isBefore(today) }
        }
        return Item(
            Kind.WEEK, title, piece, null,
            minutes = parsed.totalMinutes, sessionMinutes = parsed.sessionMinutes, deadline = deadline
        )
    }

    private fun readEvent(piece: String, today: LocalDate): Item? {
        val f = PlanParser.fold(piece)
        val template = EVENT_WORDS.firstOrNull { (regex, _) -> regex.containsMatchIn(f) }?.second ?: "other"
        val date = PlanParser.parse(piece, today).date?.takeIf { it.isAfter(today) }
        // titlul: fără ziua și fără cuvinte de legătură („examen PAD joi” → „Examen PAD”)
        val withoutDay = piece.replace(
            Regex("""(?i)\b(?:pe|de|la|in|în|pana|până)?\s*(?:azi|astăzi|astazi|mâine|maine|poimâine|poimaine|luni|marți|marti|miercuri|joi|vineri|sâmbătă|sambata|duminică|duminica)\b"""),
            " "
        )
        val title = PlanParser.cleanTitle(withoutDay) ?: return null
        return Item(Kind.EVENT, title, piece, date, templateKey = template)
    }

    /** „joi”, „mâine”, „azi”, pentru etichete. */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Azi"
        today.plusDays(1) -> "Mâine"
        else -> when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "Luni"
            DayOfWeek.TUESDAY -> "Marți"
            DayOfWeek.WEDNESDAY -> "Miercuri"
            DayOfWeek.THURSDAY -> "Joi"
            DayOfWeek.FRIDAY -> "Vineri"
            DayOfWeek.SATURDAY -> "Sâmbătă"
            DayOfWeek.SUNDAY -> "Duminică"
        }
    }
}
