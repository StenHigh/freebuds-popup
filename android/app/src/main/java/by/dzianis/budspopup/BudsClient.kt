package by.dzianis.budspopup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import by.dzianis.budspopup.protocol.HuaweiSpp
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Talks to Huawei FreeBuds over the proprietary SPP (RFCOMM) channel.
 * Blocking API — call from a background thread.
 */
@SuppressLint("MissingPermission")
class BudsClient(private val device: BluetoothDevice) : Closeable {

    companion object {
        private const val TAG = "BudsClient"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val HUAWEI_FALLBACK_CHANNEL = 1 // what OpenFreebuds uses for 5i/6i
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var connecting: BluetoothSocket? = null
    @Volatile private var closed = false
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    /** Tries SDP lookup of the SPP service first, then the raw channel Huawei uses. */
    fun connect(timeoutMs: Long = 4000) {
        val factories: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "sdp-spp" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "channel-$HUAWEI_FALLBACK_CHANNEL" to {
                // Hidden but long-standing API; the usual way to hit a fixed RFCOMM channel.
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, HUAWEI_FALLBACK_CHANNEL) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((name, make) in factories) {
            if (closed) throw IOException("closed")
            val s = try { make() } catch (e: Exception) { last = e; continue }
            // BluetoothSocket.connect() has no timeout: close it from another thread if it hangs.
            val guard = watchdog.schedule({ runCatching { if (!s.isConnected) s.close() } },
                timeoutMs, TimeUnit.MILLISECONDS)
            try {
                connecting = s
                s.connect()
                guard.cancel(false)
                connecting = null
                if (closed) { runCatching { s.close() }; throw IOException("closed") }
                socket = s
                Log.i(TAG, "connected via $name")
                return
            } catch (e: Exception) {
                guard.cancel(false)
                connecting = null
                runCatching { s.close() }
                if (closed) throw IOException("closed")
                Log.w(TAG, "connect via $name failed: ${e.message}")
                last = e
            }
        }
        throw IOException("RFCOMM connect failed: ${last?.message}", last)
    }

    /**
     * Requests battery + ANC, then keeps listening for push updates
     * (e.g. case lid / charging changes) until [close] is called.
     */
    fun listen(onBattery: (HuaweiSpp.Battery) -> Unit, onAnc: (String) -> Unit) {
        val s = socket ?: throw IOException("not connected")
        val out = s.outputStream
        val input = s.inputStream
        out.write(HuaweiSpp.batteryRequest().toBytes())
        out.write(HuaweiSpp.ancRequest().toBytes())
        out.flush()
        while (!closed) {
            val pkt = try { HuaweiSpp.readFrame(input) } catch (e: IOException) {
                if (closed) return else throw e
            } ?: return
            HuaweiSpp.parseBattery(pkt)?.let(onBattery)
            HuaweiSpp.parseAncMode(pkt)?.let(onAnc)
        }
    }

    override fun close() {
        closed = true
        runCatching { connecting?.close() }
        runCatching { socket?.close() }
        watchdog.shutdownNow()
    }
}
