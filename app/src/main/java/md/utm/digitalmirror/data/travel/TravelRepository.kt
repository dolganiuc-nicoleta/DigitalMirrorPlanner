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
class TravelRepository(context: Context) {

    private val db = AppDatabase.get(context)

    /** Estimarea finală și ce a stat la baza ei, ca să putem explica utilizatorului de unde vine. */
    data class Outcome(
        val estimate: TravelEstimator.Estimate,
        val baseMinutes: Int,            // estimarea înainte de învățare
        val observedCount: Int,          // câte drumuri reale au contat
        val distanceMeters: Double,
        val points: List<RouteGeometry.Point> = emptyList()   // traseul, pentru hartă
    )

    suspend fun estimate(from: Place, to: Place, mode: TravelEstimator.Mode): Result<Outcome> =
        estimate(from, to, mode, allowNetwork = true)

    /**
     * Drumurile pentru toate perechile date, în minute, pentru planificare.
     * Drumurile deja calculate vin de pe telefon; restul se cer de la serviciul de rute.
     * La prima eroare de rețea nu mai încercăm rețeaua (ca planificarea să nu aștepte la fiecare pereche);
     * perechile fără răspuns lipsesc din rezultat, iar planificarea folosește atunci drumul fix.
     */
    suspend fun minutesFor(pairs: Set<Pair<Long, Long>>, mode: TravelEstimator.Mode): Map<Pair<Long, Long>, Int> {
        if (pairs.isEmpty()) return emptyMap()
        val places = db.placeDao().getAll().associateBy { it.id }
        val result = mutableMapOf<Pair<Long, Long>, Int>()
        var network = true
        for (pair in pairs) {
            val from = places[pair.first] ?: continue
            val to = places[pair.second] ?: continue
            estimate(from, to, mode, allowNetwork = network)
                .onSuccess { result[pair] = it.estimate.minutes }
                .onFailure { if (it is RouteService.RouteException) network = false }
        }
        return result
    }

    /** Un mod de deplasare pentru perechea aleasă: estimarea, sau de ce nu s-a putut calcula. */
    data class ModeOption(val mode: TravelEstimator.Mode, val outcome: Outcome?, val error: String?)

    /**
     * Toate modurile de deplasare pentru o pereche, cu traseul fiecăruia (pentru hartă).
     * Mașina și transportul public au același traseu rutier, deci se cere o singură dată.
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
        val observed = observedMinutes(from.id, to.id)
        return Result.success(
            Outcome(
                estimate = TravelEstimator.learn(base, observed),
                baseMinutes = base.minutes,
                observedCount = observed.size,
                distanceMeters = raw.distanceMeters,
                points = raw.points
            )
        )
    }

    // drumurile reale A → B din vizitele ultimelor 60 de zile
    private suspend fun observedMinutes(fromId: Long, toId: Long): List<Int> {
        val now = System.currentTimeMillis()
        val since = now - 60L * 24 * 60 * 60_000L
        val visits = db.presenceLogDao().overlapping(since, now + 1).map {
            PresenceRules.Visit(it.placeId, it.enterAt, PresenceRules.resolveEnd(it.enterAt, it.exitAt, now))
        }
        return TravelEstimator.observedTrips(PresenceRules.clean(visits))
            .filter { it.fromId == fromId && it.toId == toId }
            .map { it.minutes }
    }
}