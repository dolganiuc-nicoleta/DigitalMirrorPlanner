package md.utm.digitalmirror.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.repository.PlannerRepository

/**
 * Primește alarmele programate și apăsările pe butoanele din notificări.
 * Lucrează direct cu baza de date, deci aplicația nu trebuie deschisă.
 */
class ActivityReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ActivityReminders.EXTRA_ID, -1L)
        if (id < 0) return

        // lucrul cu baza de date nu se face pe firul principal
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context.applicationContext, intent.action, id)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String?, id: Long) {
        val db = AppDatabase.get(context)
        val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
        val activity = repo.getActivity(id) ?: return

        when (action) {

            // a venit ora planificată
            ActivityReminders.ACTION_ALARM_START -> {
                if (activity.state == "PLANNED") ActivityReminders.showStart(context, activity)
            }

            // a trecut durata planificată, iar cronometrul încă merge
            ActivityReminders.ACTION_ALARM_END -> {
                if (repo.runningSession()?.activityId == id) {
                    ActivityReminders.showEnd(context, activity, repo.trackedMinutes(id))
                }
            }

            ActivityReminders.ACTION_START -> {
                // dacă rula altă activitate, startTimer o oprește; scoatem și notificarea ei
                repo.runningSession()?.takeIf { it.activityId != id }?.let {
                    ActivityReminders.onTimerStopped(context, it.activityId)
                }
                repo.startTimer(id)
                repo.runningSession()?.let { session ->
                    ActivityReminders.onTimerStarted(
                        context, repo.getActivity(id) ?: activity, session.startedAt, repo.closedMillis(id)
                    )
                }
            }

            ActivityReminders.ACTION_SNOOZE -> ActivityReminders.snooze(context, id)

            ActivityReminders.ACTION_PAUSE -> {
                repo.runningSession()?.takeIf { it.activityId == id }?.let { repo.stopTimer(it) }
                ActivityReminders.onTimerStopped(context, id)
            }

            ActivityReminders.ACTION_EXTEND -> {
                repo.runningSession()?.takeIf { it.activityId == id }?.let { session ->
                    ActivityReminders.extend(context, activity, session.startedAt - repo.closedMillis(id))
                }
            }

            // finalizare direct din notificare: durata reală e cea cronometrată
            ActivityReminders.ACTION_DONE, ActivityReminders.ACTION_PARTIAL -> {
                val state = if (action == ActivityReminders.ACTION_DONE) "DONE" else "PARTIAL"
                repo.finishActivity(id, repo.trackedMinutes(id), state)
                ActivityReminders.onActivityClosed(context, id)
            }
        }
    }
}