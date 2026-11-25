package dev.southstar21.vectra.core.designsystem

import dev.southstar21.vectra.core.designsystem.components.ToastAction
import dev.southstar21.vectra.core.designsystem.components.Toaster
import kotlin.test.Test
import kotlin.test.assertEquals

class ToasterQueueTest {
    @Test fun everyQueuedUndoToastIsKept() {
        val toaster = Toaster()
        repeat(3) { n -> toaster.show("Session deleted", "Session $n", ToastAction("Undo") {}) }
        assertEquals(listOf("Session 0", "Session 1", "Session 2"), toaster.queue.map { it.description })
    }

    @Test fun aQueuedPlainToastIsStillReplacedByANewerOneOfTheSameTitle() {
        val toaster = Toaster()
        toaster.show("Busy", null, ToastAction("Undo") {})
        toaster.show("Saved", "a")
        toaster.show("Saved", "b")
        assertEquals(listOf("Busy", "Saved"), toaster.queue.map { it.title })
        assertEquals("b", toaster.queue.last().description)
    }
}
