package dev.southstar21.vectra.core.designsystem

import androidx.compose.ui.semantics.LiveRegionMode
import dev.southstar21.vectra.core.designsystem.components.ToastType
import dev.southstar21.vectra.core.designsystem.components.toastLiveRegion
import dev.southstar21.vectra.core.designsystem.components.topModalIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class ModalSurfaceAccessibilityTest {
    @Test
    fun modalOverlayCoversThePageAndTheLayersBelowIt() {
        assertEquals(-1, topModalIndex(emptyList()))
        assertEquals(-1, topModalIndex(listOf(false)))
        // A sheet with a confirm dialog over it: the dialog (index 1) is the one left readable.
        assertEquals(1, topModalIndex(listOf(true, true)))
        assertEquals(0, topModalIndex(listOf(true, false)))
    }

    @Test
    fun toastsAreLiveRegionsAndErrorsInterrupt() {
        assertEquals(LiveRegionMode.Assertive, toastLiveRegion(ToastType.Error))
        for (type in listOf(ToastType.Default, ToastType.Success, ToastType.Warning)) {
            assertEquals(LiveRegionMode.Polite, toastLiveRegion(type))
        }
    }
}
