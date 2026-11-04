package dev.southstar21.vectra.features.coursetracker

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.southstar21.vectra.core.designsystem.components.ToastAction
import dev.southstar21.vectra.core.designsystem.components.Toaster
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * The Course Hub's toasts (Undo actions, save confirmations), raised through the
 * app toaster, which paints above dialogs.
 */
@Stable
internal class UndoToastState(private val toaster: Toaster) {
    /** toast(title, { action: Undo, duration: 6000 }) */
    fun show(title: String, description: String?, actionLabel: String = "Undo", onAction: () -> Unit) {
        toaster.show(title, description, ToastAction(actionLabel, onAction), durationMs = 6000)
    }

    /** A success toast: a check icon, no action, the default 4 s lifetime. */
    fun success(title: String, description: String?) {
        toaster.success(title, description)
    }
}
