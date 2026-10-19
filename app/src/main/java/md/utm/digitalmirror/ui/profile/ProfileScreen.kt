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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.ui.theme.Accent
import md.utm.digitalmirror.ui.theme.ThemeSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenAuth: () -> Unit
) {
    val context = LocalContext.current
    // e-mailul se schimbă singur la autentificare și la ieșire din cont
    val email by AuthRepository.emailFlow().collectAsState(initial = AuthRepository.email)
    var confirmSignOut by remember { mutableStateOf(false) }

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
                        } else {
                            Button(onClick = onOpenAuth) { Text("Autentifică-te sau creează cont") }
                        }
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
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Ieși din cont?") },
            text = { Text("Activitățile și celelalte date rămân pe telefon. Te poți autentifica oricând din nou.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    AuthRepository.signOut()
                }) { Text("Ieși din cont") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Anulează") }
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