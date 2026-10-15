package md.utm.digitalmirror.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import md.utm.digitalmirror.domain.AuthRules

/**
 * Autentificare și cont nou (CF-01, CF-02, CF-04).
 *
 * @param allowSkip arată „Continuă fără cont” (doar la prima pornire)
 * @param onDone apelat după autentificare sau după ce utilizatorul alege să continue fără cont
 * @param onBack apelat la „Înapoi”; null = ecranul nu are înapoi (prima pornire)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    allowSkip: Boolean,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    vm: AuthViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    // numărătoarea inversă a pauzei de după prea multe încercări greșite
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.lockedUntil) {
        while (now < state.lockedUntil) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    val secondsLeft = ((state.lockedUntil - now + 999) / 1000).toInt().coerceAtLeast(0)
    val locked = secondsLeft > 0

    val problem = vm.problem(state)
    val canSubmit = problem == null && !state.loading && !locked

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("‹ Înapoi") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (state.isRegister) "Creează-ți contul" else "Bine ai revenit",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (state.isRegister) "Durează un minut. Apoi îți setăm ziua."
                else "Intră în cont ca să-ți păstrezi contul legat de aplicație.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            TabRow(selectedTabIndex = if (state.isRegister) 1 else 0) {
                Tab(selected = !state.isRegister, onClick = { vm.setRegister(false) }, text = { Text("Autentificare") })
                Tab(selected = state.isRegister, onClick = { vm.setRegister(true) }, text = { Text("Cont nou") })
            }

            val emailWrong = state.email.length > 3 && !AuthRules.isEmailValid(state.email)
            OutlinedTextField(
                value = state.email,
                onValueChange = vm::setEmail,
                label = { Text("Adresa de e-mail") },
                singleLine = true,
                isError = emailWrong,
                supportingText = { if (emailWrong) Text("Adresa nu pare completă. Verifică @ și domeniul.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            val passwordTransformation: VisualTransformation =
                if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::setPassword,
                label = { Text("Parola") },
                supportingText = { Text("Minimum ${AuthRules.MIN_PASSWORD} caractere") },
                singleLine = true,
                visualTransformation = passwordTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = vm::toggleShowPassword) {
                        Text(if (state.showPassword) "Ascunde" else "Arată")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.isRegister) {
                val mismatch = state.repeat.isNotEmpty() && state.repeat != state.password
                OutlinedTextField(
                    value = state.repeat,
                    onValueChange = vm::setRepeat,
                    label = { Text("Repetă parola") },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("Parolele nu coincid încă.") },
                    visualTransformation = passwordTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!state.isRegister) {
                TextButton(onClick = vm::forgotPassword, enabled = !state.loading) {
                    Text("Ai uitat parola?")
                }
            }

            // eroarea de la server, sau pauza de după prea multe încercări
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            state.info?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            if (locked) {
                Text(
                    "Prea multe încercări greșite. Încearcă din nou peste $secondsLeft secunde.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = { vm.submit(onSuccess = onDone) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (state.isRegister) "Creează cont" else "Intră în cont")
                }
            }
            // butonul inactiv spune ce lipsește, în loc să dea o eroare după apăsare
            if (problem != null && !state.loading) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            if (allowSkip) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { vm.continueWithoutAccount(onDone) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("Continuă fără cont") }
                Text(
                    "Fără cont, toate datele rămân doar pe acest telefon. Dacă îl pierzi și nu ai făcut o copie, ele se pierd.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}