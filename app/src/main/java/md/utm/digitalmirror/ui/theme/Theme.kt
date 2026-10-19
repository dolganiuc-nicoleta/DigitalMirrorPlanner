package md.utm.digitalmirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Card,
    primaryContainer = GreenSoft,
    onPrimaryContainer = GreenDark,

    secondary = Ink,                    // butoanele închise, ca „Adaugă la 16:00” din prototip
    onSecondary = Card,
    secondaryContainer = GreenSoft,
    onSecondaryContainer = GreenDark,

    tertiary = Amber,
    onTertiary = Card,
    tertiaryContainer = AmberSoft,
    onTertiaryContainer = Amber,

    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = LineSoft,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Card,
    surfaceContainerLow = Card,
    surfaceContainer = Card,            // fundalul cardurilor
    surfaceContainerHigh = Card,        // fundalul dialogurilor
    surfaceContainerHighest = Card,     // fundalul cardurilor din Material 3
    outline = Line,
    outlineVariant = LineSoft,

    error = Berry,
    onError = Card
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = GreenDark,
    primaryContainer = GreenDark,
    onPrimaryContainer = GreenLight,

    secondary = NightInk,
    onSecondary = Night,
    secondaryContainer = GreenDark,
    onSecondaryContainer = GreenLight,

    tertiary = Color(0xFFF2B872),
    onTertiary = Night,
    tertiaryContainer = Color(0xFF3D2A12),
    onTertiaryContainer = Color(0xFFF2B872),

    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = NightCardHigh,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = NightCard,
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCard,
    surfaceContainerHigh = NightCardHigh,
    surfaceContainerHighest = NightCard,
    outline = NightLine,
    outlineVariant = NightLine,

    error = BerryLight,
    onError = Night
)

// colțuri rotunjite ca în prototip
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DigitalMirrorPlannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Accent = ThemeSettings.accent,
    content: @Composable () -> Unit
) {
    // restul paletei rămâne la fel; se schimbă doar culoarea principală aleasă de utilizator
    val colors = if (darkTheme) {
        DarkColors.copy(
            primary = accent.nightPrimary,
            onPrimary = accent.dark,
            primaryContainer = accent.dark,
            onPrimaryContainer = accent.nightPrimary,
            secondaryContainer = accent.dark,
            onSecondaryContainer = accent.nightPrimary
        )
    } else {
        LightColors.copy(
            primary = accent.primary,
            primaryContainer = accent.soft,
            onPrimaryContainer = accent.dark,
            secondaryContainer = accent.soft,
            onSecondaryContainer = accent.dark
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
