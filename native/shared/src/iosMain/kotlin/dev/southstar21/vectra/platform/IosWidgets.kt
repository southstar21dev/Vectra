package dev.southstar21.vectra.platform

import dev.southstar21.vectra.core.platform.WidgetHost
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
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
        val container = bridge.containerPath() ?: return
        val dir = prepareDirectory(container)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val text = NSString.create(string = snapshot)
        if (text.writeToFile("$dir/$SnapshotFile", atomically = true, encoding = NSUTF8StringEncoding, error = null)) {
            bridge.reloadAll()
        }
    }

    /**
     * The snapshot lives in its own subdirectory, excluded from iCloud and Finder backups like the
     * main store. The flag sits on the directory, so it also covers the file replaced by rename.
     * The copy older builds left in the container root is removed.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun prepareDirectory(container: String): String {
        val dir = "$container/$SnapshotDir"
        val files = NSFileManager.defaultManager
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        NSURL.fileURLWithPath(dir, isDirectory = true)
            .setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        files.removeItemAtPath("$container/$SnapshotFile", error = null)
        return dir
    }

    /** Also in iosApp/Shared/AppGroup.swift. */
    private const val SnapshotDir = "Widgets"
    private const val SnapshotFile = "widget_snapshot.json"
}
