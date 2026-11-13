package dev.southstar21.vectra.features.schedule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.SettingsRow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.PostageStamp
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.Radius

/** "Synced 5 minutes ago" from the profile's neptun_last_sync. */
internal fun syncCounterText(lastSyncMs: Long?, nowMs: Long): String {
    if (lastSyncMs == null || lastSyncMs <= 0) return "Never synced"
    val mins = (nowMs - lastSyncMs) / 60_000
    if (mins < 1) return "Synced just now"
    if (mins < 60) return "Synced $mins minute${if (mins == 1L) "" else "s"} ago"
    val hours = mins / 60
    if (hours < 24) return "Synced $hours hour${if (hours == 1L) "" else "s"} ago"
    val days = hours / 24
    return "Synced $days day${if (days == 1L) "" else "s"} ago"
}

internal fun parseSyncTimestamp(value: String?): Long? {
    val v = value?.trim()?.ifEmpty { null } ?: return null
    return runCatching { Instant.parse(v).toEpochMilliseconds() }.getOrNull()
        ?: runCatching { Instant.parse(v.replace(' ', 'T')).toEpochMilliseconds() }.getOrNull()
}

/**
 * True for an absolute http(s) link with a dotted host and no spaces: anything else can't be a
 * calendar feed, and storing it would only fail every sync. Which hosts are Neptun's is left to the proxy.
 */
internal fun isCalendarLink(text: String): Boolean {
    val lower = text.trim().lowercase()
    val rest = listOf("https://", "http://").firstOrNull(lower::startsWith)?.let { lower.removePrefix(it) }
    val host = rest?.substringBefore('/')?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('@')?.substringBefore(':')
    return host != null && lower.none(Char::isWhitespace) && '.' in host && !host.startsWith('.') && !host.endsWith('.')
}

/** A glass panel titled with a tilted postage stamp. */
@Composable
private fun SettingsSectionBox(title: String, tiltLeft: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Vectra.colors
    val shape = Radius.Card
    Box(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.fillMaxWidth().glass(shape).clip(shape).padding(top = 16.dp), content = content)
        PostageStamp(
            Modifier.padding(start = 16.dp).offset(y = (-20).dp).rotate(if (tiltLeft) -3f else 2f),
            innerModifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            VText(title, style = vtext(TypeScale.Body, Weight.Extrabold, Tracking.Tight), color = c.textPrimary)
        }
    }
}

@Composable
private fun RowDivider() {
    val line = Vectra.colors.borderSubtle
    Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
}

/** These rows keep the type scale's own line height. */
private val RowTitle = vtext(TypeScale.Body, Weight.Semibold)

/**
 * The Settings page's Neptun group (last sync + manual
 * sync with the 3/hour limit, the link, and "Clear all classes").
 */
