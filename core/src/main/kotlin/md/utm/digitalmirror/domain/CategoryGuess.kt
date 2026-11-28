package md.utm.digitalmirror.domain

/**
 * Alege categoria unei activități noi, scrise doar ca titlu.
 *  1. același titlu în istoric → categoria în care a fost pusă de obicei;
 *  2. un cuvânt important comun cu titluri din istoric → categoria lor cea mai frecventă;
 *  3. un cuvânt care seamănă cu numele unei categorii („studiu” ~ „Studiez”) → acea categorie;
 *  4. altfel nimic: utilizatorul poate alege singur.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object CategoryGuess {

    private const val MIN_WORD = 4          // cuvintele mai scurte („la”, „de”, „PAD”) nu spun destul
    private const val MIN_PREFIX = 4        // câte litere de la început trebuie să semene

    data class Cat(val id: Long, val name: String)

    /** O activitate din trecut: titlul normalizat și categoria în care a fost pusă. */
    data class Known(val titleKey: String, val categoryId: Long)

    fun guess(title: String, categories: List<Cat>, known: List<Known>): Long? {
        val valid = categories.map { it.id }.toSet()
        val usable = known.filter { it.categoryId in valid }
        val key = TitleKey.of(title)
        if (key.isBlank()) return null

        // 1. același titlu
        mostCommon(usable.filter { it.titleKey == key })?.let { return it }

        // 2. cuvinte comune cu titlurile din istoric
        val words = significantWords(key)
        if (words.isNotEmpty()) {
            mostCommon(usable.filter { k -> significantWords(k.titleKey).any { it in words } })?.let { return it }
        }

        // 3. cuvinte care seamănă cu numele unei categorii
        for (category in categories) {
            val categoryWords = significantWords(TitleKey.of(category.name))
            if (categoryWords.any { c -> words.any { w -> sharePrefix(c, w) } }) return category.id
        }
        return null
    }

    private fun mostCommon(list: List<Known>): Long? =
        list.groupBy { it.categoryId }.maxByOrNull { it.value.size }?.key

    private fun significantWords(key: String): Set<String> =
        key.split(' ').filter { it.length >= MIN_WORD }.toSet()

    private fun sharePrefix(a: String, b: String): Boolean {
        val n = minOf(a.length, b.length, 5)
        return n >= MIN_PREFIX && a.take(n) == b.take(n)
    }
}