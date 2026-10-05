package org.mattshoe.mtg.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import org.mattshoe.mtg.core.Design

/**
 * The website's look, on Android.
 *
 * Every colour and radius comes from `Design` in the shared core, which
 * is the same set of numbers `app.css` declares — `DesignTest` fails if
 * the two ever disagree. Material 3's own palette is replaced wholesale
 * rather than tinted: the default purple is nothing like the site, and
 * "looks broadly similar" is how two apps end up feeling like two apps.
 *
 * The composables below are the stylesheet's vocabulary — panel, pill,
 * seg, ghost, tag — so a screen reads the same here as the web one does
 * and neither invents its own spacing.
 */

fun c(argb: Long) = Color(argb)

/**
 * A button that is text with a background.
 *
 * Material's own buttons cannot be made to look like the site without
 * fighting every default, so these are drawn directly — but they still
 * have to announce themselves as buttons, and say when they are off, or
 * a screen reader and every UI test would see decoration.
 */
private fun Modifier.pressable(enabled: Boolean, onClick: () -> Unit) = this
    .semantics { role = Role.Button; if (!enabled) disabled() }
    .clickable(enabled = enabled, onClick = onClick)

/**
 * The smallest a thing you tap is allowed to be.
 *
 * Android's own guideline, and the website has no equivalent to copy:
 * a mouse pointer is one pixel and a fingertip is about nine
 * millimetres. The nav's Lock and Find were 25dp boxes six dp apart,
 * which on a phone is two targets inside one thumb.
 *
 * It is the *touchable* box, not the painted one. Every control that
 * uses this keeps its own padding and draws at its own size inside a
 * box this big, so the page looks much as it did and the thumb gets
 * somewhere to land. Compose will stretch a hit area to this on its
 * own, but only as a fringe around a small control — where two of
 * them sit side by side the fringes overlap and whichever was laid
 * out first takes the overlap, so the other is still unhittable.
 * Real layout space is the only version of this that works.
 */
val TouchTarget = 48.dp

val Bg = c(Design.BG)
val Bg2 = c(Design.BG_2)
val Bg3 = c(Design.BG_3)
val Line = c(Design.LINE)
val Line2 = c(Design.LINE_2)
val Ink = c(Design.TEXT)
val Ink2 = c(Design.TEXT_2)
val Ink3 = c(Design.TEXT_3)
val Accent = c(Design.ACCENT)
val Accent2 = c(Design.ACCENT_2)
val AccentDim = c(Design.ACCENT_DIM)
val Ok = c(Design.OK)
val Warn = c(Design.WARN)
val Bad = c(Design.BAD)
val Info = c(Design.INFO)

/**
 * What a modal puts between itself and the page under it.
 *
 * `.palette-scrim`'s `rgba(4,6,10,.6)` in `frontend/css/app.css`, which
 * is darker than any surface in the palette on purpose: the point is
 * that the thing behind reads as out of reach.
 */
val Scrim = c(0x9904060A)

/** `color-mix(in srgb, a P%, b)`, as a Compose colour. */
fun mix(a: Color, b: Color, shareOfA: Float): Color =
    c(Design.mixSrgb(a.toArgbLong(), b.toArgbLong(), shareOfA))

/**
 * The four channels as `0xAARRGGBB`.
 *
 * Through the components rather than through `Color.value`, which is
 * a packed `ULong` whose layout depends on the colour space.
 */
private fun Color.toArgbLong(): Long =
    (0xFFL shl 24) or
        ((red * 255f + 0.5f).toLong() shl 16) or
        ((green * 255f + 0.5f).toLong() shl 8) or
        (blue * 255f + 0.5f).toLong()

val Radius = RoundedCornerShape(Design.RADIUS.dp)
val RadiusSm = RoundedCornerShape(Design.RADIUS_SM.dp)
val Pill = RoundedCornerShape(Design.RADIUS_PILL.dp)

/** `.deck-line .thumb`: a 40px square of art, rounded by 6px and no more. */
val RadiusThumb = RoundedCornerShape(Design.RADIUS_THUMB.dp)

/** Rounded at the top only: a sheet that has come up from the bottom edge. */
val SheetShape = RoundedCornerShape(
    topStart = Design.RADIUS.dp,
    topEnd = Design.RADIUS.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = c(Design.ON_ACCENT),
    primaryContainer = AccentDim,
    onPrimaryContainer = Accent2,
    secondary = Ink2,
    onSecondary = Bg,
    background = Bg,
    onBackground = Ink,
    surface = Bg2,
    onSurface = Ink,
    surfaceVariant = Bg3,
    onSurfaceVariant = Ink2,
    outline = Line2,
    outlineVariant = Line,
    error = Bad,
    onError = Bg,
)

