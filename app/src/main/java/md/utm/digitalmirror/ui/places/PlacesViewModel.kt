package md.utm.digitalmirror.ui.places

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.geo.PlaceSearch

data class SearchState(
    val loading: Boolean = false,
    val hits: List<PlaceSearch.Hit> = emptyList(),
    val message: String? = null        // „Nu am găsit nimic.” sau eroarea de rețea
)

class PlacesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.placeDao()

    val places: StateFlow<List<Place>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    fun save(place: Place) = viewModelScope.launch {
        if (place.id == 0L) dao.insert(place) else dao.update(place)
    }

    // desfacem legătura din activități și ștergem locul într-o singură tranzacție,
    // ca lista să se actualizeze o singură dată, cu rezultatul final
    fun delete(place: Place) = viewModelScope.launch {
        db.withTransaction {
            dao.unlinkActivities(place.id)
            dao.delete(place.id)
        }
    }

    fun searchAddress(query: String) {
        if (_search.value.loading) return
        _search.value = SearchState(loading = true)
        viewModelScope.launch {
            PlaceSearch.search(query)
                .onSuccess { hits ->
                    _search.value = SearchState(
                        hits = hits,
                        message = if (hits.isEmpty()) "Nu am găsit nimic. Încearcă alt nume sau atinge harta." else null
                    )
                }
                .onFailure {
                    _search.value = SearchState(message = "Căutarea nu a mers. Verifică internetul sau atinge harta.")
                }
        }
    }

    fun clearSearch() {
        _search.value = SearchState()
    }
}