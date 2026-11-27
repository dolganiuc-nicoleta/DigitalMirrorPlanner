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
    val mode: TravelEstimator.Mode = TravelEstimator.Mode.WALK,          // modul acestui drum
    val defaultMode: TravelEstimator.Mode = TravelEstimator.Mode.WALK,   // modul obișnuit, pentru drumurile fără mod ales
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
    private val _route = MutableStateFlow(
        RouteMapUi(fromId = TravelPrefs.homePlaceId(app), mode = TravelPrefs.mode(app), defaultMode = TravelPrefs.mode(app))
    )
    val route: StateFlow<RouteMapUi> = _route
    private var routeJob: kotlinx.coroutines.Job? = null

    fun setRouteFrom(id: Long) = setPair(id, _route.value.toId)
    fun setRouteTo(id: Long) = setPair(_route.value.fromId, id)
    fun swapRoute() = setPair(_route.value.toId, _route.value.fromId)
    fun openRoute(from: Long, to: Long) = setPair(from, to)

    /** Modul ales devine cel cu care se planifică acest drum (în ambele sensuri). */
    fun chooseMode(mode: TravelEstimator.Mode) {
        val r = _route.value
        val from = r.fromId ?: return
        val to = r.toId ?: return
        TravelPrefs.setRouteMode(app, from, to, mode.takeIf { it != r.defaultMode })
        setPair(from, to)     // drumurile reale trec la modul nou
    }

    /** Modul acestui drum devine cel obișnuit, pentru toate drumurile fără mod ales. */
    fun useModeForAll() {
        val r = _route.value
        TravelPrefs.setMode(app, r.mode)
        if (r.fromId != null && r.toId != null) TravelPrefs.setRouteMode(app, r.fromId, r.toId, null)
        _route.value = r.copy(defaultMode = r.mode)
    }

    private fun setPair(fromId: Long?, toId: Long?) {
        val mode = if (fromId != null && toId != null) TravelPrefs.modeFor(app, fromId, toId) else _route.value.defaultMode
        _route.value = _route.value.copy(fromId = fromId, toId = toId, mode = mode, loading = false,
            // același drum (s-a schimbat doar modul): lista veche rămâne până vine cea nouă
            options = if (fromId == _route.value.fromId && toId == _route.value.toId) _route.value.options else emptyList())
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

    // harta pornește cu un drum deja ales: de acasă (sau primul loc) până la alt loc
    init {
        viewModelScope.launch {
            dao.observeAll().collect { list ->
                val r = _route.value
                val ids = list.map { it.id }.toSet()
                val from = r.fromId?.takeIf { it in ids }
                    ?: list.firstOrNull { it.name.contains("acas", ignoreCase = true) }?.id
                    ?: list.firstOrNull()?.id
                val to = r.toId?.takeIf { it in ids && it != from } ?: list.firstOrNull { it.id != from }?.id
                if (from != r.fromId || to != r.toId) setPair(from, to)
            }
        }
    }

    // ultimele intrări detectate, ca utilizatorul să vadă că detectarea merge
    val recentVisits: StateFlow<List<RecentVisit>> =
        presenceDao.observeRecent(8).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    /** Locul de unde pleci de obicei (acasă), ca lista și editorul să-l arate. */
    private val _homeId = MutableStateFlow(TravelPrefs.homePlaceId(app))
    val homeId: StateFlow<Long?> = _homeId

    /** Salvează locul; [isHome] îl face (sau nu mai) locul de unde pleci de obicei. */
    fun save(place: Place, isHome: Boolean = place.id != 0L && place.id == _homeId.value) = viewModelScope.launch {
        val id = if (place.id == 0L) dao.insert(place)
        else {
            dao.update(place)
            routeDao.deleteForPlace(place.id)     // locul s-ar fi putut muta, drumurile vechi nu mai sunt bune
            place.id
        }
        when {
            isHome -> TravelPrefs.setHomePlaceId(app, id)
            _homeId.value == id -> TravelPrefs.setHomePlaceId(app, null)
        }
        _homeId.value = TravelPrefs.homePlaceId(app)
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