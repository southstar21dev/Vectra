package dev.southstar21.vectra.features.update

/** Where this install gets its updates: the update screen's button. */
data class UpdateTarget(val label: String, val url: String)

expect fun updateTarget(): UpdateTarget

internal const val GitHubLatestRelease = "https://github.com/southstar21dev/Vectra/releases/latest"

/** The install page next to the AltStore source (.github/pages/index.html). */
internal const val AltStoreInstallPage = "https://southstar21dev.github.io/Vectra/"

/** Opens the TestFlight app (a deep link to Vectra's own page there would need its numeric Apple ID). */
internal const val TestFlightApp = "itms-beta://"
