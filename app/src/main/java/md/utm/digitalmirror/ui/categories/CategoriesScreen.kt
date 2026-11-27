package md.utm.digitalmirror.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MergeType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.domain.CategoryTidy
import md.utm.digitalmirror.ui.components.CategoryColor
import md.utm.digitalmirror.ui.components.CategoryIcons
import md.utm.digitalmirror.ui.theme.Fraunces

private fun parse(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** Semnul unei categorii: iconița în culoarea ei, pe un cerc deschis; fără iconiță, un punct. */
@Composable
fun CategoryBadge(category: Category, size: Int = 40) {
    val color = parse(CategoryColor.hex(category))
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        val icon = CategoryIcons.of(category)
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.55f).dp))
        else Box(Modifier.size((size * 0.3f).dp).clip(CircleShape).background(color))
    }
}

/**
 * „Categoriile mele”: fiecare cu iconița, culoarea și câte activități are; apeși ca s-o schimbi.
 * Sus, dublurile găsite singure („Laborator” și „Laboratoare”), cu „Unește” dintr-o atingere.
 * Mai puține categorii, cu mai multe activități în fiecare, înseamnă propuneri mai bune, mai repede.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: CategoriesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var editing by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categoriile mele", fontFamily = Fraunces) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Înapoi") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Categorie nouă") }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Cu mai puține categorii, fiecare adună mai multe activități, iar aplicația învață mai repede cât îți iau lucrurile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // dublurile, găsite singure
            items(state.duplicates, key = { "dup-${it.drop.id}" }) { pair ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("„${pair.drop.name}” și „${pair.keep.name}” par același lucru", fontWeight = FontWeight.Medium)
                            Text(
                                "Le unesc în „${pair.keep.name}”, cu toate activitățile.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        FilledTonalButton(onClick = { vm.merge(pair.drop.id, pair.keep.id) }) { Text("Unește") }
                    }
                }
            }

            items(state.rows, key = { it.category.id }) { row ->
                Surface(
                    onClick = { editing = row.category },
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(row.category)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.category.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (row.count) {
                                    0 -> "nicio activitate încă"
                                    1 -> "o activitate"
                                    else -> "${row.count} activități"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        CategorySheet(
            initial = null,
            others = state.rows.map { it.category },
            onDismiss = { creating = false },
            onSave = { name, color, icon -> vm.add(name, color, icon); creating = false },
            onMerge = {},
            onDelete = {}
        )
    }
    editing?.let { category ->
        CategorySheet(
            initial = category,
            others = state.rows.map { it.category }.filter { it.id != category.id },
            onDismiss = { editing = null },
            onSave = { name, color, icon -> vm.save(category, name, color, icon); editing = null },
            onMerge = { editing = null; merging = category },
            onDelete = { editing = null; deleting = category }
        )
    }

    // „Unește cu…”: alegi categoria în care trec toate activitățile acesteia
    merging?.let { from ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Unește „${from.name}” cu…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Toate activitățile din „${from.name}” trec în categoria aleasă, iar „${from.name}” dispare.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.rows.map { it.category }.filter { it.id != from.id }.forEach { into ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { vm.merge(from.id, into.id); merging = null }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryBadge(into, size = 32)
                            Spacer(Modifier.width(10.dp))
                            Text(into.name)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Anulează") } }
        )
    }

    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Ștergi „${category.name}”?") },
            text = { Text("Activitățile din ea rămân, dar fără categorie. Dacă e același lucru ca alta, mai bine o unești.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(category); deleting = null }) {
                    Text("Șterge", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anulează") } }
        )
    }
}

/** Panoul unei categorii: nume, culoare, iconiță; la una existentă și „Unește cu…” / „Șterge”. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategorySheet(
    initial: Category?,
    others: List<Category>,
    onDismiss: () -> Unit,
    onSave: (name: String, colorHex: String, icon: String?) -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.let(CategoryColor::hex) ?: CategoryColor.CHOICES.first()) }
    var icon by remember { mutableStateOf(initial?.let { it.icon ?: CategoryTidy.suggestIcon(it.name) }) }
    var iconTouched by remember { mutableStateOf(initial?.icon != null) }
    // la una nouă, iconița se ghicește din nume până alegi tu alta
    LaunchedEffect(name) { if (!iconTouched) icon = CategoryTidy.suggestIcon(name) }
    val duplicate = others.any { it.name.trim().equals(name.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(Category(id = initial?.id ?: 0, name = name, colorHex = color, icon = icon), size = 48)
                Spacer(Modifier.width(12.dp))
                Text(if (initial == null) "Categorie nouă" else "Categoria", fontFamily = Fraunces, style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nume") },
                singleLine = true,
                isError = duplicate,
                supportingText = { if (duplicate) Text("Există deja o categorie cu numele ăsta.") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Culoare", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryColor.CHOICES.forEach { hex ->
                        val selected = hex.equals(color, ignoreCase = true)
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(parse(hex))
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = hex },
                            contentAlignment = Alignment.Center
                        ) { if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Iconiță", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryIcons.ALL.forEach { (key, vector) ->
                        IconChoice(vector, selected = icon == key, tint = parse(color)) { icon = key; iconTouched = true }
                    }
                }
            }

            Button(
                onClick = { onSave(name, color, icon) },
                enabled = name.isNotBlank() && !duplicate,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (initial == null) "Adaugă categoria" else "Salvează") }

            if (initial != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (others.isNotEmpty()) {
                        OutlinedButton(onClick = onMerge, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.MergeType, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Unește cu…")
                        }
                    }
                    TextButton(onClick = onDelete) { Text("Șterge", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun IconChoice(vector: ImageVector, selected: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) tint.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(2.dp, tint, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(vector, null, tint = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
