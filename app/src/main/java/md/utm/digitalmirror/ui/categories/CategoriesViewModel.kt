package md.utm.digitalmirror.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.domain.CategoryTidy

/** „Categoriile mele”: culoare, iconiță, redenumire, ștergere, unire, și dublurile găsite singure. */
class CategoriesViewModel(app: Application) : AndroidViewModel(app) {

    data class Row(val category: Category, val count: Int)

    data class UiState(
        val rows: List<Row> = emptyList(),
        val duplicates: List<CategoryTidy.Pair> = emptyList()
    )

    private val db = AppDatabase.get(app)
    private val dao = db.categoryDao()

    val state: StateFlow<UiState> = combine(dao.observeAll(), dao.observeUsage()) { categories, usage ->
        val counts = usage.associate { it.categoryId to it.count }
        UiState(
            rows = categories.map { Row(it, counts[it.id] ?: 0) },
            duplicates = CategoryTidy.duplicates(categories.map { CategoryTidy.Named(it.id, it.name) }, counts)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    fun add(name: String, colorHex: String, icon: String?) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isBlank() || dao.findByName(clean) != null) return@launch
        dao.insert(Category(name = clean, colorHex = colorHex, icon = icon))
    }

    fun save(category: Category, name: String, colorHex: String, icon: String?) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isBlank()) return@launch
        dao.update(category.copy(name = clean, colorHex = colorHex, icon = icon))
    }

    /** Activitățile rămân, doar fără categorie. */
    fun delete(category: Category) = viewModelScope.launch {
        db.activityDao().clearCategory(category.id)
        dao.delete(category)
    }

    /** Tot ce ținea de [from] (activități, sarcini, obiective, pariuri) trece la [into]; [from] dispare. */
    fun merge(from: Long, into: Long) = viewModelScope.launch { dao.merge(from, into) }
}
