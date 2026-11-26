package dev.hect.lauravoice.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Settings → Invocation: calling Laura up from outside the app, then the floating bubble while she's on. */
@Composable
fun InvocationSettingsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        dev.hect.lauravoice.invocation.InvocationSettingsSection()
        dev.hect.lauravoice.overlay.OverlaySettings()
    }
}
