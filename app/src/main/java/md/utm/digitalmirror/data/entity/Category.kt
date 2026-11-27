package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "category")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#378ADD",
    val createdAt: Long = System.currentTimeMillis(),
    val icon: String? = null             // v22 · numele iconiței (vezi CategoryIcons); null = ghicită din nume
)