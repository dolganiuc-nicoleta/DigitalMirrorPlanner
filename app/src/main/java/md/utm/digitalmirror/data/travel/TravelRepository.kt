package md.utm.digitalmirror.data.travel

import android.content.Context
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.entity.TravelRoute
import md.utm.digitalmirror.data.geo.RouteService
import md.utm.digitalmirror.domain.PresenceRules
import md.utm.digitalmirror.domain.RouteGeometry
import md.utm.digitalmirror.domain.TravelEstimator

/**
 * Estimarea unui drum între două locuri: distanța din serviciul de rute (păstrată pe telefon),
 * apoi corectată cu drumurile reale ale utilizatorului din ultimele 60 de zile.
 */
class TravelRepository(private val context: Context) {

    private val db = AppDatabase.get(context)

    /** Estimarea finală și ce a stat la baza ei, ca să putem explica utilizatorului de unde vine. */
    data class Outcome(
        val estimate: TravelEstimator.Estimate,
        val baseMinutes: Int,            // estimarea înainte de învățare
        val observedCount: Int,          // câte drumuri reale au contat
        val distanceMeters: Double,
        val points: List<RouteGeometry.Point> = emptyList(),  // traseul, pentru hartă
        val spread: TravelEstimator.Spread? = null,           // cât variază drumul și cu cât planificăm
        val trips: List<TravelEstimator.Trip> = emptyList()   // drumurile reale A → B, cele mai noi primele
    )

    suspend fun estimate(from: Place, to: Place, mode: TravelEstimator.Mode): Result<Outcome> =
        estimate(from, to, mode, allowNetwork = true)

    /**
     * Drumurile pentru toate perechile date, în minute, pentru planificare.
     * Drumurile deja calculate vin de pe telefon; restul se cer de la serviciul de rute.
     * La prima eroare de rețea nu mai încercăm rețeaua (ca planificarea să nu aștepte la fiecare pereche);
     * perechile fără răspuns lipsesc din rezultat, iar planificarea folosește atunci drumul fix.
     * Fiecare drum se calculează cu modul ales pentru el (altfel [mode]) și cu valoarea prudentă din drumurile reale.
     */
    suspend fun minutesFor(pairs: Set<Pair<Long, Long>>, mode: TravelEstimator.Mode): Map<Pair<Long, Long>, Int> {
        if (pairs.isEmpty()) return emptyMap()
        val places = db.placeDao().getAll().associateBy { it.id }
        val result = mutableMapOf<Pair<Long, Long>, Int>()
        var network = true
        for (pair in pairs) {
            val from = places[pair.first] ?: continue
            val to = places[pair.second] ?: continue
            val pairMode = md.utm.digitalmirror.data.prefs.TravelPrefs.routeMode(context, from.id, to.id) ?: mode
            estimate(from, to, pairMode, allowNetwork = network)
                .onSuccess { result[pair] = it.spread?.planned ?: it.estimate.minutes }
                .onFailure { if (it is RouteService.RouteException) network = false }
        }
        return result
    }

    /** Un mod de deplasare pentru perechea aleasă: estimarea, sau de ce nu s-a putut calcula. */
    data class ModeOption(val mode: TravelEstimator.Mode, val outcome: Outcome?, val error: String?)

    /**
     * Toate modurile de deplasare pentru o pereche, cu traseul fiecăruia (pentru hartă).
     * Transportul public folosește traseul pe jos (ORS nu are autobuze), deci se cere o singură dată pentru amândouă.
     */
    suspend fun allModes(from: Place, to: Place): List<ModeOption> =
        TravelEstimator.Mode.entries.map { mode ->
            estimate(from, to, mode, allowNetwork = true, needGeometry = true).fold(
                onSuccess = { ModeOption(mode, it, null) },
                onFailure = { ModeOption(mode, null, it.message ?: "Nu s-a putut calcula.") }
            )
        }

    private suspend fun estimate(
        from: Place, to: Place, mode: TravelEstimator.Mode, allowNetwork: Boolean, needGeometry: Boolean = false
    ): Result<Outcome> {
        val routes = db.travelRouteDao()
        val cached = routes.get(from.id, to.id, mode.orsProfile)
            // drumurile calculate înainte de v21 nu au traseu: pentru hartă le cerem din nou
            ?.takeIf { !needGeometry || !it.geometry.isNullOrBlank() }
        val raw = cached
            ?.let { RouteService.Raw(it.distanceMeters, it.durationSeconds, RouteGeometry.decode(it.geometry)) }
            ?: run {
                if (!allowNetwork) return Result.failure(IllegalStateException("Drumul nu e calculat încă."))
                val fetched = RouteService.fetch(mode.orsProfile, from, to)
                    .getOrElse { return Result.failure(it) }
                routes.upsert(
                    TravelRoute(
                        from.id, to.id, mode.orsProfile, fetched.distanceMeters, fetched.durationSeconds,
                        geometry = fetched.points.takeIf { it.isNotEmpty() }?.let(RouteGeometry::encode)
                    )
                )
                fetched.copy(points = RouteGeometry.simplify(fetched.points))
            }

        val base = TravelEstimator.base(mode, raw.distanceMeters, raw.durationSeconds)
        val trips = observedTrips(from.id, to.id)
        // drumurile reale nu știu cu ce te-ai deplasat: le socotim făcute cu modul ales pentru drumul ăsta
        val usual = md.utm.digitalmirror.data.prefs.TravelPrefs.modeFor(context, from.id, to.id)
        val observed = if (mode == usual) trips.map { it.minutes }.filter { TravelEstimator.plausible(base, it) } else emptyList()
        val spread = TravelEstimator.spread(base, observed)
        return Result.success(
            Outcome(
                estimate = TravelEstimator.Estimate(spread.typical, if (observed.isEmpty()) base.source else TravelEstimator.Source.LEARNED),
                baseMinutes = base.minutes,
                observedCount = observed.size,
                distanceMeters = raw.distanceMeters,
                points = raw.points,
                spread = spread,
                trips = trips.sortedByDescending { it.departAt }
            )
        )
    }

    // drumurile reale A → B din vizitele ultimelor 60 de zile
    private suspend fun observedTrips(fromId: Long, toId: Long): List<TravelEstimator.Trip> {
        val now = System.currentTimeMillis()
        val since = now - 60L * 24 * 60 * 60_000L
        val visits = db.presenceLogDao().overlapping(since, now + 1).map {
            PresenceRules.Visit(it.placeId, it.enterAt, PresenceRules.resolveEnd(it.enterAt, it.exitAt, now))
        }
        return TravelEstimator.observedTrips(PresenceRules.clean(visits))
            .filter { it.fromId == fromId && it.toId == toId }
    }
}