package md.utm.digitalmirror.data.prefs

import android.content.Context
import md.utm.digitalmirror.domain.MealPlanner
import org.json.JSONArray
import org.json.JSONObject

/** Ferestrele meselor alese de utilizator; fără alegere, valorile din [MealPlanner.DEFAULTS]. */
object MealPrefs {
    private const val FILE = "meal_prefs"
    private const val KEY = "windows"

    fun windows(context: Context): List<MealPlanner.Window> {
        val text = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return MealPlanner.DEFAULTS
        return runCatching { fromJson(JSONArray(text)) }.getOrDefault(MealPlanner.DEFAULTS)
    }

    fun save(context: Context, windows: List<MealPlanner.Window>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, toJson(windows).toString()).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun toJson(windows: List<MealPlanner.Window>): JSONArray = JSONArray().apply {
        windows.forEach { w ->
            put(
                JSONObject()
                    .put("kind", w.kind.name)
                    .put("from", w.from)
                    .put("to", w.to)
                    .put("minutes", w.minutes)
                    .put("optional", w.optional)
                    .put("enabled", w.enabled)
            )
        }
    }

    /** Mesele lipsă din text (de ex. o versiune mai veche) se completează cu valorile implicite. */
    fun fromJson(array: JSONArray): List<MealPlanner.Window> {
        val read = (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val kind = runCatching { MealPlanner.Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            MealPlanner.Window(
                kind = kind,
                from = o.getInt("from"),
                to = o.getInt("to"),
                minutes = o.getInt("minutes"),
                optional = o.optBoolean("optional", true),
                enabled = o.optBoolean("enabled", true)
            )
        }.associateBy { it.kind }
        return MealPlanner.DEFAULTS.map { read[it.kind] ?: it }
    }
}
