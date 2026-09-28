package by.dzianis.budspopup

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.OvershootInterpolator
import by.dzianis.budspopup.protocol.HuaweiSpp

/**
 * The popup card: title, status line and three slots (left bud / case / right bud)
 * with hand-drawn icons and animated battery bars. No image assets needed.
 */
class BudsCardView(context: Context, private val title: String) : View(context) {

    private val dp = resources.displayMetrics.density
    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val cBg = if (night) Color.rgb(28, 28, 30) else Color.WHITE
    private val cText = if (night) Color.WHITE else Color.rgb(17, 17, 17)
    private val cSub = if (night) Color.rgb(160, 160, 166) else Color.rgb(110, 110, 115)
    private val cBody = if (night) Color.rgb(236, 236, 240) else Color.rgb(246, 246, 248)
    private val cEdge = if (night) Color.rgb(90, 90, 96) else Color.rgb(200, 200, 206)
    private val cTrack = if (night) Color.rgb(58, 58, 62) else Color.rgb(229, 229, 234)
    private val cGreen = Color.rgb(52, 199, 89)
    private val cAmber = Color.rgb(255, 159, 10)
    private val cRed = Color.rgb(255, 69, 58)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    var battery: HuaweiSpp.Battery? = null
        set(v) { field = v; animateBars(); invalidate() }
    var anc: String? = null
        set(v) { field = v; invalidate() }
    var status: String = "Подключено"
        set(v) { field = v; invalidate() }

    private var intro = 0f      // icons pop-in
    private var bars = 0f       // battery bars fill
    private var loadingPhase = 0f

