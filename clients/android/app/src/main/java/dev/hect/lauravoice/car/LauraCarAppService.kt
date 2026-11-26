package dev.hect.lauravoice.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.hect.lauravoice.App
import dev.hect.lauravoice.R
import dev.hect.lauravoice.core.CarCall
import dev.hect.lauravoice.core.CarState
import dev.hect.lauravoice.core.UiState
import dev.hect.lauravoice.core.View
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Laura's car screen (Car App Library, calling category): who's talking, what you said, what she's saying, and
 * Call Laura / Hang up + Mute. Android Auto shows its own call screen while the call is up and this screen when
 * it isn't (or when he opens Laura from the launcher during the call).
 */
class LauraCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = AgentScreen(carContext)
    }
}

/** What the agent screen shows; equal models don't re-send the template. */
data class AgentModel(val status: String, val state: String, val you: String, val her: String, val call: Boolean, val muted: Boolean)

class AgentScreen(carContext: CarContext) : Screen(carContext) {
    private var model = model(App.engine.ui.value, CarState.call.value)

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(App.engine.ui, CarState.call) { ui, call -> model(ui, call) }
                    .distinctUntilChanged().conflate().collect {
                        model = it
                        invalidate()
                        // Captions change with every partial; one template a second is plenty for a glance.
                        delay(MIN_REFRESH_MS)
                    }
            }
        }
    }

    private fun model(ui: UiState, call: Boolean): AgentModel {
        val last = ui.lines.lastOrNull { it.role == "user" || it.role == "assistant" }
        val you = ui.partial.ifBlank { ui.lines.lastOrNull { it.role == "user" }?.text.orEmpty() }
        val her = if (last?.role == "assistant") last.text else ""
        val state = when {
            !ui.active || ui.muted -> "idle"
            ui.view == View.SPEAKING -> "speak"
            ui.view == View.THINKING -> "think"
            call || ui.awake -> "listen"
            else -> "idle"
        }
        return AgentModel(CarCall.status(ui, call), state, CarCall.caption(you, 90), CarCall.caption(her, 120), call, ui.muted)
    }

    override fun onGetTemplate(): Template {
        val m = model
        // Two rows with fixed titles, so every update is a template refresh (no step quota used).
        val you = Row.Builder().setTitle("You").setImage(icon(R.drawable.ic_car_person, GREY))
        if (m.you.isNotEmpty()) you.addText(m.you)
        val her = Row.Builder().setTitle("Laura").setImage(icon(GLYPH[m.state] ?: R.drawable.ic_car_dot, TINT[m.state] ?: GREY))
            .addText(if (m.her.isNotEmpty() && m.state != "listen") m.her else m.status)
        val pane = Pane.Builder().addRow(you.build()).addRow(her.build())
        val main = if (m.call) {
            Action.Builder().setTitle("Hang up").setIcon(icon(R.drawable.ic_car_call_end, null))
                .setBackgroundColor(CarColor.RED).setOnClickListener { LauraCall.hangUp() }
        } else {
            Action.Builder().setTitle("Call Laura").setIcon(icon(R.drawable.ic_car_call, null))
                .setBackgroundColor(CarColor.GREEN).setOnClickListener {
                    if (!LauraCall.callLaura()) {
                        CarToast.makeText(carContext, "Open Laura on your phone once", CarToast.LENGTH_LONG).show()
                    }
                }
        }
        if (carContext.carAppApiLevel >= 4) main.setFlags(Action.FLAG_PRIMARY)
        pane.addAction(main.build())
        pane.addAction(
            Action.Builder().setTitle(if (m.muted) "Unmute" else "Mute")
                .setOnClickListener { App.engine.setMuted(!App.engine.ui.value.muted) }.build()
        )
        if (carContext.carAppApiLevel >= 4) pane.setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.car_portrait)).build())
        return PaneTemplate.Builder(pane.build())
            .setHeader(Header.Builder().setTitle("Laura").setStartHeaderAction(Action.APP_ICON).build())
            .build()
    }

    private fun icon(res: Int, tint: CarColor?): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).apply { tint?.let { setTint(it) } }.build()

    companion object {
        private const val MIN_REFRESH_MS = 1000L
        // Lavender (her hair) = listening, apricot (her icon plate) = speaking; see docs/car/design.
        private val LISTEN = CarColor.createCustom(0xFF7C83E6.toInt(), 0xFF5E6AD2.toInt())
        private val SPEAK = CarColor.createCustom(0xFFFFD9A8.toInt(), 0xFFB5651D.toInt())
        private val GREY = CarColor.createCustom(0xFF9AA0A6.toInt(), 0xFF5F6368.toInt())
        private val GLYPH = mapOf("listen" to R.drawable.ic_car_mic, "speak" to R.drawable.ic_car_wave, "think" to R.drawable.ic_car_dots)
        private val TINT = mapOf("listen" to LISTEN, "speak" to SPEAK)
    }
}
