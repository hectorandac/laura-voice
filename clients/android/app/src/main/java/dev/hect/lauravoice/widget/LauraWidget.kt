package dev.hect.lauravoice.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNight
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.hect.lauravoice.App
import dev.hect.lauravoice.MainActivity
import dev.hect.lauravoice.R
import dev.hect.lauravoice.invocation.Invocation

/**
 * Laura on the home screen. One responsive widget: 1x1 face, 2x1 face + state, 2x2 face + Talk, 4x2 adds who is
 * answering with Mute and Open, 4x3 adds her last reply. Talk goes through [Invocation] like the side key. While she
 * is on, the Talk pill (2x2 and up) and a round button on the 2x1 pill turn her off instead ([EndAction]); her face
 * stays Talk.
 * The content follows [WidgetSync.model]; nothing here polls or animates.
 */
open class LauraWidget : GlanceAppWidget() {
    // Exact: LocalSize is the real size (one layout per orientation), so faces scale with the widget
    override val sizeMode = SizeMode.Exact

    /** Settings shown instead of the saved ones (the configure screen's live preview). */
    internal var previewConfig: WidgetConfig? = null
    internal var previewModel: WidgetModel? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appId = runCatching { GlanceAppWidgetManager(context).getAppWidgetId(id) }.getOrDefault(-1)
        provideContent {
            val version by WidgetSync.configs.collectAsState()
            val live by WidgetSync.model.collectAsState()
            val cfg = remember(version) { previewConfig ?: WidgetPrefs.load(context, appId) }
            Content(previewModel ?: live, cfg)
        }
    }

    companion object {
        /** Layout thresholds: narrower than PILL_W is the face, lower than SQUARE_H the pill, and so on. */
        val PILL_W = 110.dp
        /** A 2x1 at least this wide has room for the round End button next to "Laura". */
        val PILL_END_W = 150.dp
        val SQUARE_H = 110.dp
        val CARD_W = 240.dp
        val FULL_H = 240.dp
    }
}

/** The small "Talk to Laura" entry in the widget picker (2x1 by default); same widget, other default size. */
class LauraTalkWidget : LauraWidget()

class LauraWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LauraWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPrefs.delete(context, appWidgetIds)
    }
}

class LauraTalkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LauraTalkWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetPrefs.delete(context, appWidgetIds)
    }
}

/** End from the widget: stops her reply and turns her off, in the app process, no activity. */
class EndAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (App.engine.ui.value.active) Invocation.end(context, "widget end")
    }
}

/** Mute / unmute from the widget; only offered while she is on. */
class MuteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ui = App.engine.ui.value
        if (ui.active) App.engine.setMuted(!ui.muted)
    }
}

// ---------------------------------------------------------------- palette

/** Raycast-style chrome in Laura's colours: plum-black / apricot-cream surfaces, hairlines, one solid pill. */
internal class Pal(theme: String) {
    private val mode = theme
    private fun c(light: Long, dark: Long): ColorProvider = when (mode) {
        "light" -> ColorProvider(Color(light))
        "dark" -> ColorProvider(Color(dark))
        else -> DayNight(day = Color(light), night = Color(dark))
    }
    val surface = c(0xFFFFF6EE, 0xFF1A1320)
    val raised = c(0xFFFCEADB, 0xFF251B2D)
    val hair = c(0xFFEADDD6, 0xFF2E2534)
    val ink = c(0xFF2B1622, 0xFFF7EEE9)
    val body = c(0xFF4E3743, 0xFFD8C9CF)
    val mute = c(0xFF8A7380, 0xFFA08F9C)
    val cta = c(0xFF68243D, 0xFFFFC98F)
    val onCta = c(0xFFFFF1E2, 0xFF3A1626)
    val btn = c(0xFFF6E2D2, 0xFF2C2134)
    val onBtn = c(0xFF2B1622, 0xFFF7EEE9)
    val claude = c(0xFF9A3A5A, 0xFFE9A0B4)
    val local = c(0xFF128373, 0xFF5ED3C0)
    val bad = c(0xFFC62F2F, 0xFFFF7A7A)

