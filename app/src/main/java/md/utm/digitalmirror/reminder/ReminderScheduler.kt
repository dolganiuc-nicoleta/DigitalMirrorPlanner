package md.utm.digitalmirror.reminder

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {

    private const val WORK_NAME = "evening_reminder"

    /**
     * Programează reminder-ul zilnic după setările salvate.
     * replaceExisting = false la pornirea aplicației (păstrează programarea existentă),
     * true când utilizatorul schimbă ora din Setări.
     */
    fun schedule(context: Context, replaceExisting: Boolean = false) {
        val workManager = WorkManager.getInstance(context)

        if (!ReminderPrefs.isEnabled(context)) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val minute = ReminderPrefs.minuteOfDay(context)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(minute / 60, minute % 60)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val delayMinutes = Duration.between(now, next).toMinutes()

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replaceExisting) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else ExistingPeriodicWorkPolicy.KEEP,
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