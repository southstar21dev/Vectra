package dev.southstar21.vectra.core.data

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.io.files.Path

internal actual fun syncFile(path: Path) {
    RandomAccessFile(path.toString(), "rw").use { it.fd.sync() }
}

internal actual fun syncDirectory(path: Path) {
    try {
        FileChannel.open(Paths.get(path.toString()), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // See the expect declaration.
    }
}
