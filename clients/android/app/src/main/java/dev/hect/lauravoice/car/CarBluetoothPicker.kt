package dev.hect.lauravoice.car

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hect.lauravoice.settings.Glyphs
import dev.hect.lauravoice.settings.NavRow

/** "My car's Bluetooth": a fallback car-mode signal for when Android Auto isn't used. Stored in pref car_bt. */
@Composable
fun CarBluetoothPicker() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("laura", Context.MODE_PRIVATE) }
    var address by remember { mutableStateOf(prefs.getString(CarDetector.PREF_CAR_BT, "").orEmpty()) }
    var name by remember { mutableStateOf(prefs.getString(PREF_NAME, "").orEmpty()) }
    var open by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) open = true }

    NavRow(Glyphs.Bluetooth, "Car's Bluetooth", if (address.isEmpty()) "Choose your car's Bluetooth" else name.ifEmpty { address }) {
        if (CarDetector.hasPermission(ctx)) open = true
        else ask.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    if (open) {
        val devices = remember { CarDetector.pairedDevices(ctx) }
        fun pick(n: String, a: String) {
            prefs.edit().putString(CarDetector.PREF_CAR_BT, a).putString(PREF_NAME, n).apply()
            address = a
            name = n
            open = false
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Your car's Bluetooth") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (devices.isEmpty()) Text("No paired devices.")
                    devices.forEach { (n, a) ->
                        Text(n, Modifier.fillMaxWidth().clickable { pick(n, a) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            dismissButton = if (address.isNotEmpty()) ({ TextButton(onClick = { pick("", "") }) { Text("None") } }) else null,
        )
    }
}

private const val PREF_NAME = "car_bt_name"
