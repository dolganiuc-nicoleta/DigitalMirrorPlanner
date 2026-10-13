package md.utm.digitalmirror.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.domain.ForegroundTime

/**
 * Citește din Android timpul petrecut în aplicații (UsageStatsManager).
 *
 * Accesul nu se cere printr-un dialog obișnuit: utilizatorul îl activează o singură dată
 * în setările telefonului („Acces la utilizarea aplicațiilor”).
 * Android păstrează evenimentele doar câteva zile, de aceea UsageCollector le salvează
 * local imediat ce o sesiune s-a terminat.
 */
object UsageReader {

    // valorile din UsageEvents.Event (ACTIVITY_RESUMED / ACTIVITY_PAUSED)
    private const val EVENT_RESUMED = 1
    private const val EVENT_PAUSED = 2

    // câte ore înaintea intervalului căutăm, ca să prindem aplicațiile deschise de dinainte
    private const val LOOKBACK_MILLIS = 6 * 60 * 60 * 1000L

    data class AppTime(val pkg: String, val millis: Long)

    data class Result(val totalMillis: Long, val apps: List<AppTime>)

    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Deschide ecranul din setările telefonului unde utilizatorul acordă accesul. */
    fun openSettings(context: Context) {
        val withPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(withPackage)
        } catch (e: Exception) {
            // unele telefoane nu acceptă varianta cu pachet: deschidem lista generală
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Timpul în prim-plan pentru fiecare aplicație între [from] și [to] (milisecunde epoch),
     * fără aplicația noastră și fără ecranul principal al telefonului.
     */
    suspend fun foreground(context: Context, from: Long, to: Long): Result? = withContext(Dispatchers.IO) {
        if (!hasPermission(context) || to <= from) return@withContext null

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = mutableListOf<ForegroundTime.Event>()
        try {
            val stream = manager.queryEvents(from - LOOKBACK_MILLIS, to)
            val e = UsageEvents.Event()
            while (stream.hasNextEvent()) {
                stream.getNextEvent(e)
                val type = when (e.eventType) {
                    EVENT_RESUMED -> ForegroundTime.RESUMED
                    EVENT_PAUSED -> ForegroundTime.PAUSED
                    else -> continue
                }
                events += ForegroundTime.Event(e.packageName, type, e.timeStamp)
            }
        } catch (ex: SecurityException) {
            return@withContext null     // accesul a fost retras între timp
        }

        val ignored = setOf(context.packageName, "com.android.systemui") + homePackages(context)
        val perApp = ForegroundTime.compute(events, from, to)
            .filterKeys { it !in ignored }
            .map { AppTime(it.key, it.value) }
            .sortedByDescending { it.millis }

        Result(perApp.sumOf { it.millis }, perApp)
    }

    /** Numele aplicației pentru afișare; dacă Android nu ni-l dă, ultima parte din numele pachetului. */
    fun label(context: Context, pkg: String): String =
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }

    private fun homePackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }
}