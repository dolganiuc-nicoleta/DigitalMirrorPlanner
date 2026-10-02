package md.utm.digitalmirror.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import md.utm.digitalmirror.data.db.AppDatabase
import java.time.LocalDate

class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val today = LocalDate.now().toString()

        // dacă reflecția de azi e deja completată, nu deranjăm utilizatorul
        val alreadyDone = AppDatabase.get(applicationContext)
            .reflectionDao()
            .getByDate(today) != null

        if (force || !alreadyDone) {
            ReminderNotifications.show(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}