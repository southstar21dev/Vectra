package dev.southstar21.vectra.features.transit

/*
 * Debug builds only: the transit demo accounts on the dev backend. Each DEMO_ tracker shows one
 * line-strip edge case; its stop is named after the case. The data behind them is built by
 * [DemoFeed] from these descriptions, relative to the current time, so it never goes stale.
 */

/** How a demo stop's arrivals feed behaves. */
internal enum class DemoBehaviour { Live, Error, Loading, StaleAfterFirst, Empty }

/** One upcoming vehicle: due in [minutes], [dist] stops out (null: no live position). */
internal class DemoTrip(val minutes: Double, val dist: Double?, val predicted: Boolean = true)

internal class DemoDirection(
    val headsign: String,
    /** Names the stops before yours; directions sharing it come in the same way (a fork). */
    val approach: String,
    val origin: Boolean = false,
    val terminus: Boolean = false,
    /** Every vehicle's trip turns over each this many ms (the "just left" cycle); 0 = fixed. */
    val cycleMs: Long = 0,
    val trips: List<DemoTrip> = emptyList(),
    /** Run by the line's replacement buses (their own route, [DemoLine.replacementRouteId]). */
    val replacement: Boolean = false,
)

internal class DemoStop(
    val key: String,
    val name: String,
    val directions: List<DemoDirection>,
    val behaviour: DemoBehaviour = DemoBehaviour.Live,
    val alert: String? = null,
) {
    val stopId get() = "${DemoFeed.Prefix}S_$key"
}

internal class DemoLine(
    val key: String,
    val shortName: String,
    val type: String,
    val color: String,
    val textColor: String,
    val stops: List<DemoStop>,
    val pinned: Boolean = false,
) {
    val routeId get() = "${DemoFeed.Prefix}R_$key"
    val route get() = FutarRoute(routeId, shortName, "Demo line", type = type, color = color, textColor = textColor)

    /** Shaped like BKK's replacement routes (BKK_VP28), so the app takes it for one. */
    val replacementRouteId get() = "${DemoFeed.Prefix}VP_$key"
    val replacementRoute get() = FutarRoute(replacementRouteId, shortName, "Replacement bus", type = "BUS", color = BusBlueHex, textColor = White)
    val hasReplacement get() = stops.any { s -> s.directions.any { it.replacement } }
}

private const val BusBlueHex = "009EE3"

/** The header BKK gives a replacement (the real one for tram 28 in November 2026). */
private const val ReplacementAlert = "replacement bus service between Teleki László tér (Dobozi utca) and Blaha Lujza tér M (Népszínház utca)"

private const val Yellow = "FFD800"
private const val Blue = "009FE3"
private const val Red = "E41F18"
private const val MetroBlue = "005CA5"
private const val Black = "1E1E1E"
private const val Dark = "000000"
private const val White = "FFFFFF"

private fun two(a: List<DemoTrip>, b: List<DemoTrip>) = listOf(
    DemoDirection("Jászai Mari tér", "Corvin", trips = a),
    DemoDirection("Közvágóhíd H", "Fővám", trips = b),
)

private fun stop(key: String, name: String, dirs: List<DemoDirection>, behaviour: DemoBehaviour = DemoBehaviour.Live, alert: String? = null) =
    DemoStop(key, name, dirs, behaviour, alert)

private val Steady = two(listOf(DemoTrip(3.0, 1.4), DemoTrip(11.0, 4.5)), listOf(DemoTrip(5.0, 2.2), DemoTrip(13.0, 5.0)))

