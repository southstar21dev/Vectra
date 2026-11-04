package dev.southstar21.vectra.core.data

import kotlinx.io.files.Path

/** Blocks until the file's contents are on disk. Throws when that fails. */
internal expect fun syncFile(path: Path)

/**
 * Blocks until the directory's entries (a rename into it) are on disk. Best effort: not
 * every file system can sync a directory, and the data itself is already safe by then.
 */
internal expect fun syncDirectory(path: Path)
