package com.classic.launcher

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Draws the custom status bar over the real one, catches swipe-downs on it, shows
 * the notification center / control center, and plays the blue glow when an app is closed.
 *
 * Everything is drawn in TYPE_ACCESSIBILITY_OVERLAY windows. These are overlays on top
 * of the system: they cover the real status bar, they do not remove it.
 */
class StatusBarService : AccessibilityService() {

    companion object {
        @Volatile var instance: StatusBarService? = null
        private const val LAUNCHER_ACTIVITY = "com.classic.launcher.MainActivity"
        private const val SYSTEM_UI = "com.android.systemui"
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bar: StatusBarView? = null
    private var sbH = 0
    private var screenW = 0
    private var screenH = 0
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // panel
    private var panelRoot: FrameLayout? = null
    private var panelContent: View? = null
    private var panelH = 1
    private var panelProgress = 0f
    private var panelAnim: ValueAnimator? = null
    private var notifPanel: NotificationPanel? = null
    private var ctrlPanel: ControlPanel? = null

    // app-close detection
    private var lastPkg: String? = null

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { closePanelNow() }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        measureScreen()
        addBar()
        try { registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) } catch (e: Exception) { }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == SYSTEM_UI) return

        if (pkg == packageName) {
            // the launcher's home screen came to the front -> an app was closed
            if (e.className?.toString() == LAUNCHER_ACTIVITY) {
                val prev = lastPkg
                lastPkg = packageName
                if (prev != null && prev != packageName) {
                    closePanelNow()
                    bar?.playGlow()
                }
            }
        } else {
            lastPkg = pkg
            // another app or the home button took over: never leave a panel hanging
            if (panelRoot != null) closePanelNow()
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::wm.isInitialized) return
        closePanelNow()
        measureScreen()
        val b = bar ?: return
        try {
            val lp = b.layoutParams as WindowManager.LayoutParams
            lp.height = sbH
            wm.updateViewLayout(b, lp)
        } catch (ex: Exception) { }
        // landscape apps / games: stay out of the way
        b.visibility = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        closePanelNow()
        try { unregisterReceiver(screenOff) } catch (e: Exception) { }
        bar?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bar = null
        if (instance === this) instance = null
    }

    // ------------------------------------------------------------------ geometry + windows

    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        sbH = if (id > 0) resources.getDimensionPixelSize(id)
        else (24 * resources.displayMetrics.density).toInt()
    }

    private fun overlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun addBar() {
        val v = StatusBarView(this, this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, sbH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags(), PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.title = "ClassicStatusBar"
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            bar = v
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                v.visibility = View.GONE
            }
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ helpers for the panels

    /** Start an activity from the panel and close the panel. Returns false if nothing could handle it. */
    fun startSafe(i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            closePanel()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The little grab bar at the bottom of a panel: swipe up (or tap) to close. */
    fun makeHandle(): View {
        val d = resources.displayMetrics.density
        val box = FrameLayout(this)
        val pill = View(this)
        pill.background = GradientDrawable().apply {
            setColor(0x66FFFFFF)
            cornerRadius = 4 * d
        }
        box.addView(pill, FrameLayout.LayoutParams((40 * d).toInt(), (5 * d).toInt(), Gravity.CENTER))
        box.setOnTouchListener(closeTouch(true))
        return box
    }

    /** Drag up to close the panel. If tapCloses, a tap closes it too. */
    fun closeTouch(tapCloses: Boolean): View.OnTouchListener = object : View.OnTouchListener {
        var y0 = 0f
        var dragging = false
        var vt: VelocityTracker? = null

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    y0 = e.rawY
                    dragging = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain()
                    vt?.addMovement(e)
                    panelAnim?.cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e)
                    val dy = e.rawY - y0
                    if (!dragging && dy < -slop) dragging = true
                    if (dragging) setProgress((1f + dy / panelH).coerceIn(0f, 1f))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle()
                    vt = null
                    if (dragging) {
                        animateTo(if (panelProgress < 0.65f || vy < -900f) 0f else 1f)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP && tapCloses && abs(e.rawY - y0) < slop) {
                        closePanel()
                    } else if (panelProgress < 1f && panelRoot != null) {
                        animateTo(1f)
                    }
                }
            }
            return true
        }
    }

    // ------------------------------------------------------------------ panel life

    /** Called by the status bar when a swipe-down starts. kind: 0 = notifications, 1 = control center. */
    fun beginPanel(kind: Int): Boolean {
        if (panelRoot != null) return false
        val avail = screenH - sbH
        if (avail <= 0) return false

        val content: View = if (kind == 0) {
            val np = NotificationPanel(this)
            notifPanel = np
            np.view
        } else {
            val cp = ControlPanel(this, screenW)
            ctrlPanel = cp
            cp.view
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(avail, if (kind == 0) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST)
        )
        panelH = content.measuredHeight.coerceAtLeast(1)

        val root = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(screenW, panelH, Gravity.TOP))
        content.translationY = -panelH.toFloat()
        root.setOnTouchListener(closeTouch(true))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, avail,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags(), PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.y = sbH
        lp.title = "ClassicPanel"
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return try {
            wm.addView(root, lp)
            panelRoot = root
            panelContent = content
            panelProgress = 0f
            true
        } catch (e: Exception) {
            notifPanel = null
            ctrlPanel?.release()
            ctrlPanel = null
            false
        }
    }

    /** The finger moved down by dy pixels from where it started. */
    fun dragPanel(dy: Float) {
        if (panelRoot == null) return
        setProgress((dy / panelH).coerceIn(0f, 1f))
    }

    /** Finger lifted. */
    fun endPanel(vy: Float) {
        if (panelRoot == null) return
        val open = panelProgress > 0.3f || vy > 900f
        animateTo(if (open && vy > -900f) 1f else 0f)
    }

    private fun setProgress(p: Float) {
        val c = panelContent ?: return
        val r = panelRoot ?: return
        panelProgress = p
        c.translationY = -panelH * (1f - p)
        r.setBackgroundColor(Color.argb((p * 150f).toInt(), 0, 0, 0))
    }

    private fun animateTo(target: Float) {
        if (panelRoot == null) return
        panelAnim?.cancel()
        val a = ValueAnimator.ofFloat(panelProgress, target)
        a.duration = if (target >= 1f) 330L else 240L
        a.interpolator = if (target >= 1f) DecelerateInterpolator(2.2f) else AccelerateInterpolator(1.4f)
        a.addUpdateListener { setProgress(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled && target <= 0f) removePanel()
            }
        })
        panelAnim = a
        a.start()
    }

    /** Animated close. */
    fun closePanel() {
        if (panelRoot != null) animateTo(0f)
    }

    private fun closePanelNow() {
        panelAnim?.cancel()
        removePanel()
    }

    private fun removePanel() {
        panelRoot?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        panelRoot = null
        panelContent = null
        panelProgress = 0f
        NotifService.listener = null
        notifPanel = null
        ctrlPanel?.release()
        ctrlPanel = null
    }
}
