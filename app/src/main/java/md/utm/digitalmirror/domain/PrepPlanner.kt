package md.utm.digitalmirror.domain

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Regulile pregătirii pentru un eveniment, fără Android:
 *  - zilele disponibile sunt de azi până în ajunul evenimentului;
 *  - fiecare pas primește o fereastră de zile proporțională cu timpul lui, în ordinea pașilor,
 *    deci ultimul pas (recapitularea) cade în ultimele zile;
 *  - cât de pregătit ești = cât din timpul pașilor ai lucrat deja.
 * Așezarea pe ore o face WeekPlanner, cu fereastra fiecărui pas ca „nu înainte de” și „nu după”.
 */
object PrepPlanner {

    data class Window(val from: LocalDate, val to: LocalDate)

    /** Zilele de la [from] până în ajunul evenimentului, inclusiv. Gol dacă evenimentul e azi sau a trecut. */
    fun daysBefore(event: LocalDate, from: LocalDate): List<LocalDate> {
        if (!from.isBefore(event)) return emptyList()
        val count = java.time.temporal.ChronoUnit.DAYS.between(from, event).toInt()
        return (0 until count).map { from.plusDays(it.toLong()) }
    }

    /**
     * Fereastra de zile a fiecărui pas. Ferestrele urmează ordinea pașilor și se suprapun cu o zi la capăt,
     * ca un pas să aibă puțin joc dacă ziua lui e plină.
     */
    fun windows(days: List<LocalDate>, minutes: List<Int>): List<Window> {
        if (days.isEmpty() || minutes.isEmpty()) return emptyList()
        val n = days.size
        val total = minutes.sum().coerceAtLeast(1).toDouble()
        var before = 0
        return minutes.map { m ->
            val startFraction = before / total
            before += m
            val endFraction = before / total
            val start = floor(startFraction * n).toInt().coerceIn(0, n - 1)
            val end = (ceil(endFraction * n).toInt() - 1 + 1).coerceIn(start, n - 1)   // +1: o zi de joc
            Window(days[start], days[end])
        }
    }

    /** Procentul de pregătire: partea din timpul pașilor deja lucrată (un pas nu poate trece de 100%). */
    fun readyPercent(plannedMinutes: List<Int>, doneMinutes: List<Int>): Int {
        val total = plannedMinutes.sum()
        if (total <= 0) return 0
        val done = plannedMinutes.indices.sumOf { minOf(plannedMinutes[it], doneMinutes.getOrElse(it) { 0 }) }
        return Math.round(100.0 * done / total).toInt().coerceIn(0, 100)
    }
}
