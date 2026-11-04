package dev.southstar21.vectra.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-app paths opened from outside (notification taps carry `data.url`).
 * Consumed once by the shell.
 */
object DeepLinks {
    val pending = MutableStateFlow<String?>(null)

    /** Section paths (from notification and app links) → routes; sub-paths render their section. */
    fun routeFor(path: String): Route? {
        val p = path.substringBefore('?').substringBefore('#').trimEnd('/').ifEmpty { "/" }
        return when {
            p == "/" -> Route.Dashboard
            p == "/schedule" || p.startsWith("/schedule/") -> Route.Schedule
            p == "/transit" || p.startsWith("/transit/") -> Route.Transit
            p == "/settings" || p.startsWith("/settings/") -> Route.Settings
            else -> null
        }
    }
}
