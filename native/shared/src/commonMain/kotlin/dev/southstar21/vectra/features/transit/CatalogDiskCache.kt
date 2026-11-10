package dev.southstar21.vectra.features.transit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.coroutines.CoroutineContext

/**
 * The catalog's offline copy: each file next to `<file>.ts` (when it was fetched or last
 * confirmed) and `<file>.etag`. It lives in app storage, not the temp dir, which the OS
 * empties on its own schedule (audit 03 #17). A copy left in [legacyDir] by an older build
 * is moved over on first read, so updating the app doesn't cost a download.
 */
class CatalogDiskCache(
    private val dir: Path,
    private val legacyDir: Path? = null,
    private val fs: FileSystem = SystemFileSystem,
    private val io: CoroutineContext = Dispatchers.Default,
) {
    /** The cached text of [file] and when it was stored, or null when there is no complete copy. */
    suspend fun read(file: String): Pair<Long, String>? = withContext(io) {
        runCatching {
            if (!fs.exists(Path(dir, "$file.ts"))) migrateLegacy(file)
            val p = Path(dir, file)
            val meta = Path(dir, "$file.ts")
            if (!fs.exists(p) || !fs.exists(meta)) return@runCatching null
            val ts = readText(meta).trim().toLong()
            ts to readText(p)
        }.getOrNull()
    }

    suspend fun etag(file: String): String? = withContext(io) {
        runCatching {
            val p = Path(dir, "$file.etag")
            if (fs.exists(p)) readText(p).ifEmpty { null } else null
        }.getOrNull()
    }

    suspend fun write(file: String, text: String, etag: String?, now: Long) = withContext(io) {
        runCatching {
            fs.createDirectories(dir)
            writeAtomically(file, text)
            writeAtomically("$file.etag", etag.orEmpty())
            // Last: a copy only counts once its timestamp exists.
            writeAtomically("$file.ts", now.toString())
        }
    }

    /** A 304 confirmed the copy: its age starts over. */
    suspend fun touch(file: String, now: Long) = withContext(io) {
        runCatching { writeAtomically("$file.ts", now.toString()) }
    }

    private fun readText(p: Path): String = fs.source(p).buffered().use { it.readString() }

    private fun writeAtomically(name: String, text: String) {
        val temp = Path(dir, "$name.tmp")
        fs.sink(temp).buffered().use { it.writeString(text) }
        fs.atomicMove(temp, Path(dir, name))
    }

    private fun migrateLegacy(file: String) {
        val old = legacyDir ?: return
        if (!fs.exists(Path(old, file)) || !fs.exists(Path(old, "$file.ts"))) return
        fs.createDirectories(dir)
        // The timestamp goes last, as in [write]: an interrupted move leaves no half copy behind.
        for (name in listOf(file, "$file.etag", "$file.ts")) {
            val from = Path(old, name)
            if (fs.exists(from)) {
                writeAtomically(name, readText(from))
                fs.delete(from, mustExist = false)
            }
        }
    }
}
