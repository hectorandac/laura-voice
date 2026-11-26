package dev.hect.lauravoice.car

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import dev.hect.lauravoice.core.CarState

/**
 * Feeds [CarState]: Android Auto connection (androidx.car.app CarConnection, the primary signal) and, optionally,
 * the car's Bluetooth device picked in settings (pref [PREF_CAR_BT], a MAC address; needs BLUETOOTH_CONNECT).
 * Call [start] once on the main thread; it lives as long as the process.
 */
class CarDetector(private val context: Context) {
    private val prefs = context.getSharedPreferences("laura", Context.MODE_PRIVATE)
    private val connected = mutableSetOf<String>()

    private val typeObserver = Observer<Int> { t ->
        Log.i(TAG, "car connection type $t")
        CarState.setConnectionType(t)
    }

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val addr = dev.address ?: return
            if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) connected += addr else connected -= addr
            updateBluetooth()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_CAR_BT) {
            refreshBluetooth()
        }
    }

    fun start() {
        CarConnection(context).type.observeForever(typeObserver)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, aclReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshBluetooth()
    }

    private fun carAddress(): String = prefs.getString(PREF_CAR_BT, "").orEmpty()

    private fun updateBluetooth() {
        val want = carAddress()
        CarState.setCarBluetooth(want.isNotEmpty() && hasPermission(context) && want in connected)
    }

    /** Seeds [connected] from the A2DP and hands-free profiles (ACL broadcasts only report changes). */
    @SuppressLint("MissingPermission")
    private fun refreshBluetooth() {
        updateBluetooth()
        if (carAddress().isEmpty() || !hasPermission(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.forEach { connected += it.address } }
                        adapter.closeProfileProxy(p, proxy)
                        updateBluetooth()
                    }
                    override fun onServiceDisconnected(p: Int) {}
                }, profile)
            }
        }
    }

    companion object {
        private const val TAG = "CarDetector"
        const val PREF_CAR_BT = "car_bt"

        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        /** Paired devices (name, address) for a "my car" picker; empty without BLUETOOTH_CONNECT. */
        @SuppressLint("MissingPermission")
        fun pairedDevices(context: Context): List<Pair<String, String>> {
            if (!hasPermission(context)) return emptyList()
            val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            return runCatching { adapter.bondedDevices.map { (it.name ?: it.address) to it.address } }.getOrDefault(emptyList())
                .sortedBy { it.first.lowercase() }
        }
    }
}
