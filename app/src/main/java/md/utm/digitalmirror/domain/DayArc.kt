package md.utm.digitalmirror.domain

/**
 * Ziua ca un arc de cer: de la trezire (stânga, răsărit) la culcare (dreapta).
 * Fiecare lucru din zi devine un segment al arcului (o fracție 0..1 din zi), iar „acum” e soarele de pe arc.
 * În mijlocul arcului stă un singur lucru: ce contează acum („1h 36 până te pregătești”).
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple. Desenul e în ui/day/DayArcHero.kt.
 */
object DayArc {

    enum class Kind { ACTIVITY, DONE, EVENT, MEAL, PREP, TRAVEL }

    /** Un lucru din zi, în minute de la miezul nopții (pot trece de 24:00 spre culcare). */
    data class Item(val start: Int, val end: Int, val title: String, val kind: Kind)

    /** Un drum de pe linia de timp: pregătirea (doar de acasă), apoi drumul până la [toName]. */
    data class Leg(val prepStart: Int, val start: Int, val end: Int, val toName: String)

    /** O bucată din arc; [from] și [to] sunt fracții din zi (0 = trezire, 1 = culcare). */
    data class Segment(val from: Float, val to: Float, val kind: Kind)

    /** Ce stă în mijlocul arcului: o etichetă mică, un număr mare de minute, o propoziție. */
    data class Focus(val label: String, val minutes: Int, val sentence: String)

    data class Result(
        val segments: List<Segment>,
        val now: Float?,             // unde e soarele; null = altă zi decât azi, sau în afara zilei
        val focus: Focus
    )

    /**
     * @param now minutul de acum, doar pentru ziua de azi (null pentru altă zi)
     * @param bedtime ora de culcare, peste 24:00 dacă e după miezul nopții
     * @param running titlul activității pe care rulează cronometrul acum, dacă e cazul
     */
    fun build(wake: Int, bedtime: Int, items: List<Item>, legs: List<Leg>, now: Int?, running: String? = null): Result {
        val span = (bedtime - wake).coerceAtLeast(60).toFloat()
        fun frac(minute: Int) = ((minute - wake) / span).coerceIn(0f, 1f)

        val segments = items.map { Segment(frac(it.start), frac(it.end), it.kind) } +
                legs.flatMap { leg ->
                    listOfNotNull(
                        Segment(frac(leg.prepStart), frac(leg.start), Kind.PREP).takeIf { leg.prepStart < leg.start },
                        Segment(frac(leg.start), frac(leg.end), Kind.TRAVEL)
                    )
                }
        val visible = segments.filter { it.to > it.from }

        val sun = now?.takeIf { it in wake..bedtime }?.let { frac(it) }
        val base = focus(wake, bedtime, items.filter { it.title != running }, legs, now)
        // lucrezi la ceva cu cronometrul: asta e „acum”; numărul rămâne cât timp mai ai până la următorul lucru
        val f = if (running != null && now != null) {
            Focus("$running · în lucru", base.minutes, "${base.label} · ${base.sentence}")
        } else base
        return Result(visible, sun, f)
    }

    /** Ce contează acum: lucrul în care ești, sau cât mai ai până la următorul, sau până la culcare. */
    fun focus(wake: Int, bedtime: Int, items: List<Item>, legs: List<Leg>, now: Int?): Focus {
        val upcoming = items.filter { it.kind != Kind.DONE && it.kind != Kind.MEAL }

        // altă zi decât azi: rezumatul zilei
        if (now == null) {
            val busy = upcoming.sumOf { (it.end - it.start).coerceAtLeast(0) } +
                    legs.sumOf { it.end - it.prepStart } +
                    items.filter { it.kind == Kind.MEAL }.sumOf { it.end - it.start }
            val free = (bedtime - wake - busy).coerceAtLeast(0)
            return Focus("libere în ziua asta", free, "trezire ${clock(wake)} · culcare ${clock(bedtime)}")
        }

        // ești chiar acum într-un lucru
        upcoming.firstOrNull { now >= it.start && now < it.end }?.let {
            return Focus("acum", it.end - now, "${it.title} · până la ${clock(it.end)}")
        }
        // ești pe drum sau te pregătești
        legs.firstOrNull { now >= it.prepStart && now < it.end }?.let {
            return if (now < it.start) Focus("pleci în", it.start - now, "spre ${it.toName}, la ${clock(it.start)}")
            else Focus("pe drum", it.end - now, "ajungi la ${it.toName} la ${clock(it.end)}")
        }

        // următorul lucru: pregătirea de plecare (dacă e prima) sau lucrul în sine
        val nextLeg = legs.filter { it.prepStart > now }.minByOrNull { it.prepStart }
        val nextItem = upcoming.filter { it.start > now }.minByOrNull { it.start }
        return when {
            nextLeg != null && (nextItem == null || nextLeg.prepStart <= nextItem.start) ->
                if (nextLeg.prepStart < nextLeg.start)
                    Focus("până te pregătești", nextLeg.prepStart - now, "pleci la ${clock(nextLeg.start)} spre ${nextLeg.toName}")
                else
                    Focus("până pleci", nextLeg.start - now, "spre ${nextLeg.toName}")
            nextItem != null ->
                Focus("liber până la", nextItem.start - now, "${nextItem.title}, ${clock(nextItem.start)}")
            now < bedtime ->
                Focus("liber până la culcare", bedtime - now, "culcare ${clock(bedtime)}")
            else ->
                Focus("e timpul de culcare", 0, "noapte bună")
        }
    }

    /** „1h 36”, „45 min”. */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "${h}h"
            else -> "${h}h %02d".format(m)
        }
    }

    fun clock(minute: Int): String {
        val m = ((minute % (24 * 60)) + 24 * 60) % (24 * 60)
        return "%d:%02d".format(m / 60, m % 60)
    }
}
