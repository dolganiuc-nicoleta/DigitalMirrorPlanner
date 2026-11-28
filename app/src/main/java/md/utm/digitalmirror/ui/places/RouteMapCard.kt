package md.utm.digitalmirror.ui.places

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.travel.TravelRepository
import md.utm.digitalmirror.domain.PlaceRules
import md.utm.digitalmirror.domain.RouteGeometry
import md.utm.digitalmirror.domain.TravelEstimator
import md.utm.digitalmirror.domain.TravelEstimator.Mode
import md.utm.digitalmirror.domain.TravelEstimator.Source
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.theme.Fraunces
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

private val DEFAULT_CENTER = GeoPoint(47.0105, 28.8638)

private fun modeIcon(mode: Mode): ImageVector = when (mode) {
    Mode.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
    Mode.TRANSIT -> Icons.Outlined.DirectionsBus
    Mode.CAR -> Icons.Outlined.DirectionsCar
    Mode.BIKE -> Icons.AutoMirrored.Outlined.DirectionsBike
}

/**
 * „Drumurile tale”: harta cu locurile, traseul dintre două dintre ele și cât durează
 * pe jos, cu transportul public, cu mașina și cu bicicleta. Modul ales e cel folosit la planificare.
 */
@Composable
fun RouteMapCard(
    places: List<Place>,
    state: RouteMapUi,
    onFrom: (Long) -> Unit,
    onTo: (Long) -> Unit,
    onSwap: () -> Unit,
    onMode: (Mode) -> Unit,
    onUseForAll: () -> Unit = {}
) {
    if (places.size < 2) return   // un drum are nevoie de două locuri

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("Drumurile tale", fontFamily = Fraunces, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Alege de unde și până unde, sau atinge un loc pe hartă.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            RouteMap(
                places = places,
                fromId = state.fromId,
                toId = state.toId,
                points = state.chosen?.outcome?.points.orEmpty(),
                onTapPlace = { id ->
                    when {
                        state.fromId == null -> onFrom(id)
                        id != state.fromId -> onTo(id)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp))
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlaceChips("De la", places, state.fromId, onFrom)
                    PlaceChips("Până la", places, state.toId, onTo)
                }
                IconButton(onClick = onSwap, enabled = state.fromId != null && state.toId != null) {
                    Icon(Icons.Outlined.SwapVert, contentDescription = "Inversează")
                }
            }

            when {
                state.fromId == null || state.toId == null -> Unit
                state.fromId == state.toId -> Text(
                    "Alege două locuri diferite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.loading && state.options.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Calculez drumul…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.options.forEach { option ->
                            ModeRow(option, selected = option.mode == state.mode, onClick = { onMode(option.mode) })
                        }
                    }
                    // drum scurt: autobuzul nu câștigă mare lucru după ce aștepți în stație
                    val walk = state.options.firstOrNull { it.mode == Mode.WALK }?.outcome?.estimate?.minutes
                    val bus = state.options.firstOrNull { it.mode == Mode.TRANSIT }?.outcome?.estimate?.minutes
                    if (walk != null && bus != null && walk <= bus + 5) {
                        Text(
                            "Pe jos ajungi aproape la fel de repede (${formatDuration(walk)}): nu aștepți în stație.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Google Maps știe liniile de autobuz și traficul: deschidem drumul acolo, cu modul ales
                    val fromPlace = places.firstOrNull { it.id == state.fromId }
                    val toPlace = places.firstOrNull { it.id == state.toId }
                    if (fromPlace != null && toPlace != null) {
                        val context = LocalContext.current
                        OutlinedButton(
                            onClick = { openInGoogleMaps(context, fromPlace, toPlace, state.mode) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Vezi în Google Maps · ${state.mode.label.lowercase()}")
                        }
                    }
                    if (state.mode != state.defaultMode) {
                        TextButton(onClick = onUseForAll, contentPadding = PaddingValues(0.dp)) {
                            Text("Folosește „${state.mode.label}” pentru toate drumurile")
                        }
                    }
                    state.chosen?.outcome?.let { o ->
                        o.spread?.let { SpreadCard(it) }
                        Text(
                            explain(o),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        RealTrips(o.trips, TravelEstimator.Estimate(o.baseMinutes, o.estimate.source))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceChips(label: String, places: List<Place>, selectedId: Long?, onSelect: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            places.forEach { p ->
                FilterChip(selected = p.id == selectedId, onClick = { onSelect(p.id) }, label = { Text(p.name) })
            }
        }
    }
}

@Composable
private fun ModeRow(option: TravelRepository.ModeOption, selected: Boolean, onClick: () -> Unit) {
    val o = option.outcome
    Surface(
        onClick = onClick,
        enabled = o != null,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                modeIcon(option.mode), null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(option.mode.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        o == null -> option.error ?: "Nu s-a putut calcula."
                        selected && o.observedCount > 0 -> "cum faci drumul ăsta · traseul singur ${formatDuration(o.baseMinutes)}, corectat din drumurile tale"
                        selected -> "cum faci drumul ăsta · ${PlaceRules.distanceLabel(o.distanceMeters)}"
                        o.estimate.source == Source.LEARNED -> "din drumurile tale reale"
                        o.estimate.source == Source.APPROXIMATION -> "aproximare"
                        else -> PlaceRules.distanceLabel(o.distanceMeters)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (o == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (o != null) {
                Text(
                    (if (o.estimate.source == Source.APPROXIMATION) "~" else "") + formatDuration(o.estimate.minutes),
                    fontFamily = Fraunces,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}

/** „De obicei 25 min, uneori până la 40 · planific cu 35”: cât variază drumul și cu cât se planifică. */
@Composable
private fun SpreadCard(s: TravelEstimator.Spread) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val color = MaterialTheme.colorScheme.onSecondaryContainer
            val low = s.low
            val high = s.high
            if (low != null && high != null) {
                Text("De obicei ${formatDuration(s.typical)}", style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    if (high > s.typical) "uneori până la ${formatDuration(high)} (cel mai repede ${formatDuration(low)})"
                    else "aproape mereu la fel",
                    style = MaterialTheme.typography.bodyMedium, color = color
                )
                Text(
                    if (s.planned > s.typical)
                        "Planific cu ${formatDuration(s.planned)}, ca să nu întârzii în zilele mai lente (ambuteiaj, autobuz pierdut)."
                    else "Planific cu ${formatDuration(s.planned)}.",
                    style = MaterialTheme.typography.bodySmall, color = color
                )
            } else {
                Text("Planific cu ${formatDuration(s.planned)}", style = MaterialTheme.typography.titleMedium, color = color)
                Text(
                    if (s.count == 0) "Încă nu am drumuri reale aici. După ${TravelEstimator.MIN_FOR_SPREAD} drumuri îți arăt cât variază."
                    else "Am ${s.count} din ${TravelEstimator.MIN_FOR_SPREAD} drumuri reale de care am nevoie ca să-ți arăt cât variază.",
                    style = MaterialTheme.typography.bodySmall, color = color
                )
            }
        }
    }
}

private val TRIP_DAYS = listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică")

/** Drumurile reale pe ruta aleasă: când ai plecat și cât ți-a luat, cu o bară ca să se vadă diferențele. */
@Composable
private fun RealTrips(trips: List<TravelEstimator.Trip>, base: TravelEstimator.Estimate) {
    if (trips.isEmpty()) {
        Text(
            "Drumurile reale apar aici singure: telefonul vede când ieși dintr-un loc și când ajungi în celălalt " +
                    "(cu detectarea locurilor pornită, mai jos).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Drumurile tale reale", style = MaterialTheme.typography.labelLarge)
        if (trips.any { !TravelEstimator.plausible(base, it.minutes) }) {
            Text(
                "Cele cu ✕ nu se potrivesc cu modul ales (de ex. ieșirea detectată târziu sau o oprire pe drum), așa că nu le iau în calcul.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val shown = trips.take(8)
        val longest = shown.maxOf { it.minutes }.coerceAtLeast(1)
        val zone = java.time.ZoneId.systemDefault()
        shown.forEach { t ->
            val at = java.time.Instant.ofEpochMilli(t.departAt).atZone(zone)
            // un drum care nu se potrivește cu modul (ieșire detectată târziu, o oprire pe drum) nu contează
            val counts = TravelEstimator.plausible(base, t.minutes)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TRIP_DAYS[at.dayOfWeek.value - 1]} ${at.dayOfMonth}.${"%02d".format(at.monthValue)}, " +
                            "%02d:%02d".format(at.hour, at.minute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(130.dp)
                )
                Box(
                    Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(t.minutes.toFloat() / longest)
                            .clip(RoundedCornerShape(4.dp)).background(if (counts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                Text(
                    formatDuration(t.minutes) + if (counts) "" else " ✕",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (counts) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.width(64.dp).padding(start = 8.dp)
                )
            }
        }
    }
}

// de unde vine estimarea modului ales, în cuvinte
private fun explain(o: TravelRepository.Outcome): String {
    val distance = PlaceRules.distanceLabel(o.distanceMeters)
    return when (o.estimate.source) {
        Source.ROUTE -> "Calculat de OpenRouteService pe traseul de pe hartă ($distance), fără trafic. " +
                "Pe măsură ce faci drumul, se corectează din timpii tăi reali."
        Source.APPROXIMATION ->
            "Transport public: serviciul de rute nu are autobuze, așa că pe hartă e traseul cel mai scurt pe străzi ($distance), " +
                    "iar durata vine din viteza medie a transportului în oraș și așteptarea în stație. " +
                    "Se corectează din drumurile tale detectate."
        Source.LEARNED -> {
            val n = o.observedCount
            "Corectat din $n ${if (n == 1) "drum real" else "drumuri reale"} ale tale " +
                    "(doar traseul ar fi dat ${formatDuration(o.baseMinutes)}, $distance)."
        }
    }
}

// ---------------- harta ----------------

/** Eticheta unui loc pe hartă: o pastilă colorată cu numele, cu vârful jos pe loc. */
private fun pinDrawable(context: Context, label: String, color: Int, textColor: Int): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        this.color = textColor
    }
    val padH = 10 * density
    val padV = 6 * density
    val tip = 6 * density
    val w = (paint.measureText(label) + 2 * padH).toInt()
    val h = (paint.textSize + 2 * padV + tip).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val body = RectF(0f, 0f, w.toFloat(), h - tip)
    canvas.drawRoundRect(body, body.height() / 2, body.height() / 2, fill)
    val path = android.graphics.Path().apply {
        moveTo(w / 2f - tip, h - tip - 1)
        lineTo(w / 2f + tip, h - tip - 1)
        lineTo(w / 2f, h.toFloat())
        close()
    }
    canvas.drawPath(path, fill)
    canvas.drawText(label, padH, padV + paint.textSize * 0.85f, paint)
    return BitmapDrawable(context.resources, bmp)
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun RouteMap(
    places: List<Place>,
    fromId: Long?,
    toId: Long?,
    points: List<RouteGeometry.Point>,
    onTapPlace: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val onPrimary = MaterialTheme.colorScheme.onPrimary.toArgb()
    val tertiary = MaterialTheme.colorScheme.tertiary.toArgb()
    val onTertiary = MaterialTheme.colorScheme.onTertiary.toArgb()
    val other = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val onOther = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
    }
    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(13.0)
            controller.setCenter(DEFAULT_CENTER)
            // harta primește gesturile, nu lista care o conține
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }
    // ce am încadrat ultima oară, ca harta să nu sară la fiecare redesenare
    val lastFit = remember { arrayOfNulls<String>(1) }
    val tap by rememberUpdatedState(onTapPlace)

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.overlays.removeAll { it is Marker || it is Polyline }

            if (points.size >= 2) {
                val geo = points.map { GeoPoint(it.lat, it.lon) }
                // un contur alb sub linie, ca traseul să se vadă pe orice stradă
                view.overlays.add(Polyline(view).apply {
                    setPoints(geo)
                    outlinePaint.color = android.graphics.Color.WHITE
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                })
                view.overlays.add(Polyline(view).apply {
                    setPoints(geo)
                    outlinePaint.color = primary
                    outlinePaint.strokeWidth = 8f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                })
            }

            // locurile: cel de plecare și cel de sosire colorate, restul discrete, deasupra traseului
            places.sortedBy { if (it.id == fromId || it.id == toId) 1 else 0 }.forEach { p ->
                val (bg, fg) = when (p.id) {
                    fromId -> primary to onPrimary
                    toId -> tertiary to onTertiary
                    else -> other to onOther
                }
                view.overlays.add(Marker(view).apply {
                    position = GeoPoint(p.latitude, p.longitude)
                    icon = pinDrawable(context, p.name, bg, fg)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> tap(p.id); true }
                })
            }

            // încadrăm traseul, sau toate locurile când nu e niciun traseu
            val key = "$fromId-$toId-${points.size}-${places.size}"
            if (lastFit[0] != key) {
                lastFit[0] = key
                val fit = if (points.size >= 2) points.map { GeoPoint(it.lat, it.lon) }
                else places.map { GeoPoint(it.latitude, it.longitude) }
                if (fit.size >= 2) {
                    view.post {
                        runCatching { view.zoomToBoundingBox(BoundingBox.fromGeoPoints(fit).increaseByScale(1.3f), true, 48) }
                    }
                } else fit.firstOrNull()?.let { view.controller.animateTo(it) }
            }
            view.invalidate()
        }
    )
}

/**
 * Deschide drumul în Google Maps (aplicația sau, fără ea, browserul), cu modul ales.
 * Google știe liniile de transport și traficul; aplicația noastră nu trimite nimic, doar deschide legătura.
 */
private fun openInGoogleMaps(context: Context, from: Place, to: Place, mode: Mode) {
    val travelMode = when (mode) {
        Mode.WALK -> "walking"
        Mode.TRANSIT -> "transit"
        Mode.CAR -> "driving"
        Mode.BIKE -> "bicycling"
    }
    val uri = android.net.Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
                "&origin=${from.latitude},${from.longitude}" +
                "&destination=${to.latitude},${to.longitude}" +
                "&travelmode=$travelMode"
    )
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        android.widget.Toast.makeText(context, "Nu am găsit Google Maps sau un browser.", android.widget.Toast.LENGTH_SHORT).show()
    }
}
