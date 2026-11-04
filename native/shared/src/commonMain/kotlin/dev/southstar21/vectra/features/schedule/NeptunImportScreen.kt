package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.southstar21.vectra.core.auth.AuthRepository
import dev.southstar21.vectra.core.designsystem.components.BarLeading
import dev.southstar21.vectra.core.designsystem.components.BottomBar
import dev.southstar21.vectra.core.designsystem.components.LocalToaster
import dev.southstar21.vectra.core.designsystem.components.ScreenColumn
import dev.southstar21.vectra.core.designsystem.components.ScrollBody
import dev.southstar21.vectra.core.designsystem.components.SectionTitle
import dev.southstar21.vectra.core.designsystem.components.SurfaceGutter
import dev.southstar21.vectra.core.designsystem.components.TopBar
import dev.southstar21.vectra.core.designsystem.components.TopBarSubtitle
import dev.southstar21.vectra.core.designsystem.components.VBackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.FormGroup
import dev.southstar21.vectra.core.designsystem.components.SheetInputHeight
import dev.southstar21.vectra.core.designsystem.components.Spinner
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VInput
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.LocalContentColor
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Palette
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.bleed
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.util.runSuspendCatching
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import dev.southstar21.vectra.core.designsystem.theme.Radius

private data class NeptunStep(val title: String, val body: AnnotatedString, val image: List<String>, val alt: String)

private fun bold(vararg parts: Pair<String, Boolean>) = buildAnnotatedString {
    for ((text, strong) in parts) if (strong) withStyle(SpanStyle(fontWeight = Weight.Bold)) { append(text) } else append(text)
}

/** Where the subscription link lives in Neptun, with a crop of each screen. */
private val NeptunSteps = listOf(
    NeptunStep("Open Calendar", bold("In Neptun, open " to false, "Menu" to true, " at the top left and choose " to false, "Calendar" to true, "." to false), NeptunStep1Png, "Neptun menu with Calendar highlighted"),
    NeptunStep("Calendar management", bold("On the calendar page, tap " to false, "Calendar management" to true, "." to false), NeptunStep2Png, "The Calendar management button"),
    NeptunStep("Copy the link", bold("Choose " to false, "Copy subscription link" to true, ", then paste it above." to false), NeptunStep3Png, "Copy subscription link in the Calendar management menu"),
)

private fun plural(n: Int, word: String, many: String = "${word}s") = "$n ${if (n == 1) word else many}"

/**
 * "Sync Neptun": paste or reuse the saved link, fetch through the proxy, preview the one
 * semester a sync imports, then replace. Back from the preview returns to the link step.
 */
