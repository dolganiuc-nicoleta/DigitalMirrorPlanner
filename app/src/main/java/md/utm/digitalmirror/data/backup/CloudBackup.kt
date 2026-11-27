package md.utm.digitalmirror.data.backup

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Adresa serverului vine din local.properties (CLOUD_URL=https://...), nu din cod, ca să nu
 * ajungă în Git. Cât timp e goală, butoanele de cloud nu apar în aplicație.
 */
object CloudConfig {
    val BASE_URL: String = BuildConfig.CLOUD_URL
    val isConfigured: Boolean get() = BASE_URL.isNotBlank()
}

/**
 * Trimite și primește copia de rezervă de la server. Identitatea vine din tokenul Firebase
 * al contului conectat; serverul nu primește niciun uid de la aplicație.
 */
object CloudBackup {

    class Info(val exists: Boolean, private val savedAt: String?, val bytes: Long) {
        fun savedAtLabel(): String = try {
            OffsetDateTime.parse(savedAt)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d.MM.yyyy, HH:mm"))
        } catch (e: Exception) {
            savedAt ?: ""
        }
    }

    internal class Response(val code: Int, val body: ByteArray)

    suspend fun info(): Info = withContext(Dispatchers.IO) {
        val r = send("GET", "/backup/info", null)
        check(r)
        val json = JSONObject(String(r.body, Charsets.UTF_8))
        Info(json.optBoolean("exists", false), json.optString("savedAt").ifEmpty { null }, json.optLong("bytes", 0))
    }

    /** Face copia și o trimite. Returnează numărul de rânduri. */
    suspend fun upload(context: Context): Int = withContext(Dispatchers.IO) {
        val (text, rows) = BackupManager.createJson(context)
        val r = send("PUT", "/backup", text.toByteArray(Charsets.UTF_8))
        check(r)
        rows
    }

    /** Descarcă ultima copie și o verifică; nu schimbă nimic până nu confirmă utilizatorul. */
    suspend fun download(context: Context): BackupManager.Preview = withContext(Dispatchers.IO) {
        val r = send("GET", "/backup", null)
        if (r.code == 404) throw BackupManager.BackupException("Nu ai nicio copie în cloud.")
        check(r)
        BackupManager.parse(context, String(r.body, Charsets.UTF_8))
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        check(send("DELETE", "/backup", null))
    }

    // ---- ajutătoare ----

    private fun check(r: Response) {
        when (r.code) {
            in 200..299 -> Unit
            401 -> throw BackupManager.BackupException("Serverul nu a acceptat contul. Ieși din cont și intră din nou.")
            403 -> throw BackupManager.BackupException("Contul este dezactivat.")
            413 -> throw BackupManager.BackupException("Copia este prea mare pentru server.")
            else -> throw BackupManager.BackupException("Serverul a răspuns cu eroarea ${r.code}.")
        }
    }

    private suspend fun token(forceRefresh: Boolean): String {
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw BackupManager.BackupException("Nu ești conectat la cont.")
        return user.getIdToken(forceRefresh).await().token
            ?: throw BackupManager.BackupException("Nu am putut verifica contul.")
    }

    /** Dacă serverul zice 401 (token expirat), cerem unul nou o singură dată. */
    internal suspend fun send(method: String, path: String, body: ByteArray?): Response {
        if (!CloudConfig.isConfigured) throw BackupManager.BackupException("Serverul nu este configurat.")
        val first = call(method, path, token(false), body)
        if (first.code != 401) return first
        return call(method, path, token(true), body)
    }

    private fun call(method: String, path: String, token: String, body: ByteArray?): Response {
        val conn = URL(CloudConfig.BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, bytes)
        } catch (e: IOException) {
            Log.w("CloudBackup", "Conexiune eșuată: ${e.message}", e)
            throw BackupManager.BackupException("Nu pot contacta serverul. Verifică internetul.")
        } finally {
            conn.disconnect()
        }
    }
}