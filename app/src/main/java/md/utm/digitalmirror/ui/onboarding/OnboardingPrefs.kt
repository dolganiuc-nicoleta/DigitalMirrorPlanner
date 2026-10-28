package md.utm.digitalmirror.ui.onboarding

import android.content.Context

/**
 * Răspunsurile din configurarea inițială.
 *
 * Tiparul zilei dă valoarea de pornire a estimatorului (priorRatio): cu cât presupunem că
 * activitățile durează mai mult decât estimează utilizatorul, înainte să avem date despre el.
 * Cercetările despre „planning fallacy” arată că oamenii își subestimează de obicei duratele,
 * iar cu cât ziua e mai imprevizibilă, cu atât mai mult. Pe măsură ce se adună istoricul,
 * valoarea asta contează tot mai puțin (vezi HistoryEstimator).
 */
object OnboardingPrefs {

    enum class Pattern(val label: String, val priorRatio: Double, val description: String) {
        FIXED(
            "Program fix", 1.10,
            "Zilele tale seamănă între ele, așa că pornim de la ideea că activitățile îți iau cu ~10% mai mult decât estimezi."
        ),
        FLEXIBLE(
            "Flexibil", 1.20,
            "Ai câteva ore fixe și restul e flexibil. Pornim de la ~20% în plus față de estimările tale."
        ),
        FRAGMENTED(
            "Fragmentat", 1.30,
            "Planurile ți se schimbă des, așa că lăsăm o marjă mai mare la început: ~30% în plus."
        ),
        SHIFTS(
            "Pe ture", 1.15,
            "Lucrezi pe ture, deci zilele diferă între ele. Pornim de la ~15% în plus."
        ),
        SKIPPED(
            "Necompletat", 1.0,
            "Pornim de la estimările tale, fără nicio corecție."
        )
    }

    private const val FILE = "onboarding_prefs"
    private const val KEY_DONE = "done"
    private const val KEY_PATTERN = "pattern"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isDone(context: Context): Boolean = prefs(context).getBoolean(KEY_DONE, false)

    fun pattern(context: Context): Pattern =
        prefs(context).getString(KEY_PATTERN, null)
            ?.let { runCatching { Pattern.valueOf(it) }.getOrNull() }
            ?: Pattern.SKIPPED

    fun priorRatio(context: Context): Double = pattern(context).priorRatio

    fun save(context: Context, pattern: Pattern) {
        prefs(context).edit()
            .putString(KEY_PATTERN, pattern.name)
            .putBoolean(KEY_DONE, true)
            .apply()
    }

    /** La ieșirea din cont, următorul cont reia configurarea inițială. */
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Din cele trei răspunsuri rezultă tiparul zilei. */
    fun patternFrom(fixedSchedule: String?, planChanges: String?, shifts: String?): Pattern = when {
        shifts == "Da" -> Pattern.SHIFTS
        planChanges == "Des" -> Pattern.FRAGMENTED
        fixedSchedule == "Da" -> Pattern.FIXED
        fixedSchedule != null || planChanges != null -> Pattern.FLEXIBLE
        else -> Pattern.SKIPPED
    }
}