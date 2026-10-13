package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** O zi marcată ca liberă: blocurile fixe nu se aplică, iar ziua nu intră în media zilelor tipice. */
@Entity(tableName = "day_off")
data class DayOff(@PrimaryKey val date: String)   // yyyy-MM-dd