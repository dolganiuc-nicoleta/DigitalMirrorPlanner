package md.utm.digitalmirror.data.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Singura clasă care vorbește cu Firebase Authentication (CF-01, CF-02, CF-05).
 * Restul aplicației nu știe de Firebase: primește doar [Result] cu mesaj în română.
 */
object AuthRepository {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** E-mailul utilizatorului conectat, sau null dacă nu e autentificat. */
    val email: String? get() = auth.currentUser?.email

    val isSignedIn: Boolean get() = auth.currentUser != null

    /** Emite e-mailul curent și la fiecare autentificare sau ieșire din cont. */
    fun emailFlow(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.email) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun register(email: String, password: String): Result<Unit> =
        run { auth.createUserWithEmailAndPassword(email.trim(), password).await() }

    suspend fun signIn(email: String, password: String): Result<Unit> =
        run { auth.signInWithEmailAndPassword(email.trim(), password).await() }

    suspend fun sendPasswordReset(email: String): Result<Unit> =
        run { auth.sendPasswordResetEmail(email.trim()).await() }

    /** Datele de pe telefon rămân; se șterge doar sesiunea. */
    fun signOut() = auth.signOut()

    private suspend fun run(block: suspend () -> Unit): Result<Unit> =
        try {
            block()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(AuthException(message(e), e))
        }

    // mesajele cerute în CF-01 și CF-02; la date greșite nu spunem care dintre e-mail și parolă
    private fun message(e: Exception): String = when (e) {
        is FirebaseAuthUserCollisionException -> "Acest e-mail are deja cont."
        is FirebaseAuthInvalidCredentialsException,
        is FirebaseAuthInvalidUserException -> "E-mail sau parolă greșite."
        is FirebaseTooManyRequestsException -> "Prea multe încercări. Așteaptă puțin și încearcă din nou."
        is FirebaseNetworkException -> "Fără conexiune la internet. Poți continua fără cont."
        // temporar, cât testăm: arătăm codul real al erorii (îl scoatem înainte de pilot)
        else -> "Ceva nu a mers (${(e as? FirebaseAuthException)?.errorCode ?: e.javaClass.simpleName}: ${e.message?.take(120)})."
    }
}

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)