package md.utm.digitalmirror.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import md.utm.digitalmirror.R

// fontul din prototip; fișierele sunt în res/font
val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold)
)

/**
 * Fraunces, serif expresiv pentru titluri și cifrele mari (arcul zilei).
 * E un font variabil: alegem grosimea și mărimea optică (opsz) pentru fiecare variantă.
 * Licența (SIL OFL 1.1) e în licenses/Fraunces-OFL.txt.
 */
@OptIn(ExperimentalTextApi::class)
val Fraunces = FontFamily(
    Font(
        R.font.fraunces, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("opsz", 144f))
    ),
    Font(
        R.font.fraunces, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("opsz", 72f))
    )
)

private val base = Typography()

val AppTypography = Typography(
    headlineLarge = base.headlineLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = base.titleMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = Jakarta, fontSize = 16.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = Jakarta, fontSize = 14.sp),
    bodySmall = base.bodySmall.copy(fontFamily = Jakarta, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.Medium),
    displayLarge = base.displayLarge.copy(fontFamily = Jakarta),
    displayMedium = base.displayMedium.copy(fontFamily = Jakarta),
    displaySmall = base.displaySmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold)
)

// stil pentru cronometru și cifrele mari
val NumberStyle = TextStyle(
    fontFamily = Jakarta,
    fontWeight = FontWeight.Bold,
    fontSize = 32.sp
)