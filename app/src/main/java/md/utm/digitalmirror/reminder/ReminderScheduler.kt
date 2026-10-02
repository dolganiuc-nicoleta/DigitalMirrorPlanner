package md.utm.digitalmirror.reminder

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {

    private const val WORK_NAME = "evening_reminder"

    // programează reminder-ul zilnic la ora dată (implicit 21:00)
    fun schedule(context: Context, hour: Int = 21, minute: Int = 0) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, minute)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val delayMinutes = Duration.between(now, next).toMinutes()

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,   // nu reprogramează la fiecare pornire
            request
        )
    }

    // doar pentru test: afișează notificarea imediat
    fun testNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(workDataOf(ReminderWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}