/** The demo trackers, in the order they are created. */
internal val DemoLines: List<DemoLine> = listOf(
    DemoLine("arriving", "2", "TRAM", Yellow, Dark, pinned = true, stops = listOf(
        stop("arriving", "Arriving now", two(listOf(DemoTrip(0.0, 0.03), DemoTrip(6.0, 2.6)), listOf(DemoTrip(4.0, 2.0)))),
    )),
    DemoLine("justleft", "47", "TRAM", Yellow, Dark, pinned = true, stops = listOf(
        stop("justleft", "Just left (watch for a minute)", listOf(
            DemoDirection("Deák Ferenc tér M", "Kálvin", cycleMs = 120_000),
            DemoDirection("Budafok, Városház tér", "Fővám", trips = listOf(DemoTrip(5.0, 2.2))),
        )),
    )),
    DemoLine("tworow", "105", "BUS", Blue, White, pinned = true, stops = listOf(
        stop("tworow", "Two in a row", listOf(
            DemoDirection("Gyöngyösi utca M", "Lánchíd", trips = listOf(DemoTrip(2.0, 1.0), DemoTrip(7.0, 2.9))),
            // The second one sits close behind: its time drops so the labels don't collide.
            DemoDirection("Apor Vilmos tér", "Deák", trips = listOf(DemoTrip(4.0, 1.6), DemoTrip(5.0, 2.1))),
        )),
    )),
    DemoLine("far", "2", "TRAM", Yellow, Dark, stops = listOf(
        stop("far", "Far away", two(listOf(DemoTrip(14.0, 7.4)), listOf(DemoTrip(3.0, 1.2)))),
    )),
    DemoLine("onedir", "105", "BUS", Blue, White, stops = listOf(
        stop("onedir", "One direction only", listOf(DemoDirection("Gyöngyösi utca M", "Lánchíd", trips = listOf(DemoTrip(7.0, 2.4))))),
    )),
    DemoLine("first", "2", "TRAM", Yellow, Dark, stops = listOf(
        stop("first", "First stop (tram waiting)", listOf(DemoDirection("Jászai Mari tér", "-", origin = true, trips = listOf(DemoTrip(6.0, 0.0))))),
    )),
    DemoLine("last", "47", "TRAM", Yellow, Dark, stops = listOf(
        stop("last", "Last stop", listOf(DemoDirection("Deák Ferenc tér M", "Kálvin", terminus = true, trips = listOf(DemoTrip(5.0, 1.5))))),
    )),
    DemoLine("fork", "61", "TRAM", Yellow, Dark, stops = listOf(
        stop("fork", "Three directions (fork)", listOf(
            DemoDirection("Széll Kálmán tér M", "Király", trips = listOf(DemoTrip(1.0, 0.6))),
            DemoDirection("Újbuda-központ M", "Jókai", trips = listOf(DemoTrip(4.0, 1.7))),
            DemoDirection("Móricz Zsigmond körtér M", "Jókai", trips = listOf(DemoTrip(9.0, 3.0))),
        )),
    )),
    DemoLine("metro", "M3", "SUBWAY", MetroBlue, White, stops = listOf(
        stop("metro", "Metro (timetable position)", listOf(
            DemoDirection("Újpest-központ", "Ferenciek", trips = listOf(DemoTrip(3.0, null), DemoTrip(7.0, null))),
            DemoDirection("Kőbánya-Kispest", "Nagyvárad", trips = listOf(DemoTrip(2.0, null), DemoTrip(6.0, null))),
        )),
    )),
    DemoLine("timetable", "105", "BUS", Blue, White, stops = listOf(
        stop("timetable", "Timetable only", listOf(DemoDirection("Gyöngyösi utca M", "Lánchíd", trips = listOf(DemoTrip(12.0, null, predicted = false))))),
    )),
    DemoLine("alert", "2", "TRAM", Yellow, Dark, stops = listOf(
        stop(
            "alert", "Disruption (long alert)", Steady,
            alert = "Track works: trams are replaced by buses between Jászai Mari tér and Széll Kálmán tér until 24 November, 04:00. " +
                "Replacement stops are next to the tram stops.",
        ),
    )),
    DemoLine("multi", "47", "TRAM", Yellow, Dark, stops = listOf(
        stop("multi1", "Fővám tér", Steady),
        stop("multi2", "Kálvin tér", two(listOf(DemoTrip(1.0, 0.5)), listOf(DemoTrip(8.0, 3.0)))),
        stop("multi3", "Astoria", two(listOf(DemoTrip(9.0, 2.8)), listOf(DemoTrip(2.0, 0.9)))),
    )),
    DemoLine("notrunning", "N111", "BUS", Black, White, stops = listOf(
        stop("notrunning", "Not running", two(emptyList(), emptyList()), DemoBehaviour.Empty),
    )),
    DemoLine("stale", "2", "TRAM", Yellow, Dark, stops = listOf(
        stop("stale", "Out of date (after a minute)", Steady, DemoBehaviour.StaleAfterFirst),
    )),
    DemoLine("error", "2", "TRAM", Yellow, Dark, stops = listOf(
        stop("error", "Live feed unavailable", Steady, DemoBehaviour.Error),
    )),
    DemoLine("loading", "2", "TRAM", Yellow, Dark, stops = listOf(
        stop("loading", "Loading (never answers)", Steady, DemoBehaviour.Loading),
    )),
    DemoLine("long", "M3 pótló", "BUS", Blue, White, stops = listOf(
        stop("long", "Széll Kálmán tér M (Várfok utca), Budai Vigadó felé, long stop name", listOf(
            DemoDirection("Bosnyák tér / Rákospalota, Kossuth utca ► Újpest", "Lehel", trips = listOf(DemoTrip(4.0, 1.8))),
            DemoDirection("Albertfalva, kitérő / Újbuda-központ M (Fehérvári út)", "Dózsa", trips = listOf(DemoTrip(7.0, 2.5))),
        )),
    )),
    DemoLine("wait", "105", "BUS", Blue, White, stops = listOf(
        stop("wait", "Long wait (clock time)", listOf(DemoDirection("Gyöngyösi utca M", "Lánchíd", trips = listOf(DemoTrip(130.0, null, predicted = false))))),
    )),
    DemoLine("night", "N6", "BUS", Black, White, stops = listOf(
        stop("night", "Night bus", two(listOf(DemoTrip(3.0, 1.3)), listOf(DemoTrip(9.0, 2.7)))),
    )),
    // Trams replaced by buses that stop here: the buses are the line for now.
    DemoLine("replaced", "28", "TRAM", Yellow, Dark, stops = listOf(
        stop("replaced", "Replaced by buses", listOf(
            DemoDirection("Teleki László tér", "Népszínház", replacement = true, trips = listOf(DemoTrip(3.0, 1.3), DemoTrip(12.0, 4.6))),
            DemoDirection("Blaha Lujza tér M", "Dologház", replacement = true, trips = listOf(DemoTrip(6.0, 2.4))),
        ), alert = ReplacementAlert),
    )),
    // Replaced too, but its buses stop on another street: no trams here, and not "not running".
    DemoLine("replacedaway", "28A", "TRAM", Yellow, Dark, stops = listOf(
        stop("replacedaway", "Replaced (buses stop elsewhere)", two(emptyList(), emptyList()), DemoBehaviour.Empty, alert = ReplacementAlert),
    )),
)

/** A real tracker for the demo accounts: a BKK line at a real stop (both from the static catalog). */
internal class RealDemoTracker(
    val routeId: String,
    val shortName: String,
    val type: String,
    val color: String,
    val textColor: String,
    val stopId: String,
    val stopName: String,
)

/** Real lines worth seeing beside the demo ones: a metro, a forking tram, a first and a last stop. */
internal val RealDemoTrackers: List<RealDemoTracker> = listOf(
    RealDemoTracker("BKK_5200", "M2", "SUBWAY", Red, White, "BKK_CS011732", "Deák Ferenc tér"),
    RealDemoTracker("BKK_3040", "4", "TRAM", Yellow, Dark, "BKK_CSF01082", "Oktogon"),
    RealDemoTracker("BKK_2005", "200E", "BUS", Blue, White, "BKK_CS009592", "Kőbánya-Kispest"),
    RealDemoTracker("BKK_3470", "47", "TRAM", Yellow, Dark, "BKK_CS011732", "Deák Ferenc tér"),
    // Inside tram 28's replaced section (November 2026): its replacement buses stop at this station.
    RealDemoTracker("BKK_3280", "28", "TRAM", Yellow, Dark, "BKK_CSF01330", "Teleki László tér"),
)
