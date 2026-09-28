package by.dzianis.budspopup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prefs {
    private const val FILE = "buds_popup"
    private const val KEY_ADDRESS = "device_address"   // empty = auto (any FreeBuds)
    private const val KEY_LOG = "last_log"
    private const val DEBOUNCE_MS = 15_000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selectedAddress(ctx: Context): String? = sp(ctx).getString(KEY_ADDRESS, null)?.ifEmpty { null }
    fun setSelectedAddress(ctx: Context, addr: String?) = sp(ctx).edit().putString(KEY_ADDRESS, addr ?: "").apply()

    @SuppressLint("MissingPermission")
    fun matches(ctx: Context, device: BluetoothDevice): Boolean {
        selectedAddress(ctx)?.let { return it.equals(device.address, ignoreCase = true) }
        val name = runCatching { device.name }.getOrNull() ?: return false
        return looksLikeFreeBuds(name)
    }

    fun looksLikeFreeBuds(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return "freebuds" in n || "freeclip" in n || "freelace" in n
    }

    /** Collapses duplicate ACL events (BR/EDR + LE, reconnect bursts). */
    fun shouldShow(ctx: Context, address: String): Boolean {
        val key = "shown_$address"
        val now = System.currentTimeMillis()
        val last = sp(ctx).getLong(key, 0)
        if (now - last < DEBOUNCE_MS) return false
        sp(ctx).edit().putLong(key, now).apply()
        return true
    }

    fun log(ctx: Context, msg: String) {
        val ts = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(Date())
        sp(ctx).edit().putString(KEY_LOG, "$ts  $msg").apply()
    }

    fun lastLog(ctx: Context): String = sp(ctx).getString(KEY_LOG, "—") ?: "—"
}
