package dev.southstar21.vectra.core.platform

import androidx.compose.runtime.Composable

enum class PermissionKind { Location, Notifications }

/** Launches the system permission prompt; the callback receives whether it was granted. */
fun interface PermissionRequester {
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberPermissionRequester(kind: PermissionKind): PermissionRequester
