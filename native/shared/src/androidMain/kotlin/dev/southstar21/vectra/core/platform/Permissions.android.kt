package dev.southstar21.vectra.core.platform

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPermissionRequester(kind: PermissionKind): PermissionRequester {
    // Remembered: the screen recomposes while the system dialog is up, and a plain local would
    // leave the result callback reading a fresh, empty one.
    val pending = remember { PendingResult() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending.callback?.invoke(result.values.any { it })
        pending.callback = null
    }
    return remember(kind) {
        PermissionRequester { onResult ->
            val perms = when (kind) {
                PermissionKind.Location -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                PermissionKind.Notifications ->
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            }
            if (perms.isEmpty()) onResult(true) else {
                pending.callback = onResult
                launcher.launch(perms)
            }
        }
    }
}

private class PendingResult {
    var callback: ((Boolean) -> Unit)? = null
}
