package md.utm.digitalmirror.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Sincronizarea în fundal: periodic (la 15 minute, cât permite Android) și imediat ce e nevoie
 * (la deschiderea aplicației, când o lași în fundal). Rulează doar cu internet și cu un cont conectat.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!SyncEngine.isPossible) return Result.success()
        return try {
            SyncEngine.sync(applicationContext)
            Result.success()
        } catch (e: SyncEngine.SyncException) {
            Log.w("SyncWorker", "sincronizarea nu a mers: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.w("SyncWorker", "eroare la sincronizare", e)
            Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val NOW = "sync-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Programează sincronizarea periodică (o singură dată; dacă există deja, rămâne). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build()
            )
        }

        /** O sincronizare acum (sau imediat ce apare internetul); cererile repetate se unesc într-una. */
        fun runNow(context: Context) {
            if (!SyncEngine.isPossible) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            )
        }
    }
}
