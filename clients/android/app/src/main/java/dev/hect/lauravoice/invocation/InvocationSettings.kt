package dev.hect.lauravoice.invocation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hect.lauravoice.LocalPalette
import dev.hect.lauravoice.R
import dev.hect.lauravoice.settings.Glyph
import dev.hect.lauravoice.settings.Glyphs
import dev.hect.lauravoice.settings.Label
import dev.hect.lauravoice.settings.SButton
import dev.hect.lauravoice.settings.SCard
import dev.hect.lauravoice.settings.Sep
import dev.hect.lauravoice.settings.Tone

/** How many of the steps that let her be called from anywhere are done (for the summary in Settings). */
fun invocationReadiness(ctx: Context): Pair<Int, Int> {
    val assistant = SystemScreens.isAssistant(ctx)
    val steps = buildList {
        add(SystemScreens.micGranted(ctx))
        add(assistant)
        if (SystemScreens.isSamsung()) add(Invocation.sideKeyWorking(ctx, assistant) == true)
        add(android.provider.Settings.canDrawOverlays(ctx))
        add(SystemScreens.batteryExempt(ctx))
    }
    return steps.count { it } to steps.size
}

/**
 * Settings → Call her from anywhere: what's needed to call Laura up from anywhere (side key, assist gesture, lock
 * screen), each with its status and a button to the exact system screen. Re-checked every time the screen resumes.
 */
@Composable
fun InvocationSettingsSection() {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val mic = remember(resumes) { SystemScreens.micGranted(ctx) }
    val assistant = remember(resumes) { SystemScreens.isAssistant(ctx) }
    val overlay = remember(resumes) { android.provider.Settings.canDrawOverlays(ctx) }
    val battery = remember(resumes) { SystemScreens.batteryExempt(ctx) }
    val sideKey = remember(resumes) { Invocation.sideKeyWorking(ctx, assistant) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        resumes++
        val act = ctx.findActivity()
        if (!ok && act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) SystemScreens.openAppInfo(ctx)
    }
    val samsung = SystemScreens.isSamsung()
    val done = listOfNotNull(mic, assistant, if (samsung) sideKey == true else null, overlay, battery)

    Column {
        Text(
            if (samsung) "Hold the side button to call Laura from anywhere, even the lock screen. She pops up over " +
                "whatever is on screen, listens right away, and leaves when you're done."
            else "Call Laura from anywhere with your phone's assistant gesture (hold the power button, or swipe up " +
                "from a bottom corner). She pops up over whatever is on screen, listens right away, and leaves when you're done.",
            Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp), fontSize = 13.5.sp, lineHeight = 20.sp, color = p.ink2,
        )
        SCard {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
                Label("Setup", "${done.count { it }} of ${done.size} ready")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.s2)) {
                    Box(
                        Modifier.fillMaxWidth(done.count { it } / done.size.toFloat()).height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(p.accent)
                    )
                }
            }
            Step(
                "Microphone", mic,
                if (mic) "Allowed." else "She can't hear you without it.",
                if (mic) null else "Allow",
            ) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
            Sep(50.dp)
            Step(
                "Default digital assistant", assistant,
                if (assistant) "Laura is your digital assistant app."
                else if (samsung) "Tap Digital assistant app → Device assistance app → Laura. (Settings → Apps → Choose default apps)"
                else "Pick Laura as the digital assistant app.",
                if (assistant) "Change" else "Open",
            ) { SystemScreens.openAssistant(ctx) }
            if (samsung) {
                Sep(50.dp)
                Step(
                    "Side button", sideKey == true,
                    when (sideKey) {
                        true -> "Working: hold the side button to call her."
                        false -> "Try holding the side button. If she doesn't come up, set Press and hold → Digital assistant app."
                        null -> "Press and hold → Digital assistant app (needs the step above; the power-off menu then moves to " +
                            "Side + Volume down). Or Double press → Open app → Laura, then the ⚙ next to her → Talk to Laura."
                    },
                    "Open",
                ) { SystemScreens.openSideKey(ctx) }
            }
            Sep(50.dp)
            Step(
                "Display over other apps", overlay,
                if (overlay) "She can float over other apps."
                else "Without it she shows in a small panel at the bottom instead, and the app behind her pauses.",
                if (overlay) null else "Allow",
            ) { SystemScreens.openOverlay(ctx) }
            Sep(50.dp)
            Step(
                "Battery", battery,
                if (battery) "Not restricted."
                else if (samsung) "Allow it here, or App info → Battery → Unrestricted, so One UI doesn't put her to sleep."
                else "Let her run without battery optimization so she answers quickly.",
                if (battery) null else "Allow",
            ) { SystemScreens.openBattery(ctx) }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SButton("Try it", { Invocation.launch(ctx, "settings") }, Modifier.weight(1f), enabled = mic, icon = Glyphs.Mic)
            SButton("Add to home screen", { pinShortcut(ctx) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Step(title: String, ok: Boolean, detail: String, action: String?, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 1.dp).size(22.dp).then(
                if (ok) Modifier.background(p.good.copy(alpha = 0.18f), CircleShape)
                else Modifier.border(1.5.dp, p.warn, CircleShape)
            ),
            contentAlignment = Alignment.Center,
        ) { if (ok) Glyph(Glyphs.Check, p.good, 14.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.ink)
            Text(detail, Modifier.padding(top = 2.dp), fontSize = 12.5.sp, lineHeight = 18.sp, color = p.ink3)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            SButton(action, onClick, tone = if (ok) Tone.Ghost else Tone.Secondary, small = !ok)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun pinShortcut(ctx: Context) {
    val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
    if (!sm.isRequestPinShortcutSupported) return
    val info = ShortcutInfo.Builder(ctx, "talk-pinned")
        .setShortLabel(ctx.getString(R.string.invoke_short))
        .setLongLabel(ctx.getString(R.string.invoke_long))
        .setIcon(Icon.createWithResource(ctx, R.drawable.ic_launcher))
        .setIntent(Invocation.intent(ctx, "shortcut").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        .build()
    sm.requestPinShortcut(info, null)
}
