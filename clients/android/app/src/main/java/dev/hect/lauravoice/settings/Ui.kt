package dev.hect.lauravoice.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hect.lauravoice.LocalPalette
import dev.hect.lauravoice.R

/** Stroke icons on a 24 grid, the same paths as the design mockup (docs/app/settings-design/mock.html). */
object Glyphs {
    private fun icon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    val Down = icon("down", "M6 9l6 6 6-6")
    val Right = icon("right", "M9 6l6 6-6 6")
    val Back = icon("back", "M15 6l-6 6 6 6")
    val Sliders = icon("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4")
    val Voice = icon("voice", "M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2")
    val Car = icon("car", "M5 17h14M5 17v-5l2-5h10l2 5v5M5 17v2M19 17v2M5 12h14M8 14.5h1M15 14.5h1")
    val Watch = icon("watch", "M9 6h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2zM9.5 6l.5-3h4l.5 3M9.5 18l.5 3h4l.5-3")
    val Spark = icon("spark", "M12 3l1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7zM18.5 15.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z")
    val Info = icon("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 8h.01")
    val Code = icon("code", "M9 8l-4 4 4 4M15 8l4 4-4 4")
    val Qr = icon("qr", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 18h2v2h-2zM14 19h1M19 14h1")
    val Mic = icon("mic", "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5")
    val Bluetooth = icon("bt", "M7 7.5l10 9-5 4.5V3l5 4.5-10 9")
    val Lock = icon("lock", "M7 11h10a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1zM8.5 11V8a3.5 3.5 0 0 1 7 0v3")
}

@Composable
fun Glyph(v: ImageVector, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) =
    Icon(v, null, modifier.size(size), tint = tint)

private val CardShape = RoundedCornerShape(20.dp)

/** A sub-page: back arrow and title, then the content. */
@Composable
internal fun PageTop(title: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(56.dp).offset(x = (-8).dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(Glyphs.Back, "Back", onBack)
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp, color = p.ink)
    }
}

@Composable
internal fun IconTap(v: ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(v, label, Modifier.size(22.dp), tint = p.ink2) }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(
        text, Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp, color = LocalPalette.current.ink3,
    )
}

@Composable
internal fun SCard(modifier: Modifier = Modifier, glow: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(p.s1).border(1.dp, p.hair, CardShape)
            .then(if (glow) Modifier.drawBehind {
                drawRect(Brush.radialGradient(listOf(p.glow, Color.Transparent), Offset(40.dp.toPx(), 0f), 200.dp.toPx()))
            } else Modifier),
        content = content,
    )
}

/** Padded block inside a card. */
@Composable
internal fun Block(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth().padding(16.dp), content = content)

@Composable
internal fun Sep(inset: Dp = 16.dp) =
    Box(Modifier.padding(start = inset, end = 16.dp).fillMaxWidth().height(1.dp).background(LocalPalette.current.hair))

@Composable
internal fun Label(text: String, trailing: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp, color = p.ink)
        if (trailing != null) Text(trailing, fontSize = 13.5.sp, color = p.ink3)
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, top: Dp = 9.dp) =
    Text(text, modifier.padding(top = top), fontSize = 13.sp, lineHeight = 19.sp, color = LocalPalette.current.ink3)