@Composable
internal fun NeptunImportScreen(repo: ScheduleRepository, auth: AuthRepository, onBack: () -> Unit) {
    val c = Vectra.colors
    val haptics = Vectra.haptics
    val toaster = LocalToaster.current
    val profile by auth.profile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val exit = rememberExitOnce(onBack)
    var subscriptionUrl by remember { mutableStateOf("") }
    // A saved link shows the "saved" card at once; its decrypted URL follows below.
    var secureConnected by remember { mutableStateOf(!profile?.neptunIcsUrl.isNullOrBlank()) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<List<ParsedIcsEvent>>(emptyList()) }
    var parseErrors by remember { mutableStateOf<List<IcsParseError>>(emptyList()) }
    var parsing by remember { mutableStateOf(false) }
    var semester by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var scrolled by remember { mutableStateOf(false) }

    // The profile is the single source of truth for the saved link. It decrypts after
    // the screen opens, and again whenever this flow saves a new one.
    var initialUrl by remember { mutableStateOf("") }
    LaunchedEffect(profile?.neptunIcsUrl) { initialUrl = runSuspendCatching { repo.savedNeptunUrl() }.getOrDefault("") }
    var prevInitial by remember { mutableStateOf("") }
    if (initialUrl != prevInitial) {
        prevInitial = initialUrl
        if (initialUrl.isNotEmpty()) {
            subscriptionUrl = initialUrl
            secureConnected = true
        }
    }

    val semesterEvents = semester?.let { s -> parsed.filter { it.semester == s } }.orEmpty()
    fun reset() {
        error = null; parsed = emptyList(); parseErrors = emptyList(); semester = null
    }

    suspend fun saveUrl(url: String) {
        try {
            repo.saveNeptunUrl(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            toaster.error("Failed to save Neptun URL", "The encryption service is unavailable. Please try again.")
        }
    }

    fun processUrl() {
        if (subscriptionUrl.isBlank()) {
            error = "Please enter a valid Neptun calendar subscription URL."
            return
        }
        error = null
        parsing = true
        scope.launch {
            try {
                val text = repo.fetchNeptunIcs(subscriptionUrl.trim())
                val result = repo.parseIcs(text)
                if (result.valid.isEmpty()) error("No valid class events found in the Neptun calendar feed.")
                parsed = result.valid
                parseErrors = result.errors
                semester = repo.pickNeptunSyncSemester(result.valid)
                    ?: error("No class events found for the current or upcoming semester.")
                if (!secureConnected) {
                    saveUrl(subscriptionUrl.trim())
                    // Clear the unencrypted URL from the input unless re-syncing.
                    subscriptionUrl = ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message?.ifBlank { null } ?: "Failed to fetch or parse Neptun calendar from URL."
                parsed = emptyList()
                haptics.reject()
            } finally {
                parsing = false
            }
        }
    }

    fun confirmImport() {
        if (semesterEvents.isEmpty()) return
        saving = true
        scope.launch {
            try {
                repo.importNeptunEvents(semesterEvents.map { it.toSyncEvent() })
                haptics.confirm()
                exit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message?.ifBlank { null } ?: "Failed to save imported events."
                haptics.reject()
            } finally {
                saving = false
            }
        }
    }

    val hasPreview = parsed.isNotEmpty()
    // The preview is the second step: back returns to the link instead of leaving.
    val back: () -> Unit = {
        if (!hasPreview) exit() else if (!saving) reset()
    }
    VBackHandler(enabled = hasPreview) { back() }

    // The preview names each course once; sessions and exams show only as counts.
    val previewCourses = LinkedHashMap<String, ParsedIcsEvent>()
    for (e in semesterEvents) {
        val key = e.courseName.lowercase().trim()
        if (e.eventType != "exam" && key !in previewCourses) previewCourses[key] = e
    }
    val exams = semesterEvents.count { it.eventType == "exam" }
    val classes = semesterEvents.size - exams

    ScreenColumn {
        TopBar(
            title = "Sync Neptun",
            leading = BarLeading.Back,
            onLeading = back,
            subtitle = { TopBarSubtitle(if (hasPreview) "Check the preview before syncing" else "Keep your timetable up to date") },
            scrolled = scrolled,
        )
        ScrollBody(
            scrollState = rememberScrollState(),
            onScrolledChange = { scrolled = it },
            contentPadding = PaddingValues(start = SurfaceGutter, end = SurfaceGutter, top = 8.dp, bottom = 24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                error?.let { msg -> ErrorBanner(msg) }
                when {
                    hasPreview -> SheetSection {
                        SectionTitle(semester ?: "This semester") {
                            AccentTextButton("Change link", enabled = !saving) { reset() }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                (if (previewCourses.size == 1) "Course" else "Courses") to previewCourses.size,
                                (if (classes == 1) "Class" else "Classes") to classes,
                                (if (exams == 1) "Exam" else "Exams") to exams,
                            ).forEach { (label, value) ->
                                Column(Modifier.weight(1f).glass(Radius.Panel).padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    VText(value.toString(), style = vtext(TypeScale.Title, Weight.Extrabold), color = c.textPrimary)
                                    VText(label, style = vtext(TypeScale.Caption), color = c.textMuted)
                                }
                            }
                        }
                        if (parseErrors.isNotEmpty()) {
                            val shape = Radius.Panel
                            Row(
                                Modifier.fillMaxWidth().background(Palette.Amber500.copy(alpha = 0.1f), shape).border(1.dp, Palette.Amber500.copy(alpha = 0.3f), shape)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                VIcon(RemixIcons.ErrorWarningLine, tint = c.tints.amber.text)
                                VText("${plural(parseErrors.size, "item")} couldn’t be read and will be skipped.", style = vtext(TypeScale.Caption), color = c.textSecondary)
                            }
                        }
                        if (previewCourses.isNotEmpty()) {
                            FormGroup(rows = previewCourses.values.map { evt ->
                                @Composable {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(Modifier.size(10.dp).background(parseOpaqueHexColor(evt.color.ifEmpty { "#3B82F6" }), CircleShape))
                                        VText(evt.courseName, style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary, maxLines = 1)
                                    }
                                }
                            })
                        }
                    }
                    secureConnected -> SheetSection {
                        FormGroup(rows = listOf({
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(40.dp).background(Palette.Emerald500.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                    VIcon(RemixIcons.LinkM, size = 20.dp, tint = c.tints.emerald.text)
                                }
                                Column(Modifier.weight(1f)) {
                                    VText("Neptun link saved", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
                                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        VIcon(RemixIcons.LockLine, size = 12.dp, tint = c.textMuted)
                                        VText("Encrypted · syncs across your devices", style = vtext(TypeScale.Caption), color = c.textMuted, maxLines = 1)
                                    }
                                }
                                AccentTextButton("Change", enabled = !parsing) {
                                    secureConnected = false
                                    subscriptionUrl = ""
                                }
                            }
                        }))
                        VText(
                            "Fetch to preview this semester’s classes and exams. Nothing changes until you confirm.",
                            Modifier.padding(horizontal = 4.dp),
                            vtext(TypeScale.Caption),
                            c.textMuted,
                        )
                    }
                    else -> {
                        SheetSection {
                            SectionTitle("Subscription link")
                            VInput(
                                value = subscriptionUrl,
                                onValueChange = { subscriptionUrl = it },
                                placeholder = "Paste the link from Neptun",
                                enabled = !parsing,
                                height = SheetInputHeight,
                                textSize = TypeScale.BodyLarge,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                                keyboardActions = KeyboardActions(onGo = { if (subscriptionUrl.isNotBlank() && !parsing) processUrl() }),
                            )
                            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                VIcon(RemixIcons.LockLine, Modifier.padding(top = 2.dp), size = 12.dp, tint = c.textMuted)
                                VText(
                                    "The link holds a private token, so it and your classes are encrypted (AES-256-GCM) before they’re stored.",
                                    style = vtext(TypeScale.Caption),
                                    color = c.textMuted,
                                )
                            }
                        }
                        SheetSection {
                            SectionTitle("Where to find it")
                            // Bleeds to the screen edge so the next card peeks in as a scroll hint.
                            Row(
                                Modifier
                                    .bleed(SurfaceGutter)
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = SurfaceGutter),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                NeptunSteps.forEachIndexed { i, step -> TutorialCard(i, step) }
                            }
                        }
                    }
                }
            }
        }
        BottomBar {
            if (hasPreview) {
                EditorSubmitButton(!saving && semesterEvents.isNotEmpty(), onClick = { confirmImport() }) {
                    if (saving) Spinner(size = 16.dp, color = LocalContentColor.current, thickness = 2.dp)
                    else {
                        VIcon(RemixIcons.CheckLine)
                        VText("Sync ${plural(classes, "class", "classes")} · ${plural(exams, "exam")}", style = vtext(TypeScale.Body, Weight.Bold), maxLines = 1)
                    }
                }
            } else {
                // A saved link can be fetched once it has decrypted into the field.
                EditorSubmitButton(!parsing && subscriptionUrl.isNotBlank(), onClick = { processUrl() }) {
                    if (parsing) {
                        Spinner(size = 16.dp, color = LocalContentColor.current, thickness = 2.dp)
                        VText("Fetching…", style = vtext(TypeScale.Body, Weight.Bold))
                    } else VText("Fetch calendar", style = vtext(TypeScale.Body, Weight.Bold))
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val c = Vectra.colors
    val shape = Radius.Panel
    Row(
        Modifier.fillMaxWidth().background(Palette.Rose500.copy(alpha = 0.1f), shape).border(1.dp, Palette.Rose500.copy(alpha = 0.3f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VIcon(RemixIcons.ErrorWarningLine, Modifier.padding(top = 2.dp), size = 20.dp, tint = c.tints.red.text)
        Column {
            VText("Couldn’t sync", style = vtext(TypeScale.Body, Weight.Semibold), color = c.textPrimary)
            VText(message, Modifier.padding(top = 2.dp), vtext(TypeScale.Caption, lineHeight = 19.5.sp), c.textSecondary)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun TutorialCard(index: Int, step: NeptunStep) {
    val c = Vectra.colors
    val shape = Radius.Card
    val bitmap = remember(step) {
        runCatching { Base64.Default.decode(step.image.joinToString("")).decodeToImageBitmap() }.getOrNull()
    }
    Column(Modifier.width(240.dp).glass(shape).clip(shape)) {
        Box(Modifier.fillMaxWidth().height(128.dp).background(Color.White).padding(12.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, step.alt, contentScale = ContentScale.Fit)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).background(c.accentPrimary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    VText("${index + 1}", style = vtext(11.sp to 11.sp, Weight.Bold), color = c.accentPrimary)
                }
                VText(step.title, style = vtext(TypeScale.Body, Weight.Bold), color = c.textPrimary, maxLines = 1)
            }
            VText(step.body, style = vtext(TypeScale.Caption, lineHeight = 19.5.sp), color = c.textSecondary)
        }
    }
}
