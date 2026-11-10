package com.classic.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Hand-drawn icons (everything is drawn in a 24 x 24 box, then scaled). */
object Icons {
    const val AIRPLANE = 0
    const val DATA = 1
    const val WIFI = 2
    const val BT = 3
    const val ROTATE = 4
    const val MIRROR = 5
    const val MOON = 6
    const val FLASH = 7
    const val TIMER = 8
    const val CALC = 9
    const val CAMERA = 10
    const val SUN = 11
    const val SPEAKER = 12
    const val PREV = 13
    const val PLAY = 14
    const val NEXT = 15

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    fun draw(c: Canvas, kind: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val u = size / 24f
        fill.color = color
        line.color = color
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(u, u)
        when (kind) {
            AIRPLANE -> {
                path.reset()
                path.moveTo(12f, 2f); path.lineTo(14f, 8.5f); path.lineTo(22f, 13f)
                path.lineTo(22f, 15.2f); path.lineTo(14f, 12.8f); path.lineTo(13.6f, 18f)
                path.lineTo(16f, 19.8f); path.lineTo(16f, 21.5f); path.lineTo(12f, 20.4f)
                path.lineTo(8f, 21.5f); path.lineTo(8f, 19.8f); path.lineTo(10.4f, 18f)
                path.lineTo(10f, 12.8f); path.lineTo(2f, 15.2f); path.lineTo(2f, 13f)
                path.lineTo(10f, 8.5f); path.close()
                c.drawPath(path, fill)
            }
            DATA -> {
                c.drawCircle(12f, 10f, 2f, fill)
                c.drawLine(12f, 12f, 12f, 21f, line)
                rect.set(7f, 5f, 17f, 15f)
                c.drawArc(rect, -40f, 80f, false, line)
                c.drawArc(rect, 140f, 80f, false, line)
                rect.set(3.5f, 1.5f, 20.5f, 18.5f)
                c.drawArc(rect, -40f, 80f, false, line)
                c.drawArc(rect, 140f, 80f, false, line)
            }
            WIFI -> {
                for (r in floatArrayOf(5f, 9f, 13f)) {
                    rect.set(12f - r, 19f - r, 12f + r, 19f + r)
                    c.drawArc(rect, -135f, 90f, false, line)
                }
                c.drawCircle(12f, 19.5f, 1.6f, fill)
            }
            BT -> {
                path.reset()
                path.moveTo(7f, 7.5f); path.lineTo(17f, 16.5f); path.lineTo(12f, 21f)
                path.lineTo(12f, 3f); path.lineTo(17f, 7.5f); path.lineTo(7f, 16.5f)
                c.drawPath(path, line)
            }
            ROTATE -> {
                rect.set(4f, 4f, 20f, 20f)
                c.drawArc(rect, -50f, 280f, false, line)
                path.reset()
                path.moveTo(15f, 1.5f); path.lineTo(15f, 8f); path.lineTo(21f, 5f); path.close()
                c.drawPath(path, fill)
                rect.set(9.2f, 11.4f, 14.8f, 16.4f)
                c.drawRoundRect(rect, 1f, 1f, fill)
                rect.set(10.2f, 8.4f, 13.8f, 13.4f)
                c.drawArc(rect, 180f, 180f, false, line)
            }
            MIRROR -> {
                rect.set(3f, 5f, 16f, 15f)
                c.drawRoundRect(rect, 2f, 2f, line)
                rect.set(9f, 9f, 21f, 19f)
                c.drawRoundRect(rect, 2f, 2f, line)
            }
            MOON -> {
                val a = Path()
                a.addCircle(12f, 12f, 8.5f, Path.Direction.CW)
                val b = Path()
                b.addCircle(17f, 8f, 7f, Path.Direction.CW)
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, fill)
            }
            FLASH -> {
                rect.set(7f, 2f, 17f, 7f)
                c.drawRoundRect(rect, 1.5f, 1.5f, line)
                path.reset()
                path.moveTo(8.5f, 8.5f); path.lineTo(15.5f, 8.5f)
                path.lineTo(14.5f, 22f); path.lineTo(9.5f, 22f); path.close()
                c.drawPath(path, line)
                c.drawCircle(12f, 14f, 1.4f, fill)
            }
            TIMER -> {
                c.drawCircle(12f, 13.5f, 8f, line)
                c.drawLine(12f, 13.5f, 15.5f, 10f, line)
                c.drawLine(10f, 2.5f, 14f, 2.5f, line)
                c.drawLine(12f, 2.5f, 12f, 5f, line)
            }
            CALC -> {
                rect.set(5f, 2.5f, 19f, 21.5f)
                c.drawRoundRect(rect, 2.5f, 2.5f, line)
                rect.set(7.5f, 5f, 16.5f, 8.5f)
                c.drawRoundRect(rect, 0.8f, 0.8f, fill)
                for (yy in floatArrayOf(12f, 15.5f, 19f)) {
                    for (xx in floatArrayOf(8.5f, 12f, 15.5f)) c.drawCircle(xx, yy, 1.1f, fill)
                }
            }
            CAMERA -> {
                rect.set(3f, 7f, 21f, 20f)
                c.drawRoundRect(rect, 3f, 3f, line)
                c.drawCircle(12f, 13.5f, 3.6f, line)
                rect.set(8.5f, 3.8f, 15.5f, 7.2f)
                c.drawRoundRect(rect, 1f, 1f, line)
            }
            SUN -> {
                c.drawCircle(12f, 12f, 3.8f, fill)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = 12f + (6.6 * cos(a)).toFloat()
                    val y1 = 12f + (6.6 * sin(a)).toFloat()
                    val x2 = 12f + (9.2 * cos(a)).toFloat()
                    val y2 = 12f + (9.2 * sin(a)).toFloat()
                    c.drawLine(x1, y1, x2, y2, line)
                }
            }
            SPEAKER -> {
                path.reset()
                path.moveTo(3f, 9.5f); path.lineTo(7f, 9.5f); path.lineTo(12f, 5f)
                path.lineTo(12f, 19f); path.lineTo(7f, 14.5f); path.lineTo(3f, 14.5f); path.close()
                c.drawPath(path, fill)
                rect.set(8f, 8f, 16f, 16f)
                c.drawArc(rect, -45f, 90f, false, line)
                rect.set(5f, 5f, 19f, 19f)
                c.drawArc(rect, -45f, 90f, false, line)
            }
            PREV -> {
                path.reset()
                path.moveTo(12f, 12f); path.lineTo(21f, 5.5f); path.lineTo(21f, 18.5f); path.close()
                path.moveTo(3f, 12f); path.lineTo(12f, 5.5f); path.lineTo(12f, 18.5f); path.close()
                c.drawPath(path, fill)
            }
            PLAY -> {
                path.reset()
                path.moveTo(7f, 4f); path.lineTo(7f, 20f); path.lineTo(20f, 12f); path.close()
                c.drawPath(path, fill)
            }
            NEXT -> {
                path.reset()
                path.moveTo(12f, 12f); path.lineTo(3f, 5.5f); path.lineTo(3f, 18.5f); path.close()
                path.moveTo(21f, 12f); path.lineTo(12f, 5.5f); path.lineTo(12f, 18.5f); path.close()
                c.drawPath(path, fill)
            }
        }
        c.restore()
    }
}

