package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import md.utm.digitalmirror.domain.DayArc
import md.utm.digitalmirror.ui.theme.Fraunces
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Momentul zilei, pentru culorile cerului. */
enum class SkyTime { MORNING, AFTERNOON, EVENING;
    companion object {
        fun at(minute: Int): SkyTime = when {
            minute < 11 * 60 -> MORNING
            minute < 18 * 60 -> AFTERNOON
            else -> EVENING
        }
    }
}

/** Culorile cerului: plate, ca hârtia decupată, nu degradeuri. */
private data class SkyPalette(
    val sky: Color, val hillBack: Color, val hillFront: Color, val sun: Color,
    val ink: Color, val muted: Color,
    val arcBase: Color, val arcInk: Color, val done: Color, val meal: Color, val event: Color
)

private fun palette(time: SkyTime, accent: Color): SkyPalette = when (time) {
    SkyTime.MORNING -> SkyPalette(
        sky = Color(0xFFDCE8EE), hillBack = Color(0xFFC5DAE2), hillFront = Color(0xFFAECAD4), sun = Color(0xFFE9A23B),
        ink = Color(0xFF17181F), muted = Color(0xFF44505A),
        arcBase = Color(0xFF9FB4BE), arcInk = Color(0xFF2B3640), done = Color(0xFF7E909A),
        meal = Color(0xFFC9772F), event = accent
    )
    SkyTime.AFTERNOON -> SkyPalette(
        sky = Color(0xFFF4E4C9), hillBack = Color(0xFFEBD0A8), hillFront = Color(0xFFE0BA8C), sun = Color(0xFFE2762E),
        ink = Color(0xFF17181F), muted = Color(0xFF5A4A38),
        arcBase = Color(0xFFC9A982), arcInk = Color(0xFF3A2E22), done = Color(0xFFA8957C),
        meal = Color(0xFFB8641F), event = accent
    )
    SkyTime.EVENING -> SkyPalette(
        sky = Color(0xFF232947), hillBack = Color(0xFF30375E), hillFront = Color(0xFF3D4573), sun = Color(0xFFF1E4C3),
        ink = Color(0xFFF4EFE6), muted = Color(0xFFC2C6DE),
        arcBase = Color(0xFF5A628F), arcInk = Color(0xFFC2C6DE), done = Color(0xFF7A82AE),
        meal = Color(0xFFE9A866), event = lerp(accent, Color.White, 0.55f)
    )
}

/** Minutul de acum, actualizat în fiecare minut (ca soarele să meargă pe arc). */
@Composable
fun rememberNowMinute(): Int {
    val now by produceState(LocalTime.now().let { it.hour * 60 + it.minute }) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            value = LocalTime.now().let { it.hour * 60 + it.minute }
        }
    }
    return now
}

/**
 * Hero-ul zilei: un arc de la trezire (stânga) la culcare (dreapta), peste un orizont din dealuri.
 * Segmentele sunt lucrurile zilei, soarele e „acum”, iar în mijloc stă ce contează acum.
 */
