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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import md.utm.digitalmirror.ui.theme.Fraunces
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
fun PlacesScreen(
    onBack: () -> Unit,
    initialFrom: Long? = null,
    initialTo: Long? = null,
    vm: PlacesViewModel = viewModel()
) {
    // deschis de pe un drum din zi: harta pornește cu el
    LaunchedEffect(initialFrom, initialTo) {
        if (initialFrom != null && initialTo != null) vm.openRoute(initialFrom, initialTo)
    }
    val places by vm.places.collectAsState()
    val recentVisits by vm.recentVisits.collectAsState()
    val route by vm.route.collectAsState()
    val homeId by vm.homeId.collectAsState()
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
            isHomeNow = current.id != 0L && current.id == homeId,
            onSave = { place, isHome ->
                vm.save(place, isHome)

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
                    onMode = vm::chooseMode,
                    onUseForAll = vm::useModeForAll
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
                                (if (place.id == homeId) "🏠 acasă · " else "") + "rază ${place.radiusMeters} m" +
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

/** Razele propuse: cât de mare e zona în care contează că „ești acolo”. */
private val RADIUS_PRESETS = listOf(100 to "Casă", 150 to "Clădire", 250 to "Campus")

/** Numele des folosite, ca să nu le scrii de fiecare dată. */
private val NAME_SUGGESTIONS = listOf("Acasă", "Facultate", "Sală", "Serviciu", "Bibliotecă")

/**
 * Locul nou (sau editat): harta mare sus, cu căutarea plutind peste ea; jos numele, raza și „Acesta e Acasă”.
 * Căutarea pornește de la tasta de căutare (Nominatim nu permite sugestii la fiecare literă).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditor(
    initial: Place,
    vm: PlacesViewModel,
    isHomeNow: Boolean,
    onSave: (Place, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val isNew = initial.id == 0L
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf(initial.radiusMeters) }
    var address by rememberSaveable { mutableStateOf(initial.address) }
    var isHome by rememberSaveable { mutableStateOf(isHomeNow) }
    var customRadius by rememberSaveable { mutableStateOf(RADIUS_PRESETS.none { it.first == initial.radiusMeters }) }
    // un loc nou nu are încă punct ales (coordonate 0,0 înseamnă „nealeasă”)
    var latitude by rememberSaveable { mutableStateOf(if (isNew) null else initial.latitude) }
    var longitude by rememberSaveable { mutableStateOf(if (isNew) null else initial.longitude) }
    val search by vm.search.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    val lat = latitude
    val lon = longitude
    val point = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    val problem = PlaceRules.problem(name, point != null)

    fun runSearch() {
        if (query.trim().length >= 3) {
            keyboard?.hide()
            vm.searchAddress(query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Loc nou" else "Editează locul", fontFamily = Fraunces) },
                navigationIcon = { TextButton(onClick = onCancel) { Text("‹ Înapoi") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ---- harta, cu căutarea peste ea ----
            Box(Modifier.fillMaxWidth().weight(1f)) {
                PlaceMap(
                    point = point,
                    radiusMeters = radius,
                    onPick = { la, lo ->
                        latitude = la
                        longitude = lo
                        address = ""       // punctul ales manual nu mai e adresa căutată
                        vm.clearSearch()
                    },
                    modifier = Modifier.fillMaxSize().clipToBounds()
                )

                Column(
                    Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Caută o adresă sau atinge harta") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            trailingIcon = {
                                when {
                                    search.loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    query.isNotEmpty() -> IconButton(onClick = { query = ""; vm.clearSearch() }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Șterge căutarea")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // rezultatele căutării, ca o listă sub bară
                    if (search.hits.isNotEmpty() || search.message != null) {
                        Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                search.message?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                search.hits.forEach { hit ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                latitude = hit.latitude
                                                longitude = hit.longitude
                                                address = hit.label
                                                if (name.isBlank()) name = hit.label.substringBefore(",").take(PlaceRules.MAX_NAME)
                                                query = ""
                                                vm.clearSearch()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Outlined.Place, null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(hit.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }
                }

                if (point == null && search.hits.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    ) {
                        Text(
                            "Atinge harta acolo unde e locul",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ---- detaliile, într-un panou jos ----
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Cum îi spui?", style = MaterialTheme.typography.titleLarge) },
                        textStyle = MaterialTheme.typography.titleLarge,
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (name.isBlank()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NAME_SUGGESTIONS.forEach { s ->
                                SuggestionChip(
                                    onClick = {
                                        name = s
                                        if (s == "Acasă") isHome = true
                                    },
                                    label = { Text(s) }
                                )
                            }
                        }
                    }
                    if (address.isNotBlank()) {
                        Text(
                            address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }

                    // raza: cât de mare e zona în care „ești acolo”
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cât de mare e locul?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RADIUS_PRESETS.forEach { (meters, label) ->
                                FilterChip(
                                    selected = !customRadius && radius == meters,
                                    onClick = { radius = meters; customRadius = false },
                                    label = { Text("$label · $meters m") }
                                )
                            }
                            FilterChip(
                                selected = customRadius,
                                onClick = { customRadius = true },
                                label = { Text(if (customRadius) "Altă · $radius m" else "Altă") }
                            )
                        }
                        if (customRadius) {
                            Slider(
                                value = radius.toFloat(),
                                onValueChange = { radius = PlaceRules.clampRadius((it / 10f).roundToInt() * 10) },
                                valueRange = PlaceRules.MIN_RADIUS.toFloat()..PlaceRules.MAX_RADIUS.toFloat()
                            )
                        }
                    }

                    // „Acesta e Acasă”: de aici pleci, deci pregătirea se pune înainte de plecare
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { isHome = !isHome }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Home, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Acesta e Acasă", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "De aici pleci de obicei; pregătirea de plecare se pune înainte de drum.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isHome, onCheckedChange = { isHome = it })
                    }

                    Button(
                        onClick = {
                            if (lat != null && lon != null) {
                                onSave(
                                    initial.copy(
                                        name = name.trim(), latitude = lat, longitude = lon,
                                        radiusMeters = radius, address = address
                                    ),
                                    isHome
                                )
                            }
                        },
                        enabled = problem == null,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(if (isNew) "Salvează locul" else "Salvează") }

                    if (problem != null) {
                        Text(
                            problem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}
