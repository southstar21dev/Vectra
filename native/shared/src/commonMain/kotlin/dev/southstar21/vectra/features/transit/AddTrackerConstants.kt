package dev.southstar21.vectra.features.transit

data class MetroFallbackColors(val color: String, val textColor: String)

val MetroFallbackColorsByLine: Map<String, MetroFallbackColors> = mapOf(
    "M1" to MetroFallbackColors("FFD800", "000000"),
    "M2" to MetroFallbackColors("E41F18", "FFFFFF"),
    "M3" to MetroFallbackColors("005CA5", "FFFFFF"),
    "M4" to MetroFallbackColors("009645", "FFFFFF"),
)

val GroupLineColors: Map<String, String> = mapOf(
    "M1" to "#FFD800",
    "M2" to "#E41F18",
    "M3" to "#005CA5",
    "M4" to "#009645",
    "POPULAR" to "#f59e0b",
)

/** 'M1' | 'M2' | 'M3' | 'M4' | 'POPULAR' */
enum class MetroView(val key: String) { M1("M1"), M2("M2"), M3("M3"), M4("M4"), POPULAR("POPULAR") }

enum class BrowseMode { Stop, Line }

/** Sheet title for a curated group: "Metro M2", "Popular stops". */
fun metroViewLabel(view: MetroView): String = if (view == MetroView.POPULAR) "Popular stops" else "Metro ${view.key}"

/** Type chips shown above the By line catalog list. */
val RouteTypeFilters = listOf("ALL", "SUBWAY", "TRAM", "TROLLEYBUS", "BUS", "RAIL", "FERRY", "COACH")
