package md.utm.digitalmirror.ui.places

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.domain.PlaceRules
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

/** Locurile mele: listă, adăugare și editare cu hartă. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(onBack: () -> Unit, vm: PlacesViewModel = viewModel()) {
    val places by vm.places.collectAsState()
    val recentVisits by vm.recentVisits.collectAsState()
    val route by vm.route.collectAsState()
    // null = lista; altfel locul care se editează (id 0 = loc nou)
    var editing by remember { mutableStateOf<Place?>(null) }
    var deleting by remember { mutableStateOf<Place?>(null) }

    BackHandler(enabled = editing != null) {
        editing = null
        vm.clearSearch()
    }

    val current = editing
    if (current != null) {
        PlaceEditor(
            initial = current,
            vm = vm,
            onSave = { place ->
                vm.save(place)
                editing = null
                vm.clearSearch()
            },
            onCancel = {
                editing = null
                vm.clearSearch()
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locurile mele") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Înapoi") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Locurile rămân doar pe acest telefon. Le folosim ca să știm unde se desfășoară activitățile tale.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                RouteMapCard(
                    places = places,
                    state = route,
                    onFrom = vm::setRouteFrom,
                    onTo = vm::setRouteTo,
                    onSwap = vm::swapRoute,
                    onMode = vm::chooseMode
                )
            }
            item {
                PresenceCard(
                    placeCount = places.size,
                    recentVisits = recentVisits,
                    onChanged = { vm.syncGeofences() }
                )
            }
            if (places.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Încă nu ai niciun loc", fontWeight = FontWeight.Medium)
                            Text(
                                "Adaugă locurile unde îți petreci timpul: facultatea, sala de sport, biroul.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(places, key = { it.id }) { place ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(place.name, fontWeight = FontWeight.Medium)
                            Text(
                                "rază ${place.radiusMeters} m" +
                                        if (place.address.isNotBlank()) " · ${place.address.take(40)}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = { editing = place }) { Text("Editează") }
                        TextButton(onClick = { deleting = place }) {
                            Text("Șterge", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        editing = Place(
                            name = "", latitude = 0.0, longitude = 0.0,
                            radiusMeters = PlaceRules.DEFAULT_RADIUS, address = ""
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Adaugă un loc") }
            }
        }
    }

    deleting?.let { place ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Ștergi „${place.name}”?") },
            text = { Text("Activitățile nu se șterg. Doar locul dispare din listă.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(place)
                    deleting = null
                }) { Text("Șterge", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anulează") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceEditor(
    initial: Place,
    vm: PlacesViewModel,
    onSave: (Place) -> Unit,
    onCancel: () -> Unit
) {
    val isNew = initial.id == 0L
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf(initial.radiusMeters) }
    var address by rememberSaveable { mutableStateOf(initial.address) }
    // un loc nou nu are încă punct ales (coordonate 0,0 înseamnă „nealeasă”)
    var latitude by rememberSaveable { mutableStateOf(if (isNew) null else initial.latitude) }
    var longitude by rememberSaveable { mutableStateOf(if (isNew) null else initial.longitude) }
    val search by vm.search.collectAsState()

    val lat = latitude
    val lon = longitude
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    val problem = PlaceRules.problem(name, point != null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Loc nou" else "Editează locul") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("‹ Înapoi") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Numele locului (ex. Facultate)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Caută o adresă") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { vm.searchAddress(query) },
                    enabled = query.trim().length >= 3 && !search.loading
                ) {
                    if (search.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Caută")
                }
            }

            search.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            search.hits.forEach { hit ->
                TextButton(
                    onClick = {
                        latitude = hit.latitude
                        longitude = hit.longitude
                        address = hit.label
                        if (name.isBlank()) name = hit.label.substringBefore(",").take(PlaceRules.MAX_NAME)
                        vm.clearSearch()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(hit.label, maxLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }

            // harta ocupă tot spațiul rămas, ca să nu se certe cu derularea paginii
            PlaceMap(
                point = point,
                radiusMeters = radius,
                onPick = { la, lo ->
                    latitude = la
                    longitude = lo
                    address = ""       // punctul ales manual nu mai e adresa căutată
                },
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()
            )

            Text("Raza locului: $radius m", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = radius.toFloat(),
                onValueChange = { radius = PlaceRules.clampRadius((it / 10f).roundToInt() * 10) },
                valueRange = PlaceRules.MIN_RADIUS.toFloat()..PlaceRules.MAX_RADIUS.toFloat()
            )

            Button(
                onClick = {
                    if (lat != null && lon != null) {
                        onSave(
                            initial.copy(
                                name = name.trim(), latitude = lat, longitude = lon,
                                radiusMeters = radius, address = address
                            )
                        )
                    }
                },
                enabled = problem == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) { Text("Salvează locul") }

            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}