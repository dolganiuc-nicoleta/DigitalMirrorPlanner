package md.utm.digitalmirror.domain

/**
 * Ordinea în lista de categorii: dublurile („Laborator” și „Laboratoare”, „Proiect” și „proiecte”)
 * și iconița potrivită unui nume. Nu depinde de Android, deci se poate testa simplu.
 */
object CategoryTidy {

    data class Named(val id: Long, val name: String)

    /** Două categorii care par același lucru; [keep] e cea cu mai multe activități (în ea se unesc). */
    data class Pair(val keep: Named, val drop: Named)

    // terminațiile de plural și articolul, ca „proiecte” și „proiect” să aibă aceeași rădăcină
    private val ENDINGS = listOf("urile", "uri", "ele", "le", "ile", "ii", "e", "i", "a", "ul")

    fun stem(name: String): String {
        val folded = PlanParser.fold(name).trim().replace(Regex("""\s+"""), " ")
        val ending = ENDINGS.firstOrNull { folded.endsWith(it) && folded.length - it.length >= 4 }
        return if (ending != null) folded.dropLast(ending.length) else folded
    }

    /**
     * Perechile care par dubluri: aceeași rădăcină, sau un început comun lung
     * (cel puțin 5 litere și cel puțin 80% din cuvântul mai scurt: „laborator” / „laboratoare”).
     * @param usage câte activități are fiecare categorie, după id
     */
    fun duplicates(categories: List<Named>, usage: Map<Long, Int>): List<Pair> {
        val result = mutableListOf<Pair>()
        val used = mutableSetOf<Long>()
        for (i in categories.indices) for (j in i + 1 until categories.size) {
            val a = categories[i]
            val b = categories[j]
            if (a.id in used || b.id in used) continue
            val sa = stem(a.name)
            val sb = stem(b.name)
            val common = sa.commonPrefixWith(sb).length
            val similar = sa == sb || (common >= 5 && common >= 0.8 * minOf(sa.length, sb.length))
            if (!similar) continue
            val aFirst = (usage[a.id] ?: 0) >= (usage[b.id] ?: 0)
            val keep = if (aFirst) a else b
            val drop = if (aFirst) b else a
            result += Pair(keep, drop)
            used += drop.id
        }
        return result
    }

    // iconița după cuvinte din nume (fără diacritice); prima potrivire câștigă
    private val ICON_WORDS = listOf(
        "school" to listOf("studi", "invat", "curs", "facult", "examen", "licent", "teza", "seminar", "lectii"),
        "science" to listOf("laborator", "lab"),
        "book" to listOf("lectur", "cit", "carte", "carti"),
        "code" to listOf("program", "cod", "proiect", "dezvolt", "aplicat"),
        "work" to listOf("munc", "lucru", "servici", "job", "birou", "practic", "intern"),
        "fitness" to listOf("sport", "sala", "fitness", "alerg", "antren", "gym", "inot"),
        "home" to listOf("cas", "curaten", "gospod", "menaj", "cumpar"),
        "spa" to listOf("relax", "odihn", "meditat", "somn", "pauza"),
        "groups" to listOf("prieten", "famil", "social", "intalnir"),
        "restaurant" to listOf("mancare", "gatit", "masa", "bucatar"),
        "palette" to listOf("hobby", "desen", "pictur", "creat", "art"),
        "music" to listOf("muzic", "chitar", "pian", "cant"),
        "heart" to listOf("sanat", "doctor", "medic", "terap"),
        "travel" to listOf("drum", "calator", "transport")
    )

    /** Iconița ghicită din nume; null dacă nu se potrivește nimic (atunci se folosește un punct colorat). */
    fun suggestIcon(name: String): String? {
        val folded = PlanParser.fold(name)
        return ICON_WORDS.firstOrNull { (_, words) -> words.any { folded.contains(it) } }?.first
    }
}