// 14px/1.5 system sans, the same as the body rule.
private val type = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = Design.BODY.sp, lineHeight = 21.sp),
        bodyMedium = bodyMedium.copy(fontSize = Design.SMALL.sp, lineHeight = 19.sp),
        bodySmall = bodySmall.copy(fontSize = Design.MINI.sp, lineHeight = 17.sp),
        titleLarge = titleLarge.copy(fontSize = Design.H1.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = Design.H2.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = Design.H3.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun MtgTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Ink,
        ) {
            androidx.compose.material3.Surface(color = Bg, contentColor = Ink) { content() }
        }
    }
}

// ------------------------------------------------------------- pieces

/** `.panel`: a bordered surface a shade above the page. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    head: String? = null,
    note: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(Bg2, Radius)
            .border(1.dp, Line, Radius)
            .padding(Design.PANEL_PAD.dp),
        verticalArrangement = Arrangement.spacedBy(Design.GAP.dp),
    ) {
        head?.let { Text(it, fontSize = Design.H2.sp, fontWeight = FontWeight.SemiBold, color = Ink) }
        note?.let { Text(it, fontSize = Design.SMALL.sp, color = Ink3) }
        content()
    }
}

/**
 * `.seg`: one row, one choice, hairlines between.
 *
 * `fill` is for a row of short labels — the five comparison
 * operators, which are one glyph each. Left to size themselves off
 * their own text they came out 26dp a side, five of them in a huddle,
 * and the one you hit was whichever your thumb overlapped first.
 * Filled, they share the row equally and each is its own target.
 */
