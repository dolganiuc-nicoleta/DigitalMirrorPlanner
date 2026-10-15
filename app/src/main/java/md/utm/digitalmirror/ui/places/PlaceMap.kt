package md.utm.digitalmirror.ui.places

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/** Chișinău: de aici pornește harta când nu e ales încă niciun loc. */
private val DEFAULT_CENTER = GeoPoint(47.0105, 28.8638)

/**
 * Hartă OpenStreetMap (osmdroid). Atingerea hărții alege locul, iar cercul arată raza.
 *
 * @param point locul ales, sau null
 * @param radiusMeters raza desenată în jurul locului
 * @param onPick apelat cu (latitudine, longitudine) la atingerea hărții
 */
@Composable
fun PlaceMap(
    point: GeoPoint?,
    radiusMeters: Int,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // osmdroid cere un User-Agent și un loc pentru cache; punem cache-ul în folderul aplicației,
    // ca să nu fie nevoie de permisiuni pentru stocare
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
            controller.setZoom(15.0)
            controller.setCenter(point ?: DEFAULT_CENTER)
        }
    }

    // harta se pornește când ecranul apare și se oprește când dispare
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // ultimul punct spre care s-a mutat harta, ca să nu o mutăm la fiecare redesenare
    val lastCentered = remember { arrayOfNulls<GeoPoint>(1) }

    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = {
            mapView.apply {
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        onPick(p.latitude, p.longitude)
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }))
            }
        },
        update = { view ->
            view.overlays.removeAll { it is Marker || it is Polygon }
            if (point != null) {
                val circle = Polygon(view).apply {
                    points = Polygon.pointsAsCircle(point, radiusMeters.toDouble())
                    fillPaint.color = 0x332E7D32
                    outlinePaint.color = 0xFF2E7D32.toInt()
                    outlinePaint.strokeWidth = 3f
                }
                val marker = Marker(view).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> true }
                }
                view.overlays.add(circle)
                view.overlays.add(marker)

                if (lastCentered[0] != point) {
                    view.controller.animateTo(point)
                    lastCentered[0] = point
                }
            }
            view.invalidate()
        }
    )
}