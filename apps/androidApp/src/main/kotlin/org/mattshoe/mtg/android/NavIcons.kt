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

/**
 * The Fetch list: a checklist, because it is a list of cards to go and
 * pull, ticked off one section at a time. Not the bar chart Stats had
 * in this slot — Stats kept its own drawing and moved behind the
 * profile.
 */
@Composable
fun FetchIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        listOf(6f, 12f, 18f).forEach { y ->
            pen.line(3.5f, y - 0.5f, 5f, y + 1f)
            pen.line(5f, y + 1f, 8f, y - 2f)
            pen.line(11f, y, 20.5f, y)
        }
    }

/** The Guide: an open book, because it is the page you look things up in. */
@Composable
fun GuideIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        listOf(3f, 21f).forEach { edge ->
            val mid = (12f + edge) / 2f
            pen.path { p ->
                moveTo(p.at(12f, 6.5f).x, p.at(12f, 6.5f).y)
                cubicTo(
                    p.at(12f + (mid - 12f) * 0.6f, 5f).x, p.at(0f, 5f).y,
                    p.at(mid, 4.5f).x, p.at(0f, 4.5f).y,
                    p.at(edge, 5.5f).x, p.at(edge, 5.5f).y,
                )
                lineTo(p.at(edge, 18.5f).x, p.at(edge, 18.5f).y)
                cubicTo(
                    p.at(mid, 17.5f).x, p.at(0f, 17.5f).y,
                    p.at(12f + (mid - 12f) * 0.6f, 18f).x, p.at(0f, 18f).y,
                    p.at(12f, 19.5f).x, p.at(12f, 19.5f).y,
                )
            }
        }
        pen.line(12f, 6.5f, 12f, 19.5f)
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

/**
 * Tools: a toolbox, lid line and handle, because it is the box Fetch
 * and Entry are kept in. The website's menu is words, so it has no twin.
 */
@Composable
fun ToolsIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.box(3f, 8f, 18f, 12f)
        pen.path { p ->
            moveTo(p.at(9f, 8f).x, p.at(9f, 8f).y)
            lineTo(p.at(9f, 5f).x, p.at(9f, 5f).y)
            lineTo(p.at(15f, 5f).x, p.at(15f, 5f).y)
            lineTo(p.at(15f, 8f).x, p.at(15f, 8f).y)
        }
        pen.line(3f, 13f, 21f, 13f)
        pen.line(12f, 12f, 12f, 14.5f)
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
 * Everybody: two people, one a step behind the other. `app.css`'s
 * `.nav-icon-users` is the same shape.
 */
