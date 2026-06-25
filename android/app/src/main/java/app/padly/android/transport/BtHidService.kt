package app.padly.android.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Bluetooth HID fallback: advertises the phone as a Combo HID (mouse + keyboard).
 * The Mac's standard Bluetooth stack handles the rest — no Padly Mac app needed.
 *
 * Feature surface is reduced compared to the WiFi path: cursor + click + scroll +
 * keyboard only (no multi-touch gestures, no presenter mode).
 */
class BtHidService(private val context: Context) {
    private var adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false
    private var connectedDevice: BluetoothDevice? = null

    private val executor = Executors.newSingleThreadExecutor()

    /** Combo HID descriptor: report id 1 = mouse, report id 2 = keyboard. */
    private val descriptor: ByteArray = byteArrayOf(
        // Mouse
        0x05, 0x01,                    // Usage Page (Generic Desktop)
        0x09, 0x02,                    // Usage (Mouse)
        0xA1.toByte(), 0x01,           // Collection (Application)
        0x85.toByte(), 0x01,           //   Report ID (1)
        0x09, 0x01,                    //   Usage (Pointer)
        0xA1.toByte(), 0x00,           //   Collection (Physical)
        0x05, 0x09,                    //     Usage Page (Button)
        0x19, 0x01,                    //     Usage Minimum (1)
        0x29, 0x03,                    //     Usage Maximum (3)
        0x15, 0x00,                    //     Logical Min 0
        0x25, 0x01,                    //     Logical Max 1
        0x95.toByte(), 0x03,           //     Report Count (3)
        0x75, 0x01,                    //     Report Size (1)
        0x81.toByte(), 0x02,           //     Input (Data,Var,Abs)
        0x95.toByte(), 0x01,           //     Report Count (1)
        0x75, 0x05,                    //     Report Size (5)
        0x81.toByte(), 0x03,           //     Input (Cnst,Var,Abs) — padding
        0x05, 0x01,                    //     Usage Page (Generic Desktop)
        0x09, 0x30,                    //     Usage (X)
        0x09, 0x31,                    //     Usage (Y)
        0x09, 0x38,                    //     Usage (Wheel)
        0x15, 0x81.toByte(),           //     Logical Min -127
        0x25, 0x7F,                    //     Logical Max 127
        0x75, 0x08,                    //     Report Size (8)
        0x95.toByte(), 0x03,           //     Report Count (3)
        0x81.toByte(), 0x06,           //     Input (Data,Var,Rel)
        0xC0.toByte(),                 //   End Collection
        0xC0.toByte(),                 // End Collection
        // Keyboard
        0x05, 0x01,
        0x09, 0x06,                    // Keyboard
        0xA1.toByte(), 0x01,
        0x85.toByte(), 0x02,           // Report ID 2
        0x05, 0x07,                    // Usage Page (Keyboard)
        0x19, 0xE0.toByte(),           // Modifier keys range
        0x29, 0xE7.toByte(),
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95.toByte(), 0x08,
        0x81.toByte(), 0x02,           // Input modifiers
        0x95.toByte(), 0x01,
        0x75, 0x08,
        0x81.toByte(), 0x03,           // Reserved
        0x95.toByte(), 0x06,
        0x75, 0x08,
        0x15, 0x00,
        0x25, 0x65,
        0x05, 0x07,
        0x19, 0x00,
        0x29, 0x65,
        0x81.toByte(), 0x00,           // Input keys
        0xC0.toByte(),
    )

    fun start() {
        val adapter = adapter ?: return
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
            override fun onServiceDisconnected(profile: Int) {
                hidDevice = null
                registered = false
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Padly",
            "Phone as trackpad",
            "padly.app",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            descriptor,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
            800, 9, 0, 11_250, BluetoothHidDeviceAppQosSettings.MAX,
        )
        try {
            hidDevice?.registerApp(sdp, null, qos, executor, object : BluetoothHidDevice.Callback() {
                override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                    this@BtHidService.registered = registered
                    Log.i(TAG, "HID registered=$registered")
                }
                override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                    connectedDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                }
            })
        } catch (sec: SecurityException) {
            Log.w(TAG, "BT permission missing: $sec")
        }
    }

    fun stop() {
        hidDevice?.unregisterApp()
        registered = false
    }

    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val device = connectedDevice ?: return
        val payload = byteArrayOf(
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte(),
        )
        try {
            hidDevice?.sendReport(device, 1, payload)
        } catch (_: SecurityException) {}
    }

    fun sendKeyReport(modifiers: Int, keys: IntArray) {
        val device = connectedDevice ?: return
        val payload = ByteArray(8)
        payload[0] = (modifiers and 0xFF).toByte()
        for (i in 0 until 6) {
            payload[2 + i] = if (i < keys.size) (keys[i] and 0xFF).toByte() else 0
        }
        try {
            hidDevice?.sendReport(device, 2, payload)
        } catch (_: SecurityException) {}
    }

    private companion object { const val TAG = "Padly/BT" }
}
