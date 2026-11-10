package dev.southstar21.vectra.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StorageMoveTest {
    private val fs = SystemFileSystem
    private val root = Path(SystemTemporaryDirectory, "vectra-move-test-${Random.nextLong().toULong()}")
    private val documents = Path(root, "Documents")
    private val support = Path(root, "Support/vectra")
    private val names = listOf("prefs", "blobs")

    @AfterTest fun cleanUp() {
        runCatching { deleteAll(root) }
    }

    private fun deleteAll(path: Path) {
        if (fs.metadataOrNull(path)?.isDirectory == true) fs.list(path).forEach(::deleteAll)
        fs.delete(path, mustExist = false)
    }

    private fun write(path: Path, text: String) {
        path.parent?.let(fs::createDirectories)
        fs.sink(path).buffered().use { it.writeString(text) }
    }

    private fun read(path: Path): String? =
        if (fs.exists(path)) fs.source(path).buffered().use { it.readString() } else null

    private fun move() = moveStorage(documents, support, names)

    @Test fun anUpgradeMovesEverythingAndTheStoresStillReadIt() = runTest {
        write(Path(documents, "prefs"), "settings")
        write(Path(documents, "blobs/${encodeBlobKey("vectra_schedule_items")}"), "queue")
        write(Path(documents, "unrelated"), "kept")
        move()
        assertEquals("settings", read(Path(support, "prefs")))
        assertEquals("queue", withContext(Dispatchers.Default) { BlobStore(Path(support, "blobs")).get("vectra_schedule_items") })
        assertFalse(fs.exists(Path(documents, "prefs")))
        assertFalse(fs.exists(Path(documents, "blobs")))
        assertEquals("kept", read(Path(documents, "unrelated")))
    }

    @Test fun runningAgainChangesNothing() {
        write(Path(documents, "prefs"), "settings")
        move()
        write(Path(support, "prefs"), "newer")
        move()
        assertEquals("newer", read(Path(support, "prefs")))
        assertFalse(fs.exists(Path(support, SupersededDirName)))
    }

    @Test fun anInterruptedMoveFinishesOnTheNextRun() {
        // The previous launch moved the preferences, then stopped before the blobs.
        write(Path(support, "prefs"), "settings")
        write(Path(documents, "blobs/a"), "blob")
        move()
        assertEquals("settings", read(Path(support, "prefs")))
        assertEquals("blob", read(Path(support, "blobs/a")))
        assertFalse(fs.exists(Path(documents, "blobs")))
    }

    @Test fun aCopyAtBothPlacesKeepsTheCurrentOneAndParksTheOther() {
        write(Path(support, "prefs"), "current")
        write(Path(support, "blobs/a"), "current blob")
        write(Path(documents, "prefs"), "old")
        write(Path(documents, "blobs/a"), "old blob")
        move()
        assertEquals("current", read(Path(support, "prefs")))
        assertEquals("current blob", read(Path(support, "blobs/a")))
        assertEquals("old", read(Path(support, "$SupersededDirName/prefs")))
        assertEquals("old blob", read(Path(support, "$SupersededDirName/blobs/a")))
        assertFalse(fs.exists(Path(documents, "prefs")))

        // A later leftover replaces the parked copy instead of failing the launch.
        write(Path(documents, "blobs/b"), "older still")
        move()
        assertEquals("older still", read(Path(support, "$SupersededDirName/blobs/b")))
        assertNull(read(Path(support, "$SupersededDirName/blobs/a")))
    }

    @Test fun nothingToMoveCreatesNothing() {
        move()
        assertFalse(fs.exists(support))
    }
}
