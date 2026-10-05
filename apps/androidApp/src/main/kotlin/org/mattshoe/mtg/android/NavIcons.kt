package org.mattshoe.mtg.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.mattshoe.mtg.core.View

/**
 * The bottom bar's icons, drawn rather than imported.
 *
 * Five line icons on one 24-unit grid at one stroke weight, the same
 * way `ShareMark` reproduces the website's own SVG. A dependency
 * would have given four of these and not the fifth — there is no bar
 * chart in `material-icons-core` — and a bar with four Material icons
 * and one hand-drawn one looks exactly as mixed as that sounds.
 *
 * Line work rather than solid shapes, because a filled glyph and an
 * outlined one at the same size read as different weights, and these
 * sit in a row where that is the first thing you notice.
 */
private const val GRID = 24f
private const val WEIGHT = 1.9f

private class Pen(val scope: DrawScope, val tint: Color) {
    val u = scope.size.minDimension / GRID
    val stroke = Stroke(width = WEIGHT * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun at(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        scope.drawLine(tint, at(x1, y1), at(x2, y2), strokeWidth = WEIGHT * u, cap = StrokeCap.Round)
    fun box(x: Float, y: Float, w: Float, h: Float, round: Float = 2f) =
        scope.drawRoundRect(
            tint, at(x, y), Size(w * u, h * u),
            androidx.compose.ui.geometry.CornerRadius(round * u, round * u), stroke,
        )
    fun circle(x: Float, y: Float, r: Float) =
        scope.drawCircle(tint, r * u, at(x, y), style = stroke)
    fun path(build: Path.(Pen) -> Unit) {
        val p = Path(); p.build(this); scope.drawPath(p, tint, style = stroke)
    }
}

/** The collection: a grid of cards. */
@Composable
fun LibraryIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.box(3f, 3f, 7.5f, 7.5f)
        pen.box(13.5f, 3f, 7.5f, 7.5f)
        pen.box(3f, 13.5f, 7.5f, 7.5f)
        pen.box(13.5f, 13.5f, 7.5f, 7.5f)
    }

/** A deck: cards stacked, the front one face on. */
@Composable
fun DecksIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.box(7.5f, 3f, 13f, 14f)
        pen.path { p -> moveTo(p.at(5f, 6f).x, p.at(5f, 6f).y); lineTo(p.at(5f, 19f).x, p.at(5f, 19f).y) }
        pen.path { p -> moveTo(p.at(2.5f, 9f).x, p.at(2.5f, 9f).y); lineTo(p.at(2.5f, 19f).x, p.at(2.5f, 19f).y) }
        pen.line(5f, 19f, 18f, 19f)
        pen.line(2.5f, 19f, 3.5f, 21f)
    }

/** Totals: three bars, tallest last. */
@Composable
fun StatsIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.line(4f, 20f, 20f, 20f)
        pen.line(7f, 20f, 7f, 14f)
        pen.line(12f, 20f, 12f, 9f)
        pen.line(17f, 20f, 17f, 4.5f)
    }

/** Mass entry: a pencil, because it is the screen you write a list into. */
@Composable
fun EntryIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.path { p ->
            moveTo(p.at(4f, 20f).x, p.at(4f, 20f).y)
            lineTo(p.at(4f, 16f).x, p.at(4f, 16f).y)
            lineTo(p.at(16f, 4f).x, p.at(16f, 4f).y)
            lineTo(p.at(20f, 8f).x, p.at(20f, 8f).y)
            lineTo(p.at(8f, 20f).x, p.at(8f, 20f).y)
            close()
        }
        pen.line(13f, 7f, 17f, 11f)
    }

/** Who you are: a head and shoulders. */
@Composable
fun ProfileIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.circle(12f, 8.5f, 4f)
        pen.path { p ->
            moveTo(p.at(4.5f, 20f).x, p.at(4.5f, 20f).y)
            cubicTo(
                p.at(5f, 16f).x, p.at(5f, 16f).y,
                p.at(19f, 16f).x, p.at(19f, 16f).y,
                p.at(19.5f, 20f).x, p.at(19.5f, 20f).y,
            )
        }
    }

/**
 * The icon for a view, by the view.
 *
 * One place, so a tab cannot be added to `Admin.bar` and then drawn
 * with nothing — the `else` is a deliberate fallback rather than a
 * crash, and `everyTabIsAnIconAboveAWord` is what catches a view that
 * reaches the bar without a drawing of its own.
 */
@Composable
fun NavIcon(view: View, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val tagged = modifier.testTag("nav-icon-${view.label}")
    when (view) {
        View.LIBRARY -> LibraryIcon(tint, size, tagged)
        View.DECKS -> DecksIcon(tint, size, tagged)
        View.STATS -> StatsIcon(tint, size, tagged)
        View.ENTRY -> EntryIcon(tint, size, tagged)
        else -> LibraryIcon(tint, size, tagged)
    }
}
