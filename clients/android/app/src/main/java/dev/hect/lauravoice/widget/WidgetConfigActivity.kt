package dev.hect.lauravoice.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import dev.hect.lauravoice.R

private val Bg = Color(0xFF120D16)
private val Raised = Color(0xFF1D1523)
private val Hair = Color(0x17FFFFFF)
private val Ink = Color(0xFFF7EEE9)
private val Body = Color(0xFFD8C9CF)
private val Mute = Color(0xFFA08F9C)
private val Cta = Color(0xFFFFC98F)
private val OnCta = Color(0xFF3A1626)

/** Placing or reconfiguring a widget: theme, face style, face only, last reply and its privacy, with a live preview. */
class WidgetConfigActivity : ComponentActivity() {
    private var appId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        appId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // backing out of the first configure leaves the widget unplaced
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appId))
        if (appId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val start = WidgetPrefs.load(this, appId)
        // preview at this widget's own size (portrait: min width, max height), within the screen
        val o = AppWidgetManager.getInstance(this).getAppWidgetOptions(appId)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        val size = if (w > 0 && h > 0) DpSize(w.coerceAtMost(328).dp, h.coerceAtMost(340).dp) else null
        setContent { Screen(start, size, ::done, ::finish) }
    }

    private fun done(c: WidgetConfig) {
        WidgetPrefs.save(this, appId, c)
        WidgetSync.configChanged()
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appId))
        finish()
    }
}

@Composable
private fun Screen(start: WidgetConfig, widgetSize: DpSize?, onDone: (WidgetConfig) -> Unit, onClose: () -> Unit) {
    var c by remember { mutableStateOf(start) }
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", color = Ink, fontSize = 20.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            Spacer(Modifier.width(8.dp))
            Text("Laura widget", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Cta).clickable { onDone(c) }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                Text("Done", color = OnCta, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Preview(c, widgetSize)
            Section("Theme")
            Segmented(listOf("auto" to "Auto", "light" to "Light", "dark" to "Dark"), c.theme) { c = c.copy(theme = it) }
            Section("Face")
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((key, label, res) in listOf(
                    Triple("live", "Live", R.drawable.widget_face_idle),
                    Triple("tile", "Tile", R.drawable.car_art_idle),
                    Triple("icon", "Icon", R.drawable.widget_face_icon),
                )) {
                    val on = c.face == key
                    Column(
                        Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(14.dp)).background(Raised)
                            .border(if (on) BorderStroke(2.dp, Cta) else BorderStroke(1.dp, Hair), RoundedCornerShape(14.dp))
                            .clickable { c = c.copy(face = key) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Image(painterResource(res), null, Modifier.size(50.dp).clip(RoundedCornerShape(if (key == "tile") 10.dp else 0.dp)),
                            contentScale = ContentScale.Fit)
                        Spacer(Modifier.height(6.dp))
                        Text(label, color = Body, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Section("Content")
            Toggle("Face only", "Just her face at any size. Tap still talks.", c.faceOnly) { c = c.copy(faceOnly = it) }
            Toggle("Last reply", "Her latest answer and who gave it, on 4x3 and larger.", c.showReply) { c = c.copy(showReply = it) }
            Toggle("Hide reply text", "Keep who answered, hide what she said. Lock screen widgets always hide it.", c.hideText) {
                c = c.copy(hideText = it)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The real widget, composed by Glance into RemoteViews with the settings being edited. */
@OptIn(ExperimentalGlanceApi::class)
@Composable
private fun Preview(c: WidgetConfig, widgetSize: DpSize?) {
    val ctx = LocalContext.current
    val size = widgetSize ?: DpSize(328.dp, 190.dp)
    var views by remember { mutableStateOf<android.widget.RemoteViews?>(null) }
    LaunchedEffect(c) {
        val w = LauraWidget().apply { previewConfig = c; previewModel = WidgetSync.model.value }
        views = runCatching { w.compose(ctx, size = size) }.getOrNull()
    }
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(size.height + 28.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3F2A63), Color(0xFF5A2340), Color(0xFF1B2F52)))),
        contentAlignment = Alignment.Center,
    ) {
        val v = views
        if (v != null) AndroidView(
            factory = { FrameLayout(it) },
            update = { f -> f.removeAllViews(); runCatching { f.addView(v.apply(f.context, f)) } },
            modifier = Modifier.size(size.width, size.height),
        )
    }
}

@Composable
private fun Section(t: String) = Text(t.uppercase(), color = Mute, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp,
    modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))

@Composable
private fun Segmented(items: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised)
        .border(1.dp, Hair, RoundedCornerShape(12.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((k, label) in items) {
            val on = k == value
            Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp)).background(if (on) Cta else Color.Transparent)
                .clickable { onPick(k) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (on) OnCta else Body, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun Toggle(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 15.sp)
            Text(desc, color = Mute, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Cta, checkedThumbColor = OnCta,
            uncheckedTrackColor = Color(0xFF3A2E40), uncheckedThumbColor = Mute, uncheckedBorderColor = Color.Transparent))
    }
}
