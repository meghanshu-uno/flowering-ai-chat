package com.classic.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs

private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

class AppItem(val label: String, val intent: Intent, val icon: Drawable)

class MainActivity : Activity() {
    private var all = listOf<AppItem>()
    private val shown = ArrayList<AppItem>()
    private val adapter = A()
    private lateinit var home: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var rv: RecyclerView
    private lateinit var search: EditText
    private lateinit var year: YearView
    private var open = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Light "glass" look. No real blur on purpose: keeps it smooth on 3GB RAM.
    private fun glass(r: Int, color: Int = 0x40FFFFFF) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); setStroke(dp(1), 0x33FFFFFF)
    }

    private val gd by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) < abs(vx)) return false
                if (!open && vy < -900) setDrawer(true)
                else if (open && vy > 900 && !rv.canScrollVertically(-1)) setDrawer(false)
                return false
            }
        })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = FrameLayout(this).apply { fitsSystemWindows = true }

        home = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        home.addView(TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"; textSize = 72f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(MP, WC))
        home.addView(TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"; format24Hour = "EEEE, d MMMM"; textSize = 16f
            setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MP, WC))

        home.addView(TextView(this).apply {
            text = "Search apps"; textSize = 15f; setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), 0, dp(22), 0)
            background = glass(28); setOnClickListener { setDrawer(true) }
        }, LinearLayout.LayoutParams(MP, dp(54)).apply { topMargin = dp(28) })

        year = YearView(this).apply { background = glass(24) }
        home.addView(year, LinearLayout.LayoutParams(MP, dp(190)).apply { topMargin = dp(20) })
        home.addView(View(this), LinearLayout.LayoutParams(MP, 0, 1f))

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12)); background = glass(32)
        }
        home.addView(dock, LinearLayout.LayoutParams(MP, WC))
        root.addView(home, FrameLayout.LayoutParams(MP, MP))

        search = EditText(this).apply {
            hint = "Search apps"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 15f; isSingleLine = true; background = glass(26)
            setPadding(dp(20), 0, dp(20), 0)
            doAfterTextChanged { refilter(it?.toString().orEmpty()) }
        }
        rv = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true); setItemViewCacheSize(24)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            background = glass(28, 0xF2121212.toInt())
            translationY = resources.displayMetrics.heightPixels.toFloat()
        }
        drawer.addView(search, LinearLayout.LayoutParams(MP, dp(50)))
        drawer.addView(rv, LinearLayout.LayoutParams(MP, 0, 1f).apply { topMargin = dp(8) })
        root.addView(drawer, FrameLayout.LayoutParams(MP, MP).apply { topMargin = dp(40) })

        setContentView(root)
        loadApps()
    }

    private fun loadApps() = Executors.newSingleThreadExecutor().execute {
        val pm = packageManager; val s = dp(48)
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppItem(it.loadLabel(pm).toString(),
                    Intent(main).setClassName(it.activityInfo.packageName, it.activityInfo.name),
                    bmp(it.loadIcon(pm), s))
            }.sortedBy { it.label.lowercase() }

        val dockIntents = listOf(
            Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://")), Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val dockItems = dockIntents.mapNotNull { i ->
            pm.queryIntentActivities(i, 0).firstOrNull()?.let {
                AppItem(it.loadLabel(pm).toString(),
                    Intent(i).setClassName(it.activityInfo.packageName, it.activityInfo.name),
                    bmp(it.loadIcon(pm), dp(54)))
            }
        }
        runOnUiThread { all = list; refilter(""); fillDock(dockItems) }
    }

    private fun bmp(d: Drawable, s: Int): Drawable {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s); d.draw(Canvas(b))
        return BitmapDrawable(resources, b)
    }

    private fun refilter(q: String) {
        shown.clear(); shown.addAll(all.filter { it.label.contains(q, true) })
        adapter.notifyDataSetChanged()
    }

    private fun fillDock(items: List<AppItem>) {
        dock.removeAllViews()
        items.forEach { a ->
            dock.addView(ImageView(this).apply {
                setImageDrawable(a.icon); press(this); setOnClickListener { launch(a) }
            }, LinearLayout.LayoutParams(0, dp(54), 1f))
        }
    }

    private fun launch(a: AppItem) {
        try { startActivity(Intent(a.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        window.decorView.postDelayed({ if (open) setDrawer(false) }, 350)
    }

    // Tiny press-scale animation = feels responsive
    private fun press(v: View) = v.setOnTouchListener { x, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> x.animate().scaleX(.9f).scaleY(.9f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                x.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        false
    }

    private fun setDrawer(o: Boolean) {
        open = o
        val h = resources.displayMetrics.heightPixels.toFloat()
        drawer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        drawer.animate().translationY(if (o) 0f else h).setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction { drawer.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
        home.animate().alpha(if (o) 0f else 1f).scaleX(if (o) .94f else 1f)
            .scaleY(if (o) .94f else 1f).setDuration(260).start()
        if (!o) {
            search.setText(""); rv.scrollToPosition(0)
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean { gd.onTouchEvent(e); return super.dispatchTouchEvent(e) }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (open) setDrawer(false) }
    override fun onResume() { super.onResume(); year.invalidate() }
    @Deprecated("launcher: never exit") override fun onBackPressed() { if (open) setDrawer(false) }

    class VH(v: View, val iv: ImageView, val tv: TextView) : RecyclerView.ViewHolder(v)

    inner class A : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val col = LinearLayout(p.context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, dp(10)); layoutParams = RecyclerView.LayoutParams(MP, WC)
            }
            val iv = ImageView(p.context); col.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
            val tv = TextView(p.context).apply {
                textSize = 11f; setTextColor(Color.WHITE); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), 0)
            }
            col.addView(tv, LinearLayout.LayoutParams(MP, WC))
            press(col)
            return VH(col, iv, tv)
        }
        override fun onBindViewHolder(h: VH, i: Int) {
            val a = shown[i]
            h.iv.setImageDrawable(a.icon); h.tv.text = a.label
            h.itemView.setOnClickListener { launch(a) }
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
        val yr = now.get(Calendar.YEAR); val doy = now.get(Calendar.DAY_OF_YEAR)
        val max = now.getActualMaximum(Calendar.DAY_OF_YEAR)
        val d = resources.displayMetrics.density
        t.color = Color.WHITE; t.textSize = 16 * d; t.textAlign = Paint.Align.LEFT
        cv.drawText("$yr", 16 * d, 30 * d, t)
        t.textSize = 12 * d; t.color = 0xB3FFFFFF.toInt(); t.textAlign = Paint.Align.RIGHT
        cv.drawText("${doy * 100 / max}% · ${max - doy} days left", width - 16 * d, 30 * d, t)
        val left = 16 * d; val top = 46 * d
        val cw = (width - 32 * d) / 31f; val rh = (height - top - 14 * d) / 12f
        val r = minOf(cw, rh) * .28f
        var n = 0
        for (m in 0 until 12) {
            val dim = Calendar.getInstance().apply { clear(); set(yr, m, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
            for (day in 0 until dim) {
                n++
                p.color = when { n < doy -> Color.WHITE; n == doy -> 0xFFFF3B30.toInt(); else -> 0x4DFFFFFF }
                cv.drawCircle(left + cw * (day + .5f), top + rh * (m + .5f), r, p)
            }
        }
    }
}
