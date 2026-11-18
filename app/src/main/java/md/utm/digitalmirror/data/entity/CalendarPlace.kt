package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Unde au loc evenimentele din calendar cu un anumit nume („C.PSI 3.3” → Facultate).
 * Orarul se repetă, deci locul se alege o singură dată și se aplică tuturor evenimentelor cu același nume.
 * [titleKey] e titlul normalizat (TitleKey), ca diferențele de scriere să nu conteze.
 */
@Entity(tableName = "calendar_place")
data class CalendarPlace(
    @PrimaryKey val titleKey: String,
    val placeId: Long,
    val source: String = SOURCE_USER,      // USER = ales de utilizator; LEARNED = învățat din prezență (mai târziu)
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_USER = "USER"
        const val SOURCE_LEARNED = "LEARNED"
    }
}
