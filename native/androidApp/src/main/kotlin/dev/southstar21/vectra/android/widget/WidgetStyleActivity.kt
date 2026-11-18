package dev.southstar21.vectra.android.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import dev.southstar21.vectra.android.VectraApplication
import dev.southstar21.vectra.core.designsystem.components.ButtonSize
import dev.southstar21.vectra.core.designsystem.components.ButtonVariant
import dev.southstar21.vectra.core.designsystem.components.PostageStamp
import dev.southstar21.vectra.core.designsystem.components.VButton
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.theme.Radius
import dev.southstar21.vectra.core.designsystem.theme.Tracking
import dev.southstar21.vectra.core.designsystem.theme.TypeScale
import dev.southstar21.vectra.core.designsystem.theme.Vectra
import dev.southstar21.vectra.core.designsystem.theme.VectraTheme
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.glass
import dev.southstar21.vectra.core.designsystem.theme.vtext
import dev.southstar21.vectra.core.platform.rememberSystemReduceMotion
import kotlinx.coroutines.launch

/**
 * A widget's style screen: opens when the widget is added (and from its long-press "Reconfigure"),
 * and saves the look in that widget's own state.
 */
class WidgetStyleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out leaves the widget unplaced, as Android expects from a configure screen.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val manager = GlanceAppWidgetManager(this)
        val glanceId = manager.getGlanceIdBy(widgetId)
        val label = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.loadLabel(packageManager) ?: "Widget"
        val appearance = (application as VectraApplication).graph.appearanceRepository
        lifecycleScope.launch {
            val saved = WidgetLook.of(getAppWidgetState<Preferences>(this@WidgetStyleActivity, PreferencesGlanceStateDefinition, glanceId))
            setContent {
                val prefs by appearance.prefs.collectAsState()
                VectraTheme(prefs, systemDark = isSystemInDarkTheme(), reduceMotion = rememberSystemReduceMotion()) {
                    val scope = rememberCoroutineScope()
                    StyleScreen(label, saved) { look ->
                        scope.launch {
                            updateAppWidgetState(this@WidgetStyleActivity, PreferencesGlanceStateDefinition, glanceId) { it.toMutablePreferences().apply { set(WidgetLook.Key, look.name) } }
                            WidgetStore.redraw(this@WidgetStyleActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                            finish()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleScreen(label: String, saved: WidgetLook, onDone: (WidgetLook) -> Unit) {
    val c = Vectra.colors
    var look by remember { mutableStateOf(saved) }
    Column(
        Modifier.fillMaxSize().background(c.surfacePrimary).safeDrawingPadding().padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            VText("$label widget", style = vtext(26.sp to 30.sp, Weight.Extrabold, Tracking.Heading), color = c.textPrimary)
            VText("Pick how it looks on your home screen.", style = vtext(TypeScale.Caption, Weight.Medium), color = c.textSecondary)
        }
        StylePreview(look)
        LookPanel(look) { look = it }
        Spacer(Modifier.weight(1f))
        VText(
            "Long-press the widget to change this later.",
            Modifier.fillMaxWidth().wrapContentSize(Alignment.Center),
            vtext(TypeScale.Caption, Weight.Medium),
            c.textMuted,
        )
        VButton(if (saved == look && saved != WidgetLook.System) "Done" else "Add widget", onClick = { onDone(look) }, Modifier.fillMaxWidth(), ButtonVariant.Primary, ButtonSize.Lg)
    }
}

/** The "Look" glass panel with its postage stamp, and the four look tiles. */
@Composable
private fun LookPanel(look: WidgetLook, onPick: (WidgetLook) -> Unit) {
    val c = Vectra.colors
    Box(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.fillMaxWidth().glass(Radius.Card).clip(Radius.Card).padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 16.dp)) {
            VText("Style", Modifier.padding(bottom = 8.dp), vtext(11.sp to 16.sp, Weight.Semibold), c.textMuted)
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WidgetLook.entries.forEach { option ->
                    LookTile(option, option == look, onClick = { onPick(option) }, modifier = Modifier.weight(1f))
                }
            }
        }
        PostageStamp(
            Modifier.padding(start = 16.dp).offset(y = (-20).dp).rotate(-3f).zIndex(1f),
            innerModifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            VText("Look", style = vtext(TypeScale.Body, Weight.Extrabold, Tracking.Tight, lineHeight = 14.sp), color = c.textPrimary)
        }
    }
}

@Composable
private fun LookTile(look: WidgetLook, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Vectra.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(64.dp)
                .then(if (selected) Modifier.border(4.dp, c.accentPrimary.copy(alpha = 0.4f), shape).padding(1.dp) else Modifier)
                .border(1.dp, if (selected) c.accentPrimary else c.borderSubtle, shape)
                .clip(shape),
        ) { LookSketch(look) }
        VText(look.label, style = vtext(TypeScale.Caption, if (selected) Weight.Extrabold else Weight.Semibold), color = if (selected) c.accentPrimary else c.textSecondary)
    }
}
