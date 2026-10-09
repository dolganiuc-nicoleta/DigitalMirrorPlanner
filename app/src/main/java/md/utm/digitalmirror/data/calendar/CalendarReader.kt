package md.utm.digitalmirror.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Citește evenimentele din calendarele telefonului (Samsung Calendar, Google, Outlook…),
 * prin furnizorul de calendar al sistemului Android. Doar citire: aplicația nu modifică nimic.
 *
 * Folosește tabelul Instances, nu Events, ca evenimentele recurente (de exemplu perechile
 * din fiecare săptămână) să apară în fiecare zi în care au loc.
 */
object CalendarReader {

    data class Event(
        val id: Long,
        val title: String,
        val startMinute: Int,        // minute de la miezul nopții, tăiat la ziua cerută
        val endMinute: Int,
        val allDay: Boolean,
        val busy: Boolean,           // false pentru evenimentele marcate „liber” și pentru cele de toată ziua
        val calendarName: String?
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun eventsFor(context: Context, date: LocalDate): List<Event> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()

        val zone = ZoneId.systemDefault()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, dayStart)
            ContentUris.appendId(this, dayEnd)
        }.build()

        val projection = arrayOf(
            Instances.EVENT_ID,               // 0
            Instances.TITLE,                  // 1
            Instances.BEGIN,                  // 2
            Instances.END,                    // 3
            Instances.ALL_DAY,                // 4
            Instances.AVAILABILITY,           // 5
            Instances.SELF_ATTENDEE_STATUS,   // 6
            Instances.CALENDAR_DISPLAY_NAME   // 7
        )

        val events = mutableListOf<Event>()
        try {
            context.contentResolver.query(
                uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    // invitațiile refuzate nu ocupă timp
                    if (c.getInt(6) == Attendees.ATTENDEE_STATUS_DECLINED) continue

                    val id = c.getLong(0)
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(fără titlu)"
                    val begin = c.getLong(2)
                    val end = c.getLong(3)
                    val calendarName = c.getString(7)

                    if (c.getInt(4) == 1) {
                        // evenimentele de toată ziua sunt salvate în UTC, deci verificăm ziua separat
                        val first = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        val last = Instant.ofEpochMilli(end - 1).atZone(ZoneOffset.UTC).toLocalDate()
                        if (date < first || date > last) continue
                        events += Event(id, title, 0, 24 * 60, allDay = true, busy = false, calendarName = calendarName)
                    } else {
                        val start = ((maxOf(begin, dayStart) - dayStart) / 60_000).toInt()
                        val finish = ((minOf(end, dayEnd) - dayStart) / 60_000).toInt()
                        if (finish <= start) continue
                        events += Event(
                            id, title, start, finish,
                            allDay = false,
                            busy = c.getInt(5) != Events.AVAILABILITY_FREE,
                            calendarName = calendarName
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            return@withContext emptyList()   // permisiunea a fost retrasă între timp
        }

        // întâi cele de toată ziua, apoi după oră
        events.sortedWith(compareBy({ !it.allDay }, { it.startMinute }))
    }
}