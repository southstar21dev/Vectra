package dev.southstar21.vectra.features.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.southstar21.vectra.core.designsystem.components.VIcon
import dev.southstar21.vectra.core.designsystem.components.VText
import dev.southstar21.vectra.core.designsystem.icons.RemixIcons
import dev.southstar21.vectra.core.designsystem.theme.BoxShadow
import dev.southstar21.vectra.core.designsystem.theme.Weight
import dev.southstar21.vectra.core.designsystem.theme.boxShadow
import dev.southstar21.vectra.core.designsystem.theme.specularBorder
import dev.southstar21.vectra.core.designsystem.theme.tappable
import dev.southstar21.vectra.core.designsystem.theme.tnum
import dev.southstar21.vectra.core.designsystem.theme.vtext

/** What the stacked time block on the right shows. */
internal sealed interface TimeBlock {
    data class Range(val start: String, val end: String, val nextDay: Boolean = false) : TimeBlock
    data class Due(val time: String) : TimeBlock
    data object AllDay : TimeBlock
}

/** A line under the title row: a clash, an offline check-in. */
internal data class CardNote(val text: String, val alert: Boolean = false)

/** Everything a day card shows; [ClassCard] and [ItemCard] fill it in. */
@Immutable
internal data class DayCardContent(
    val typeWord: String,
    val typeColor: Color,
    val title: String,
    val subtitle: String?,
    val time: TimeBlock,
    val startColor: Color,
    val struck: Boolean = false,
    /** A cancelled class: dark card, dimmed contents and a CANCELLED stamp across it. */
    val cancelled: Boolean = false,
    val clash: Boolean = false,
    val notes: List<CardNote> = emptyList(),
    val room: String? = null,
    val group: String? = null,
    val clickLabel: String = title,
)

/**
 * The card every class and item shares: type word, title, subtitle and the stacked time,
 * then the room row with the check-in at the far right.
 */
@Composable
internal fun DayCard(
    content: DayCardContent,
    d: DayColors,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    extra: (@Composable () -> Unit)? = null,
) {
    val hasRoomRow = !content.room.isNullOrEmpty() || content.group != null || trailing != null
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .dayCardSurface(content, d, onClick)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (hasRoomRow) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DayCardTitle(content, d)
            for (note in content.notes) CardNoteLine(note, d)
            if (hasRoomRow) RoomRow(content.room, content.group, d, trailing)
            extra?.invoke()
        }
        if (content.cancelled) CardStamp("CANCELLED", d, Modifier.align(Alignment.Center))
    }
}

/** The card's opaque surface, border and tap; a cancelled card dims only its contents. */
private fun Modifier.dayCardSurface(content: DayCardContent, d: DayColors, onClick: (() -> Unit)?): Modifier = this
    .boxShadow(CardShape, d.cardShadow)
    .background(if (content.cancelled) d.cancelledCard else d.card, CardShape)
    .then(
        if (content.clash) Modifier.specularBorder(CardShape, d.clashBorder, d.clashBorder)
        else Modifier.specularBorder(CardShape, d.border, d.borderTop),
    )
    .then(if (onClick != null) Modifier.tappable(CardShape, role = null, onClickLabel = content.clickLabel, onClick = onClick) else Modifier)
    // Faded after the background: the card itself stays opaque, so the connector line behind
    // the list never shows through it.
    .alpha(if (content.cancelled) 0.45f else 1f)

@Composable
private fun DayCardTitle(content: DayCardContent, d: DayColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            VText(content.typeWord, style = vtext(12.sp to 14.sp, Weight.Extrabold, 0.16.em, mono = true), color = content.typeColor, maxLines = 1)
            VText(
                content.title,
                style = vtext(17.sp to 22.sp, Weight.Bold).copy(textDecoration = if (content.struck) TextDecoration.LineThrough else null),
                color = d.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!content.subtitle.isNullOrEmpty()) {
                VText(content.subtitle, style = vtext(14.sp to 19.sp, Weight.Medium), color = d.secondary, maxLines = 1)
            }
        }
        StackedTimeBlock(content.time, content.startColor, d)
    }
}

