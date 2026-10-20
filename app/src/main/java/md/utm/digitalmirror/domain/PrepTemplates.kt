package md.utm.digitalmirror.domain

/**
 * Șabloane pentru pregătirea unui eveniment (susținere, examen, interviu…): ce pași ai de făcut,
 * în ce ordine și ce parte din timp ia fiecare. Pașii pot fi schimbați de utilizator.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object PrepTemplates {

    private const val ROUND = 15           // minutele se rotunjesc la sferturi de oră
    private const val MIN_STEP = 15

    data class Template(
        val key: String,
        val label: String,
        val hint: String,
        val defaultMinutes: Int,
        val steps: List<Pair<String, Int>>     // titlu și procent din timpul total; în ordinea în care se fac
    )

    data class StepDraft(val title: String, val minutes: Int)

    val ALL: List<Template> = listOf(
        Template(
            "lab", "Lab sau prezentare", "Susținerea unei lucrări, a unui proiect sau a unei prezentări.", 6 * 60,
            listOf(
                "Înțeleg materialul și codul" to 30,
                "Termin ce am de predat (raport, slide-uri)" to 30,
                "Repet prezentarea cu voce tare" to 20,
                "Mă gândesc la întrebările posibile" to 10,
                "Recapitulare finală" to 10
            )
        ),
        Template(
            "exam", "Examen", "Învățat pe o perioadă, cu teste și recapitulare la sfârșit.", 12 * 60,
            listOf(
                "Învăț materia" to 45,
                "Rezolv exerciții și teste vechi" to 30,
                "Recapitulez" to 15,
                "Recapitulare finală" to 10
            )
        ),
        Template(
            "interview", "Interviu", "Interviu de angajare sau de practică.", 6 * 60,
            listOf(
                "Studiez compania și postul" to 15,
                "Repet teoria" to 35,
                "Exersez exerciții practice" to 25,
                "Îmi pregătesc povestea și întrebările pentru ei" to 10,
                "Simulare de interviu" to 10,
                "Recapitulare finală" to 5
            )
        ),
        Template(
            "delivery", "Predare proiect", "Un proiect sau o lucrare care trebuie predată la un termen.", 10 * 60,
            listOf(
                "Fac partea principală" to 50,
                "Finalizez și testez" to 25,
                "Scriu documentația" to 15,
                "Verificare finală" to 10
            )
        ),
        Template(
            "other", "Altceva", "Orice eveniment pentru care vrei să te pregătești.", 4 * 60,
            listOf(
                "Pregătirea principală" to 60,
                "Repetare și verificare" to 30,
                "Recapitulare finală" to 10
            )
        )
    )

    fun byKey(key: String): Template = ALL.firstOrNull { it.key == key } ?: ALL.last()

    /**
     * Împarte [totalMinutes] pe pașii șablonului, în sferturi de oră, fără să piardă niciun minut:
     * diferența de la rotunjire se pune pe pasul cel mai mare.
     */
    fun build(template: Template, totalMinutes: Int): List<StepDraft> {
        val total = (totalMinutes / ROUND * ROUND).coerceAtLeast(template.steps.size * MIN_STEP)
        val raw = template.steps.map { (_, percent) ->
            val minutes = Math.round(total * percent / 100.0 / ROUND).toInt() * ROUND
            minutes.coerceAtLeast(MIN_STEP)
        }.toMutableList()
        val drift = total - raw.sum()
        if (drift != 0) {
            val biggest = raw.indices.maxByOrNull { raw[it] } ?: return emptyList()
            raw[biggest] = (raw[biggest] + drift).coerceAtLeast(MIN_STEP)
        }
        return template.steps.mapIndexed { i, (title, _) -> StepDraft(title, raw[i]) }
    }
}
