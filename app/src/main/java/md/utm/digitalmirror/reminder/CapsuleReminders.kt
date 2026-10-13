package md.utm.digitalmirror.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import md.utm.digitalmirror.MainActivity
import md.utm.digitalmirror.data.capsule.GoalEvaluator
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.TimeCapsule
import md.utm.digitalmirror.domain.CapsuleLogic
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Notificările capsulei timpului: „se poate deschide” în ziua stabilită și, doar dacă utilizatorul
 * a cerut asta, o amintire blândă când ritmul obiectivelor a scăzut. O verificare pe zi, dimineața.
 */
object CapsuleReminders {

    const val CHANNEL = "capsule"
    const val EXTRA_OPEN_CAPSULE = "open_capsule"
    private const val WORK_NAME = "capsule_check"
    private const val CHECK_HOUR = 10

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Capsula timpului", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Când se poate deschide o capsulă și amintiri blânde"
            }
        )
    }

    /** Programează verificarea zilnică; apelul repetat nu schimbă programarea existentă. */
    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(CHECK_HOUR, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val request = PeriodicWorkRequestBuilder<CapsuleWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Doar pentru test: face verificarea imediat. */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<CapsuleWorker>().build())
    }

    fun showReady(context: Context, capsule: TimeCapsule) = notify(
        context, capsule.id,
        "Capsula „${capsule.title}” se poate deschide",
        "Vezi ce ți-ai scris și ce s-a întâmplat de atunci."
    )

    fun showNudge(context: Context, capsule: TimeCapsule, nudge: CapsuleLogic.Nudge) = notify(
        context, capsule.id,
        "Un mesaj de la tine",
        when (nudge) {
            CapsuleLogic.Nudge.STAGNANT ->
                "S-a mai oprit ritmul, se întâmplă. Ai lăsat „${capsule.title}” pentru zile ca asta. Vrei să o deschizi acum?"
            else ->
                "Ritmul a scăzut puțin. Ai lăsat „${capsule.title}” pentru asemenea zile. Vrei să o deschizi acum?"
        }
    )

    @SuppressLint("MissingPermission")   // verificăm permisiunea mai jos
    private fun notify(context: Context, capsuleId: Long, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val open = PendingIntent.getActivity(
            context, 20_000 + capsuleId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_CAPSULE, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        NotificationManagerCompat.from(context).notify(
            20_000 + capsuleId.toInt(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

/** Verificarea zilnică: capsule care s-au deschis și capsule la care merită o amintire. */
class CapsuleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val dao = db.capsuleDao()
        val sealed = dao.getCapsules().filter { it.openedAt == null }
        if (sealed.isEmpty()) return Result.success()

        val today = LocalDate.now()
        val goalDao = db.goalDao()
        val goals = goalDao.observeGoals().first()
        val results = GoalEvaluator.evaluate(
            goals, goalDao.observeLinks().first(), goalDao.observeClosed().first(), today
        )

        for (capsule in sealed) {
            val state = CapsuleLogic.state(LocalDate.parse(capsule.unlockDate), opened = false, today = today)

            if (state == CapsuleLogic.State.READY) {
                if (!capsule.readyNotified) {
                    CapsuleReminders.showReady(applicationContext, capsule)
                    dao.markReadyNotified(capsule.id)
                }
                continue
            }

            val momenta = GoalEvaluator.activeMomenta(
                dao.getLinks(capsule.id).map { it.goalId }, goals, results
            )
            val nudge = CapsuleLogic.nudge(
                state = state,
                openWhenSlowing = capsule.openWhenSlowing,
                createdOn = LocalDate.parse(capsule.startDate),
                lastNudge = capsule.lastNudgeDate?.let { LocalDate.parse(it) },
                momenta = momenta,
                today = today
            )
            if (nudge != CapsuleLogic.Nudge.NONE) {
                CapsuleReminders.showNudge(applicationContext, capsule, nudge)
                dao.markNudged(capsule.id, today.toString())
            }
        }
        return Result.success()
    }
}