@Composable
private fun StackedTimeBlock(block: TimeBlock, startColor: Color, d: DayColors) {
    val style = vtext(15.sp to 18.sp, Weight.Bold, mono = true).tnum()
    val label = vtext(12.sp to 15.sp, Weight.Extrabold, 0.12.em, mono = true)
    Column(Modifier.padding(start = 8.dp, top = 2.dp, end = 2.dp, bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (block) {
            is TimeBlock.Range -> {
                VText(block.start, style = style, color = startColor, maxLines = 1)
                Box(Modifier.padding(vertical = 6.dp).size(16.dp, 1.5.dp).background(d.rule, RoundedCornerShape(1.dp)))
                Row(verticalAlignment = Alignment.Top) {
                    VText(block.end, style = style, color = d.secondary, maxLines = 1)
                    if (block.nextDay) VText("+1", Modifier.padding(start = 2.dp), vtext(10.sp to 12.sp, Weight.Bold, mono = true), d.muted, maxLines = 1)
                }
            }
            is TimeBlock.Due -> {
                VText("DUE", style = vtext(11.sp to 14.sp, Weight.Extrabold, 0.12.em, mono = true), color = d.muted, maxLines = 1)
                VText(block.time, Modifier.padding(top = 4.dp), style, startColor, maxLines = 1)
            }
            TimeBlock.AllDay -> {
                VText("ALL", style = label, color = d.secondary, maxLines = 1)
                VText("DAY", style = label, color = d.secondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CardNoteLine(note: CardNote, d: DayColors) {
    Row(Modifier.padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (note.alert) VIcon(RemixIcons.AlertLine, size = 14.dp, tint = d.clashIcon)
        VText(note.text, style = vtext(12.sp to 16.sp, Weight.Semibold).tnum(), color = if (note.alert) d.clashText else d.secondary)
    }
}

/** Pin + room (the only part that shortens), people + group, and the check-in slot at the end. */
@Composable
private fun RoomRow(room: String?, group: String?, d: DayColors, trailing: (@Composable () -> Unit)?) {
    Row(Modifier.heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!room.isNullOrEmpty()) {
                VIcon(RemixIcons.MapPinLine, size = 17.dp, tint = d.secondary)
                VText(room, Modifier.weight(1f, fill = false), vtext(14.sp to 19.sp, Weight.Semibold), d.room, maxLines = 1)
            }
            if (group != null) {
                Row(
                    Modifier.padding(start = if (room.isNullOrEmpty()) 0.dp else 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIcon(RemixIcons.GroupLine, size = 17.dp, tint = d.secondary, contentDescription = "Group")
                    VText(group, style = vtext(14.sp to 19.sp, Weight.Semibold), color = d.room, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                }
            }
        }
        // The 44dp slot overhangs the card's padding by 6dp, like the mockup's -6px margin.
        if (trailing != null) Box(Modifier.offset(x = 6.dp)) { trailing() }
    }
}

/**
 * A tilted rubber stamp, drawn over the card and never taking taps. It is a raised object: a
 * drop shadow under it, a red-tinted face lit from the top, a brighter top edge and a soft
 * shadow under the letters.
 */
@Composable
private fun CardStamp(text: String, d: DayColors, modifier: Modifier = Modifier) {
    val ink = if (d.isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    val edgeLight = if (d.isDark) Color(0xFFFECACA) else Color(0xFFF87171)
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .rotate(-12f)
            .boxShadow(
                shape,
                listOf(
                    BoxShadow(y = 8f, blur = 18f, spread = -2f, color = Color.Black.copy(alpha = if (d.isDark) 0.75f else 0.28f)),
                    BoxShadow(y = 2f, blur = 4f, color = Color.Black.copy(alpha = if (d.isDark) 0.5f else 0.18f)),
                ),
            )
            .background(Brush.verticalGradient(listOf(lerp(d.cancelledCard, ink, 0.30f), lerp(d.cancelledCard, ink, 0.12f))), shape)
            .specularBorder(shape, ink.copy(alpha = 0.9f), edgeLight, width = 2.5f)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        VText(
            text,
            style = vtext(20.sp to 26.sp, Weight.Extrabold, 0.18.em, mono = true).copy(
                shadow = Shadow(Color.Black.copy(alpha = if (d.isDark) 0.6f else 0.25f), Offset(0f, 2f), 4f),
            ),
            color = ink,
            maxLines = 1,
        )
    }
}
