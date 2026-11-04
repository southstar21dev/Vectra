package dev.southstar21.vectra.core.platform

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPermissionRequester(kind: PermissionKind): PermissionRequester {
    var pending: ((Boolean) -> Unit)? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending?.invoke(result.values.any { it })
        pending = null
    }
    return remember(kind) {
        PermissionRequester { onResult ->
            val perms = when (kind) {
                PermissionKind.Location -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                PermissionKind.Notifications ->
                    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
            }
            if (perms.isEmpty()) onResult(true) else {
                pending = onResult
                launcher.launch(perms)
            }
        }
    }
}
