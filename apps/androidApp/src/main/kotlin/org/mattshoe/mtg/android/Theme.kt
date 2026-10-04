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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

val Radius = RoundedCornerShape(Design.RADIUS.dp)
val RadiusSm = RoundedCornerShape(Design.RADIUS_SM.dp)
val Pill = RoundedCornerShape(Design.RADIUS_PILL.dp)

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

/** `h1`, and the page it names. */
@Composable
fun PageHead(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = Design.H1.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        trailing?.invoke()
    }
}

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

/** `.btn`: bordered, a shade above the page. */
@Composable
fun Btn(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .background(Bg3, RadiusSm)
            .border(1.dp, if (danger) Bad.copy(alpha = 0.6f) else Line2, RadiusSm)
            .pressable(enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (!enabled) Ink3 else if (danger) Bad else Ink,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Medium,
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
        placeholder = { Text(placeholder, color = Ink3, fontSize = Design.BODY.sp) },
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
