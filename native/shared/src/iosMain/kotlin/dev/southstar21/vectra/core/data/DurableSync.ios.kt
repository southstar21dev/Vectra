package dev.southstar21.vectra.core.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.F_FULLFSYNC
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.open

/** fsync alone leaves data in the drive's cache on Apple platforms; F_FULLFSYNC flushes that too. */
@OptIn(ExperimentalForeignApi::class)
private fun flush(fd: Int): Boolean = fcntl(fd, F_FULLFSYNC) == 0 || fsync(fd) == 0

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncFile(path: Path) {
    val fd = open(path.toString(), O_RDWR)
    if (fd < 0) throw IOException("open failed for $path: errno $errno")
    try {
        if (!flush(fd)) throw IOException("fsync failed for $path: errno $errno")
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncDirectory(path: Path) {
    val fd = open(path.toString(), O_RDONLY)
    if (fd < 0) return
    flush(fd)
    close(fd)
}
