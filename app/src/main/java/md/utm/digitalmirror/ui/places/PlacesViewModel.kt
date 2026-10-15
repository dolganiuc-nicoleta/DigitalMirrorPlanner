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
import md.utm.digitalmirror.data.dao.RecentVisit
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.geo.GeofenceManager
import md.utm.digitalmirror.data.geo.PlaceSearch
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.data.travel.TravelRepository
import md.utm.digitalmirror.domain.TravelEstimator

data class SearchState(
    val loading: Boolean = false,
    val hits: List<PlaceSearch.Hit> = emptyList(),
    val message: String? = null        // „Nu am găsit nimic.” sau eroarea de rețea
)

/** Rezultatul ultimei estimări de drum, pentru cardul „Drum între locuri”. */
data class RouteUi(
    val loading: Boolean = false,
    val outcome: TravelRepository.Outcome? = null,
    val fromName: String = "",
    val toName: String = "",
    val error: String? = null
)

class PlacesViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.placeDao()
    private val presenceDao = db.presenceLogDao()
    private val routeDao = db.travelRouteDao()
    private val availabilityDao = db.availabilityDao()
    private val travel = TravelRepository(app)

    private val _route = MutableStateFlow(RouteUi())
    val route: StateFlow<RouteUi> = _route

    /** Estimează drumul dintre două locuri; modul ales se păstrează pentru data viitoare. */
    fun estimateRoute(from: Place, to: Place, mode: TravelEstimator.Mode) {
        if (_route.value.loading) return
        TravelPrefs.setMode(app, mode)
        _route.value = RouteUi(loading = true)
        viewModelScope.launch {
            travel.estimate(from, to, mode)
                .onSuccess { _route.value = RouteUi(outcome = it, fromName = from.name, toName = to.name) }
                .onFailure { _route.value = RouteUi(error = it.message ?: "Ceva nu a mers.") }
        }
    }

    val places: StateFlow<List<Place>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ultimele intrări detectate, ca utilizatorul să vadă că detectarea merge
    val recentVisits: StateFlow<List<RecentVisit>> =
        presenceDao.observeRecent(8).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    fun save(place: Place) = viewModelScope.launch {
        if (place.id == 0L) dao.insert(place)
        else {
            dao.update(place)
            routeDao.deleteForPlace(place.id)     // locul s-ar fi putut muta, drumurile vechi nu mai sunt bune
        }
        GeofenceManager.sync(app)      // zonele urmăresc locurile salvate
    }

    // desfacem legătura din activități, ștergem prezențele și locul într-o singură tranzacție,
    // ca lista să se actualizeze o singură dată, cu rezultatul final
    fun delete(place: Place) = viewModelScope.launch {
        db.withTransaction {
            dao.unlinkActivities(place.id)
            availabilityDao.unlinkPlace(place.id)
            presenceDao.deleteForPlace(place.id)
            routeDao.deleteForPlace(place.id)
            dao.delete(place.id)
        }
        GeofenceManager.sync(app)
    }

    /** Reînregistrează zonele (după ce s-a schimbat comutatorul sau permisiunile). */
    fun syncGeofences() = viewModelScope.launch { GeofenceManager.sync(app) }

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