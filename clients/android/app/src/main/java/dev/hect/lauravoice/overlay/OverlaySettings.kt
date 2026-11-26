package dev.hect.lauravoice.overlay

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hect.lauravoice.settings.SButton
import dev.hect.lauravoice.settings.SCard
import dev.hect.lauravoice.settings.SwitchRow

/** Settings section for the floating bubble: on/off and the "Display over other apps" permission. */
@Composable
fun OverlaySettings() {
    val ctx = LocalContext.current
    val prefs = remember { OverlayPrefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var resumes by remember { mutableIntStateOf(0) }
    var explain by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val permitted = remember(resumes) { LauraOverlay.permitted(ctx) }

    SCard {
        SwitchRow(
            "Float over other apps",
            if (permitted) "While she's on and you leave the app, a small bubble shows her. Tap stops a reply " +
                "(or opens the app when she's quiet), hold to talk, drag to the × to turn her off."
            else "Needs \"Display over other apps\". Without it she stays in the notification only.",
            enabled && permitted,
        ) { on ->
            if (on && !permitted) explain = true
            prefs.enabled = on
            enabled = on
        }
        if (!permitted) SButton(
            "Allow display over other apps", { explain = true },
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }

    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("Show Laura over other apps") },
        text = {
            Text(
                "To keep Laura visible as a small bubble while you use other apps, Android needs the " +
                    "\"Display over other apps\" permission. Find Laura in the list on the next screen and switch it on.\n\n" +
                    "The bubble only appears while she is on, and never turns the microphone on by itself. " +
                    "On Samsung, also set Battery to Unrestricted for Laura (App info → Battery) so she isn't put to sleep."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                explain = false
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { explain = false }) { Text("Not now") } },
    )
}
