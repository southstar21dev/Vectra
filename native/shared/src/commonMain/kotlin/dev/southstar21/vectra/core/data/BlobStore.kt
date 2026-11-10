package dev.southstar21.vectra.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * One file per key, for values too large for the shared preferences file. Writes go
 * to a temp file and rename over the target, so a reader never sees half a value, and
 * both are flushed to disk: `vectra_schedule_items` holds the unsynced offline queue,
 * which has no other copy and must survive a power cut right after the write.
 */
class BlobStore(
    private val dir: Path,
    private val fs: FileSystem = SystemFileSystem,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val flushFile: (Path) -> Unit = ::syncFile,
    private val flushDirectory: (Path) -> Unit = ::syncDirectory,
) {
    private val writes = Mutex()

    private fun path(key: String) = Path(dir, encodeBlobKey(key))

    /** The stored value; an unreadable file counts as a cache miss. */
    suspend fun get(key: String): String? = withContext(io) {
        val file = path(key)
        try {
            if (fs.exists(file)) fs.source(file).buffered().use { it.readString() } else null
        } catch (_: IOException) {
            null
        }
    }

    /** Stores [value], or removes the key when it is null. Throws when the write fails. */
    suspend fun set(key: String, value: String?) = writes.withLock {
        withContext(io) {
            val file = path(key)
            if (value == null) {
                fs.delete(file, mustExist = false)
            } else {
                fs.createDirectories(dir)
                val temp = Path(dir, encodeBlobKey(key) + TempSuffix)
                fs.sink(temp).buffered().use { it.writeString(value) }
                // In this order: a rename that reaches disk before the data leaves an empty file.
                flushFile(temp)
                fs.atomicMove(temp, file)
                flushDirectory(dir)
            }
        }
    }

    /** Removes every key starting with one of [prefixes], except the exact keys in [keep]. */
    suspend fun removeByPrefix(vararg prefixes: String, keep: Set<String> = emptySet()) = writes.withLock {
        withContext(io) {
            if (!fs.exists(dir)) return@withContext
            val encoded = prefixes.map(::encodeBlobKey)
            val kept = keep.mapTo(HashSet(), ::encodeBlobKey)
            for (file in fs.list(dir)) {
                if (file.name !in kept && encoded.any(file.name::startsWith)) fs.delete(file, mustExist = false)
            }
        }
    }

    private companion object {
        const val TempSuffix = ".tmp"
    }
}

/** A key as a file name: anything outside `[A-Za-z0-9._-]` is percent-encoded. */
internal fun encodeBlobKey(key: String): String = buildString {
    for (byte in key.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') append(c)
        else append('%').append((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
    }
}
