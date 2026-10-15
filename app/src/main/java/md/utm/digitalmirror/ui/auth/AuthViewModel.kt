package md.utm.digitalmirror.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.prefs.AuthPrefs
import md.utm.digitalmirror.domain.AuthRules
import md.utm.digitalmirror.domain.LoginThrottle

data class AuthUiState(
    val isRegister: Boolean = false,
    val email: String = "",
    val password: String = "",
    val repeat: String = "",
    val showPassword: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,        // eroarea de la server (parolă greșită, fără internet…)
    val info: String? = null,         // de ex. „Ți-am trimis un e-mail pentru parolă”
    val lockedUntil: Long = 0L        // până când e blocat butonul, după prea multe încercări
)

class AuthViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    private val throttle = LoginThrottle()

    fun setRegister(value: Boolean) = _state.update { it.copy(isRegister = value, error = null, info = null) }
    fun setEmail(value: String) = _state.update { it.copy(email = value, error = null, info = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun setRepeat(value: String) = _state.update { it.copy(repeat = value, error = null) }
    fun toggleShowPassword() = _state.update { it.copy(showPassword = !it.showPassword) }

    /** Ce lipsește din formular, sau null dacă se poate trimite. */
    fun problem(s: AuthUiState): String? =
        AuthRules.problem(s.email, s.password, if (s.isRegister) s.repeat else null)

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        val now = System.currentTimeMillis()
        if (s.loading || problem(s) != null || throttle.isLocked(now)) return

        _state.update { it.copy(loading = true, error = null, info = null) }
        viewModelScope.launch {
            val result = if (s.isRegister) AuthRepository.register(s.email, s.password)
            else AuthRepository.signIn(s.email, s.password)

            result.onSuccess {
                throttle.recordSuccess()
                AuthPrefs.setSkipped(app, false)
                _state.update { it.copy(loading = false) }
                onSuccess()
            }.onFailure { e ->
                // doar la autentificare numărăm încercările greșite
                if (!s.isRegister) throttle.recordFailure(System.currentTimeMillis())
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message,
                        lockedUntil = System.currentTimeMillis() + throttle.remainingMillis(System.currentTimeMillis())
                    )
                }
            }
        }
    }

    fun forgotPassword() {
        val s = _state.value
        if (!AuthRules.isEmailValid(s.email)) {
            _state.update { it.copy(error = "Scrie mai întâi adresa de e-mail.", info = null) }
            return
        }
        viewModelScope.launch {
            AuthRepository.sendPasswordReset(s.email)
                .onSuccess { _state.update { it.copy(info = "Dacă adresa are cont, ți-am trimis un e-mail pentru parolă.", error = null) } }
                .onFailure { e -> _state.update { it.copy(error = e.message, info = null) } }
        }
    }

    /** CF-04: utilizare fără cont. Datele rămân doar pe telefon. */
    fun continueWithoutAccount(onDone: () -> Unit) {
        AuthPrefs.setSkipped(app, true)
        onDone()
    }

    private fun MutableStateFlow<AuthUiState>.update(block: (AuthUiState) -> AuthUiState) {
        value = block(value)
    }
}