@Composable
fun NeptunSettings(repo: ScheduleRepository, auth: AuthRepository) {
    val c = Vectra.colors
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val profile by auth.profile.collectAsStateWithLifecycle()
    val status by repo.neptunSyncStatus.collectAsStateWithLifecycle()
    val syncError by repo.neptunSyncError.collectAsStateWithLifecycle()

    var urlInput by remember { mutableStateOf("") }
    var savedUrl by remember { mutableStateOf("") }
    var rateLimited by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    // Tick every 30s so "Synced X ago" moves.
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = Clock.System.now().toEpochMilliseconds()
        }
    }
    val lastSyncMs = parseSyncTimestamp(profile?.neptunLastSync)
    val counter = syncCounterText(lastSyncMs, now)

    LaunchedEffect(profile?.neptunIcsUrl) {
        savedUrl = runSuspendCatching { repo.savedNeptunUrl() }.getOrDefault("")
        urlInput = "" // Cleared for security.
    }

    val shake = remember { Animatable(0f) }
    fun manualSync() {
        if (savedUrl.isEmpty()) return
        scope.launch {
            val rate = repo.checkSyncRateLimit()
            if (!rate.allowed) {
                rateLimited = true
                toaster.error(
                    "Rate limit reached",
                    "You can manually sync at most ${ScheduleRepository.MAX_SYNCS_PER_HOUR} times per hour to prevent Neptun servers from blocking your requests. Please wait ${rate.remainingMinutes} minute(s) before syncing again.",
                )
                launch {
                    shake.snapTo(0f)
                    shake.animateTo(0f, keyframes { durationMillis = 300; -4f at 75; 4f at 150; -4f at 225 })
                }
                delay(1500)
                rateLimited = false
                return@launch
            }
            syncing = true
            try {
                val semester = repo.runSync(savedUrl, isAutoSync = false)
                // A manual "Sync now" otherwise finishes silently.
                if (semester != null) toaster.success("Neptun schedule synced", "Imported $semester.")
            } finally {
                syncing = false
            }
        }
    }

    val busy = syncing || status == NeptunSyncStatus.Syncing
    SettingsSectionBox("Neptun") {
        SettingsRow(RemixIcons.TimeLine, c.tints.blue.text, "Last sync", counter, titleStyle = RowTitle) {
            if (savedUrl.isNotEmpty() && lastSyncMs != null) {
                SyncLink(
                    state = when {
                        rateLimited -> SyncLinkState.RateLimited
                        busy -> SyncLinkState.Syncing
                        status == NeptunSyncStatus.Success -> SyncLinkState.Synced
                        else -> SyncLinkState.Idle
                    },
                    shake = shake.value,
                    onClick = ::manualSync,
                )
            }
        }
        RowDivider()
        if (savedUrl.isNotEmpty()) {
            // Removing it lives in the Danger zone, with clearing the classes.
            SettingsRow(RemixIcons.Link, c.tints.emerald.text, "Calendar link", "Connected · stored encrypted", titleStyle = RowTitle)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    VText("Calendar link (.ics)", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
                    VText(
                        "Paste your personal Neptun calendar link to sync your classes once a day.",
                        Modifier.padding(top = 2.dp),
                        vtext(TypeScale.Caption),
                        c.textSecondary,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VInput(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = "https://neptun3r.web.uni-corvinus.hu/Hallgatoi/api/Calendar/...",
                        textSize = TypeScale.Caption,
                    )
                    VButton(
                        onClick = {
                            scope.launch {
                                val trimmed = urlInput.trim()
                                // Nothing is connected while this field shows, so there is nothing to remove.
                                if (!isCalendarLink(trimmed)) {
                                    toaster.error("That isn't a calendar link", "Copy the full https:// link from Neptun's calendar export.")
                                    return@launch
                                }
                                try {
                                    repo.saveNeptunUrl(trimmed)
                                    savedUrl = trimmed
                                    urlInput = ""
                                    toaster.success("Neptun URL saved successfully")
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    toaster.error("Failed to save Neptun URL", "The encryption service is unavailable. Please try again.")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        size = ButtonSize.Sm,
                    ) {
                        VIcon(RemixIcons.CheckLine, size = 14.dp)
                        VText("Connect", style = vtext(TypeScale.Caption, Weight.Bold))
                    }
                }
            }
        }
        if (status == NeptunSyncStatus.Error && !syncError.isNullOrEmpty()) {
            RowDivider()
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                val shape = Radius.Control
                Row(
                    Modifier.fillMaxWidth().background(Palette.Red500.copy(alpha = 0.1f), shape).border(1.dp, c.tints.red.border, shape).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VIcon(RemixIcons.ErrorWarningLine, size = 20.dp, tint = c.tints.red.text)
                    Column {
                        VText("Sync failed", style = vtext(TypeScale.Body, Weight.Bold, Tracking.Wide), color = c.tints.red.text)
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            VText(syncError!!, style = vtext(TypeScale.Caption, lineHeight = 19.5.sp), color = c.tints.red.text.copy(alpha = 0.9f))
                            Column(Modifier.drawBehind {
                                drawLine(Palette.Red500.copy(alpha = 0.2f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                            }.padding(top = 12.dp)) {
                                VText(
                                    "The automatic background sync failed. Please check your network connection, or if your URL has expired, generate a new one from Neptun.",
                                    style = vtext(TypeScale.Caption, lineHeight = 16.5.sp),
                                    color = c.tints.red.text.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun SpinningIcon(icon: ImageVector, tint: Color) {
    val t = rememberInfiniteTransition(label = "syncSpin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "syncAngle")
    VIcon(icon, Modifier.graphicsLayer { rotationZ = angle }, size = 14.dp, tint = tint)
}

internal enum class SyncLinkState { Idle, Syncing, Synced, RateLimited }

/** "Sync now" as a text link in the accent; it turns green once synced and red (and shakes) when rate limited. */
@Composable
private fun SyncLink(state: SyncLinkState, shake: Float, onClick: () -> Unit) {
    val c = Vectra.colors
    val ink = when (state) {
        SyncLinkState.RateLimited -> c.tints.red.text
        SyncLinkState.Synced -> c.tints.emerald.text
        else -> c.accentPrimary
    }
    Row(
        Modifier
            .offset(x = shake.dp)
            .defaultMinSize(minHeight = 36.dp)
            .tappable(enabled = state == SyncLinkState.Idle || state == SyncLinkState.Synced, role = Role.Button, highlight = false, pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            SyncLinkState.RateLimited -> VIcon(RemixIcons.ErrorWarningLine, size = 14.dp, tint = ink)
            SyncLinkState.Syncing -> SpinningIcon(RemixIcons.RefreshLine, ink)
            SyncLinkState.Synced -> VIcon(RemixIcons.CheckboxCircleLine, size = 14.dp, tint = ink)
            SyncLinkState.Idle -> VIcon(RemixIcons.RefreshLine, size = 14.dp, tint = ink)
        }
        VText(
            when (state) {
                SyncLinkState.RateLimited -> "Rate limited"
                SyncLinkState.Synced -> "Synced"
                SyncLinkState.Syncing -> "Syncing…"
                SyncLinkState.Idle -> "Sync now"
            },
            style = vtext(TypeScale.Body, Weight.Bold),
            color = ink,
            maxLines = 1,
        )
    }
}
