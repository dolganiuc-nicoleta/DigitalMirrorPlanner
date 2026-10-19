package md.utm.digitalmirror.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Culorile principale pe care le poate alege utilizatorul. [nightPrimary] se folosește în tema întunecată. */
enum class Accent(
    val label: String,
    val primary: Color,
    val dark: Color,
    val soft: Color,
    val nightPrimary: Color
) {
    GREEN("Verde", Color(0xFF0E6B5C), Color(0xFF09493F), Color(0xFFE3F1EE), Color(0xFF9FD3C7)),
    BLUE("Albastru", Color(0xFF1E4FD8), Color(0xFF14338F), Color(0xFFE3EAFB), Color(0xFFA9BEF5)),
    VIOLET("Violet", Color(0xFF5B35C9), Color(0xFF3C2185), Color(0xFFEAE3FA), Color(0xFFC7B6F2)),
    ROSE("Roz", Color(0xFFB0165A), Color(0xFF780F3E), Color(0xFFFAE1EB), Color(0xFFF3B0CB)),
    CORAL("Corai", Color(0xFFC8432F), Color(0xFF8A2B1D), Color(0xFFFBE6E1), Color(0xFFF5B5A9)),
    ORANGE("Portocaliu", Color(0xFFB45F00), Color(0xFF7A4100), Color(0xFFFFEBD3), Color(0xFFFFC98A)),
    NAVY("Albastru închis", Color(0xFF1B2B4D), Color(0xFF101A30), Color(0xFFE2E7F1), Color(0xFFB3C1E0))
}

/** Culoarea aleasă. Ține starea în Compose, așa că toată aplicația se recolorează imediat. */
object ThemeSettings {
    private const val FILE = "theme_prefs"
    private const val KEY_ACCENT = "accent"

    var accent by mutableStateOf(Accent.GREEN)
        private set

    /** Se apelează o dată, la pornirea aplicației. */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ACCENT, null)
        accent = Accent.entries.firstOrNull { it.name == saved } ?: Accent.GREEN
    }

    fun setAccent(context: Context, value: Accent) {
        accent = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ACCENT, value.name).apply()
    }
}
