package dev.southstar21.vectra.features.weather

import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.southstar21.vectra.core.designsystem.components.VToggle
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.platform.PermissionKind
import dev.southstar21.vectra.core.platform.rememberPermissionRequester
import dev.southstar21.vectra.core.designsystem.components.SettingsRow

private fun copy(status: WeatherLocationStatus): String = when (status) {
    WeatherLocationStatus.Idle -> "Checking location…"
    WeatherLocationStatus.Locating -> "Reading your location…"
    WeatherLocationStatus.Located -> "Local weather and nearby stops"
    WeatherLocationStatus.Budapest -> "Off: weather shows Budapest"
    WeatherLocationStatus.Fallback -> "Not granted: weather shows Budapest"
    WeatherLocationStatus.Denied -> "Denied. Allow location for Vectra in system settings"
    WeatherLocationStatus.Unsupported -> "Location is not supported on this device"
}

/** The Permissions › Location row: weather and nearby stops follow the device, or weather stays on Budapest. */
@Composable
fun LocationSettingRow(repo: WeatherRepository) {
    val c = Vectra.colors
    val state by repo.location.collectAsStateWithLifecycle()
    // Settings stays composed in its tab, so re-read the OS permission on every resume.
    LifecycleResumeEffect(repo) {
        repo.onForeground()
        onPauseOrDispose { }
    }
    val requester = rememberPermissionRequester(PermissionKind.Location)
    val s = state
    val status = s?.status ?: WeatherLocationStatus.Idle
    val subtitle = if (status == WeatherLocationStatus.Located && s?.city != null) "${copy(status)}, ${s.city}" else copy(status)
    SettingsRow(RemixIcons.MapPinLine, c.tints.amber.text, "Location", subtitle) {
        VToggle(locationToggleOn(s?.mode, status), { checked ->
            if (checked) requester.request { repo.setMode(WeatherLocationMode.Auto) } else repo.setMode(WeatherLocationMode.Budapest)
        }, label = "Allow location access")
    }
}

/** The switch shows on only while the app really uses the device location, not when it is denied or not granted. */
internal fun locationToggleOn(mode: WeatherLocationMode?, status: WeatherLocationStatus): Boolean =
    mode == WeatherLocationMode.Auto && status != WeatherLocationStatus.Denied && status != WeatherLocationStatus.Fallback
