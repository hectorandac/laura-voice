package dev.hect.lauravoice.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable

/** Settings → Call her from anywhere: calling Laura up from outside the app, then the floating bubble while she's on. */
@Composable
fun InvocationSettingsSection() {
    Column {
        dev.hect.lauravoice.invocation.InvocationSettingsSection()
        Eyebrow("Floating Laura")
        dev.hect.lauravoice.overlay.OverlaySettings()
    }
}
