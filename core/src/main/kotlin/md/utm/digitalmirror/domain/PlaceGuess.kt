package md.utm.digitalmirror.domain

/**
 * Alege locul unei activități noi, scrise doar ca titlu.
 *  1. titlul pomenește numele unui loc („sport la sala”, „mă întorc acasă”) → acel loc;
 *  2. același titlu în istoric → locul în care s-a făcut de obicei;
 *  3. altfel nimic: utilizatorul poate alege singur.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object PlaceGuess {

    private const val MIN_NAME = 3          // numele mai scurte de 3 litere nu se caută în text

    data class Named(val id: Long, val name: String)

    /** O activitate din trecut: titlul normalizat și locul în care s-a desfășurat. */
    data class Known(val titleKey: String, val placeId: Long)

    /**
     * [history] se dă cu cele mai noi activități primele: la egalitate câștigă locul folosit mai recent.
     */
    fun guess(title: String, places: List<Named>, history: List<Known>): Long? {
        val key = TitleKey.of(title)
        if (key.isEmpty() || places.isEmpty()) return null

        // 1. numele unui loc, ca un cuvânt întreg sau grup de cuvinte din titlu
        val padded = " $key "
        val byName = places
            .map { it to TitleKey.of(it.name) }
            .filter { (_, nameKey) -> nameKey.length >= MIN_NAME && padded.contains(" $nameKey ") }
            .maxByOrNull { (_, nameKey) -> nameKey.length }      // „sala de sport” bate „sala”
            ?.first?.id
        if (byName != null) return byName

        // 2. același titlu, același loc ca de obicei (doar locuri care mai există)
        val existing = places.map { it.id }.toSet()
        val used = history.filter { it.titleKey == key && it.placeId in existing }.map { it.placeId }
        if (used.isEmpty()) return null
        val counts = used.groupingBy { it }.eachCount()
        val best = counts.values.max()
        return used.first { counts[it] == best }
    }
}