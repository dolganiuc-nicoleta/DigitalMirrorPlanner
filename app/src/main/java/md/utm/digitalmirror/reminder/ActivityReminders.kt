package md.utm.digitalmirror.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
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
import md.utm.digitalmirror.MainActivity
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Notificările legate de activități, cu butoane, ca utilizatorul să nu fie nevoit să deschidă aplicația:
 *  - la ora planificată:          „Începi Laborator broker?”      [Start] [Peste 15 min]
 *  - cât rulează cronometrul:     „Laborator broker · 0:42:15”    [Pauză] [Gata]
 *  - la finalul duratei planificate: „Ai terminat Laborator broker?” [Gata] [+15 min] [Parțial]
 *
 * Fiecare activitate are o singură notificare, care se înlocuiește de la o etapă la alta.
 */
object ActivityReminders {

    const val CHANNEL_ACTIVITIES = "activity_reminders"
    const val CHANNEL_TIMER = "activity_timer"

    // alarmele programate
    const val ACTION_ALARM_START = "md.utm.digitalmirror.ALARM_START"
    const val ACTION_ALARM_END = "md.utm.digitalmirror.ALARM_END"

    // butoanele din notificări
    const val ACTION_START = "md.utm.digitalmirror.START"
    const val ACTION_SNOOZE = "md.utm.digitalmirror.SNOOZE"
    const val ACTION_PAUSE = "md.utm.digitalmirror.PAUSE"
    const val ACTION_DONE = "md.utm.digitalmirror.DONE"
    const val ACTION_EXTEND = "md.utm.digitalmirror.EXTEND"
    const val ACTION_PARTIAL = "md.utm.digitalmirror.PARTIAL"

    const val EXTRA_ID = "activity_id"
    const val SNOOZE_MINUTES = 15
    private const val MINUTE = 60_000L

