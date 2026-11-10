package com.classic.launcher

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MAX_DOCK = 4
private const val REQ_RINGTONE = 11
private const val REQ_FILE = 12
private const val SRC_DRAWER = 0
private const val SRC_HOME = 1
private const val SRC_DOCK = 2

class AppItem(
    val key: String,
    val pkg: String,
    val label: String,
    val intent: Intent,
    val icon: Drawable
)

// One thing placed on a home page: an app (kind 0) or a folder (kind 1)
class Placement(var page: Int, var col: Int, var row: Int, var kind: Int, var ref: String)

class Folder(val id: String, var name: String, val keys: MutableList<String>)

class MainActivity : Activity() {

    private class DragState(
        val kind: Int,
        val ref: String,
        val src: Int,
        val srcView: View?,
        val placement: Placement?,
        val ghost: View,
        val ghostSize: Int
    )

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // data
    private var all = listOf<AppItem>()
    private var byKey = mapOf<String, AppItem>()
    private val shown = ArrayList<AppItem>()
    private val appAdapter = AppAdapter()
    private var dockKeys = mutableListOf<String>()
    private var hiddenSet = setOf<String>()
    private var hiddenMode = false
    private val placements = ArrayList<Placement>()
    private val folders = HashMap<String, Folder>()
    private val pageIds = ArrayList<Int>()
    private val pageViews = HashMap<Int, FrameLayout>()
    private val layers = HashMap<Int, FrameLayout>()

    // views
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var dock: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var rv: RecyclerView
    private lateinit var search: EditText
    private lateinit var cmdHint: TextView
    private lateinit var dragLayer: FrameLayout
    private lateinit var cellHi: View
    private lateinit var removeBar: TextView
    private lateinit var dayTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var clockCol: LinearLayout

    // state
    private var open = false
    private var imeUp = false
    private var topInset = 0
    private var bottomInset = 0
    private var drawerDragging = false
    private var resumeAfter = false
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var vt: VelocityTracker? = null
    private var springAnim: SpringAnimation? = null
    private var dockAnim: ValueAnimator? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // drag & drop
    private var drag: DragState? = null
    private var dragMoved = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var edgeDir = 0
    private val edgeRun = object : Runnable {
        override fun run() {
            if (edgeDir == 0 || drag == null || open) return
            val cur = pager.currentItem
            val n = cur + edgeDir
            if (n >= 0 && n < pageIds.size) {
                pager.setCurrentItem(n, true)
                handler.postDelayed(this, 800)
            } else {
                // at the last page: start a new one (unless this one is an empty spare)
                val id = pageIds.getOrNull(cur) ?: return
                if (id == 0 || placements.any { it.page == id }) {
                    createPage(edgeDir < 0)
                    handler.postDelayed(this, 800)
                }
            }
        }
    }

    // timer dialog
    private var pendingSound: String? = null
    private var soundLabel: TextView? = null

