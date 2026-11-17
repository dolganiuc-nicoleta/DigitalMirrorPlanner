package md.utm.digitalmirror.data.capsule

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.backup.CloudConfig
import java.io.File

/**
 * Videoul unei capsule trăiește în spațiul privat al aplicației, într-un folder al contului:
 * filesDir/capsules/<uid> (sau filesDir/capsules/local fără cont). Așa, pe un telefon cu mai multe
 * conturi, fiecare își vede doar videourile lui; la ieșire din cont ele rămân pe telefon, ascunse,
 * și reapar când contul intră din nou.
 *
 * Nu intră în copia de rezervă JSON (sunt prea mari); după o restaurare pe alt telefon capsula
 * rămâne fără video.
 */
object CapsuleVideo {

    private const val LOCAL = "local"

    private fun root(context: Context): File = File(context.filesDir, "capsules")

    /**
     * Folderul contului curent. Datele urmează contul doar când cloudul e configurat;
     * altfel datele rămân pe telefon la ieșire, deci și videourile rămân în folderul comun.
     */
    private fun scope(): String =
        if (CloudConfig.isConfigured) AuthRepository.uid ?: LOCAL else LOCAL

    private fun dir(context: Context, scope: String = scope()): File =
        File(root(context), scope).apply { mkdirs() }

    /**
     * Fișierul videoului, în folderul contului curent. Videourile făcute înainte de folderele pe cont
     * stau direct în filesDir/capsules; dacă numele e găsit acolo, îl folosim pe acela.
     */
    fun file(context: Context, name: String): File {
        // doar numele fișierului, ca un nume modificat să nu iasă din folder
        val safe = File(name).name
        val scoped = File(dir(context), safe)
        val legacy = File(root(context), safe)
        return if (!scoped.exists() && legacy.isFile) legacy else scoped
    }

    /**
     * După intrarea în cont: videourile folosite fără cont (și cele vechi, din afara folderelor)
     * trec în folderul contului, împreună cu datele de pe telefon care rămân ale lui.
     */
    suspend fun adoptLocal(context: Context) = withContext(Dispatchers.IO) {
        val uid = AuthRepository.uid ?: return@withContext
        if (!CloudConfig.isConfigured) return@withContext
        val target = dir(context, uid)
        val sources = (root(context).listFiles { f -> f.isFile }.orEmpty().toList() +
                File(root(context), LOCAL).listFiles { f -> f.isFile }.orEmpty().toList())
        sources.forEach { moveInto(it, target) }
    }

    /**
     * Înainte de ieșirea din cont: videourile vechi, din afara folderelor, sunt ale contului care iese,
     * deci le mutăm în folderul lui, ca următorul cont să nu le găsească.
     */
    suspend fun stashLegacy(context: Context) = withContext(Dispatchers.IO) {
        val uid = AuthRepository.uid ?: return@withContext
        val target = dir(context, uid)
        root(context).listFiles { f -> f.isFile }.orEmpty().forEach { moveInto(it, target) }
    }

    private fun moveInto(file: File, target: File) {
        val dest = File(target, file.name)
        if (dest.exists()) return
        if (!file.renameTo(dest)) {
            file.copyTo(dest)
            file.delete()
        }
    }

    fun newFileName(): String = "capsula-${System.currentTimeMillis()}.mp4"

    /** Fișierul există și nu e gol; altfel null (de exemplu după restaurarea pe alt telefon). */
    fun existing(context: Context, name: String?): File? =
        name?.let { file(context, it) }?.takeIf { it.exists() && it.length() > 0 }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun delete(context: Context, name: String?) {
        if (name != null) file(context, name).delete()
    }

    /** Copiază un video ales din galerie în spațiul aplicației. Returnează numele sau null dacă a eșuat. */
    suspend fun importFrom(context: Context, source: Uri): String? = withContext(Dispatchers.IO) {
        val name = newFileName()
        val target = file(context, name)
        try {
            val input = context.contentResolver.openInputStream(source) ?: return@withContext null
            input.use { stream -> target.outputStream().use { out -> stream.copyTo(out) } }
            if (target.length() > 0) name else { target.delete(); null }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }
}

/** Înregistrarea unui video cu aplicația de cameră a telefonului, cel mult 2 minute. */
class RecordVideo : ActivityResultContract<Uri, Boolean>() {

    override fun createIntent(context: Context, input: Uri): Intent =
        Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, 120)
            clipData = ClipData.newRawUri("", input)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}