/** Segmented control: a track with a raised thumb under the chosen option. */
@Composable
internal fun Seg(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.s2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((v, label) in options) {
            val on = v == value
            val bg by animateColorAsState(if (on) p.thumb else Color.Transparent, label = "seg")
            val shape = RoundedCornerShape(9.dp)
            Box(
                Modifier.weight(1f).height(38.dp).clip(shape).background(bg)
                    .then(if (on) Modifier.border(1.dp, p.hair2, shape) else Modifier)
                    .clickable(role = Role.RadioButton) { onChange(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) p.ink else p.ink3, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Tile(v: ImageVector) {
    val p = LocalPalette.current
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(p.s2), contentAlignment = Alignment.Center) {
        Glyph(v, p.ink2, 18.dp)
    }
}

@Composable
internal fun Dot(color: Color, size: Dp = 8.dp, halo: Boolean = true) {
    Box(
        Modifier.size(size + if (halo) 6.dp else 0.dp).then(if (halo) Modifier.background(color.copy(alpha = 0.18f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(size).background(color, CircleShape)) }
}

/** A row that opens a sub-page: icon tile, title, one-line summary, chevron. */
@Composable
internal fun NavRow(icon: ImageVector, title: String, summary: String, dot: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tile(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp, color = p.ink)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Dot(dot, 6.dp, halo = false)
                    Spacer(Modifier.width(6.dp))
                }
                Text(summary, fontSize = 13.sp, color = p.ink3, maxLines = 1)
            }
        }
        Glyph(Glyphs.Right, p.ink4, 18.dp)
    }
}

/** Title and description with a switch on the right; the whole row toggles. */
@Composable
internal fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp, color = p.ink)
            Hint(detail, top = 4.dp)
        }
        Spacer(Modifier.width(14.dp))
        Toggle(checked)
    }
}

@Composable
internal fun Toggle(on: Boolean) {
    val p = LocalPalette.current
    val track by animateColorAsState(if (on) p.accent else p.s3, label = "track")
    val knob by animateColorAsState(if (on) p.onAccent else p.ink3, label = "knob")
    val x by animateDpAsState(if (on) 21.dp else 3.dp, label = "x")
    Box(Modifier.padding(top = 2.dp).size(46.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(track)) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).background(knob, CircleShape))
    }
}

enum class Tone { Primary, Secondary, Ghost }

@Composable
internal fun SButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: Tone = Tone.Secondary, enabled: Boolean = true,
    icon: ImageVector? = null, small: Boolean = false,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(if (small) 10.dp else 14.dp)
    val (bg, fg) = when {
        !enabled && tone != Tone.Ghost -> p.s2 to p.ink4
        !enabled -> Color.Transparent to p.ink4
        tone == Tone.Primary -> p.accent to p.onAccent
        tone == Tone.Secondary -> p.s2 to p.ink
        else -> Color.Transparent to p.accentText
    }
    Row(
        modifier.height(if (small) 34.dp else if (tone == Tone.Ghost) 44.dp else 48.dp).clip(shape).background(bg)
            .then(if (tone == Tone.Secondary && enabled) Modifier.border(BorderStroke(1.dp, p.hair), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else if (tone == Tone.Ghost) 8.dp else 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Glyph(icon, fg, 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = if (small) 13.5.sp else 15.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/** Laura's face from the launcher icon, on the icon's apricot-cream disc. */
@Composable
internal fun LauraFace(size: Dp = 56.dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFFFF1E2), Color(0xFFFFE7CA), Color(0xFFFFD9A8)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(size * 1.5f).offset(y = size * 0.01f))
    }
}

/** Labelled single-line input in the card style. */
@Composable
internal fun Input(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", secret: Boolean = false,
    mono: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
) {
    val p = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(label, Modifier.padding(start = 2.dp, bottom = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.ink2)
        BasicTextField(
            value, onChange, singleLine = true, interactionSource = source,
            textStyle = TextStyle(
                color = if (mono) p.ink2 else p.ink, fontSize = if (mono) 12.5.sp else 15.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                letterSpacing = if (secret) 2.sp else TextUnit.Unspecified,
            ),
            cursorBrush = SolidColor(p.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
            modifier = Modifier.fillMaxWidth().then(
                if (focused) Modifier.border(3.dp, p.accentSoft, RoundedCornerShape(14.dp)).padding(2.dp) else Modifier.padding(2.dp)
            ),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(50.dp).clip(shape).background(p.s2)
                        .border(1.dp, if (focused) p.accent.copy(alpha = 0.7f) else p.hair, shape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 15.sp, color = p.ink4)
                    inner()
                }
            },
        )
    }
}

/** Status line: coloured dot and text. */
@Composable
internal fun StatusLine(color: Color, text: String, size: TextUnit = 13.5.sp, ink: Color = LocalPalette.current.ink2) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = size, color = ink, maxLines = 1)
    }
}

internal val PagePadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)

@Composable
internal fun RowScope.Grow() = Spacer(Modifier.weight(1f))