/**
 * A control-center tile / round button.
 * `round` = circle (connectivity buttons), otherwise a rounded square.
 * `activeColor` = fill when switched on (white by default, with a dark icon).
 */
class TileView(
    c: Context,
    private val kind: Int,
    private val round: Boolean = false,
    private val activeColor: Int = Color.WHITE,
    private val plain: Boolean = false     // no background, icon only (media buttons)
) : View(c) {

    var active = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    init {
        isClickable = true
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (!plain) {
            p.style = Paint.Style.FILL
            p.color = if (active) activeColor else 0x4DFFFFFF
            rf.set(0f, 0f, w, h)
            if (round) cv.drawOval(rf, p) else cv.drawRoundRect(rf, w * 0.28f, w * 0.28f, p)
        }
        val iconColor = when {
            plain -> Color.WHITE
            active && activeColor == Color.WHITE -> 0xFF1C1C1E.toInt()
            else -> Color.WHITE
        }
        Icons.draw(cv, kind, w / 2f, h / 2f, min(w, h) * 0.5f, iconColor)
    }
}

/** Tall rounded slider (brightness / volume), fills from the bottom like iOS. */
class VSlider(c: Context, private val kind: Int) : View(c) {

    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var onChange: ((Float) -> Unit)? = null
    /** return false to refuse the touch (e.g. permission missing) */
    var onStart: (() -> Boolean)? = null

    private var allowed = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val clip = Path()

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w * 0.34f
        rf.set(0f, 0f, w, h)
        p.style = Paint.Style.FILL
        p.color = 0x4DFFFFFF
        cv.drawRoundRect(rf, r, r, p)

        cv.save()
        clip.reset()
        clip.addRoundRect(rf, r, r, Path.Direction.CW)
        cv.clipPath(clip)
        p.color = Color.WHITE
        cv.drawRect(0f, h * (1f - value), w, h, p)
        cv.restore()

        val iconColor = if (value > 0.14f) 0xFF6E6E73.toInt() else Color.WHITE
        Icons.draw(cv, kind, w / 2f, h - w * 0.5f - h * 0.03f, w * 0.44f, iconColor)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                allowed = onStart?.invoke() ?: true
                if (!allowed) return true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (allowed) update(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun update(y: Float) {
        if (height <= 0) return
        value = 1f - y / height
        onChange?.invoke(value)
    }
}