@Composable
fun Seg(
    options: List<Pair<String, String>>,
    selected: String?,
    fill: Boolean = false,
    onPick: (String) -> Unit,
) {
    Row(
        Modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .background(Bg, RadiusSm)
            .border(1.dp, Line2, RadiusSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, (value, label) ->
            val on = selected == value
            if (i > 0) {
                Column(Modifier.background(Line2).padding(horizontal = 0.5.dp)) {
                    Text("", fontSize = Design.MINI.sp)
                }
            }
            Box(
                Modifier
                    // Equal shares of the row, so no segment is a
                    // sliver because its label is one character.
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
                    .background(if (on) AccentDim else Color.Transparent)
                    .pressable(true) { onPick(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (on) Accent2 else Ink2,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** `.btn.primary`: gold, dark text. */
@Composable
fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .background(if (enabled) Accent else Bg3, RadiusSm)
            .pressable(enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (enabled) c(Design.ON_ACCENT) else Ink3,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * `.btn { font-weight: 550 }`, which is not one of Material's named
 * weights.
 *
 * Between Medium and SemiBold, and the website means the half step:
 * at 500 "Reset everything" read as a label and at 600 it shouted
 * next to the panel heading above it. `FontWeight` takes any value
 * from 1 to 1000, so there is no reason to round it to one of the
 * nine that have names.
 */
val ButtonWeight = FontWeight(550)

/** `.btn`: bordered, a shade above the page. */
@Composable
fun Btn(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        modifier
            .background(Bg3, RadiusSm)
            .border(1.dp, if (danger) Bad.copy(alpha = 0.6f) else Line2, RadiusSm)
            .pressable(enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (!enabled) Ink3 else if (danger) Bad else Ink,
        fontSize = 13.5.sp,
        fontWeight = ButtonWeight,
    )
}

/** `.btn.sm.ghost`, and `.on` when it is in force. */
@Composable
fun Ghost(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.sizeIn(minWidth = TouchTarget, minHeight = TouchTarget)
            .pressable(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            Modifier
                .background(if (on) AccentDim else Color.Transparent, RadiusSm)
                .border(1.dp, if (on) Accent else Color.Transparent, RadiusSm)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            color = if (on) Accent2 else if (enabled) Ink2 else Ink3,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** `.tag`: a small stated fact, not a control. */
@Composable
fun Tag(label: String, tone: Color = Ink2) {
    Text(
        label,
        Modifier
            .background(Bg3, RadiusSm)
            .border(1.dp, tone.copy(alpha = 0.4f), RadiusSm)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = tone,
        fontSize = Design.TINY.sp,
    )
}

/**
 * `.err`'s face and edge: the bad tone at 12% and 42%, straight off
 * the `.err` rule in `app.css`. Not `:root` variables, so `DesignTest`
 * cannot police them — they are written here beside the composable
 * that uses them rather than hidden inside it.
 */
const val ERR_FILL = 0.12f
const val ERR_EDGE = 0.42f

/** `.err`'s `padding: 10px 13px`. */
const val ERR_PAD_X = 13
const val ERR_PAD_Y = 10

/** What the box and the words inside it answer to, in tests. */
const val ERR_TAG = "err"
const val ERR_TEXT_TAG = "err-text"

/**
 * The share mark: three nodes and two links, the same drawing the
 * website makes.
 *
 * `.icon-share` in `app.css` is an inline SVG on a 24-unit viewBox —
 * circles at (18,5), (6,12) and (18,19), radius 3, joined by a path
 * from (8.6,13.5) to (15.4,17.5) and another from (15.4,6.5) to
 * (8.6,10.5), all at stroke-width 1.9 with round caps. Those numbers
 * are reproduced exactly here and scaled, so the phone and the page
 * draw one symbol rather than two things that resemble each other.
 *
 * Android used to type it instead: `Line("⤴", …)`, a 16sp U+2934.
 * That is an arrow pointing up and to the right, not a share mark; it
 * came out around 11dp of actual ink inside a 44dp box; and what it
 * looked like at all was down to whichever font the handset shipped.
 */
@Composable
fun ShareMark(tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(size).testTag(SHARE_MARK_TAG)) {
        // One unit of the web's viewBox, so every number below is the
        // number in the stylesheet.
        val u = this.size.minDimension / 24f
        val stroke = 1.9f * u
        fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)

        listOf(18f to 5f, 6f to 12f, 18f to 19f).forEach { (x, y) ->
            drawCircle(
                color = tint,
                radius = 3f * u,
                center = at(x, y),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
        }
        listOf(
            at(8.6f, 13.5f) to at(15.4f, 17.5f),
            at(15.4f, 6.5f) to at(8.6f, 10.5f),
        ).forEach { (from, to) ->
            drawLine(
                color = tint,
                start = from,
                end = to,
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/** What the share mark answers to in a test. */
const val SHARE_MARK_TAG = "share-mark"

/**
 * `.err`: an error is a box you are meant to find, not a sentence
 * loose in the column.
 *
 * The website gives every error a tinted face, an edge a shade
 * stronger than that face, 10px by 13px of air and the fixed-width
 * family. Android drew the words at body size in the dialog's own
 * prose colour, so in a wizard step with eight other lines of prose
 * in it the one line saying what went wrong looked like all the rest.
 *
 * Three things set it apart here and not one of them is a hue: it is
 * the only thing in the dialog with a ring round it, the only thing
 * in a monospace face, and its face is lighter than the dialog
 * behind it. Red on its own would say nothing at all to the person
 * who reads this.
 */
@Composable
fun ErrBox(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .testTag(ERR_TAG)
            .background(Bad.copy(alpha = ERR_FILL), RadiusSm)
            .border(1.dp, Bad.copy(alpha = ERR_EDGE), RadiusSm)
            .padding(horizontal = ERR_PAD_X.dp, vertical = ERR_PAD_Y.dp),
    ) {
        // `monoSmall` already is `var(--mono)` at 12.5px; the tone is
        // the only thing this adds to it.
        Text(message, Modifier.testTag(ERR_TEXT_TAG), color = Bad, style = monoSmall)
    }
}

/** `.muted.small`, which is most of the prose on the site. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = Design.SMALL) {
    Text(text, modifier, color = Ink3, fontSize = size.sp)
}

/** `.field`, and it has to look like the web's inputs, not Material's. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        shape = RadiusSm,
        textStyle = LocalTextStyle.current.copy(
            fontSize = Design.BODY.sp,
            fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        ),
        // Blank means none at all, not an empty one. The web's
        // `<textarea>`s mostly carry no `placeholder` attribute, and a
        // slot holding an empty `Text` is a node on the screen and in
        // the semantics tree that says nothing.
        placeholder = placeholder.takeIf { it.isNotEmpty() }?.let {
            { Text(it, color = Ink3, fontSize = Design.BODY.sp) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Bg,
            unfocusedContainerColor = Bg,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line2,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            cursorColor = Accent,
        ),
    )
}

/** The card frame's own corner, so the art does not square off. */
val cardColors
    @Composable get() = CardDefaults.cardColors(containerColor = Bg2, contentColor = Ink)

val panelBorder = BorderStroke(1.dp, Line)

/** The text style tables use. */
val monoSmall = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontSize = 12.5.sp,
)

// --------------------------------------------- the wizard’s pieces
//
// Written for the entry wizard and lifted here unchanged when the
// new-deck wizard was rebuilt in the same shape. Matt: "I like the
// format of the entry flow, so make sure the new deck flow matches
// that style exactly" — which is a thing you promise by sharing the
// components, not by drawing them twice and keeping them in step.

/**
 * One thing you can pick.
 *
 * The same `.opt` row the web draws: a mark, a label and a line of
 * help, at the height of a button rather than the height of a card.
 *
 * What is chosen is said by a filled tick as well as by a colour,
 * because colour on its own is not a signal everybody can read — the
 * web asserts that and so does the Android suite. `selected` in the
 * semantics tree is what `aria-pressed` is in the DOM, so the same
 * fact is checkable on both.
 */
@Composable
fun Choice(label: String, help: String?, on: Boolean, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // Tagged, because "every option on this step" used to
            // mean "every node carrying `selected`" and the stepper
            // carries that too now.
            .testTag("option")
            .padding(vertical = 3.dp)
            .background(if (on) AccentDim else Bg2, Radius)
            .border(1.dp, if (on) Accent else Line2, Radius)
            .clickable(onClick = click)
            .semantics(mergeDescendants = true) { selected = on; role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // `.opt-mark`: the ring is drawn whether or not it is filled,
        // so an unpicked option still looks like something you pick
        // and the label does not shift when the tick arrives. Filled
        // and dark-ticked when it is on — a shape and a lightness
        // step, not a change of colour.
        Box(
            Modifier.size(19.dp)
                .background(if (on) Accent else Color.Transparent, CircleShape)
                .border(1.5.dp, if (on) Accent else Line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Line("✓", c(Design.ON_ACCENT), Design.TINY, FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Line(label, if (on) Accent2 else Ink, 15)
            help?.let { Line(it, Ink3, Design.MINI) }
        }
    }
}

/**
 * `.tally`: three figures, as a grid of cells.
 *
 * The website draws these as a ruled, bordered block of equal cells —
 * a big mono number over a small uppercase label, hairlines between.
 * This was three loose columns bunched against the left margin with
 * nothing around them, which reads as a row of stray numbers rather
 * than as the summary of what is in the box.
 */
@Composable
fun Tally(vararg cells: Pair<String, String>) {
    Row(
        Modifier.fillMaxWidth()
            // The gridlines are the background showing through the
            // 1dp gaps between cells, which is what `gap: 1px` over a
            // `--line` ground does on the web.
            .background(Line, RadiusSm)
            .border(1.dp, Line, RadiusSm)
            .clip(RadiusSm),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        cells.forEach { (value, label) ->
            Column(
                Modifier.weight(1f).background(Bg2).padding(vertical = 9.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    value,
                    color = Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Line(label.uppercase(), Ink3, Design.TINY)
            }
        }
    }
}

/**
 * `.wiz-foot`: every step ends the same way, in the same place.
 *
 * Ruled off from the body above it, and the reason a button is dead
 * on its own full-width line underneath — which is where the web puts
 * it, and where it is not mistaken for part of the button.
 */
@Composable
fun Foot(hint: String? = null, buttons: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { buttons() }
        hint?.let { Line(it, Ink, Design.SMALL) }
    }
}

/**
 * `.steps`: where you are in a wizard, and the way back to anywhere
 * you have already answered.
 *
 * Bordered chips at the site's radius, not Material's stadium, and
 * they wrap rather than squeeze — the same as the web's `flex-wrap`,
 * because four of these do not fit a phone in a row.
 *
 * Index-based rather than typed, because the two wizards step
 * through different enums and one of them drops a step depending on
 * the format. What they share is the shape, so the shape is what
 * lives here.
 */
@Composable
fun WizardSteps(labels: List<String>, at: Int, canGo: (Int) -> Boolean, go: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val done = i < at
            // `.step.on` in the semantics as well as in the ink.
            // Seven identical pills said nothing about where you
            // were, and a screen reader was told even less.
            Btn(
                "${if (done) "✓" else "${i + 1}"} $label",
                enabled = done && canGo(i),
                modifier = Modifier.semantics { selected = i == at },
            ) { go(i) }
        }
    }
}
