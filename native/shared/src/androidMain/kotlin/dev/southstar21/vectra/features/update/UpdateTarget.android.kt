package dev.southstar21.vectra.features.update

/** Android installs come from the GitHub release APK (directly or through Obtainium). */
actual fun updateTarget(): UpdateTarget = UpdateTarget("Get the update", GitHubLatestRelease)
