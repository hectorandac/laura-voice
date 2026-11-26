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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hect.lauravoice.Good
import dev.hect.lauravoice.R
import dev.hect.lauravoice.TextMuted
import dev.hect.lauravoice.TextSecondary
import dev.hect.lauravoice.Warn

/**
 * Settings → Invocation: what's needed to call Laura up from anywhere (side key, assist gesture, lock screen),
 * each with its status and a button to the exact system screen. Re-checked every time the screen resumes.
 */
@Composable
fun InvocationSettingsSection() {
    val ctx = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val mic = remember(resumes) { SystemScreens.micGranted(ctx) }
    val assistant = remember(resumes) { SystemScreens.isAssistant(ctx) }
    val overlay = remember(resumes) { android.provider.Settings.canDrawOverlays(ctx) }
    val battery = remember(resumes) { SystemScreens.batteryExempt(ctx) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        resumes++
        val act = ctx.findActivity()
        if (!ok && act != null && !act.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) SystemScreens.openAppInfo(ctx)
    }
    val samsung = SystemScreens.isSamsung()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (samsung) "Hold the side button to call Laura from anywhere, even the lock screen. She pops up over " +
                "whatever is on screen, listens right away, and leaves when you're done."
            else "Call Laura from anywhere with your phone's assistant gesture (hold the power button, or swipe up " +
                "from a bottom corner). She pops up over whatever is on screen, listens right away, and leaves when you're done.",
            fontSize = 12.sp, color = Muted,
        )
        Step(
            "Microphone", mic,
            if (mic) "Allowed." else "She can't hear you without it.",
            if (mic) null else "Allow",
        ) { askMic.launch(Manifest.permission.RECORD_AUDIO) }
        Step(
            "Default digital assistant", assistant,
            if (assistant) "Laura is your digital assistant app."
            else if (samsung) "Tap Digital assistant app → Device assistance app → Laura. (Settings → Apps → Choose default apps)"
            else "Pick Laura as the digital assistant app.",
            if (assistant) "Change" else "Open",
        ) { SystemScreens.openAssistant(ctx) }
        if (samsung) Step(
            "Side button", null,
            "Press and hold → Digital assistant app (needs the step above; the power-off menu then moves to " +
                "Side + Volume down). Or Double press → Open app → Laura, then the ⚙ next to her → Talk to Laura.",
            "Open",
        ) { SystemScreens.openSideKey(ctx) }
        Step(
            "Display over other apps", overlay,
            if (overlay) "She can float over other apps."
            else "Without it she shows in a small panel at the bottom instead, and the app behind her pauses.",
            if (overlay) null else "Allow",
        ) { SystemScreens.openOverlay(ctx) }
        Step(
            "Battery", battery,
            if (battery) "Not restricted."
            else if (samsung) "Allow it here, or App info → Battery → Unrestricted, so One UI doesn't put her to sleep."
            else "Let her run without battery optimization so she answers quickly.",
            if (battery) null else "Allow",
        ) { SystemScreens.openBattery(ctx) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Invocation.launch(ctx, "settings") }, enabled = mic) { Text("Try it") }
            TextButton(onClick = { pinShortcut(ctx) }) { Text("Add to home screen") }
        }
    }
}

@Composable
private fun Step(title: String, ok: Boolean?, detail: String, action: String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.padding(end = 10.dp).size(10.dp).background(
                when (ok) { true -> Good; false -> Warn; null -> TextMuted }, CircleShape
            )
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = TextSecondary)
            Text(detail, fontSize = 12.sp, color = Muted)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClick) { Text(action) }
        }
    }
}

private val Muted = TextMuted

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
