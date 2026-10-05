package org.mattshoe.mtg.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mattshoe.mtg.core.Design
import org.mattshoe.mtg.core.LogLine
import org.mattshoe.mtg.core.LogsState

/**
 * The server log, on Android. Sibling of `LogsPage`.
 *
 * Reached through the profile rather than the bottom bar: it is a
 * screen you open when something is wrong, not one you move between,
 * and a bar is for the latter. It shared a file with the query
 * console until that page was dropped.
 */
@Composable
fun LogsScreen(state: LogsState, onState: (LogsState) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Design.WRAP_PAD_NARROW.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        // No heading: this screen is not in the bar, so the header
        // at the top carries its name on its own.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Ghost("Errors only (${state.errorCount})", on = state.onlyErrors) {
                onState(state.toggleErrors())
            }
        }
        when {
            state.busy -> Line("Loading…", Ink3, modifier = Modifier.testTag("logs-busy"))
            state.error != null -> ErrBlock(state.error!!)
            state.shown.isEmpty() ->
                Line("Nothing logged.", Ink3, modifier = Modifier.testTag("logs-empty"))
            else -> {
                val shown = state.shown
                Grid(
                    cols = listOf("When", "Level", "Method", "Path", "Status", "ms"),
                    rows = shown.map { l -> cells(l) },
                    tag = "log",
                    // A failed row is lighter, heavier and ruled down its
                    // left edge. Three signals, none of them a hue.
                    rowTone = { i -> if (shown[i].failed) Bad.copy(alpha = 0.14f) else null },
                    rowWeight = { i -> if (shown[i].failed) FontWeight.SemiBold else FontWeight.Normal },
                    rowRule = { i -> shown[i].failed },
                    rowLabel = { i -> "failed".takeIf { shown[i].failed } },
                )
            }
        }
    }
}

/** The six facts the web's log table shows, in its order. */
private fun cells(l: LogLine) = listOf(
    l.ts.substringAfter('T').take(8),
    l.level,
    l.method ?: "",
    l.path ?: "",
    l.status?.toString() ?: "",
    l.ms?.toString() ?: "",
)

// --------------------------------------------------------------- pieces

/**
 * `.err`: the message, in a block of its own.
 *
 * Tinted background and a border as well as the colour, so it is still
 * obviously an error to an eye that cannot tell the red from the grey.
 */
@Composable
private fun ErrBlock(message: String) {
    Text(
        message,
        // The tag goes on the surface, not inside it. Below the
        // padding it named the inner text box, so anything measuring
        // "is this block lighter than what it sits on" sampled the
        // block's own tint on both sides of its reported edge and
        // found no difference at all.
        Modifier.fillMaxWidth()
            .testTag("err")
            .background(Bad.copy(alpha = 0.12f), RadiusSm)
            .border(1.dp, Bad.copy(alpha = 0.42f), RadiusSm)
            .padding(horizontal = 13.dp, vertical = 10.dp)
            .semantics { contentDescription = "error: $message" },
        color = Bad,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
    )
}

/**
 * A real table: ruled, aligned, with a header row of its own.
 *
 * `<table>` on the web, and the point of it is the ruling. The phone
 * used to join each row with `"  |  "` into one `Text`, which reads as
 * a paragraph of pipes: nothing lines up down a column, a long cell
 * shoves every later cell sideways, and the header is just the first
 * line. So this is laid out column-major — one `Column` per column, so
 * every cell in a column is exactly as wide as the column — and each
 * cell draws the hairline under it, which joins up into one rule
 * across the row.
 */
@Composable
private fun Grid(
    cols: List<String>,
    rows: List<List<String>>,
    tag: String,
    maxHeight: androidx.compose.ui.unit.Dp? = null,
    rowTone: (Int) -> Color? = { null },
    rowWeight: (Int) -> FontWeight = { FontWeight.Normal },
    rowRule: (Int) -> Boolean = { false },
    rowLabel: (Int) -> String? = { null },
) {
    val sideways = rememberScrollState()
    val down = rememberScrollState()
    var frame = Modifier.fillMaxWidth()
        .background(Bg, RadiusSm)
        .border(1.dp, Line, RadiusSm)
        .testTag("$tag-grid")
    if (maxHeight != null) frame = frame.heightIn(max = maxHeight).verticalScroll(down)
    Column(frame) {
        Row(
            Modifier.height(IntrinsicSize.Min).horizontalScroll(sideways),
            verticalAlignment = Alignment.Top,
        ) {
            cols.forEachIndexed { c, name ->
                if (c > 0) VRule()
                Column(Modifier.testTag("$tag-col-$c")) {
                    HeadCell(name, Modifier.testTag("$tag-head-$c"))
                    rows.indices.forEach { r ->
                        BodyCell(
                            value = rows[r].getOrElse(c) { "" },
                            tone = rowTone(r),
                            weight = rowWeight(r),
                            ruled = c == 0 && rowRule(r),
                            label = rowLabel(r).takeIf { c == 0 },
                            modifier = Modifier.testTag("$tag-cell-$r-$c"),
                        )
                    }
                }
            }
        }
    }
}

/** `th`: small, bold, uppercase, on its own shade, ruled underneath. */
@Composable
private fun HeadCell(name: String, modifier: Modifier) {
    Text(
        name.uppercase(),
        modifier.background(Bg3).hairline().padding(horizontal = 11.dp, vertical = 8.dp),
        color = Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.44.sp,
        maxLines = 1,
        softWrap = false,
    )
}

/** `td`: ruled underneath, one line, the row's own shade behind it. */
@Composable
private fun BodyCell(
    value: String,
    tone: Color?,
    weight: FontWeight,
    ruled: Boolean,
    label: String?,
    modifier: Modifier,
) {
    var m = modifier.background(tone ?: Color.Transparent).hairline()
    if (ruled) m = m.leftRule()
    if (label != null) m = m.semantics { contentDescription = "$label: $value" }
    Text(
        value,
        m.padding(horizontal = 11.dp, vertical = 7.dp),
        color = Ink2,
        fontSize = 13.5.sp,
        fontWeight = weight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/** `border-bottom: 1px solid var(--line)` on every cell. */
private fun Modifier.hairline() = drawBehind {
    val px = 1.dp.toPx()
    drawLine(Line, Offset(0f, size.height - px / 2), Offset(size.width, size.height - px / 2), px)
}

/** The non-hue half of a failed row: a rule down its leading edge. */
private fun Modifier.leftRule() = drawBehind {
    val px = 3.dp.toPx()
    drawLine(Bad, Offset(px / 2, 0f), Offset(px / 2, size.height), px)
}

/** The hairline between two columns. */
@Composable
private fun VRule() = Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