    // ---------- canale ----------

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVITIES, "Activități", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Începutul și sfârșitul activităților planificate"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Cronometru", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Activitatea în desfășurare"
            }
        )
    }

    // ---------- momentele importante din viața unei activități ----------

    /** Activitate nouă, editată sau amânată: (re)programăm alarma de început. */
    fun onActivityPlanned(context: Context, activity: Activity) {
        cancelAlarm(context, activity.id, ACTION_ALARM_START)
        startTimeMillis(activity)?.let { at ->
            if (activity.state == "PLANNED" && at > System.currentTimeMillis()) {
                setAlarm(context, at, alarmIntent(context, activity.id, ACTION_ALARM_START))
            }
        }
    }

    /** Cronometrul a pornit: afișăm notificarea cu cronometru și programăm „Ai terminat?”. */
    fun onTimerStarted(context: Context, activity: Activity, startedAt: Long, closedMillis: Long) {
        cancelAlarm(context, activity.id, ACTION_ALARM_START)
        showRunning(context, activity, chronometerBase = startedAt - closedMillis)

        val planned = activity.userEstimatedMinutes ?: activity.estimatedMinutes
        cancelAlarm(context, activity.id, ACTION_ALARM_END)
        if (planned != null) {
            val endAt = startedAt + planned * MINUTE - closedMillis
            if (endAt > System.currentTimeMillis()) {
                setAlarm(context, endAt, alarmIntent(context, activity.id, ACTION_ALARM_END))
            }
        }
    }

    /** Cronometrul s-a oprit (pauză): scoatem notificarea și alarma de sfârșit. */
    fun onTimerStopped(context: Context, activityId: Long) {
        cancelAlarm(context, activityId, ACTION_ALARM_END)
        cancelNotification(context, activityId)
    }

    /** Activitate finalizată, abandonată sau ștearsă: nu mai trebuie nicio notificare. */
    fun onActivityClosed(context: Context, activityId: Long) {
        cancelAlarm(context, activityId, ACTION_ALARM_START)
        cancelAlarm(context, activityId, ACTION_ALARM_END)
        cancelNotification(context, activityId)
    }

    /**
     * La pornirea aplicației: alarmele se pierd la repornirea telefonului,
     * așa că le reprogramăm pe toate și refacem notificarea cronometrului, dacă rulează.
     */
    suspend fun rescheduleAll(context: Context) {
        val db = AppDatabase.get(context)
        val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())

        repo.upcomingPlanned(LocalDate.now().toString()).forEach { onActivityPlanned(context, it) }

        repo.runningSession()?.let { session ->
            repo.getActivity(session.activityId)?.let { activity ->
                onTimerStarted(context, activity, session.startedAt, repo.closedMillis(activity.id))
            }
        }
    }

    // ---------- alarmele ----------

    fun snooze(context: Context, activityId: Long) {
        cancelNotification(context, activityId)
        setAlarm(
            context,
            System.currentTimeMillis() + SNOOZE_MINUTES * MINUTE,
            alarmIntent(context, activityId, ACTION_ALARM_START)
        )
    }

    fun extend(context: Context, activity: Activity, chronometerBase: Long) {
        showRunning(context, activity, chronometerBase)
        setAlarm(
            context,
            System.currentTimeMillis() + SNOOZE_MINUTES * MINUTE,
            alarmIntent(context, activity.id, ACTION_ALARM_END)
        )
    }

    private fun startTimeMillis(activity: Activity): Long? {
        val minute = activity.plannedStartMinute ?: return null
        return LocalDate.parse(activity.date)
            .atTime(LocalTime.of(minute / 60, minute % 60))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // alarmă exactă dacă e permisă; altfel una aproximativă, într-o fereastră de 5 minute
    private fun setAlarm(context: Context, triggerAt: Long, intent: PendingIntent) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 5 * MINUTE, intent)
        }
    }

    private fun cancelAlarm(context: Context, activityId: Long, action: String) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, activityId, action))
    }

    private fun alarmIntent(context: Context, activityId: Long, action: String): PendingIntent =
        broadcast(context, activityId, action)

    // același PendingIntent pentru aceeași activitate și aceeași acțiune, ca să-l putem anula
    private fun broadcast(context: Context, activityId: Long, action: String): PendingIntent {
        val intent = Intent(context, ActivityReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, activityId)
        }
        val requestCode = (activityId * 16 + action.hashCode().mod(16)).toInt()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---------- notificările ----------

    fun showStart(context: Context, activity: Activity) {
        val planned = activity.userEstimatedMinutes ?: activity.estimatedMinutes
        notify(
            context, activity.id,
            NotificationCompat.Builder(context, CHANNEL_ACTIVITIES)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Începi ${activity.title}?")
                .setContentText(planned?.let { "planificat ${formatDuration(it)}" } ?: "e ora planificată")
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .addAction(0, "Start", broadcast(context, activity.id, ACTION_START))
                .addAction(0, "Peste $SNOOZE_MINUTES min", broadcast(context, activity.id, ACTION_SNOOZE))
                .build()
        )
    }

    fun showRunning(context: Context, activity: Activity, chronometerBase: Long) {
        val planned = activity.userEstimatedMinutes ?: activity.estimatedMinutes
        notify(
            context, activity.id,
            NotificationCompat.Builder(context, CHANNEL_TIMER)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(activity.title)
                .setContentText(planned?.let { "în desfășurare · planificat ${formatDuration(it)}" } ?: "în desfășurare")
                .setWhen(chronometerBase)
                .setUsesChronometer(true)        // Android afișează singur timpul scurs
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .addAction(0, "Pauză", broadcast(context, activity.id, ACTION_PAUSE))
                .addAction(0, "Gata", broadcast(context, activity.id, ACTION_DONE))
                .build()
        )
    }

    fun showEnd(context: Context, activity: Activity, trackedMinutes: Int) {
        notify(
            context, activity.id,
            NotificationCompat.Builder(context, CHANNEL_ACTIVITIES)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Ai terminat ${activity.title}?")
                .setContentText("cronometrat ${formatDuration(trackedMinutes)} · cronometrul încă merge")
                .setContentIntent(openApp(context))
                .addAction(0, "Gata", broadcast(context, activity.id, ACTION_DONE))
                .addAction(0, "+$SNOOZE_MINUTES min", broadcast(context, activity.id, ACTION_EXTEND))
                .addAction(0, "Parțial", broadcast(context, activity.id, ACTION_PARTIAL))
                .build()
        )
    }

    fun cancelNotification(context: Context, activityId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(activityId))
    }

    @SuppressLint("MissingPermission")   // verificăm permisiunea mai jos
    private fun notify(context: Context, activityId: Long, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(notificationId(activityId), notification)
    }

    private fun notificationId(activityId: Long): Int = 10_000 + activityId.toInt()

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}