    fun tone(t: Tone): ColorProvider = when (t) {
        Tone.OFF -> c(0xFF998894, 0xFF857684)
        Tone.IDLE -> c(0xFF6A4FC9, 0xFFB9A6FF)
        Tone.LISTEN -> local
        Tone.THINK -> c(0xFFB76A00, 0xFFFFB04A)
        Tone.SPEAK -> c(0xFFC2513A, 0xFFFF9E7A)
        Tone.BAD -> bad
    }
}

// ---------------------------------------------------------------- content

private fun faceRes(style: String, face: String): Int = when (style) {
    "icon" -> R.drawable.widget_face_icon
    "tile" -> when (face) {
        "listening" -> R.drawable.car_art_listening
        "thinking" -> R.drawable.car_art_thinking
        "speaking" -> R.drawable.car_art_speaking
        "sleeping" -> R.drawable.car_art_sleeping
        "offline" -> R.drawable.car_art_offline
        "error" -> R.drawable.car_art_error
        else -> R.drawable.car_art_idle
    }
    else -> when (face) {
        "listening" -> R.drawable.widget_face_listening
        "thinking" -> R.drawable.widget_face_thinking
        "speaking" -> R.drawable.widget_face_speaking
        "sleeping" -> R.drawable.widget_face_sleeping
        "offline" -> R.drawable.widget_face_offline
        "error" -> R.drawable.widget_face_error
        else -> R.drawable.widget_face_idle
    }
}

private fun talk(ctx: Context): Action = actionStartActivity(Invocation.intent(ctx, Invocation.SOURCE_WIDGET))
private fun end(): Action = actionRunCallback<EndAction>()
private fun open(ctx: Context): Action =
    actionStartActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

private fun tinted(res: Int, color: ColorProvider) = GlanceModifier.background(ImageProvider(res), colorFilter = ColorFilter.tint(color))

@Composable
internal fun Content(m: WidgetModel, cfg: WidgetConfig) {
    val p = Pal(cfg.theme)
    val size = LocalSize.current
    val keyguard = LocalAppWidgetOptions.current.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1) ==
        AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
    // hairline: the shape in the hairline colour, the surface 1 dp inside it
    Box(GlanceModifier.fillMaxSize().then(tinted(R.drawable.widget_shape, p.hair)).padding(1.dp)) {
        Box(GlanceModifier.fillMaxSize().then(tinted(R.drawable.widget_shape, p.surface))) {
            when {
                cfg.faceOnly || size.width < PILL_W -> FaceLayout(m, cfg, p, size)
                size.height < SQUARE_H -> PillLayout(m, cfg, p, size)
                size.width < CARD_W -> SquareLayout(m, cfg, p, size)
                size.height < FULL_H || !cfg.showReply -> CardLayout(m, cfg, p, size, null)
                else -> CardLayout(m, cfg, p, size, if (keyguard) "lock" else if (cfg.hideText) "who" else "text")
            }
        }
    }
}

private val PILL_W = LauraWidget.PILL_W
private val PILL_END_W = LauraWidget.PILL_END_W
private val SQUARE_H = LauraWidget.SQUARE_H
private val CARD_W = LauraWidget.CARD_W
private val FULL_H = LauraWidget.FULL_H

@Composable
private fun Face(m: WidgetModel, cfg: WidgetConfig, p: Pal, size: Dp, modifier: GlanceModifier = GlanceModifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (cfg.face == "live" && cfg.theme != "light") {
            val halo = if (cfg.theme == "dark") R.drawable.widget_halo else R.drawable.widget_halo_auto
            Image(ImageProvider(halo), null, GlanceModifier.fillMaxSize())
        }
        val img = if (cfg.face == "tile") size * 0.86f else size
        var mod = GlanceModifier.size(img)
        if (cfg.face == "tile" && Build.VERSION.SDK_INT >= 31) mod = mod.cornerRadius(16.dp)
        Image(ImageProvider(faceRes(cfg.face, m.face)), "Laura, ${m.state.label}", mod, contentScale = ContentScale.Fit)
    }
}

