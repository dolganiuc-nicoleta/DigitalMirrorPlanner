package md.utm.digitalmirror.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.DailyActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Citește somnul și pașii din Health Connect și îi salvează în tabelul daily_activity.
 *
 * - pașii se adună pe zile calendaristice (Health Connect elimină singur dublurile
 *   dintre telefon și ceas când folosim aggregate);
 * - somnul se atribuie zilei în care utilizatorul s-a trezit, deci somnul din
 *   noaptea de luni spre marți apare la marți.
 */
class HealthSync(private val context: Context) {

    enum class Status { NOT_AVAILABLE, UPDATE_REQUIRED, NOT_CONNECTED, CONNECTED }

    companion object {
        private const val TAG = "HealthSync"
        private const val PREFS = "health_prefs"
        private const val KEY_INITIAL_DONE = "initial_sync_done"

        // Health Connect dă acces implicit la datele din ultimele 30 de zile dinaintea acordării permisiunii
        const val INITIAL_DAYS = 30L
        const val REGULAR_DAYS = 7L

        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class)
        )
    }

    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val dao = AppDatabase.get(context).dailyActivityDao()
    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun status(): Status =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE ->
                if (hasPermissions()) Status.CONNECTED else Status.NOT_CONNECTED
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.UPDATE_REQUIRED
            else -> Status.NOT_AVAILABLE
        }

    private suspend fun hasPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    /**
     * Sincronizează dacă avem acces. Prima dată aduce 30 de zile, apoi doar ultimele 7.
     * Întoarce numărul de zile actualizate, sau null dacă nu s-a putut sincroniza.
     */
    suspend fun syncIfPermitted(): Int? {
        if (status() != Status.CONNECTED) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initialDone = prefs.getBoolean(KEY_INITIAL_DONE, false)

        return try {
            val days = sync(if (initialDone) REGULAR_DAYS else INITIAL_DAYS)
            prefs.edit().putBoolean(KEY_INITIAL_DONE, true).apply()
            days
        } catch (e: Exception) {
            // de ex. permisiune retrasă între timp sau Health Connect indisponibil
            Log.w(TAG, "Sincronizarea a eșuat", e)
            null
        }
    }

    private suspend fun sync(days: Long): Int {
        val today = LocalDate.now()
        val from = today.minusDays(days - 1)

        val steps = readSteps(from, today)
        val sleep = readSleep(from, today)

        var updated = 0
        var day = from
        while (!day.isAfter(today)) {
            val key = day.toString()
            val daySteps = steps[key]
            val daySleep = sleep[key]

            if (daySteps != null || daySleep != null) {
                val existing = dao.getByDate(key)
                val row = (existing ?: DailyActivity(date = key, steps = null, activeMinutes = null)).copy(
                    steps = daySteps ?: existing?.steps,
                    sleepMinutes = daySleep?.minutes ?: existing?.sleepMinutes,
                    bedtimeMinute = daySleep?.bedtimeMinute ?: existing?.bedtimeMinute,
                    wakeMinute = daySleep?.wakeMinute ?: existing?.wakeMinute,
                    collectedAt = System.currentTimeMillis()
                )
                dao.upsert(row)
                updated++
            }
            day = day.plusDays(1)
        }
        return updated
    }

    // pașii pe fiecare zi calendaristică
    private suspend fun readSteps(from: LocalDate, to: LocalDate): Map<String, Int> {
        val buckets = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    from.atStartOfDay(),
                    to.plusDays(1).atStartOfDay()
                ),
                timeRangeSlicer = Period.ofDays(1)
            )
        )
        return buckets.mapNotNull { bucket ->
            bucket.result[StepsRecord.COUNT_TOTAL]?.let { total ->
                bucket.startTime.toLocalDate().toString() to total.toInt()
            }
        }.toMap()
    }

    private data class DaySleep(val minutes: Int, val bedtimeMinute: Int, val wakeMinute: Int)

    // somnul, grupat pe ziua trezirii
    private suspend fun readSleep(from: LocalDate, to: LocalDate): Map<String, DaySleep> {
        // începem cu o zi mai devreme, ca să prindem și noaptea dinaintea primei zile
        val start = from.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val end = Instant.now()

        val sessions = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records

        return sessions
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .filterKeys { !it.isBefore(from) && !it.isAfter(to) }
            .mapNotNull { (wakeDate, list) ->
                val intervals = mergeOverlapping(list.map { it.startTime to it.endTime })
                val total = intervals.sumOf { Duration.between(it.first, it.second).toMinutes() }.toInt()
                if (total <= 0) return@mapNotNull null

                // ora de culcare și de trezire le luăm din cea mai lungă sesiune (somnul de noapte, nu un pui de somn)
                val main = intervals.maxBy { Duration.between(it.first, it.second) }
                wakeDate.toString() to DaySleep(
                    minutes = total,
                    bedtimeMinute = minuteOfDay(main.first),
                    wakeMinute = minuteOfDay(main.second)
                )
            }
            .toMap()
    }

    // dacă două surse au înregistrat aceeași noapte, intervalele se suprapun și nu vrem să le numărăm de două ori
    private fun mergeOverlapping(intervals: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
        val sorted = intervals.sortedBy { it.first }
        val merged = mutableListOf<Pair<Instant, Instant>>()
        for (interval in sorted) {
            val last = merged.lastOrNull()
            if (last != null && !interval.first.isAfter(last.second)) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, interval.second)
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun minuteOfDay(instant: Instant): Int {
        val time = instant.atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }
}