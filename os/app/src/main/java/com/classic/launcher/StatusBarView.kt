package com.classic.launcher

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The replacement status bar (drawn on top of the real one by StatusBarService).
 *
 * Left : time  (2:30 PM)
 * Right: speed  VoLTE  signal  wifi/data  percent  [battery with bolt when charging]
 *
 * Swipe down on the left half  -> notification center
 * Swipe down on the right half -> control center
 */
class StatusBarView(ctx: Context, private val svc: StatusBarService) : View(ctx) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val handler = Handler(Looper.getMainLooper())
    private val fTime = SimpleDateFormat("h:mm a", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }

    // ---- state
    private var timeText = ""
    private var battLevel = 100
    private var charging = false
    private var netType = 0          // 0 none, 1 wifi, 2 mobile data
    private var signal = 0           // 0..4
    private var volte = false
    private var speedText = "0.0KB/s"
    private var lastBytes = -1L
    private var lastTs = 0L
    private var tickCount = 0
    private var ticking = false

    // ---- glow
    private var glowProg = 0f
    private var glowAlpha = 0f
    private var glowAnim: ValueAnimator? = null

    // ---- paints
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rf = RectF()
    private val path = Path()

    // ---- touch
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var vt: VelocityTracker? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
                Intent.ACTION_SCREEN_OFF -> stopTicking()
                Intent.ACTION_SCREEN_ON -> startTicking()
                else -> { updateTime(); invalidate() }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!ticking) return
            sampleSpeed()
            tickCount++
            if (tickCount % 3 == 1) readNetwork()
            handler.postDelayed(this, 1000L)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val sticky = context.registerReceiver(receiver, f)
        if (sticky != null) readBattery(sticky)
        updateTime()
        readNetwork()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) startTicking()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try { context.unregisterReceiver(receiver) } catch (e: Exception) { }
        stopTicking()
        glowAnim?.cancel()
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastBytes = -1L
        updateTime()
        readNetwork()
        handler.post(tick)
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------ data

    private fun updateTime() {
        timeText = fTime.format(System.currentTimeMillis()).uppercase(Locale.ENGLISH)
    }

    private fun readBattery(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (lvl >= 0 && scale > 0) battLevel = lvl * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        charging = st == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0
        invalidate()
    }

    private fun sampleSpeed() {
        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val bytes = if (rx < 0 || tx < 0) 0L else rx + tx
        if (lastBytes >= 0 && now > lastTs) {
            val bps = max(0f, (bytes - lastBytes) * 1000f / (now - lastTs))
            val t = fmtSpeed(bps)
            if (t != speedText) { speedText = t; invalidate() }
        }
        lastBytes = bytes
        lastTs = now
    }

    private fun fmtSpeed(bps: Float): String {
        val kb = bps / 1024f
        return if (kb >= 1000f) String.format(Locale.US, "%.1fMB/s", kb / 1024f)
        else String.format(Locale.US, "%.1fKB/s", kb)
    }

    private fun readNetwork() {
        var nt = 0
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            nt = when {
                caps == null -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 0
            }
        } catch (e: Exception) { }

        var sig = 0
        var vo = false
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val ready = tm.simState == TelephonyManager.SIM_STATE_READY
            if (ready) {
                sig = if (Build.VERSION.SDK_INT >= 28) (tm.signalStrength?.level ?: 0) else 3
                if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    val t = tm.dataNetworkType
                    // Android does not let normal apps read the real IMS/VoLTE state,
                    // so this shows "VoLTE" when the phone is on LTE / 5G.
                    vo = t == TelephonyManager.NETWORK_TYPE_LTE || t == TelephonyManager.NETWORK_TYPE_NR
                }
            }
        } catch (e: Exception) { }

        if (nt != netType || sig != signal || vo != volte) {
            netType = nt; signal = sig; volte = vo
            invalidate()
        }
    }

    // ------------------------------------------------------------------ glow

    /** Blue light sweeps from the middle of the bar out to the left and right edges. */
    fun playGlow() {
        glowAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1100L
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { v ->
            val t = v.animatedValue as Float
            val e = min(1f, t / 0.6f)
            val inv = 1f - e
            glowProg = 1f - inv * inv * inv
            glowAlpha = when {
                t < 0.12f -> t / 0.12f
                t > 0.6f -> 1f - (t - 0.6f) / 0.4f
                else -> 1f
            }
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                glowAlpha = 0f
                invalidate()
            }
        })
        glowAnim = a
        a.start()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        c.drawColor(Color.BLACK)

        drawGlow(c, w, h)

        // time (left)
        tp.textAlign = Paint.Align.LEFT
        tp.color = Color.WHITE
        tp.textSize = dp(14f)
        c.drawText(timeText, dp(18f), cy - (tp.ascent() + tp.descent()) / 2f, tp)

        // right side, drawn from the edge inwards
        var x = w - dp(16f)

        // battery
        x = drawBattery(c, x, cy)
        x -= dp(5f)

        // percent
        tp.textAlign = Paint.Align.RIGHT
        tp.textSize = dp(12.5f)
        tp.color = Color.WHITE
        val pct = "$battLevel%"
        c.drawText(pct, x, cy - (tp.ascent() + tp.descent()) / 2f, tp)
        x -= tp.measureText(pct) + dp(10f)

        // wifi / mobile data
        if (netType != 0) {
            drawNet(c, x, cy)
            x -= dp(16f) + dp(9f)
        }

        // signal towers
        x = drawSignal(c, x, cy)
        x -= dp(9f)

        // VoLTE
        if (volte) {
            tp.textSize = dp(8.5f)
            tp.textAlign = Paint.Align.CENTER
            val tw = tp.measureText("VoLTE")
            val bw = tw + dp(7f)
            val bh = dp(12f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1f)
            p.color = 0xCCFFFFFF.toInt()
            rf.set(x - bw, cy - bh / 2f, x, cy + bh / 2f)
            c.drawRoundRect(rf, dp(3f), dp(3f), p)
            c.drawText("VoLTE", x - bw / 2f, cy - (tp.ascent() + tp.descent()) / 2f, tp)
            x -= bw + dp(9f)
        }

        // network speed
        tp.textAlign = Paint.Align.RIGHT
        tp.textSize = dp(11f)
        tp.color = 0xE6FFFFFF.toInt()
        c.drawText(speedText, x, cy - (tp.ascent() + tp.descent()) / 2f, tp)
    }

    private fun drawGlow(c: Canvas, w: Float, h: Float) {
        if (glowAlpha <= 0f) return
        val half = (w / 2f) * glowProg
        if (half < 2f) return
        val l = w / 2f - half
        val r = w / 2f + half
        val colors = intArrayOf(0x003B8BFF, 0xB32F7BFF.toInt(), 0xFF7CC4FF.toInt(), 0xB32F7BFF.toInt(), 0x003B8BFF)
        val pos = floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(l, 0f, r, 0f, colors, pos, Shader.TileMode.CLAMP)
        p.alpha = (glowAlpha * 255f).toInt()
        c.drawRect(l, 0f, r, h, p)
        // bright thin line along the bottom edge of the bar
        val line = dp(2f)
        c.drawRect(l, h - line, r, h, p)
        p.shader = null
        p.alpha = 255
    }

    private fun drawBattery(c: Canvas, right: Float, cy: Float): Float {
        val bw = dp(24f)
        val bh = dp(12f)
        val nub = dp(2.2f)
        val bodyR = right - nub
        val bodyL = bodyR - bw
        val top = cy - bh / 2f
        val bottom = cy + bh / 2f

        // outline
        val sw = dp(1.2f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = 0x99FFFFFF.toInt()
        rf.set(bodyL + sw / 2f, top + sw / 2f, bodyR - sw / 2f, bottom - sw / 2f)
        c.drawRoundRect(rf, dp(3.5f), dp(3.5f), p)

        // nub
        p.style = Paint.Style.FILL
        p.color = 0x99FFFFFF.toInt()
        rf.set(bodyR, cy - dp(2.5f), right, cy + dp(2.5f))
        c.drawRoundRect(rf, dp(1f), dp(1f), p)

        // level fill
        val inset = dp(2.3f)
        val full = bw - 2f * inset
        val fillW = max(dp(1.5f), full * battLevel / 100f)
        p.color = when {
            charging -> 0xFF34C759.toInt()
            battLevel <= 15 -> 0xFFFF3B30.toInt()
            else -> Color.WHITE
        }
        rf.set(bodyL + inset, top + inset, bodyL + inset + fillW, bottom - inset)
        c.drawRoundRect(rf, dp(1.8f), dp(1.8f), p)

        // charging bolt, inside the battery
        if (charging) {
            val bx = bodyL + bw / 2f
            path.reset()
            path.moveTo(bx + dp(1.3f), cy - dp(4.6f))
            path.lineTo(bx - dp(2.6f), cy + dp(0.8f))
            path.lineTo(bx - dp(0.2f), cy + dp(0.8f))
            path.lineTo(bx - dp(1.3f), cy + dp(4.6f))
            path.lineTo(bx + dp(2.6f), cy - dp(0.8f))
            path.lineTo(bx + dp(0.2f), cy - dp(0.8f))
            path.close()
            p.color = Color.WHITE
            c.drawPath(path, p)
        }
        return bodyL
    }

    private fun drawNet(c: Canvas, right: Float, cy: Float) {
        val iw = dp(16f)
        val ix = right - iw / 2f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.WHITE
        if (netType == 1) {
            // wifi: three arcs and a dot
            val by = cy + dp(5f)
            p.strokeWidth = dp(1.7f)
            for (r in floatArrayOf(dp(3.6f), dp(7f), dp(10.4f))) {
                rf.set(ix - r, by - r, ix + r, by + r)
                c.drawArc(rf, -135f, 90f, false, p)
            }
            p.style = Paint.Style.FILL
            c.drawCircle(ix, by, dp(1.4f), p)
        } else {
            // mobile data: up arrow + down arrow
            p.strokeWidth = dp(1.5f)
            val xa = ix - dp(3.6f)
            val xb = ix + dp(3.6f)
            c.drawLine(xa, cy + dp(5f), xa, cy - dp(5f), p)
            c.drawLine(xa, cy - dp(5f), xa - dp(3f), cy - dp(2f), p)
            c.drawLine(xa, cy - dp(5f), xa + dp(3f), cy - dp(2f), p)
            c.drawLine(xb, cy - dp(5f), xb, cy + dp(5f), p)
            c.drawLine(xb, cy + dp(5f), xb - dp(3f), cy + dp(2f), p)
            c.drawLine(xb, cy + dp(5f), xb + dp(3f), cy + dp(2f), p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
    }

    private fun drawSignal(c: Canvas, right: Float, cy: Float): Float {
        val bw = dp(3f)
        val gap = dp(1.6f)
        val heights = floatArrayOf(dp(4f), dp(6.5f), dp(9f), dp(11.5f))
        val total = 4 * bw + 3 * gap
        val left = right - total
        val bottom = cy + dp(5.75f)
        p.style = Paint.Style.FILL
        for (i in 0 until 4) {
            p.color = if (i < signal) Color.WHITE else 0x4DFFFFFF
            val l = left + i * (bw + gap)
            rf.set(l, bottom - heights[i], l + bw, bottom)
            c.drawRoundRect(rf, dp(0.8f), dp(0.8f), p)
        }
        return left
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                tracking = false
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = e.rawY - downY
                val dx = e.rawX - downX
                if (!tracking && dy > slop && dy > abs(dx)) {
                    val kind = if (downX < width / 2f) 0 else 1
                    tracking = svc.beginPanel(kind)
                }
                if (tracking) svc.dragPanel(dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle()
                vt = null
                if (tracking) svc.endPanel(vy)
                tracking = false
                return true
            }
        }
        return true
    }
}