@Composable
private fun Dot(color: ColorProvider, size: Dp = 8.dp) {
    Image(ImageProvider(R.drawable.widget_circle), null, GlanceModifier.size(size), colorFilter = ColorFilter.tint(color))
}

@Composable
private fun Status(m: WidgetModel, p: Pal, fontSize: Float = 13f) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(p.tone(m.state.tone))
        Spacer(GlanceModifier.width(6.dp))
        Text(m.state.label, maxLines = 1, style = TextStyle(color = p.ink, fontSize = fontSize.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun FaceLayout(m: WidgetModel, cfg: WidgetConfig, p: Pal, size: DpSize) {
    val ctx = LocalContext.current
    val side = minOf(size.width, size.height)
    Box(GlanceModifier.fillMaxSize().clickable(talk(ctx)), contentAlignment = Alignment.Center) {
        Face(m, cfg, p, side - 6.dp)
    }
    Box(GlanceModifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomEnd) {
        Box(GlanceModifier.size(14.dp).then(tinted(R.drawable.widget_circle, p.surface)), contentAlignment = Alignment.Center) {
            Dot(p.tone(m.state.tone), 8.dp)
        }
    }
}

@Composable
private fun PillLayout(m: WidgetModel, cfg: WidgetConfig, p: Pal, size: DpSize) {
    val ctx = LocalContext.current
    val ending = m.primary == Primary.END && size.width >= PILL_END_W
    // with End on the right, the face gives up room before the text does
    val face = if (ending) minOf(68.dp, size.height - 8.dp, size.width - 120.dp).coerceAtLeast(36.dp) else minOf(68.dp, size.height - 8.dp)
    Row(GlanceModifier.fillMaxSize().clickable(talk(ctx)).padding(start = 4.dp, end = if (ending) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Face(m, cfg, p, face)
        Spacer(GlanceModifier.width(4.dp))
        Column(if (ending) GlanceModifier.defaultWeight() else GlanceModifier) {
            Text("Laura", maxLines = 1, style = TextStyle(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.height(5.dp))
            Status(m, p)
        }
        if (ending) {
            Spacer(GlanceModifier.width(4.dp))
            IconButton(R.drawable.ic_w_stop, "End", p.btn, p.onBtn, end(), 36.dp)
        }
    }
}

@Composable
private fun SquareLayout(m: WidgetModel, cfg: WidgetConfig, p: Pal, size: DpSize) {
    val ctx = LocalContext.current
    Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
        Box(GlanceModifier.padding(start = 2.dp)) { Status(m, p) }
        Box(GlanceModifier.fillMaxWidth().defaultWeight().clickable(talk(ctx)), contentAlignment = Alignment.Center) {
            Face(m, cfg, p, minOf(size.height - 104.dp, size.width - 40.dp).coerceAtLeast(48.dp))
        }
        MainButton(m, p, GlanceModifier.fillMaxWidth())
    }
}

@Composable
private fun CardLayout(m: WidgetModel, cfg: WidgetConfig, p: Pal, size: DpSize, reply: String?) {
    val ctx = LocalContext.current
    val faceSize = if (reply != null) (size.height - 240.dp).coerceIn(88.dp, 120.dp) else minOf(128.dp, size.height - 72.dp).coerceAtLeast(64.dp)
    Column(GlanceModifier.fillMaxSize().padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth().clickable(talk(ctx)), verticalAlignment = Alignment.CenterVertically) {
            Face(m, cfg, p, faceSize)
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text("Laura", maxLines = 1, style = TextStyle(color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(7.dp))
                Status(m, p)
                Spacer(GlanceModifier.height(4.dp))
                Text(m.meta, maxLines = 1, style = TextStyle(color = p.mute, fontSize = 12.sp))
            }
        }
        if (reply != null) {
            Spacer(GlanceModifier.height(8.dp))
            Spacer(GlanceModifier.defaultWeight())
            // lines that fit under the header and above the actions (about 20 dp each)
            val lines = ((size.height - faceSize - 150.dp).value / 20f).toInt().coerceIn(2, 8)
            ReplyCard(m.reply, reply, lines, p, GlanceModifier.fillMaxWidth().clickable(open(ctx)))
            Spacer(GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.height(10.dp))
        } else {
            Spacer(GlanceModifier.defaultWeight())
        }
        Actions(m, p)
    }
}

@Composable
private fun ReplyCard(r: Reply?, mode: String, lines: Int, p: Pal, modifier: GlanceModifier) {
    val ctx = LocalContext.current
    Box(modifier.then(tinted(R.drawable.widget_card, p.hair)).padding(1.dp)) {
        Column(GlanceModifier.fillMaxWidth().then(tinted(R.drawable.widget_card, p.raised)).padding(horizontal = 12.dp, vertical = 9.dp)) {
            if (r == null) {
                Text("No replies yet. Tap Talk and ask her something.", maxLines = 2, style = TextStyle(color = p.mute, fontSize = 13.sp))
                return@Column
            }
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(GlanceModifier.then(tinted(R.drawable.widget_chip, p.btn)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (r.local) p.local else p.claude, 7.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(r.source.removeSuffix(" · via Hermes"), maxLines = 1,
                        style = TextStyle(color = p.body, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
                Spacer(GlanceModifier.defaultWeight())
                if (r.time > 0) Text(DateFormat.getTimeFormat(ctx).format(r.time), style = TextStyle(color = p.mute, fontSize = 12.sp))
            }
            Spacer(GlanceModifier.height(6.dp))
            when {
                mode == "lock" -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(R.drawable.ic_w_lock), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(p.mute))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("Reply hidden on the lock screen", maxLines = 1, style = TextStyle(color = p.mute, fontSize = 14.sp))
                }
                mode == "who" || r.text.isBlank() -> Text("Reply text hidden", maxLines = 1, style = TextStyle(color = p.mute, fontSize = 14.sp))
                else -> Text(r.text, maxLines = lines, style = TextStyle(color = p.body, fontSize = 14.sp))
            }
        }
    }
}

/** Talk, or End while she is on: one solid pill either way. */
@Composable
private fun MainButton(m: WidgetModel, p: Pal, modifier: GlanceModifier) {
    val ctx = LocalContext.current
    val ending = m.primary == Primary.END
    Row(modifier.height(40.dp).then(tinted(R.drawable.widget_pill, p.cta)).clickable(if (ending) end() else talk(ctx)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(if (ending) R.drawable.ic_w_stop else R.drawable.ic_w_mic), null, GlanceModifier.size(20.dp),
            colorFilter = ColorFilter.tint(p.onCta))
        Spacer(GlanceModifier.width(8.dp))
        Text(if (ending) "End" else "Talk", style = TextStyle(color = p.onCta, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun IconButton(res: Int, label: String, bg: ColorProvider, fg: ColorProvider, action: Action, size: Dp = 40.dp) {
    Box(GlanceModifier.size(size).then(tinted(R.drawable.widget_circle, bg)).clickable(action), contentAlignment = Alignment.Center) {
        Image(ImageProvider(res), label, GlanceModifier.size(size / 2), colorFilter = ColorFilter.tint(fg))
    }
}

@Composable
private fun Actions(m: WidgetModel, p: Pal) {
    val ctx = LocalContext.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MainButton(m, p, GlanceModifier.defaultWeight())
        if (m.canMute) {
            Spacer(GlanceModifier.width(8.dp))
            if (m.muted) IconButton(R.drawable.ic_w_mic_off, "Unmute", p.raised, p.bad, actionRunCallback<MuteAction>())
            else IconButton(R.drawable.ic_w_mic_off, "Mute", p.btn, p.onBtn, actionRunCallback<MuteAction>())
        }
        Spacer(GlanceModifier.width(8.dp))
        IconButton(R.drawable.ic_w_open, "Open Laura", p.btn, p.onBtn, open(ctx))
    }
}
