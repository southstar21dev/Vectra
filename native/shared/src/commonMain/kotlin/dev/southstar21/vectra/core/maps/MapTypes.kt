package dev.southstar21.vectra.core.maps

/** Stop marker kind; [value] is the string written into GeoJSON `kind` properties. */
enum class MapStopKind(val value: String) {
    Regular("regular"),
    Terminus("terminus"),
    Tracked("tracked"),
    Selected("selected"),
    Secondary("secondary"),
    SecondaryTerminus("secondary-terminus");

    companion object {
        fun fromValue(value: String?): MapStopKind? = entries.firstOrNull { it.value == value }
    }
}

data class MapStop(
    val id: String? = null,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: MapStopKind? = null,
)

/** Label collision priority: selected > tracked > terminus > regular > secondary. */
fun labelPriority(kind: MapStopKind?): Int = when (kind) {
    MapStopKind.Selected -> 4
    MapStopKind.Tracked -> 3
    MapStopKind.Terminus -> 2
    MapStopKind.Secondary, MapStopKind.SecondaryTerminus -> 0
    else -> 1
}
