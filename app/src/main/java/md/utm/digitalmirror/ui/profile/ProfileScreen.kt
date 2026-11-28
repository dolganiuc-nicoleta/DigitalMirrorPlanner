package md.utm.digitalmirror.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.backup.AccountSync
import md.utm.digitalmirror.data.backup.CloudConfig
import md.utm.digitalmirror.ui.theme.Accent
import md.utm.digitalmirror.ui.theme.ThemeSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenAuth: () -> Unit,
    onOpenPlaces: () -> Unit = {},
    onOpenCategories: () -> Unit = {}
) {
    val context = LocalContext.current
    // e-mailul se schimbă singur la autentificare și la ieșire din cont
    val email by AuthRepository.emailFlow().collectAsState(initial = AuthRepository.email)
    var confirmSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var signOutError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val name = if (email == null) "Fără cont"
    else AuthRepository.displayName?.takeIf { it.isNotBlank() } ?: email!!.substringBefore('@')

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profil") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Setări")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // contul
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    email ?: "Datele sunt doar pe acest telefon",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (email != null) {
                            OutlinedButton(onClick = { confirmSignOut = true }) { Text("Ieși din cont") }
                            // sincronizarea cu desktopul (și alte dispozitive), prin serverul tău
                            if (CloudConfig.isConfigured) {
                                var lastSync by remember { mutableLongStateOf(md.utm.digitalmirror.data.sync.SyncPrefs.lastSync(context)) }
                                var syncing by remember { mutableStateOf(false) }
                                var syncMessage by remember { mutableStateOf<String?>(null) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        syncMessage ?: if (lastSync == 0L) "Încă nesincronizat cu desktopul"
                                        else "Sincronizat la " + java.time.Instant.ofEpochMilli(lastSync)
                                            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        enabled = !syncing,
                                        onClick = {
                                            syncing = true
                                            scope.launch {
                                                syncMessage = try {
                                                    val r = md.utm.digitalmirror.data.sync.SyncEngine.sync(context)
                                                    lastSync = md.utm.digitalmirror.data.sync.SyncPrefs.lastSync(context)
                                                    "Trimise ${r.pushed}, primite ${r.pulled}" + if (r.conflicts > 0) " · ${r.conflicts} conflicte" else ""
                                                } catch (e: Exception) {
                                                    e.message ?: "Sincronizarea nu a mers."
                                                }
                                                syncing = false
                                            }
                                        }
                                    ) { Text(if (syncing) "Se sincronizează…" else "Sincronizează acum") }
                                }
                            }
                        } else {
                            Button(onClick = onOpenAuth) { Text("Autentifică-te sau creează cont") }
                        }
                    }
                }
            }

            // locurile și drumurile, la un pas (nu ascunse în Setări)
            item {
                Card(onClick = onOpenPlaces, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Locurile și drumurile tale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Harta, cât durează fiecare drum și cum te deplasezi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }

            // categoriile: culori, iconițe, dubluri
            item {
                Card(onClick = onOpenCategories, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Category, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Categoriile mele", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Culori, iconițe și dublurile de unit",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }

            // culoarea aplicației
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Culoarea aplicației", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Accent.entries.forEach { accent ->
                                AccentDot(
                                    accent = accent,
                                    selected = accent == ThemeSettings.accent,
                                    onClick = { ThemeSettings.setAccent(context, accent) }
                                )
                            }
                        }
                        Text(
                            ThemeSettings.accent.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = {
                if (!signingOut) {
                    confirmSignOut = false
                    signOutError = null
                }
            },
            title = { Text("Ieși din cont?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (CloudConfig.isConfigured)
                            "Datele tale se salvează în cloud și se șterg de pe acest telefon. Le recuperezi când te conectezi din nou. " +
                                    "Videourile din capsule nu urcă în cloud: rămân ascunse pe acest telefon și reapar când intri din nou aici."
                        else
                            "Activitățile și celelalte date rămân pe telefon. Te poți autentifica oricând din nou."
                    )
                    signOutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !signingOut,
                    onClick = {
                        scope.launch {
                            signingOut = true
                            signOutError = null
                            AccountSync.signOut(context)
                                .onSuccess {
                                    confirmSignOut = false
                                    onOpenAuth()
                                }
                                .onFailure {
                                    signOutError = "Nu am putut salva în cloud, așa că am rămas în cont ca să nu pierzi date. " +
                                            (it.message ?: "")
                                }
                            signingOut = false
                        }
                    }
                ) { Text(if (signingOut) "Se salvează…" else "Ieși din cont") }
            },
            dismissButton = {
                TextButton(
                    enabled = !signingOut,
                    onClick = {
                        confirmSignOut = false
                        signOutError = null
                    }
                ) { Text("Anulează") }
            }
        )
    }
}

@Composable
private fun AccentDot(accent: Accent, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(accent.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = accent.label, tint = Color.White)
    }
}