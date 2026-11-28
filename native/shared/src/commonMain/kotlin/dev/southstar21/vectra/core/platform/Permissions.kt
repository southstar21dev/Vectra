package dev.southstar21.vectra.core.platform

import androidx.compose.runtime.Composable

internal enum class PermissionKind { Location, Notifications }

/** Launches the system permission prompt; the callback receives whether it was granted. */
internal fun interface PermissionRequester {
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
internal expect fun rememberPermissionRequester(kind: PermissionKind): PermissionRequester
