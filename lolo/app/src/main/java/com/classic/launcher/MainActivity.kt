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
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MAX_DOCK = 6
private const val REQ_RINGTONE = 11
private const val REQ_FILE = 12

class AppItem(
    val key: String,
    val pkg: String,
    val label: String,
    val intent: Intent,
    val icon: Drawable
)

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // data
    private var all = listOf<AppItem>()
    private var byKey = mapOf<String, AppItem>()
    private val shown = ArrayList<AppItem>()
    private val appAdapter = AppAdapter()
    private var dockKeys = mutableListOf<String>()

    // views
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: FrameLayout
    private lateinit var pager: ViewPager2
    private lateinit var dock: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var rv: RecyclerView
    private lateinit var search: EditText
    private lateinit var dayTv: TextView
    private lateinit var dateTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var timerTv: TextView
    private lateinit var year: YearView
    private lateinit var pages: List<View>

    // state
    private var open = false
    private var imeUp = false
    private var dragging = false
    private var resumeAfter = false
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var vt: VelocityTracker? = null
    private var springAnim: SpringAnimation? = null
    private var dockAnim: ValueAnimator? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // timer dialog
    private var pendingSound: String? = null
    private var soundLabel: TextView? = null

    // India time
    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private val fDay = SimpleDateFormat("EEEE", Locale.ENGLISH).apply { timeZone = tz }
    private val fDate = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).apply { timeZone = tz }
    private val fTime = SimpleDateFormat("hh:mm a", Locale.ENGLISH).apply { timeZone = tz }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val closedY: Float
        get() = resources.displayMetrics.heightPixels.toFloat() + dp(60)

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)

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

        homeLayer.addView(pager, FrameLayout.LayoutParams(MP, MP))
        homeLayer.addView(dots, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        homeLayer.addView(dock, FrameLayout.LayoutParams(WC, WC, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(homeLayer, FrameLayout.LayoutParams(MP, MP))
        root.addView(drawer, FrameLayout.LayoutParams(MP, MP))
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
        io.shutdown()
    }

    override fun onResume() {
        super.onResume()
        TimerReceiver.stop()
        hideKeyboard()
        year.invalidate()
        restartTick()
    }

    override fun onPause() {
        super.onPause()
        // Gaming / battery: do absolutely nothing in the background.
        handler.removeCallbacks(tick)
        if (springAnim?.isRunning == true) { springAnim?.cancel(); finishSettle() }
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

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            handler.removeCallbacks(reload)
            handler.postDelayed(reload, 700)
        }
    }
    private val reload = Runnable { loadApps() }

    // ------------------------------------------------------------------ UI building

    private fun glass(r: Int, color: Int = 0x40FFFFFF): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun buildPages() {
        dayTv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxLines = 1
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            letterSpacing = 0.22f
            setPadding(dp(16), 0, dp(16), 0)
        }
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(dayTv, 18, 46, 1, TypedValue.COMPLEX_UNIT_SP)

        dateTv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 13f
            letterSpacing = 0.15f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        timeTv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        timerTv = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(22), dp(11), dp(22), dp(11))
            background = glass(22)
            setOnClickListener { showTimerDialog() }
        }
        press(timerTv)

        val page0 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(30))
            addView(dayTv, LinearLayout.LayoutParams(MP, dp(60)))
            addView(dateTv, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(2) })
            addView(timeTv, LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(4) })
            addView(timerTv, LinearLayout.LayoutParams(WC, WC).apply { topMargin = dp(26) })
            layoutParams = RecyclerView.LayoutParams(MP, MP)
        }

        year = YearView(this).apply { background = glass(24, 0x33FFFFFF) }
        val page1 = FrameLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(year, FrameLayout.LayoutParams(MP, dp(320), Gravity.CENTER))
            layoutParams = RecyclerView.LayoutParams(MP, MP)
        }

        val title = TextView(this).apply {
            text = "NOTES"
            setTextColor(Color.WHITE)
            textSize = 20f
            letterSpacing = 0.2f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
        val note = EditText(this).apply {
            hint = "Write something..."
            setHintTextColor(0x88FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = glass(24, 0x33FFFFFF)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setText(prefs.note)
            doAfterTextChanged { prefs.note = it?.toString() ?: "" }
        }
        val page2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(title, LinearLayout.LayoutParams(WC, WC).apply { bottomMargin = dp(12) })
            addView(note, LinearLayout.LayoutParams(MP, dp(320)))
            layoutParams = RecyclerView.LayoutParams(MP, MP)
        }
        pages = listOf(page0, page1, page2)
    }

    private fun buildPager() {
        pager = ViewPager2(this)
        pager.adapter = PageAdapter()
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
        for (i in 0 until 3) {
            val d = View(this)
            d.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            d.alpha = if (i == 0) 1f else 0.35f
            val lp = LinearLayout.LayoutParams(dp(7), dp(7))
            lp.marginStart = dp(4)
            lp.marginEnd = dp(4)
            dots.addView(d, lp)
        }
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
        rv.layoutManager = GridLayoutManager(this, 4)
        rv.adapter = appAdapter
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(20)
        rv.itemAnimator = null
        rv.overScrollMode = View.OVER_SCROLL_NEVER

        search = EditText(this)
        search.hint = "Search apps"
        search.setHintTextColor(0x99FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 15f
        search.isSingleLine = true
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.background = glass(25)
        search.setPadding(dp(18), 0, dp(18), 0)
        search.doAfterTextChanged { refilter(it?.toString() ?: "") }
        search.setOnEditorActionListener { _, _, _ ->
            shown.firstOrNull()?.let { launch(it, null) }
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
        drawer.addView(search, LinearLayout.LayoutParams(MP, dp(50)).apply { topMargin = dp(8) })
    }

    // System bar / keyboard insets. `bottom` = nav bar height, or keyboard height when it is open.
    private fun applyInsets(top: Int, bottom: Int) {
        imeUp = bottom > dp(120)

        val pl = pager.layoutParams as FrameLayout.LayoutParams
        pl.topMargin = top
        pl.bottomMargin = if (imeUp) bottom + dp(8) else bottom + dp(132)
        pager.layoutParams = pl

        val dl = dock.layoutParams as FrameLayout.LayoutParams
        dl.bottomMargin = bottom + dp(14)
        dock.layoutParams = dl

        val ol = dots.layoutParams as FrameLayout.LayoutParams
        ol.bottomMargin = bottom + dp(106)
        dots.layoutParams = ol
        dots.visibility = if (imeUp) View.INVISIBLE else View.VISIBLE
        updateDockVisibility()

        // Drawer starts just below the status bar and fills the rest of the screen.
        val rl = drawer.layoutParams as FrameLayout.LayoutParams
        rl.topMargin = top + dp(10)
        drawer.layoutParams = rl
        drawer.setPadding(dp(12), dp(14), dp(12), bottom + dp(8))
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
            val s = dp(52)
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
            }.distinct()

            runOnUiThread {
                all = list
                byKey = list.associateBy { it.key }
                refilter(search.text.toString())
                if (prefs.dock == null) prefs.dock = defaults
                dockKeys = (prefs.dock ?: defaults).filter { byKey.containsKey(it) }.toMutableList()
                fillDock(false)
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
        shown.addAll(all.filter { it.label.contains(q, true) })
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
            iv.setPadding(dp(3), dp(3), dp(3), dp(3))
            press(iv)
            iv.setOnClickListener { launch(a, iv) }
            iv.setOnLongClickListener { showAppMenu(iv, a); true }
            dock.addView(iv, LinearLayout.LayoutParams(dp(58), dp(54)))
        }
        updateDockVisibility()
        if (animate && oldW > 0 && items.isNotEmpty()) {
            dock.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val newW = dock.measuredWidth
            val lp = dock.layoutParams
            val va = ValueAnimator.ofInt(oldW, newW)
            va.duration = 280
            va.interpolator = DecelerateInterpolator(1.4f)
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
                Toast.makeText(this, "Dock is full ($MAX_DOCK apps)", Toast.LENGTH_SHORT).show()
                return
            }
            dockKeys.add(a.key)
            Toast.makeText(this, "Added to dock", Toast.LENGTH_SHORT).show()
        }
        prefs.dock = dockKeys.toList()
        fillDock(true)
    }

    private fun showAppMenu(v: View, a: AppItem) {
        val inDock = dockKeys.contains(a.key)
        val pm = PopupMenu(this, v)
        pm.menu.add(0, 1, 0, if (inDock) "Remove from dock" else "Add to dock")
        pm.menu.add(0, 2, 1, "App info")
        pm.menu.add(0, 3, 2, "Uninstall")
        pm.setOnMenuItemClickListener { m ->
            try {
                when (m.itemId) {
                    1 -> toggleDock(a)
                    2 -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    3 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))
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
                    x.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
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
    }

    private fun prepareDrawer() {
        drawer.visibility = View.VISIBLE
        drawer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        homeLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun settle(openIt: Boolean, velocity: Float) {
        open = openIt
        prepareDrawer()
        springAnim?.cancel()
        val target = if (openIt) 0f else closedY
        val anim = SpringAnimation(drawer, DynamicAnimation.TRANSLATION_Y)
        anim.setSpring(SpringForce(target).setStiffness(380f).setDampingRatio(0.9f))
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
        if (!open) {
            drawer.visibility = View.INVISIBLE
            if (search.text.isNotEmpty()) search.setText("")
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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(e)
                startX = e.rawX
                startY = e.rawY
                lastY = e.rawY
                dragging = false
                resumeAfter = springAnim?.isRunning == true
                springAnim?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!dragging) {
                    val dy = e.rawY - startY
                    val dx = e.rawX - startX
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.3f) {
                        val canOpen = !open && dy < 0 &&
                            (pager.currentItem != 2 || startY > root.height * 0.8f)
                        val canClose = open && dy > 0 && !rv.canScrollVertically(-1)
                        if (canOpen || canClose) {
                            dragging = true
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
                if (dragging) {
                    vt?.addMovement(e)
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.yVelocity ?: 0f
                    dragging = false
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
        override fun getItemCount() = pages.size
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val v = pages[t]
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
            h.iv.setImageDrawable(a.icon)
            h.tv.text = a.label
            h.itemView.setOnClickListener { launch(a, h.iv) }
            h.itemView.setOnLongClickListener { showAppMenu(h.itemView, a); true }
        }

        override fun getItemCount() = shown.size
    }
}

// Year-progress dot widget: one row per month, one dot per day
class YearView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val now = Calendar.getInstance()
        val yr = now.get(Calendar.YEAR)
        val doy = now.get(Calendar.DAY_OF_YEAR)
        val max = now.getActualMaximum(Calendar.DAY_OF_YEAR)
        val d = resources.displayMetrics.density
        t.color = Color.WHITE
        t.textSize = 16 * d
        t.textAlign = Paint.Align.LEFT
        cv.drawText("$yr", 16 * d, 30 * d, t)
        t.textSize = 12 * d
        t.color = 0xB3FFFFFF.toInt()
        t.textAlign = Paint.Align.RIGHT
        cv.drawText("${doy * 100 / max}% \u00B7 ${max - doy} days left", width - 16 * d, 30 * d, t)
        val left = 16 * d
        val top = 46 * d
        val cw = (width - 32 * d) / 31f
        val rh = (height - top - 14 * d) / 12f
        val r = minOf(cw, rh) * .28f
        var n = 0
        for (m in 0 until 12) {
            val dim = Calendar.getInstance().apply { clear(); set(yr, m, 1) }
                .getActualMaximum(Calendar.DAY_OF_MONTH)
            for (day in 0 until dim) {
                n++
                p.color = when {
                    n < doy -> Color.WHITE
                    n == doy -> 0xFFFF3B30.toInt()
                    else -> 0x4DFFFFFF
                }
                cv.drawCircle(left + cw * (day + .5f), top + rh * (m + .5f), r, p)
            }
        }
    }
}
