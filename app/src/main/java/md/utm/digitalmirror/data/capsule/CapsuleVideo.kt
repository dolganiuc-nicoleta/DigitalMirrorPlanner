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
import java.io.File

/**
 * Videoul unei capsule trăiește în spațiul privat al aplicației (filesDir/capsules).
 * Nu intră în copia de rezervă JSON; după o restaurare pe alt telefon capsula rămâne fără video.
 */
object CapsuleVideo {

    private fun dir(context: Context): File = File(context.filesDir, "capsules").apply { mkdirs() }

    // doar numele fișierului, ca un nume modificat să nu iasă din folder
    fun file(context: Context, name: String): File = File(dir(context), File(name).name)

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