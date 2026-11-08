package dev.southstar21.vectra.platform

import dev.southstar21.vectra.core.platform.WidgetHost
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * Implemented in Swift (iosApp's WidgetBridge): finds the App Group container the widget
 * extension shares, which a sideloading store renames, and asks WidgetKit to redraw.
 */
interface IosWidgetBridge {
    /** The shared container's path, or null when the app isn't entitled to any App Group. */
    fun containerPath(): String?

    fun reloadAll()
}

/** Writes the snapshot where VectraWidgets reads it. Snapshots before the bridge attaches are replayed. */
object IosWidgets : WidgetHost {
    private var bridge: IosWidgetBridge? = null
    private var pending: String? = null

    fun attach(bridge: IosWidgetBridge) {
        this.bridge = bridge
        pending?.let { pending = null; publish(it) }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override fun publish(snapshot: String) {
        val bridge = bridge ?: run { pending = snapshot; return }
        val dir = bridge.containerPath() ?: return
        @Suppress("CAST_NEVER_SUCCEEDS")
        val text = NSString.create(string = snapshot)
        if (text.writeToFile("$dir/$SnapshotFile", atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
            bridge.reloadAll()
        }
    }

    /** Also in iosApp/VectraWidgets/Snapshot.swift. */
    private const val SnapshotFile = "widget_snapshot.json"
}
