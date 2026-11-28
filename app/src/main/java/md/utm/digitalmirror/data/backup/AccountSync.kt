package md.utm.digitalmirror.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.capsule.CapsuleVideo
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.geo.GeofenceManager
import md.utm.digitalmirror.data.prefs.MealPrefs
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import md.utm.digitalmirror.ui.theme.Accent
import md.utm.digitalmirror.ui.theme.ThemeSettings
import java.time.LocalDate

/**
 * Datele urmează contul, nu telefonul.
 *
 * La ieșire din cont: copia merge în cloud, apoi datele se șterg de pe telefon. Dacă salvarea
 * nu reușește, nu se șterge nimic și nu se iese din cont, ca să nu se piardă date.
 * La intrare: dacă telefonul nu are date proprii și contul are o copie în cloud, o aducem.
 * Dacă telefonul are deja date (de ex. folosit fără cont), nu le suprascriem.
 */
object AccountSync {

    sealed interface Outcome {
        /** Datele contului au fost aduse din cloud; [summary] e de forma „124 activități · 5 categorii”. */
        data class Restored(val summary: String) : Outcome
        object NothingInCloud : Outcome
        /** Telefonul are date proprii, deci copia din cloud nu a fost atinsă. */
        object KeptLocal : Outcome
        object Skipped : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Ieșirea din cont. Dacă serverul e configurat și utilizatorul e conectat, salvează întâi
     * în cloud și abia apoi șterge datele de pe telefon. La orice eroare, nu schimbă nimic.
     */
    suspend fun signOut(context: Context): Result<Unit> = try {
        if (CloudConfig.isConfigured && AuthRepository.isSignedIn) {
            CloudBackup.upload(context)
            // videourile capsulelor nu urcă în cloud; rămân pe telefon, în folderul contului, ascunse de alte conturi
            CapsuleVideo.stashLegacy(context)
            wipeLocal(context)
        }
        AuthRepository.signOut()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** După autentificare: aduce datele contului din cloud, dacă telefonul nu are date proprii. */
    suspend fun afterSignIn(context: Context): Outcome {
        if (!CloudConfig.isConfigured) return Outcome.Skipped
        val outcome = try {
            val info = CloudBackup.info()
            when {
                !info.exists -> Outcome.NothingInCloud
                hasUserData(context) -> Outcome.KeptLocal
                else -> {
                    val preview = CloudBackup.download(context)
                    BackupManager.restore(context, preview)
                    Outcome.Restored(preview.describe())
                }
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "Eroare necunoscută.")
        }
        // datele de pe telefon rămân ale contului, deci și videourile capsulelor lor
        if (outcome !is Outcome.Restored) runCatching { CapsuleVideo.adoptLocal(context) }
        return outcome
    }

    // ---- ajutătoare ----

    /** Șterge datele de pe telefon și oprește alarmele, notificările și zonele legate de ele. */
    private suspend fun wipeLocal(context: Context) {
        val db = AppDatabase.get(context)
        val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())

        // alarmele și notificarea cronometrului, cât timp activitățile încă există
        repo.upcomingPlanned(LocalDate.now().toString())
            .forEach { ActivityReminders.onActivityClosed(context, it.id) }
        repo.runningSession()?.let { ActivityReminders.onActivityClosed(context, it.activityId) }

        // clearAllTables nu are voie să ruleze pe firul principal
        withContext(Dispatchers.IO) { db.clearAllTables() }

        // următorul cont începe de la configurarea inițială, dacă nu are o copie în cloud
        OnboardingPrefs.reset(context)

        // culoarea aplicației e o preferință a contului: următorul cont pornește de la cea implicită
        ThemeSettings.setAccent(context, Accent.GREEN)

        // „acasă” și pregătirea de plecare sunt ale contului (locurile lui tocmai s-au șters)
        TravelPrefs.setHomePlaceId(context, null)
        TravelPrefs.setPrepMinutes(context, TravelPrefs.DEFAULT_PREP_MINUTES)
        MealPrefs.reset(context)
        md.utm.digitalmirror.data.prefs.SleepPrefs.reset(context)
        TravelPrefs.setRouteModes(context, emptyMap())                   // drumurile erau între locurile contului
        md.utm.digitalmirror.data.ai.AiPrefs.setConsent(context, false)  // acordul pentru AI e al fiecărui cont

        // fără locuri, zonele de detectare se scot
        runCatching { GeofenceManager.sync(context) }
    }

    /** Are telefonul date ale utilizatorului? Categoriile contează: se creează la configurarea inițială. */
    private suspend fun hasUserData(context: Context): Boolean = withContext(Dispatchers.IO) {
        val sdb = AppDatabase.get(context).openHelper.readableDatabase
        listOf("activity", "category", "goal", "place", "week_task", "prep_event", "reflection").any { table ->
            runCatching {
                sdb.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst() && it.getLong(0) > 0 }
            }.getOrDefault(false)
        }
    }
}