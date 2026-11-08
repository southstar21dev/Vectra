package dev.southstar21.vectra.core.platform

/**
 * Home-screen widgets. The app hands over a JSON snapshot of the coming days; the platform keeps it
 * where its widgets read it (a file on Android, the App Group container on iOS) and redraws them.
 */
interface WidgetHost {
    fun publish(snapshot: String)
}

object NoWidgets : WidgetHost {
    override fun publish(snapshot: String) = Unit
}
