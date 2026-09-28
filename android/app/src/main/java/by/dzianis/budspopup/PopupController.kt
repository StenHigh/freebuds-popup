package by.dzianis.budspopup

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Shows the overlay card and fills it with data read over SPP.
 * Must be called on the main thread. [onFinished] is always called exactly once.
 */
object PopupController {

    private const val TAG = "PopupController"
    private const val SHOW_AFTER_DATA_MS = 4500L
    private const val HARD_LIMIT_MS = 9000L   // well under the 60 s background-receiver budget

    private val main = Handler(Looper.getMainLooper())
    private var active: Session? = null

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, device: BluetoothDevice, onFinished: () -> Unit) {
        if (!Settings.canDrawOverlays(ctx)) {
            Prefs.log(ctx, "Нет разрешения на показ поверх других окон")
            onFinished(); return
        }
        active?.dismiss()
        val name = runCatching { device.alias ?: device.name }.getOrNull() ?: "Наушники"
        val s = Session(ctx.applicationContext, device, name, onFinished)
        active = s
        s.start()
    }

    private class Session(
        val ctx: Context,
        val device: BluetoothDevice,
        val name: String,
        val onFinished: () -> Unit,
    ) {
        private val wm = ctx.getSystemService(WindowManager::class.java)
        private val card = BudsCardView(ctx, name)
        private val root = FrameLayout(ctx)
        private var client: BudsClient? = null
        @Volatile private var done = false
        private val autoDismiss = Runnable { dismiss() }

        @SuppressLint("ClickableViewAccessibility")
        fun start() {
            val dp = ctx.resources.displayMetrics.density
            root.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            root.setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                y = (24 * dp).toInt()
                windowAnimations = 0
            }

            // tap or swipe down to dismiss
            var downY = 0f
            root.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downY = e.rawY; main.removeCallbacks(autoDismiss) }
                    MotionEvent.ACTION_MOVE -> v.translationY = (e.rawY - downY).coerceAtLeast(0f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dy = e.rawY - downY
                        if (dy > 60 * dp || abs(dy) < 10 * dp) dismiss()
                        else { v.animate().translationY(0f).setDuration(180).start(); main.postDelayed(autoDismiss, 2500) }
                    }
                }
                true
            }

            try {
                wm.addView(root, lp)
            } catch (e: Exception) {
                Prefs.log(ctx, "Не удалось показать окно: ${e.message}")
                finish(); return
            }

            root.translationY = 400 * dp
            root.post {
                root.animate().translationY(0f).setDuration(420)
                    .setInterpolator(DecelerateInterpolator(2.2f)).start()
                card.playIntro()
                vibrate()
            }

            main.postDelayed(autoDismiss, HARD_LIMIT_MS)
            loadData()
        }

        private fun loadData() {
            val c = BudsClient(device).also { client = it }
            thread(name = "buds-spp", isDaemon = true) {
                try {
                    Thread.sleep(600) // let A2DP/HFP settle after ACL comes up
                    c.connect()
                    var first = true
                    c.listen(
                        onBattery = { b ->
                            Log.i(TAG, "battery $b")
                            main.post {
                                if (done) return@post
                                card.battery = b
                                if (first) {
                                    first = false
                                    Prefs.log(ctx, "OK: $b")
                                    main.removeCallbacks(autoDismiss)
                                    main.postDelayed(autoDismiss, SHOW_AFTER_DATA_MS)
                                }
                            }
                        },
                        onAnc = { m -> main.post { if (!done) card.anc = m } },
                    )
                } catch (e: Exception) {
                    if (!done) {
                        Log.w(TAG, "spp failed", e)
                        Prefs.log(ctx, "SPP ошибка: ${e.message}")
                        main.post {
                            if (done) return@post
                            card.status = "Подключено (нет данных о заряде)"
                            card.battery = by.dzianis.budspopup.protocol.HuaweiSpp.Battery()
                            main.removeCallbacks(autoDismiss)
                            main.postDelayed(autoDismiss, 2500)
                        }
                    }
                }
            }
        }

        private fun vibrate() {
            runCatching {
                ctx.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        }

        fun dismiss() {
            if (done) return
            done = true
            main.removeCallbacks(autoDismiss)
            client?.close()
            root.animate().translationY(root.height.toFloat() + 200f).setDuration(260)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { runCatching { wm.removeView(root) }; finish() }
                .start()
            // safety net in case the animation never ends (e.g. view already detached)
            main.postDelayed({ runCatching { wm.removeView(root) }; finish() }, 1000)
        }

        private var finished = false

        private fun finish() {
            if (finished) return
            finished = true
            done = true
            client?.close()
            if (active === this) active = null
            onFinished()
        }
    }
}