    private val loadingAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { loadingPhase = it.animatedValue as Float; if (battery == null) invalidate() }
    }

    fun playIntro() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650; startDelay = 120; interpolator = OvershootInterpolator(1.6f)
            addUpdateListener { intro = it.animatedValue as Float; invalidate() }
        }.start()
        loadingAnim.start()
    }

    private fun animateBars() {
        loadingAnim.cancel()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener { bars = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadingAnim.cancel()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (252 * dp).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // card
        fill.color = cBg
        fill.setShadowLayer(18 * dp, 0f, 4 * dp, Color.argb(70, 0, 0, 0))
        r.set(8 * dp, 8 * dp, w - 8 * dp, h - 24 * dp)
        canvas.drawRoundRect(r, 28 * dp, 28 * dp, fill)
        fill.clearShadowLayer()

        // header
        text.color = cText; text.textSize = 17 * dp; text.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(title, w / 2, 42 * dp, text)
        text.typeface = Typeface.DEFAULT; text.textSize = 13 * dp; text.color = cSub
        val sub = listOfNotNull(status, ancLabel(anc)).joinToString("  ·  ")
        canvas.drawText(sub, w / 2, 62 * dp, text)

        // slots
        val b = battery
        val iconY = 116 * dp
        val slotW = (w - 16 * dp) / 3
        val xs = floatArrayOf(8 * dp + slotW * 0.5f, 8 * dp + slotW * 1.5f, 8 * dp + slotW * 2.5f)
        val s = intro.coerceAtLeast(0f)

        canvas.save(); canvas.scale(s, s, xs[0], iconY); drawBud(canvas, xs[0], iconY, left = true); canvas.restore()
        canvas.save(); canvas.scale(s, s, xs[1], iconY); drawCase(canvas, xs[1], iconY, b?.caseCharging == true); canvas.restore()
        canvas.save(); canvas.scale(s, s, xs[2], iconY); drawBud(canvas, xs[2], iconY, left = false); canvas.restore()

        val labels = arrayOf("Левый", "Кейс", "Правый")
        val levels = when {
            b == null -> intArrayOf(-2, -2, -2)
            b.hasTws() -> intArrayOf(b.left, b.caseLevel, b.right)
            else -> intArrayOf(b.global, -1, b.global)
        }
        val charging = booleanArrayOf(b?.leftCharging == true, b?.caseCharging == true, b?.rightCharging == true)
        for (i in 0..2) drawLevel(canvas, xs[i], 172 * dp, labels[i], levels[i], charging[i], i)
    }

    private fun drawBud(c: Canvas, cx: Float, cy: Float, left: Boolean) {
        val dir = if (left) 1f else -1f
        val head = 17 * dp
        val hx = cx - dir * 6 * dp; val hy = cy - 16 * dp
        // stem
        r.set(cx + dir * 2 * dp - 6 * dp, hy, cx + dir * 2 * dp + 6 * dp, cy + 34 * dp)
        fill.color = cBody; c.drawRoundRect(r, 6 * dp, 6 * dp, fill)
        stroke.color = cEdge; stroke.strokeWidth = 1.2f * dp; c.drawRoundRect(r, 6 * dp, 6 * dp, stroke)
        // head
        c.drawCircle(hx, hy, head, fill); c.drawCircle(hx, hy, head, stroke)
        // ear tip
        fill.color = cEdge
        c.drawCircle(hx - dir * 9 * dp, hy - 3 * dp, 6.5f * dp, fill)
        fill.color = cBody
    }

    private fun drawCase(c: Canvas, cx: Float, cy: Float, charging: Boolean) {
        val hw = 30 * dp; val hh = 24 * dp
        r.set(cx - hw, cy - hh, cx + hw, cy + hh)
        fill.color = cBody; c.drawRoundRect(r, 20 * dp, 20 * dp, fill)
        stroke.color = cEdge; stroke.strokeWidth = 1.2f * dp; c.drawRoundRect(r, 20 * dp, 20 * dp, stroke)
        c.drawLine(cx - hw + 3 * dp, cy - 6 * dp, cx + hw - 3 * dp, cy - 6 * dp, stroke)
        fill.color = if (charging) cGreen else cEdge
        c.drawCircle(cx, cy + 10 * dp, 2.5f * dp, fill)
    }

    /** level: 0..100, -1 = not available, -2 = still loading. */
    private fun drawLevel(c: Canvas, cx: Float, y: Float, label: String, level: Int, charging: Boolean, idx: Int) {
        text.textSize = 12 * dp; text.color = cSub; text.typeface = Typeface.DEFAULT
        c.drawText(label, cx, y, text)

        val barW = 56 * dp; val barH = 6 * dp; val by = y + 12 * dp
        r.set(cx - barW / 2, by, cx + barW / 2, by + barH)
        fill.color = cTrack; c.drawRoundRect(r, barH / 2, barH / 2, fill)

        text.textSize = 16 * dp; text.color = cText; text.typeface = Typeface.DEFAULT_BOLD
        when {
            level == -2 -> {
                // shimmer while loading
                val p = ((loadingPhase + idx * 0.18f) % 1f)
                val a = (Math.sin(p * Math.PI) * 180).toInt().coerceIn(40, 180)
                fill.color = Color.argb(a, Color.red(cSub), Color.green(cSub), Color.blue(cSub))
                c.drawRoundRect(r, barH / 2, barH / 2, fill)
                text.color = cSub; c.drawText("…", cx, by + 30 * dp, text)
            }
            level < 0 -> { text.color = cSub; c.drawText("—", cx, by + 30 * dp, text) }
            else -> {
                val frac = (level.coerceIn(0, 100) / 100f) * bars
                fill.color = when { charging -> cGreen; level <= 15 -> cRed; level <= 30 -> cAmber; else -> cGreen }
                r.right = r.left + barW * frac.coerceAtLeast(0.04f)
                c.drawRoundRect(r, barH / 2, barH / 2, fill)
                c.drawText((if (charging) "⚡" else "") + "$level%", cx, by + 30 * dp, text)
            }
        }
    }

    private fun ancLabel(mode: String?) = when (mode) {
        "cancellation" -> "Шумоподавление"
        "awareness" -> "Прозрачность"
        "off" -> "ANC выкл."
        else -> null
    }
}
