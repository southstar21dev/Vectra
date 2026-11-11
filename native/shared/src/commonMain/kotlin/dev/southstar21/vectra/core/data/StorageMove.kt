package dev.southstar21.vectra.core.data

import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Where [moveStorage] puts a copy it found at both places, under the new directory. */
internal const val SupersededDirName = "superseded"

/**
 * Moves each of [names] (files or directories) from [from] to [to], for a store that changes
 * directory between releases. Safe to run on every launch and to interrupt: each step is a single
 * rename within one volume, so every item is always whole at one place or the other, and the next
 * run finishes the job. An item found at both places keeps the copy at [to], the one the app has
 * been reading; the other is moved into [to]/[SupersededDirName] rather than deleted.
 */
internal fun moveStorage(from: Path, to: Path, names: List<String>, fs: FileSystem = SystemFileSystem) {
    if (names.none { fs.exists(Path(from, it)) }) return
    fs.createDirectories(to)
    for (name in names) {
        val old = Path(from, name)
        if (fs.exists(old)) fs.atomicMove(old, destination(fs, to, name))
    }
}

/** [to]/[name], or, when that is already taken, a cleared slot for it under [SupersededDirName]. */
private fun destination(fs: FileSystem, to: Path, name: String): Path {
    val new = Path(to, name)
    if (!fs.exists(new)) return new
    val parked = Path(to, SupersededDirName)
    fs.createDirectories(parked)
    return Path(parked, name).also { deleteRecursively(fs, it) }
}

private fun deleteRecursively(fs: FileSystem, path: Path) {
    val meta = fs.metadataOrNull(path) ?: return
    if (meta.isDirectory) fs.list(path).forEach { deleteRecursively(fs, it) }
    fs.delete(path, mustExist = false)
}