@Composable
fun UsersIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size)) {
        val pen = Pen(this, tint)
        pen.circle(9f, 8.5f, 3.5f)
        pen.path { p ->
            moveTo(p.at(2.5f, 20f).x, p.at(2.5f, 20f).y)
            cubicTo(
                p.at(3f, 16f).x, p.at(3f, 16f).y,
                p.at(15f, 16f).x, p.at(15f, 16f).y,
                p.at(15.5f, 20f).x, p.at(15.5f, 20f).y,
            )
        }
        pen.path { p ->
            moveTo(p.at(15f, 5.2f).x, p.at(15f, 5.2f).y)
            cubicTo(
                p.at(18.5f, 5.2f).x, p.at(18.5f, 5.2f).y,
                p.at(18.5f, 11.8f).x, p.at(18.5f, 11.8f).y,
                p.at(15f, 11.8f).x, p.at(15f, 11.8f).y,
            )
        }
        pen.path { p ->
            moveTo(p.at(17.5f, 15.5f).x, p.at(17.5f, 15.5f).y)
            cubicTo(
                p.at(20f, 16.2f).x, p.at(20f, 16.2f).y,
                p.at(21.3f, 18f).x, p.at(21.3f, 18f).y,
                p.at(21.5f, 20f).x, p.at(21.5f, 20f).y,
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
        View.USERS -> UsersIcon(tint, size, tagged)
        View.TOOLS -> ToolsIcon(tint, size, tagged)
        View.STATS -> StatsIcon(tint, size, tagged)
        View.FETCH -> FetchIcon(tint, size, tagged)
        View.GUIDE -> GuideIcon(tint, size, tagged)
        View.ENTRY -> EntryIcon(tint, size, tagged)
        else -> LibraryIcon(tint, size, tagged)
    }
}

/**
 * The drawings that stand in for an action's word.
 *
 * Matt: "Instead of an X icon or trash icon, just using the text
 * 'remove', same with share and edit etc etc. This looks terrible and
 * dated." The website's `Glyph` is the same four, the same shapes.
 */
enum class Glyph(val tag: String) {
    BIN("glyph-bin"),
    PENCIL("glyph-pencil"),
    CROSS("glyph-cross"),
    RENAME("glyph-rename"),
    FLIP("glyph-flip"),
}

/**
 * One of the [Glyph]s, on the same grid and at the same weight as the
 * nav icons. The numbers are the ones in `app.css`'s `.icon-bin`,
 * `.icon-pencil`, `.icon-close` and `.icon-rename`.
 */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) =
    Canvas(modifier.size(size).testTag(glyph.tag)) {
        val pen = Pen(this, tint)
        when (glyph) {
            // A lid with a handle, and a can narrowing to its foot.
            Glyph.BIN -> {
                pen.line(4f, 6.5f, 20f, 6.5f)
                pen.path { p ->
                    moveTo(p.at(9f, 6.5f).x, p.at(9f, 6.5f).y)
                    lineTo(p.at(9f, 4f).x, p.at(9f, 4f).y)
                    lineTo(p.at(15f, 4f).x, p.at(15f, 4f).y)
                    lineTo(p.at(15f, 6.5f).x, p.at(15f, 6.5f).y)
                }
                pen.path { p ->
                    moveTo(p.at(6f, 6.5f).x, p.at(6f, 6.5f).y)
                    lineTo(p.at(7f, 20f).x, p.at(7f, 20f).y)
                    lineTo(p.at(17f, 20f).x, p.at(17f, 20f).y)
                    lineTo(p.at(18f, 6.5f).x, p.at(18f, 6.5f).y)
                }
                pen.line(10f, 10f, 10f, 16.5f)
                pen.line(14f, 10f, 14f, 16.5f)
            }
            // `EntryIcon`'s pencil.
            Glyph.PENCIL -> {
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
            Glyph.CROSS -> {
                pen.line(6f, 6f, 18f, 18f)
                pen.line(18f, 6f, 6f, 18f)
            }
            // A name box with the text cursor in it, so it is not a
            // second pencil beside Edit list's.
            Glyph.RENAME -> {
                pen.box(2.5f, 7f, 12.5f, 10f)
                pen.line(18.5f, 4f, 18.5f, 20f)
                pen.line(16.5f, 4f, 20.5f, 4f)
                pen.line(16.5f, 20f, 20.5f, 20f)
            }
            // Two arrows chasing each other round, over the top and
            // back under: turn it over. `app.css`'s `.icon-flip`.
            Glyph.FLIP -> {
                val ring = androidx.compose.ui.geometry.Rect(pen.at(4f, 4f), pen.at(20f, 20f))
                pen.path { arcTo(ring, 0f, -145f, true) }
                pen.path { arcTo(ring, 180f, -145f, true) }
                pen.path { p ->
                    moveTo(p.at(4.5f, 3.5f).x, p.at(4.5f, 3.5f).y)
                    lineTo(p.at(4.5f, 7.5f).x, p.at(4.5f, 7.5f).y)
                    lineTo(p.at(8.5f, 7.5f).x, p.at(8.5f, 7.5f).y)
                }
                pen.path { p ->
                    moveTo(p.at(19.5f, 20.5f).x, p.at(19.5f, 20.5f).y)
                    lineTo(p.at(19.5f, 16.5f).x, p.at(19.5f, 16.5f).y)
                    lineTo(p.at(15.5f, 16.5f).x, p.at(15.5f, 16.5f).y)
                }
            }
        }
    }