@Composable
fun DayArcHero(
    arc: DayArc.Result,
    wake: Int,
    bedtime: Int,
    sky: SkyTime,
    modifier: Modifier = Modifier,
    height: Dp = 250.dp
) {
    val p = palette(sky, MaterialTheme.colorScheme.primary)
    val focus = arc.focus
    val description = "Ziua ta, de la ${DayArc.clock(wake)} la ${DayArc.clock(bedtime)}. " +
            "${focus.label} ${DayArc.duration(focus.minutes)}, ${focus.sentence}."

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(28.dp))
            .background(p.sky)
            .semantics { contentDescription = description }
    ) {
        // aceeași geometrie pentru desen și pentru etichete
        val w = maxWidth
        val horizon = height - 44.dp
        val r = min((w / 2 - 30.dp).value, (horizon - 24.dp).value).dp
        val cx = w / 2

        Canvas(Modifier.matchParentSize()) {
            val hz = horizon.toPx()
            val radius = r.toPx()
            val center = Offset(cx.toPx(), hz)
            val W = size.width
            val H = size.height

            // orizontul: două dealuri decupate
            val back = Path().apply {
                moveTo(0f, hz + 6.dp.toPx())
                cubicTo(W * 0.15f, hz - 8.dp.toPx(), W * 0.31f, hz - 4.dp.toPx(), W * 0.46f, hz + 6.dp.toPx())
                cubicTo(W * 0.62f, hz + 14.dp.toPx(), W * 0.77f, hz - 8.dp.toPx(), W, hz + 2.dp.toPx())
                lineTo(W, H); lineTo(0f, H); close()
            }
            val front = Path().apply {
                moveTo(0f, hz + 22.dp.toPx())
                cubicTo(W * 0.2f, hz + 10.dp.toPx(), W * 0.38f, hz + 17.dp.toPx(), W * 0.56f, hz + 23.dp.toPx())
                cubicTo(W * 0.72f, hz + 29.dp.toPx(), W * 0.85f, hz + 11.dp.toPx(), W, hz + 19.dp.toPx())
                lineTo(W, H); lineTo(0f, H); close()
            }
            drawPath(back, p.hillBack)
            drawPath(front, p.hillFront)

            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // arcul de bază
            drawArc(p.arcBase, 180f, 180f, false, topLeft, arcSize, style = Stroke(2.dp.toPx()))

            // lucrurile zilei
            arc.segments.forEach { s ->
                val (color, stroke) = when (s.kind) {
                    DayArc.Kind.DONE -> p.done to Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                    DayArc.Kind.MEAL -> p.meal to Stroke(8.dp.toPx(), cap = StrokeCap.Round)
                    DayArc.Kind.EVENT, DayArc.Kind.ACTIVITY -> p.event to Stroke(10.dp.toPx(), cap = StrokeCap.Round)
                    DayArc.Kind.PREP -> p.arcInk to Stroke(
                        5.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.5f, 6.dp.toPx()))
                    )
                    DayArc.Kind.TRAVEL -> p.arcInk to Stroke(
                        3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    )
                }
                drawArc(color, 180f + s.from * 180f, (s.to - s.from) * 180f, false, topLeft, arcSize, style = stroke)
            }

            // capetele: trezire și culcare
            drawCircle(p.arcInk, 4.dp.toPx(), Offset(center.x - radius, center.y))
            drawCircle(p.arcInk, 4.dp.toPx(), Offset(center.x + radius, center.y))

            // soarele (seara, luna): „acum”
            arc.now?.let { f ->
                val angle = PI + f * PI
                val sun = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                drawCircle(p.sun.copy(alpha = 0.16f), 26.dp.toPx(), sun)
                drawCircle(p.sun.copy(alpha = 0.32f), 18.dp.toPx(), sun)
                drawCircle(p.sun, 10.dp.toPx(), sun)
                drawCircle(p.sky, 10.dp.toPx(), sun, style = Stroke(3.dp.toPx()))
            }
        }

        // ora de trezire și de culcare, deasupra capetelor
        EndLabel(DayArc.clock(wake), p.muted, x = cx - r, y = horizon - 22.dp)
        EndLabel(DayArc.clock(bedtime), p.muted, x = cx + r, y = horizon - 22.dp)

        // ce contează acum, în mijlocul arcului
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp + 6.dp)
                // îngust, ca soarele de pe arc să nu treacă peste text
                .width(r * 2 - 110.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                focus.label.uppercase(),
                color = p.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                DayArc.duration(focus.minutes),
                color = p.ink,
                fontFamily = Fraunces,
                fontWeight = FontWeight.Normal,
                fontSize = 52.sp,
                lineHeight = 56.sp,
                letterSpacing = (-0.5).sp
            )
            Text(
                focus.sentence,
                color = p.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun EndLabel(text: String, color: Color, x: Dp, y: Dp) {
    Box(Modifier.offset(x = x - 24.dp, y = y).width(48.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
