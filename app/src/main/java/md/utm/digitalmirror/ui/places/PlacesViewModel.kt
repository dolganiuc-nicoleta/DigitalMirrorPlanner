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

/**
 * Harta drumurilor: perechea aleasă, toate modurile de deplasare cu durata și traseul lor,
 * și modul cu care planifici de obicei (cel desenat pe hartă).
 */
data class RouteMapUi(
    val fromId: Long? = null,
    val toId: Long? = null,
    val loading: Boolean = false,
    val options: List<TravelRepository.ModeOption> = emptyList(),
    val mode: TravelEstimator.Mode = TravelEstimator.Mode.WALK
) {
    val chosen: TravelRepository.ModeOption? get() = options.firstOrNull { it.mode == mode }
}

class PlacesViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.placeDao()
    private val presenceDao = db.presenceLogDao()
    private val routeDao = db.travelRouteDao()
    private val availabilityDao = db.availabilityDao()
    private val travel = TravelRepository(app)

    // pornim de acasă, cu modul tău obișnuit
    private val _route = MutableStateFlow(RouteMapUi(fromId = TravelPrefs.homePlaceId(app), mode = TravelPrefs.mode(app)))
    val route: StateFlow<RouteMapUi> = _route
    private var routeJob: kotlinx.coroutines.Job? = null

    fun setRouteFrom(id: Long) = setPair(id, _route.value.toId)
    fun setRouteTo(id: Long) = setPair(_route.value.fromId, id)
    fun swapRoute() = setPair(_route.value.toId, _route.value.fromId)

    /** Modul ales devine cel cu care se planifică drumurile din zi. */
    fun chooseMode(mode: TravelEstimator.Mode) {
        TravelPrefs.setMode(app, mode)
        _route.value = _route.value.copy(mode = mode)
    }

    private fun setPair(fromId: Long?, toId: Long?) {
        _route.value = _route.value.copy(fromId = fromId, toId = toId, options = emptyList(), loading = false)
        routeJob?.cancel()
        if (fromId == null || toId == null || fromId == toId) return
        routeJob = viewModelScope.launch {
            val all = dao.getAll()
            val from = all.find { it.id == fromId } ?: return@launch
            val to = all.find { it.id == toId } ?: return@launch
            _route.value = _route.value.copy(loading = true)
            val options = travel.allModes(from, to)
            _route.value = _route.value.copy(loading = false, options = options)
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
            db.calendarPlaceDao().deleteForPlace(place.id)
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