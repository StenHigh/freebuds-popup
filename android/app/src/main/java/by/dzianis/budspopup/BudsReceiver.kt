package by.dzianis.budspopup

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Fires when any Bluetooth device's ACL link comes up — for TWS earbuds that is
 * the moment the case is opened and they reconnect.
 */
class BudsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
        val ctx = context.applicationContext
        if (!Prefs.matches(ctx, device)) return
        if (!Prefs.shouldShow(ctx, device.address)) return

        // Keep the process alive while the popup is on screen (limit for background receivers is 60 s).
        val pending = goAsync()
        PopupController.show(ctx, device) { pending.finish() }
    }
}