    // India time
    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private val fDay = SimpleDateFormat("EEEE", Locale.ENGLISH).apply { timeZone = tz }
    private val fDate = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).apply { timeZone = tz }
    private val fTime = SimpleDateFormat("hh:mm a", Locale.ENGLISH).apply { timeZone = tz }

    private val commands = linkedMapOf(
        "admin" to "Admin panel (choose which apps show)",
        "hide" to "Show hidden apps",
        "customize" to "Customize (size, columns, colors)",
        "help" to "List of all commands"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val closedY: Float
        get() = resources.displayMetrics.heightPixels.toFloat() + dp(60)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        hiddenSet = prefs.hidden

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        root = FrameLayout(this)
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        homeLayer = FrameLayout(this)

        buildPages()
        buildPager()
        buildDots()
        buildDock()
        buildDrawer()
        buildOverlay()

        homeLayer.addView(pager, FrameLayout.LayoutParams(MP, MP))
        homeLayer.addView(dots, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        homeLayer.addView(dock, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(homeLayer, FrameLayout.LayoutParams(MP, MP))
        root.addView(drawer, FrameLayout.LayoutParams(MP, MP))
        root.addView(dragLayer, FrameLayout.LayoutParams(MP, MP))
        setContentView(root)

        drawer.translationY = closedY
        applyInsets(dp(28), 0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val sb = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            applyInsets(sb.top, max(sb.bottom, ime.bottom))
            ins
        }
        ViewCompat.requestApplyInsets(root)

        loadHome()
        refreshPager(0)
        applySettings()
        updateClock()
        updateTimer()
        loadApps()

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(pkgReceiver) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
    }

    override fun onResume() {
        super.onResume()
        TimerReceiver.stop()
        hideKeyboard()
        restartTick()
    }

    override fun onPause() {
        super.onPause()
        // Gaming / battery: do absolutely nothing in the background.
        handler.removeCallbacks(tick)
        handler.removeCallbacks(edgeRun)
        if (drag != null) endDrag(lastRawX, lastRawY, true)
        if (springAnim?.isRunning == true) { springAnim?.cancel(); finishSettle() }
        boostFps(false)
        hideKeyboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !open) hideKeyboard()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (open) setDrawer(false) else if (pager.currentItem != 0) pager.setCurrentItem(0, true)
    }

    @Deprecated("launcher: never exit")
    override fun onBackPressed() {
        if (open) setDrawer(false) else if (pager.currentItem != 0) pager.setCurrentItem(0, true)
    }

    private val reload = Runnable { loadApps() }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 700)
        }
    }

    // ------------------------------------------------------------------ settings

    private fun accentTint(): Int = (prefs.accent and 0x00FFFFFF) or 0x66000000

    private fun applySettings() {
        (rv.layoutManager as GridLayoutManager).spanCount = prefs.cols
        appAdapter.notifyDataSetChanged()
        timerTv.background = glass(22, accentTint())
        cellHi.background = glass(16, accentTint())
        window.setBackgroundDrawable(ColorDrawable(if (prefs.blackBg) Color.BLACK else Color.TRANSPARENT))
        normalizePlacements()
        renderHome()
    }

    // ------------------------------------------------------------------ UI building

    private fun glass(r: Int, color: Int = 0x40FFFFFF): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun buildPages() {
        dayTv = DayText(this)
        dayTv.gravity = Gravity.CENTER
        dayTv.setTextColor(Color.WHITE)
        dayTv.maxLines = 1
        dayTv.typeface = try {
            Typeface.createFromAsset(assets, "anurati.otf")
        } catch (e: Exception) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        dayTv.letterSpacing = 0.16f
        dayTv.setPadding(dp(18), 0, dp(18), 0)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(dayTv, 16, 44, 1, TypedValue.COMPLEX_UNIT_SP)

        dateTv = TextView(this)
        dateTv.gravity = Gravity.CENTER
        dateTv.setTextColor(Color.WHITE)
        dateTv.textSize = 19f
        dateTv.letterSpacing = 0.14f
        dateTv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        timeTv = TextView(this)
        timeTv.gravity = Gravity.CENTER
        timeTv.setTextColor(0xF2FFFFFF.toInt())
        timeTv.textSize = 17f
        timeTv.letterSpacing = 0.12f
        timeTv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        timerTv = TextView(this)
        timerTv.gravity = Gravity.CENTER
        timerTv.setTextColor(Color.WHITE)
        timerTv.textSize = 15f
        timerTv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        timerTv.setPadding(dp(24), dp(12), dp(24), dp(12))
        timerTv.setOnClickListener { showTimerDialog() }
        press(timerTv)

        clockCol = LinearLayout(this)
        clockCol.orientation = LinearLayout.VERTICAL
        clockCol.gravity = Gravity.CENTER
        clockCol.setPadding(0, 0, 0, dp(30))
        clockCol.addView(dayTv, LinearLayout.LayoutParams(MP, dp(64)))
        clockCol.addView(dateTv, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(4) })
        clockCol.addView(timeTv, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(4) })
        clockCol.addView(timerTv, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(26) })
    }

    // Page 0 = the clock page (never deleted). Other pages only hold icons.
    private fun pageView(id: Int): FrameLayout {
        pageViews[id]?.let { return it }
        val page = FrameLayout(this)
        page.layoutParams = RecyclerView.LayoutParams(MP, MP)
        if (id == 0) page.addView(clockCol, FrameLayout.LayoutParams(MP, MP))
        val layer = FrameLayout(this)
        layer.addOnLayoutChangeListener { _, l, t, r, bt, ol, ot, orr, ob ->
            if (r - l != orr - ol || bt - t != ob - ot) {
                handler.removeCallbacks(renderRun)
                handler.post(renderRun)
            }
        }
        layers[id] = layer
        page.addView(layer, FrameLayout.LayoutParams(MP, MP))
        pageViews[id] = page
        return page
    }

    private fun refreshPager(selectId: Int?) {
        pager.adapter = PageAdapter()
        val idx = if (selectId != null) max(0, pageIds.indexOf(selectId)) else 0
        pager.setCurrentItem(idx, false)
        rebuildDots()
        updateDots(idx)
        renderHome()
    }

    private fun savePages() {
        prefs.pages = pageIds.joinToString("|")
    }

    private fun createPage(atLeft: Boolean) {
        val id = (pageIds.maxOrNull() ?: 0) + 1
        if (atLeft) pageIds.add(0, id) else pageIds.add(id)
        savePages()
        refreshPager(id)
        root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // delete pages (except the clock page) that have no icons left
    private fun cleanupPages() {
        if (drag != null) return
        val keep = pageIds.filter { it == 0 || placements.any { p -> p.page == it } }
        if (keep.size == pageIds.size) return
        val oldIdx = pager.currentItem
        val curId = pageIds.getOrNull(oldIdx) ?: 0
        val removed = pageIds.filter { !keep.contains(it) }
        pageIds.clear()
        pageIds.addAll(keep)
        for (r in removed) { pageViews.remove(r); layers.remove(r) }
        savePages()
        val target = if (pageIds.contains(curId)) curId else pageIds[min(oldIdx, pageIds.size - 1)]
        refreshPager(target)
    }

    private val renderRun = Runnable { renderHome() }

    private fun buildPager() {
        pager = ViewPager2(this)
        pager.offscreenPageLimit = 2
        pager.setPageTransformer { page, pos ->
            val a = min(1f, abs(pos))
            page.alpha = 1f - a * 0.65f
            val s = 1f - a * 0.08f
            page.scaleX = s
            page.scaleY = s
        }
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                hideKeyboard()
            }
        })
    }

    private fun buildDots() {
        dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
    }

    private fun rebuildDots() {
        dots.removeAllViews()
        for (i in pageIds.indices) {
            val d = View(this)
            d.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            d.alpha = if (i == 0) 1f else 0.35f
            val lp = LinearLayout.LayoutParams(dp(7), dp(7))
            lp.marginStart = dp(4)
            lp.marginEnd = dp(4)
            dots.addView(d, lp)
        }
        dots.visibility = if (pageIds.size <= 1 || imeUp) View.INVISIBLE else View.VISIBLE
    }

    private fun updateDots(p: Int) {
        for (i in 0 until dots.childCount) {
            val on = i == p
            dots.getChildAt(i).animate().alpha(if (on) 1f else 0.35f)
                .scaleX(if (on) 1.3f else 1f).scaleY(if (on) 1.3f else 1f).setDuration(180).start()
        }
    }

    private fun buildDock() {
        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        dock.setPadding(dp(10), dp(10), dp(10), dp(10))
        dock.background = glass(30)
    }

    private fun buildDrawer() {
        rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, prefs.cols)
        rv.adapter = appAdapter
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(20)
        rv.itemAnimator = null
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        cmdHint = TextView(this)
        cmdHint.setTextColor(0xCCFFFFFF.toInt())
        cmdHint.textSize = 13f
        cmdHint.gravity = Gravity.CENTER
        cmdHint.visibility = View.GONE

        search = EditText(this)
        search.hint = "Search apps  (try: help)"
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 15f
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.background = glass(25)
        search.setPadding(dp(18), 0, dp(18), 0)
        search.doAfterTextChanged {
            val q = it?.toString() ?: ""
            refilter(q)
            updateHint(q)
        }
        search.setOnEditorActionListener { _, _, _ ->
            onSearchEnter()
            true
        }

        val bg = GradientDrawable()
        bg.setColor(0xF2101010.toInt())
        val r = dp(28).toFloat()
        bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

        drawer = LinearLayout(this)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.visibility = View.INVISIBLE
        drawer.background = bg
        drawer.addView(rv, LinearLayout.LayoutParams(MP, 0, 1f))
        drawer.addView(cmdHint, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(6) })
        drawer.addView(search, LinearLayout.LayoutParams(MP, dp(50)).apply { topMargin = dp(8) })
    }

    // top-most layer used while dragging an icon
    private fun buildOverlay() {
        dragLayer = FrameLayout(this)
        cellHi = View(this)
        cellHi.visibility = View.GONE
        dragLayer.addView(cellHi, FrameLayout.LayoutParams(dp(60), dp(60)))
        removeBar = TextView(this)
        removeBar.text = "\u2715   Remove"
        removeBar.setTextColor(Color.WHITE)
        removeBar.textSize = 15f
        removeBar.gravity = Gravity.CENTER
        removeBar.setBackgroundColor(0x66000000)
        removeBar.visibility = View.GONE
        dragLayer.addView(removeBar, FrameLayout.LayoutParams(MP, dp(64), Gravity.TOP))
    }

    // `bottom` = nav bar height, or keyboard height when it is open.
    private fun applyInsets(top: Int, bottom: Int) {
        topInset = top
        bottomInset = bottom
        imeUp = bottom > dp(120)

        val pl = pager.layoutParams as FrameLayout.LayoutParams
        pl.topMargin = top
        pl.bottomMargin = if (imeUp) bottom + dp(8) else bottom + dp(138)
        pager.layoutParams = pl

        val dl = dock.layoutParams as FrameLayout.LayoutParams
        dl.bottomMargin = bottom + dp(14)
        dock.layoutParams = dl

        val ol = dots.layoutParams as FrameLayout.LayoutParams
        ol.bottomMargin = bottom + dp(112)
        dots.layoutParams = ol
        dots.visibility = if (pageIds.size <= 1 || imeUp) View.INVISIBLE else View.VISIBLE
        updateDockVisibility()

        val rl = drawer.layoutParams as FrameLayout.LayoutParams
        rl.topMargin = top + dp(10)
        drawer.layoutParams = rl
        drawer.setPadding(dp(12), dp(14), dp(12), bottom + dp(8))

        val bl = removeBar.layoutParams as FrameLayout.LayoutParams
        bl.height = top + dp(64)
        removeBar.layoutParams = bl
        removeBar.setPadding(0, top, 0, 0)
    }

    private fun updateDockVisibility() {
        dock.visibility = if (dockKeys.isEmpty() || imeUp) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ clock + timer

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updateTimer()
            val now = System.currentTimeMillis()
            val running = prefs.timerEnd > now
            val next = if (running) 1000L - now % 1000L + 5L else 60_000L - now % 60_000L + 50L
            handler.postDelayed(this, next)
        }
    }

    private fun restartTick() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    private fun updateClock() {
        val d = Date()
        dayTv.text = fDay.format(d).uppercase()
        dateTv.text = fDate.format(d).uppercase()
        timeTv.text = "-" + fTime.format(d).uppercase() + "-"
    }

    private fun fmt(ms: Long): String {
        val t = (ms + 999) / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun updateTimer() {
        val now = System.currentTimeMillis()
        val end = prefs.timerEnd
        val label = prefs.timerLabel
        timerTv.text = when {
            end > now -> label + "  \u2022  " + fmt(end - now)
            end > 0L || prefs.timerDone -> label + "  \u2022  time's up"
            else -> "+  Set a timer"
        }
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 1,
        Intent(this, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_FIRE)
            .putExtra("label", prefs.timerLabel)
            .putExtra("sound", prefs.timerSound),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun startTimer(label: String, mins: Int, sound: String?) {
        val end = System.currentTimeMillis() + mins * 60_000L
        prefs.timerLabel = label
        prefs.timerSound = sound
        prefs.timerEnd = end
        prefs.timerDone = false
        val show = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(end, show), alarmIntent())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        restartTick()
    }

    private fun stopTimer() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent())
        prefs.timerEnd = 0L
        prefs.timerDone = false
        TimerReceiver.stop()
        restartTick()
    }

    private fun soundName(s: String?): String {
        if (s == null) return "Default alarm"
        return try {
            val uri = Uri.parse(s)
            var name: String? = null
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            name ?: (RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Custom sound")
        } catch (e: Exception) {
            "Custom sound"
        }
    }

    private fun showTimerDialog() {
        TimerReceiver.stop()
        val now = System.currentTimeMillis()
        if (prefs.timerEnd in 1..now) prefs.timerEnd = 0L
        prefs.timerDone = false
        val running = prefs.timerEnd > now
        pendingSound = prefs.timerSound

        val pad = dp(22)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, dp(8), pad, 0)

        val name = EditText(this)
        name.hint = "Name (Gym, Study, Cook...)"
        name.isSingleLine = true
        if (prefs.timerLabel != "Timer") name.setText(prefs.timerLabel)
        col.addView(name, LinearLayout.LayoutParams(MP, WC))

        val hours = EditText(this)
        hours.hint = "Hours"
        hours.inputType = InputType.TYPE_CLASS_NUMBER
        hours.filters = arrayOf(InputFilter.LengthFilter(2))
        val mins = EditText(this)
        mins.hint = "Minutes"
        mins.inputType = InputType.TYPE_CLASS_NUMBER
        mins.filters = arrayOf(InputFilter.LengthFilter(3))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(hours, LinearLayout.LayoutParams(0, WC, 1f).apply { marginEnd = dp(8) })
        row.addView(mins, LinearLayout.LayoutParams(0, WC, 1f))
        col.addView(row, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(8) })

        val sl = TextView(this)
        sl.text = "Sound: " + soundName(pendingSound)
        sl.setTextColor(Color.WHITE)
        sl.setPadding(0, dp(14), 0, dp(4))
        soundLabel = sl
        col.addView(sl, LinearLayout.LayoutParams(MP, WC))

        val b1 = Button(this)
        b1.text = "Ringtones"
        b1.setOnClickListener {
            val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Timer sound")
            pendingSound?.let { i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) }
            try { startActivityForResult(i, REQ_RINGTONE) } catch (e: Exception) { }
        }
        val b2 = Button(this)
        b2.text = "My audio file"
        b2.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "audio/*"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try { startActivityForResult(i, REQ_FILE) } catch (e: Exception) { }
        }
        val brow = LinearLayout(this)
        brow.orientation = LinearLayout.HORIZONTAL
        brow.addView(b1, LinearLayout.LayoutParams(0, WC, 1f))
        brow.addView(b2, LinearLayout.LayoutParams(0, WC, 1f))
        col.addView(brow, LinearLayout.LayoutParams(MP, WC))

        val builder = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (running) "Timer running" else "Set timer")
            .setView(col)
            .setPositiveButton(if (running) "Restart" else "Start", null)
            .setNegativeButton("Close", null)
        if (running) builder.setNeutralButton("Stop timer", null)
        val d = builder.create()
        d.setOnDismissListener {
            soundLabel = null
            hideKeyboard()
            updateTimer()
        }
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val total = (hours.text.toString().toIntOrNull() ?: 0) * 60 +
                (mins.text.toString().toIntOrNull() ?: 0)
            if (total <= 0) {
                Toast.makeText(this, "Enter hours or minutes", Toast.LENGTH_SHORT).show()
            } else {
                val label = name.text.toString().trim().ifEmpty { "Timer" }
                startTimer(label, total, pendingSound)
                d.dismiss()
            }
        }
        if (running) d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            stopTimer()
            d.dismiss()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            REQ_RINGTONE -> {
                val u = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                pendingSound = u?.toString()
            }
            REQ_FILE -> {
                val u = data.data
                if (u != null) {
                    try {
                        contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) { }
                    pendingSound = u.toString()
                }
            }
        }
        soundLabel?.text = "Sound: " + soundName(pendingSound)
    }

    // ------------------------------------------------------------------ apps + dock

    private fun loadApps() = io.execute {
        try {
            val pm = packageManager
            val s = dp(60)
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    val cn = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    AppItem(
                        cn.flattenToString(), cn.packageName, it.loadLabel(pm).toString(),
                        Intent(main).setComponent(cn), bmp(it.loadIcon(pm), s)
                    )
                }.sortedBy { it.label.lowercase() }

            val defaults = listOf(
                Intent(Intent.ACTION_DIAL),
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://")),
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            ).mapNotNull { i ->
                val p = pm.queryIntentActivities(i, 0).firstOrNull()?.activityInfo?.packageName
                list.firstOrNull { it.pkg == p }?.key
            }.distinct().take(MAX_DOCK)

            runOnUiThread {
                all = list
                byKey = list.associateBy { it.key }
                refilter(search.text.toString())
                if (prefs.dock == null) prefs.dock = defaults
                dockKeys = (prefs.dock ?: defaults).filter { byKey.containsKey(it) }
                    .take(MAX_DOCK).toMutableList()
                fillDock(false)
                pruneHome()
                renderHome()
            }
        } catch (e: Exception) { }
    }

    private fun bmp(d: Drawable, s: Int): Drawable {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(b))
        return BitmapDrawable(resources, b)
    }

    private fun refilter(q: String) {
        shown.clear()
        shown.addAll(all.filter { (hiddenSet.contains(it.key) == hiddenMode) && it.label.contains(q, true) })
        appAdapter.notifyDataSetChanged()
    }

    private fun fillDock(animate: Boolean) {
        dockAnim?.cancel()
        val oldW = dock.width
        val items = dockKeys.mapNotNull { byKey[it] }
        dock.removeAllViews()
        for (a in items) {
            val iv = ImageView(this)
            iv.setImageDrawable(a.icon)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setPadding(dp(5), dp(5), dp(5), dp(5))
            press(iv)
            iv.setOnClickListener { launch(a, iv) }
            iv.setOnLongClickListener { startDrag(0, a.key, SRC_DOCK, iv, null); true }
            dock.addView(iv, LinearLayout.LayoutParams(dp(64), dp(58)))
        }
        updateDockVisibility()
        if (animate && oldW > 0 && items.isNotEmpty()) {
            dock.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val newW = dock.measuredWidth
            val lp = dock.layoutParams
            val va = ValueAnimator.ofInt(oldW, newW)
            va.duration = 300
            va.interpolator = DecelerateInterpolator(1.5f)
            va.addUpdateListener {
                lp.width = it.animatedValue as Int
                dock.layoutParams = lp
            }
            va.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    lp.width = WC
                    dock.layoutParams = lp
                }
            })
            dockAnim = va
            va.start()
        }
    }

    private fun toggleDock(a: AppItem) {
        if (dockKeys.contains(a.key)) {
            dockKeys.remove(a.key)
        } else {
            if (dockKeys.size >= MAX_DOCK) {
                Toast.makeText(this, "Dock is full (max $MAX_DOCK apps)", Toast.LENGTH_SHORT).show()
                return
            }
            dockKeys.add(a.key)
            Toast.makeText(this, "Added to dock", Toast.LENGTH_SHORT).show()
        }
        prefs.dock = dockKeys.toList()
        fillDock(true)
    }

    private fun showAppMenu(v: View, a: AppItem, fromHome: Boolean = false) {
        val inDock = dockKeys.contains(a.key)
        val pm = PopupMenu(this, v)
        pm.menu.add(0, 1, 0, if (inDock) "Remove from dock" else "Add to dock")
        pm.menu.add(0, 2, 1, "Hide app")
        pm.menu.add(0, 3, 2, "App info")
        pm.menu.add(0, 4, 3, "Uninstall")
        if (fromHome) pm.menu.add(0, 5, 4, "Remove from home")
        pm.setOnMenuItemClickListener { m ->
            try {
                when (m.itemId) {
                    1 -> toggleDock(a)
                    2 -> {
                        hiddenSet = hiddenSet + a.key
                        prefs.hidden = hiddenSet
                        refilter(search.text.toString())
                        Toast.makeText(this, "Hidden. Type \"hide\" in search to find it", Toast.LENGTH_SHORT).show()
                    }
                    3 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    4 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
                    5 -> { removeFromHome(a.key); saveHome(); renderHome(); cleanupPages() }
                }
            } catch (e: Exception) { }
            true
        }
        pm.show()
    }

    private fun launch(a: AppItem, v: View?) {
        hideKeyboard()
        try {
            val opts = if (v != null && v.width > 0)
                ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle() else null
            startActivity(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show()
            return
        }
        handler.postDelayed({ if (open) snapClosed() }, 450)
    }

    // iPhone-style press: shrink on touch, bounce back on release
    private fun press(v: View) {
        v.setOnTouchListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    x.animate().scaleX(.88f).scaleY(.88f).setDuration(90).setInterpolator(null).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    x.animate().scaleX(1f).scaleY(1f).setDuration(280)
                        .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    // ------------------------------------------------------------------ search commands

    private fun updateHint(q: String) {
        val t = q.trim().lowercase()
        val m = if (t.length >= 2) commands.entries.firstOrNull { it.key.startsWith(t) } else null
        val text = when {
            hiddenMode -> "Hidden apps - tap one to open"
            m != null -> "\u21B5  Enter: " + m.value
            else -> ""
        }
        cmdHint.text = text
        cmdHint.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun onSearchEnter() {
        val q = search.text.toString().trim().lowercase()
        when (q) {
            "admin" -> { search.setText(""); hideKeyboard(); showAdmin() }
            "customize" -> { search.setText(""); hideKeyboard(); showCustomize() }
            "help" -> { search.setText(""); hideKeyboard(); showHelp() }
            "hide" -> {
                hiddenMode = true
                search.setText("")
                updateHint("")
                hideKeyboard()
                if (shown.isEmpty()) Toast.makeText(this, "No hidden apps yet", Toast.LENGTH_SHORT).show()
            }
            else -> shown.firstOrNull()?.let { launch(it, null) }
        }
    }

    private fun darkDialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun showHelp() {
        val msg = "SEARCH COMMANDS (type, then press Enter)\n\n" +
            "admin  -  choose which apps show or hide\n" +
            "hide  -  open the list of hidden apps\n" +
            "customize  -  icon size, columns, colors\n" +
            "help  -  this list\n\n" +
            "GESTURES\n\n" +
            "Swipe up  -  app drawer\n" +
            "Swipe left / right  -  home pages\n" +
            "Hold an app, then drag  -  place it on home or in the dock\n" +
            "Drop an app on another app  -  make a folder\n" +
            "Hold an app and let go  -  menu (hide, info, uninstall)\n" +
            "Drag to the screen edge  -  new home page (empty pages delete themselves)\n" +
            "Drag to the top bar  -  remove from home or dock\n" +
            "Tap the timer  -  set a timer"
        darkDialog().setTitle("Help").setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun showAdmin() {
        val lv = ListView(this)
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = all.size
            override fun getItem(p: Int) = all[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val a = all[p]
                val row = LinearLayout(this@MainActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(16), dp(6), dp(16), dp(6))
                val iv = ImageView(this@MainActivity)
                iv.setImageDrawable(a.icon)
                row.addView(iv, LinearLayout.LayoutParams(dp(38), dp(38)))
                val tv = TextView(this@MainActivity)
                tv.text = a.label
                tv.setTextColor(Color.WHITE)
                tv.textSize = 15f
                tv.maxLines = 1
                tv.ellipsize = android.text.TextUtils.TruncateAt.END
                tv.setPadding(dp(14), 0, dp(8), 0)
                row.addView(tv, LinearLayout.LayoutParams(0, WC, 1f))
                val sw = Switch(this@MainActivity)
                sw.isChecked = !hiddenSet.contains(a.key)
                sw.setOnCheckedChangeListener { _, checked ->
                    hiddenSet = if (checked) hiddenSet - a.key else hiddenSet + a.key
                    prefs.hidden = hiddenSet
                }
                row.addView(sw, LinearLayout.LayoutParams(WC, WC))
                return row
            }
        }
        val d = darkDialog().setTitle("Admin panel  (ON = shown)").setView(lv)
            .setPositiveButton("Done", null).create()
        d.setOnDismissListener { refilter(search.text.toString()) }
        d.show()
    }

    private fun showCustomize() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(22), dp(6), dp(22), dp(10))
        sv.addView(col)

        fun label(t: String): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            tv.setPadding(0, dp(14), 0, dp(4))
            return tv
        }

        val sizeTv = label("Icon size: " + prefs.iconDp)
        val sizeSb = SeekBar(this)
        sizeSb.max = 20
        sizeSb.progress = prefs.iconDp - 40
        sizeSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                sizeTv.text = "Icon size: " + (40 + p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                prefs.iconDp = 40 + (s?.progress ?: 12)
                applySettings()
            }
        })
        col.addView(sizeTv)
        col.addView(sizeSb)

        val colsTv = label("Grid columns: " + prefs.cols)
        val colsSb = SeekBar(this)
        colsSb.max = 2
        colsSb.progress = prefs.cols - 4
        colsSb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                colsTv.text = "Grid columns: " + (4 + p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                prefs.cols = 4 + (s?.progress ?: 0)
                applySettings()
            }
        })
        col.addView(colsTv)
        col.addView(colsSb)

        col.addView(label("Accent color"))
        val colors = intArrayOf(
            0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFD60A.toInt(), 0xFF34C759.toInt(),
            0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFFFFFF.toInt()
        )
        val crow = LinearLayout(this)
        crow.orientation = LinearLayout.HORIZONTAL
        val dots2 = ArrayList<View>()
        for (c in colors) {
            val v = View(this)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            v.scaleX = if (c == prefs.accent) 1.25f else 1f
            v.scaleY = v.scaleX
            v.setOnClickListener {
                prefs.accent = c
                for (o in dots2) { o.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                v.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start()
                applySettings()
            }
            dots2.add(v)
            val lp = LinearLayout.LayoutParams(dp(32), dp(32))
            lp.marginEnd = dp(10)
            crow.addView(v, lp)
        }
        col.addView(crow)

        fun toggle(t: String, on: Boolean, change: (Boolean) -> Unit): Switch {
            val sw = Switch(this)
            sw.text = t
            sw.setTextColor(Color.WHITE)
            sw.isChecked = on
            sw.setPadding(0, dp(10), 0, dp(10))
            sw.setOnCheckedChangeListener { _, c -> change(c); applySettings() }
            return sw
        }
        col.addView(label("Look"))
        col.addView(toggle("App names on home", prefs.labels) { prefs.labels = it })
        col.addView(toggle("Pure black background (saves battery)", prefs.blackBg) { prefs.blackBg = it })

        darkDialog().setTitle("Customize").setView(sv).setPositiveButton("Done", null).show()
    }

    // ------------------------------------------------------------------ home grid (placements + folders)

    private fun cellH(): Int = dp(prefs.iconDp) + dp(if (prefs.labels) 30 else 14)

    private fun rowsOf(layer: FrameLayout): Int = max(1, layer.height / cellH())

    private fun loadHome() {
        placements.clear()
        folders.clear()
        pageIds.clear()
        for (x in prefs.pages.split("|")) {
            val n = x.toIntOrNull()
            if (n != null && !pageIds.contains(n)) pageIds.add(n)
        }
        if (!pageIds.contains(0)) pageIds.add(0)
        val s = prefs.home
        if (s != null) {
            try {
                val o = JSONObject(s)
                val pa = o.getJSONArray("p")
                for (i in 0 until pa.length()) {
                    val p = pa.getJSONObject(i)
                    placements.add(Placement(p.getInt("pg"), p.getInt("c"), p.getInt("r"), p.getInt("k"), p.getString("ref")))
                }
                val fa = o.getJSONArray("f")
                for (i in 0 until fa.length()) {
                    val f = fa.getJSONObject(i)
                    val ks = f.getJSONArray("keys")
                    val list = ArrayList<String>()
                    for (j in 0 until ks.length()) list.add(ks.getString(j))
                    folders[f.getString("id")] = Folder(f.getString("id"), f.getString("n"), list)
                }
            } catch (e: Exception) { }
        }
        for (p in placements) if (!pageIds.contains(p.page)) pageIds.add(p.page)
    }

    private fun saveHome() {
        val o = JSONObject()
        val pa = JSONArray()
        for (p in placements) {
            val j = JSONObject()
            j.put("pg", p.page); j.put("c", p.col); j.put("r", p.row); j.put("k", p.kind); j.put("ref", p.ref)
            pa.put(j)
        }
        val fa = JSONArray()
        for (f in folders.values) {
            val j = JSONObject()
            j.put("id", f.id); j.put("n", f.name)
            val ks = JSONArray()
            for (k in f.keys) ks.put(k)
            j.put("keys", ks)
            fa.put(j)
        }
        o.put("p", pa)
        o.put("f", fa)
        prefs.home = o.toString()
    }

    // remove things that were uninstalled
    private fun pruneHome() {
        if (byKey.isEmpty()) return
        var changed = false
        for (f in folders.values.toList()) {
            if (f.keys.removeAll { !byKey.containsKey(it) }) { changed = true; cleanFolder(f) }
        }
        if (placements.removeAll { it.kind == 0 && !byKey.containsKey(it.ref) }) changed = true
        if (changed) { saveHome(); cleanupPages() }
    }

    private fun cleanFolder(f: Folder) {
        if (f.keys.size <= 1) {
            val p = placements.firstOrNull { it.kind == 1 && it.ref == f.id }
            if (p != null) {
                if (f.keys.size == 1) { p.kind = 0; p.ref = f.keys[0] } else placements.remove(p)
            }
            folders.remove(f.id)
        }
    }

    private fun removeFromHome(key: String) {
        placements.removeAll { it.kind == 0 && it.ref == key }
        for (f in folders.values.toList()) {
            if (f.keys.remove(key)) cleanFolder(f)
        }
    }

    private fun occupied(page: Int, col: Int, row: Int, except: Placement?): Placement? =
        placements.firstOrNull { it !== except && it.page == page && it.col == col && it.row == row }

    private fun findFree(page: Int, col: Int, row: Int, except: Placement?): IntArray {
        val layer = layers[page]
        val rows = if (layer != null && layer.height > 0) rowsOf(layer) else 6
        val cols = prefs.cols
        var best: IntArray? = null
        var bestD = Int.MAX_VALUE
        for (r in 0 until rows) for (c in 0 until cols) {
            if (occupied(page, c, r, except) == null) {
                val d = abs(c - col) + abs(r - row)
                if (d < bestD) { bestD = d; best = intArrayOf(c, r) }
            }
        }
        return best ?: intArrayOf(col, row)
    }

    private fun normalizePlacements() {
        val cols = prefs.cols
        val done = ArrayList<Placement>()
        for (p in placements) {
            val layer = layers[p.page]
            val rows = if (layer != null && layer.height > 0) rowsOf(layer) else 6
            p.col = min(p.col, cols - 1)
            p.row = min(p.row, rows - 1)
            if (done.any { it.page == p.page && it.col == p.col && it.row == p.row }) {
                val saved = ArrayList(placements)
                placements.clear(); placements.addAll(done)
                val f = findFree(p.page, p.col, p.row, null)
                placements.clear(); placements.addAll(saved)
                p.col = f[0]; p.row = f[1]
            }
            done.add(p)
        }
    }

    private fun renderHome() {
        val cols = prefs.cols
        for (pid in pageIds.toList()) {
            val layer = layers[pid] ?: continue
            layer.removeAllViews()
            val w = layer.width
            if (w == 0 || layer.height == 0) continue
            val cw = w / cols
            val ch = cellH()
            for (p in placements) {
                if (p.page != pid) continue
                val v = homeItemView(p) ?: continue
                val lp = FrameLayout.LayoutParams(cw, ch)
                lp.leftMargin = p.col * cw
                lp.topMargin = p.row * ch
                layer.addView(v, lp)
            }
        }
    }

    private fun homeItemView(p: Placement): View? {
        val ip = dp(prefs.iconDp)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(4), 0, 0)
        val labelText: String
        if (p.kind == 0) {
            val a = byKey[p.ref] ?: return null
            val iv = ImageView(this)
            iv.setImageDrawable(a.icon)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            col.addView(iv, LinearLayout.LayoutParams(ip, ip))
            labelText = a.label
            col.setOnClickListener { launch(a, iv) }
        } else {
            val f = folders[p.ref] ?: return null
            val fv = FolderIconView(this, folderBitmaps(f))
            col.addView(fv, LinearLayout.LayoutParams(ip, ip))
            labelText = f.name
            col.setOnClickListener { openFolder(f) }
        }
        if (prefs.labels) {
            val tv = TextView(this)
            tv.text = labelText
            tv.textSize = 11f
            tv.setTextColor(Color.WHITE)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            tv.setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            tv.setPadding(dp(2), dp(4), dp(2), 0)
            col.addView(tv, LinearLayout.LayoutParams(MP, WC))
        }
        press(col)
        col.setOnLongClickListener { startDrag(p.kind, p.ref, SRC_HOME, col, p); true }
        return col
    }

    private fun folderBitmaps(f: Folder): List<Bitmap> =
        f.keys.mapNotNull { (byKey[it]?.icon as? BitmapDrawable)?.bitmap }

    private fun openFolder(f: Folder) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(6), dp(16), dp(6))
        val name = EditText(this)
        name.setText(f.name)
        name.isSingleLine = true
        name.gravity = Gravity.CENTER
        name.setTextColor(Color.WHITE)
        name.textSize = 18f
        col.addView(name, LinearLayout.LayoutParams(MP, WC))

        val d = darkDialog().setView(col).create()
        val grid = GridLayout(this)
        grid.columnCount = 4
        for (key in f.keys.toList()) {
            val a = byKey[key] ?: continue
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(0, dp(10), 0, dp(6))
            val iv = ImageView(this)
            iv.setImageDrawable(a.icon)
            item.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
            val tv = TextView(this)
            tv.text = a.label
            tv.textSize = 11f
            tv.setTextColor(Color.WHITE)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            item.addView(tv, LinearLayout.LayoutParams(MP, WC))
            press(item)
            item.setOnClickListener { d.dismiss(); launch(a, iv) }
            item.setOnLongClickListener {
                val pm = PopupMenu(this, item)
                pm.menu.add(0, 1, 0, "Take out of folder")
                pm.menu.add(0, 2, 1, "App info")
                pm.setOnMenuItemClickListener { m ->
                    try {
                        if (m.itemId == 1) {
                            val fp = placements.firstOrNull { it.kind == 1 && it.ref == f.id }
                            if (fp != null) {
                                val cell = findFree(fp.page, fp.col, fp.row, fp)
                                f.keys.remove(key)
                                cleanFolder(f)
                                placements.add(Placement(fp.page, cell[0], cell[1], 0, key))
                                saveHome(); renderHome()
                            }
                            d.dismiss()
                        } else {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    } catch (e: Exception) { }
                    true
                }
                pm.show()
                true
            }
            val lp = GridLayout.LayoutParams()
            lp.width = dp(76)
            lp.height = WC
            grid.addView(item, lp)
        }
        col.addView(grid, LinearLayout.LayoutParams(MP, WC))
        d.setOnDismissListener {
            val n = name.text.toString().trim()
            if (n.isNotEmpty() && n != f.name) f.name = n
            saveHome()
            renderHome()
            hideKeyboard()
        }
        d.show()
    }

    // ------------------------------------------------------------------ drag & drop

    private fun boostFps(on: Boolean) {
        try {
            val a = window.attributes
            a.preferredRefreshRate = if (on) 120f else 0f
            window.attributes = a
        } catch (e: Exception) { }
    }

    private fun startDrag(kind: Int, ref: String, src: Int, srcView: View?, placement: Placement?) {
        if (drag != null) return
        val size = (dp(prefs.iconDp) * 1.15f).toInt()
        val ghost: View = if (kind == 0) {
            val iv = ImageView(this)
            iv.setImageDrawable(byKey[ref]?.icon)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv
        } else {
            FolderIconView(this, folders[ref]?.let { folderBitmaps(it) } ?: emptyList())
        }
        dragLayer.addView(ghost, FrameLayout.LayoutParams(size, size))
        drag = DragState(kind, ref, src, srcView, placement, ghost, size)
        dragMoved = false
        dragStartX = lastRawX
        dragStartY = lastRawY
        positionGhost(lastRawX, lastRawY)
        ghost.scaleX = 0.8f
        ghost.scaleY = 0.8f
        ghost.animate().scaleX(1.12f).scaleY(1.12f).setDuration(200)
            .setInterpolator(OvershootInterpolator(2f)).start()
        if (src != SRC_DRAWER) srcView?.alpha = 0f
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // stop the finger from also scrolling / clicking whatever was under it
        val now = SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, lastRawX, lastRawY, 0)
        window.superDispatchTouchEvent(c)
        c.recycle()
        removeBar.visibility = if (src == SRC_HOME || src == SRC_DOCK) View.VISIBLE else View.GONE
        removeBar.setBackgroundColor(0x66000000)
        boostFps(true)
    }

    private fun positionGhost(x: Float, y: Float) {
        val d = drag ?: return
        d.ghost.translationX = x - d.ghostSize / 2f
        d.ghost.translationY = y - d.ghostSize / 2f - dp(28)
    }

    private fun inRemoveZone(y: Float) = y < topInset + dp(64)

    private fun inDockZone(y: Float) = y > root.height - bottomInset - dp(14) - dp(100) - dp(14)

    // returns [pageId, col, row] under the finger, or null
    private fun cellAt(x: Float, y: Float): IntArray? {
        if (open) return null
        val pid = pageIds.getOrNull(pager.currentItem) ?: return null
        val layer = layers[pid] ?: return null
        if (layer.width == 0) return null
        val loc = IntArray(2)
        layer.getLocationOnScreen(loc)
        val lx = x - loc[0]
        val ly = y - loc[1]
        if (lx < 0 || ly < 0 || lx >= layer.width || ly >= layer.height) return null
        val cols = prefs.cols
        val cw = layer.width / cols
        val ch = cellH()
        val rows = rowsOf(layer)
        return intArrayOf(pid, (lx / cw).toInt().coerceIn(0, cols - 1), (ly / ch).toInt().coerceIn(0, rows - 1))
    }

    private fun updateDragTargets(x: Float, y: Float) {
        val d = drag ?: return
        val inRemove = d.src != SRC_DRAWER && inRemoveZone(y)
        removeBar.setBackgroundColor(if (inRemove) 0xCCFF3B30.toInt() else 0x66000000)

        val cell = if (!inRemove && !inDockZone(y)) cellAt(x, y) else null
        if (cell == null) {
            cellHi.visibility = View.GONE
        } else {
            val layer = layers[cell[0]]
            if (layer == null) { cellHi.visibility = View.GONE; return }
            val loc = IntArray(2)
            layer.getLocationOnScreen(loc)
            val cw = layer.width / prefs.cols
            val ch = cellH()
            val lp = cellHi.layoutParams
            if (lp.width != cw || lp.height != ch) {
                lp.width = cw
                lp.height = ch
                cellHi.layoutParams = lp
            }
            cellHi.translationX = (loc[0] + cell[1] * cw).toFloat()
            cellHi.translationY = (loc[1] + cell[2] * ch).toFloat()
            cellHi.visibility = View.VISIBLE
        }

        // hold at the screen edge to flip pages while dragging
        val edge = dp(36)
        val dir = when {
            x < edge -> -1
            x > root.width - edge -> 1
            else -> 0
        }
        if (dir != edgeDir) {
            handler.removeCallbacks(edgeRun)
            edgeDir = dir
            if (dir != 0 && !open) handler.postDelayed(edgeRun, 650)
        }
    }

    private fun handleDrag(e: MotionEvent) {
        val d = drag ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                positionGhost(e.rawX, e.rawY)
                if (!dragMoved && hypot(e.rawX - dragStartX, e.rawY - dragStartY) > slop) {
                    dragMoved = true
                    if (d.src == SRC_DRAWER && open) settle(false, 0f)   // reveal the home screen
                }
                if (dragMoved) updateDragTargets(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP -> endDrag(e.rawX, e.rawY, false)
            MotionEvent.ACTION_CANCEL -> endDrag(e.rawX, e.rawY, true)
        }
    }

    private fun endDrag(x: Float, y: Float, cancelled: Boolean) {
        endDragInner(x, y, cancelled)
        cleanupPages()
    }

    private fun endDragInner(x: Float, y: Float, cancelled: Boolean) {
        val d = drag ?: return
        drag = null
        handler.removeCallbacks(edgeRun)
        edgeDir = 0
        cellHi.visibility = View.GONE
        removeBar.visibility = View.GONE
        boostFps(false)
        d.srcView?.alpha = 1f
        d.ghost.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(150)
            .withEndAction { dragLayer.removeView(d.ghost) }.start()
        if (cancelled) return

        if (!dragMoved) {
            // finger lifted without moving: show the menu instead
            if (d.kind == 0) {
                val a = byKey[d.ref]
                val anchor = d.srcView
                if (a != null && anchor != null) showAppMenu(anchor, a, d.src == SRC_HOME)
            }
            return
        }

        if (d.src != SRC_DRAWER && inRemoveZone(y)) {
            if (d.src == SRC_HOME && d.placement != null) {
                placements.remove(d.placement)
                if (d.kind == 1) folders.remove(d.ref)
                saveHome(); renderHome()
            } else if (d.src == SRC_DOCK) {
                dockKeys.remove(d.ref)
                prefs.dock = dockKeys.toList()
                fillDock(true)
            }
            return
        }

        if (inDockZone(y)) {
            if (d.kind != 0) {
                Toast.makeText(this, "Folders can't go in the dock", Toast.LENGTH_SHORT).show()
                return
            }
            if (dropToDock(d, x) && d.src == SRC_HOME && d.placement != null) {
                placements.remove(d.placement)
                saveHome(); renderHome()
            }
            return
        }

        val cell = cellAt(x, y)
        if (cell != null) {
            dropToHome(d, cell)
            if (d.src == SRC_DOCK && d.kind == 0) {
                dockKeys.remove(d.ref)
                prefs.dock = dockKeys.toList()
                fillDock(true)
            }
        }
    }

    private fun dropToDock(d: DragState, x: Float): Boolean {
        val key = d.ref
        val wasIn = dockKeys.contains(key)
        if (!wasIn && dockKeys.size >= MAX_DOCK) {
            Toast.makeText(this, "Dock is full (max $MAX_DOCK apps)", Toast.LENGTH_SHORT).show()
            return false
        }
        var idx = 0
        val loc = IntArray(2)
        for (i in 0 until dock.childCount) {
            val v = dock.getChildAt(i)
            v.getLocationOnScreen(loc)
            if (loc[0] + v.width / 2f < x) idx = i + 1
        }
        if (wasIn) {
            val old = dockKeys.indexOf(key)
            dockKeys.remove(key)
            if (old < idx) idx--
        }
        dockKeys.add(min(max(idx, 0), dockKeys.size), key)
        prefs.dock = dockKeys.toList()
        fillDock(true)
        return true
    }

    private fun newFolderId() = "f" + System.currentTimeMillis()

    private fun dropToHome(d: DragState, cell: IntArray) {
        val pid = cell[0]
        val col = cell[1]
        val row = cell[2]
        val srcP = d.placement
        val occ = occupied(pid, col, row, srcP)
        if (d.kind == 0) {
            val key = d.ref
            if (occ != null && occ.kind == 0 && occ.ref != key) {
                // app dropped on app -> new folder
                val other = occ.ref
                removeFromHome(key)
                val f = Folder(newFolderId(), "Folder", mutableListOf(other, key))
                folders[f.id] = f
                occ.kind = 1
                occ.ref = f.id
            } else if (occ != null && occ.kind == 1) {
                val fid = occ.ref
                removeFromHome(key)
                folders[fid]?.let { if (!it.keys.contains(key)) it.keys.add(key) }
            } else if (occ == null) {
                removeFromHome(key)
                placements.add(Placement(pid, col, row, 0, key))
            }
        } else if (srcP != null) {
            if (occ == null) {
                srcP.page = pid; srcP.col = col; srcP.row = row
            } else {
                val f = findFree(pid, col, row, srcP)
                srcP.page = pid; srcP.col = f[0]; srcP.row = f[1]
            }
        }
        saveHome()
        renderHome()
    }

    // ------------------------------------------------------------------ keyboard fix

    private fun hideKeyboard() {
        if (!::root.isInitialized) return
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
            currentFocus?.clearFocus()
            root.requestFocus()
        } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ drawer (finger-following + spring)

    private fun applyProgress(y: Float) {
        val p = (1f - y / closedY).coerceIn(0f, 1f)
        homeLayer.alpha = (1f - p * 1.5f).coerceIn(0f, 1f)
        val s = 1f - 0.07f * p
        homeLayer.scaleX = s
        homeLayer.scaleY = s
        val rs = 0.95f + 0.05f * p
        rv.scaleX = rs
        rv.scaleY = rs
    }

    private fun prepareDrawer() {
        drawer.visibility = View.VISIBLE
        drawer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        homeLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        boostFps(true)
    }

    private fun settle(openIt: Boolean, velocity: Float) {
        open = openIt
        prepareDrawer()
        springAnim?.cancel()
        val target = if (openIt) 0f else closedY
        val anim = SpringAnimation(drawer, DynamicAnimation.TRANSLATION_Y)
        anim.setSpring(SpringForce(target).setStiffness(420f).setDampingRatio(0.88f))
        anim.setStartVelocity(velocity)
        anim.addUpdateListener { _, value, _ -> applyProgress(value) }
        anim.addEndListener { _, canceled, _, _ -> if (!canceled) finishSettle() }
        springAnim = anim
        anim.start()
    }

    private fun finishSettle() {
        val y = if (open) 0f else closedY
        drawer.translationY = y
        applyProgress(y)
        drawer.setLayerType(View.LAYER_TYPE_NONE, null)
        homeLayer.setLayerType(View.LAYER_TYPE_NONE, null)
        if (drag == null) boostFps(false)
        if (!open) {
            drawer.visibility = View.INVISIBLE
            hiddenMode = false
            if (search.text.isNotEmpty()) search.setText("") else refilter("")
            updateHint("")
            rv.scrollToPosition(0)
            hideKeyboard()
        }
    }

    private fun setDrawer(o: Boolean) = settle(o, 0f)

    private fun snapClosed() {
        springAnim?.cancel()
        open = false
        finishSettle()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        lastRawX = e.rawX
        lastRawY = e.rawY
        if (drag != null) {
            handleDrag(e)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                startX = e.rawX
                startY = e.rawY
                lastY = e.rawY
                drawerDragging = false
                resumeAfter = springAnim?.isRunning == true
                springAnim?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!drawerDragging) {
                    val dy = e.rawY - startY
                    val dx = e.rawX - startX
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.3f) {
                        val canOpen = !open && dy < 0
                        val canClose = open && dy > 0 && !rv.canScrollVertically(-1)
                        if (canOpen || canClose) {
                            drawerDragging = true
                            resumeAfter = false
                            val c = MotionEvent.obtain(e)
                            c.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(c)
                            c.recycle()
                            prepareDrawer()
                            lastY = e.rawY
                            return true
                        }
                    }
                } else {
                    val y = (drawer.translationY + (e.rawY - lastY)).coerceIn(0f, closedY)
                    drawer.translationY = y
                    applyProgress(y)
                    lastY = e.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawerDragging) {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    drawerDragging = false
                    val openIt = if (abs(v) > 700f) v < 0 else drawer.translationY < closedY * 0.55f
                    settle(openIt, v)
                    return true
                }
                if (resumeAfter) {
                    resumeAfter = false
                    settle(open, 0f)
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    // ------------------------------------------------------------------ adapters

    inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = pageIds.size
        override fun getItemViewType(position: Int) = pageIds[position]
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val v = pageView(t)
            (v.parent as? ViewGroup)?.removeView(v)
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {}
    }

    class VH(v: View, val iv: ImageView, val tv: TextView) : RecyclerView.ViewHolder(v)

    inner class AppAdapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val col = LinearLayout(p.context)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(0, dp(10), 0, dp(10))
            col.layoutParams = RecyclerView.LayoutParams(MP, WC)
            val iv = ImageView(p.context)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            col.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)))
            val tv = TextView(p.context)
            tv.textSize = 11f
            tv.setTextColor(Color.WHITE)
            tv.maxLines = 1
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(4), dp(6), dp(4), 0)
            col.addView(tv, LinearLayout.LayoutParams(MP, WC))
            press(col)
            return VH(col, iv, tv)
        }

        override fun onBindViewHolder(h: VH, i: Int) {
            val a = shown[i]
            val ip = dp(prefs.iconDp)
            val lp = h.iv.layoutParams
            if (lp.width != ip) {
                lp.width = ip
                lp.height = ip
                h.iv.layoutParams = lp
            }
            h.iv.setImageDrawable(a.icon)
            h.tv.text = a.label
            h.itemView.setOnClickListener { launch(a, h.iv) }
            h.itemView.setOnLongClickListener { startDrag(0, a.key, SRC_DRAWER, h.itemView, null); true }
        }

        override fun getItemCount() = shown.size
    }
}

// Folder icon: rounded glass square with up to 4 mini app icons
class FolderIconView(c: Context, private val bitmaps: List<Bitmap>) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rf = RectF()
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.color = 0x66FFFFFF
        rf.set(0f, 0f, w, h)
        cv.drawRoundRect(rf, w * 0.24f, w * 0.24f, p)
        val pad = w * 0.12f
        val gap = w * 0.06f
        val s = (w - 2 * pad - gap) / 2f
        for (i in 0 until min(4, bitmaps.size)) {
            val r = i / 2
            val col = i % 2
            val l = pad + col * (s + gap)
            val t = pad + r * (s + gap)
            rf.set(l, t, l + s, t + s)
            cv.drawBitmap(bitmaps[i], null, rf, bp)
        }
    }
}

// Day-name text: letter spacing adds a gap after the last letter too, so shift
// the text by half of it to keep the word truly centered.
class DayText(c: Context) : TextView(c) {
    override fun onDraw(canvas: Canvas) {
        val shift = letterSpacing * textSize / 2f
        canvas.save()
        canvas.translate(shift, 0f)
        super.onDraw(canvas)
        canvas.restore()
    }
}
