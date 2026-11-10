package com.classic.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.bluetooth.BluetoothAdapter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun glassBg(color: Int, radiusPx: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
}

// =====================================================================================
//  Notification center  (swipe down from the top-LEFT)
// =====================================================================================

class NotificationPanel(private val svc: StatusBarService) {

    private val ctx: Context = svc
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val list = LinearLayout(ctx)
    val view = LinearLayout(ctx)

    init {
        list.orientation = LinearLayout.VERTICAL

        view.orientation = LinearLayout.VERTICAL
        val r = dp(28).toFloat()
        view.background = GradientDrawable().apply {
            setColor(0xF2121216.toInt())
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        view.setPadding(dp(16), dp(18), dp(16), 0)
        view.isClickable = true

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(ctx)
        title.text = "Notifications"
        title.setTextColor(Color.WHITE)
        title.textSize = 24f
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))

        val clear = TextView(ctx)
        clear.text = "Clear all"
        clear.setTextColor(Color.WHITE)
        clear.textSize = 13f
        clear.setPadding(dp(14), dp(7), dp(14), dp(7))
        clear.background = glassBg(0x33FFFFFF, dp(20).toFloat())
        clear.setOnClickListener {
            try { NotifService.instance?.cancelAllNotifications() } catch (e: Exception) { }
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 250)
        }
        header.addView(clear, LinearLayout.LayoutParams(WRAP, WRAP))
        view.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        val sv = ScrollView(ctx)
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.isVerticalScrollBarEnabled = false
        sv.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        view.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(12) })

        view.addView(svc.makeHandle(), LinearLayout.LayoutParams(MATCH, dp(40)))

        refresh()
        NotifService.listener = { refresh() }
    }

    fun refresh() {
        list.removeAllViews()
        val ns = NotifService.instance
        if (ns == null) {
            list.addView(infoCard("Notification access is off", "Tap here to turn it on") {
                svc.startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            return
        }
        val items: List<StatusBarNotification> = try {
            ns.activeNotifications?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val shown = items.filter { sbn ->
            val n = sbn.notification
            val hasText = n.extras.getCharSequence(Notification.EXTRA_TITLE) != null ||
                n.extras.getCharSequence(Notification.EXTRA_TEXT) != null
            (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && hasText
        }.sortedByDescending { it.postTime }

        if (shown.isEmpty()) {
            list.addView(infoCard("No notifications", null, null))
            return
        }
        for (sbn in shown) list.addView(card(sbn))
    }

    private fun infoCard(title: String, sub: String?, click: (() -> Unit)?): View {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.setPadding(dp(16), dp(22), dp(16), dp(22))
        col.background = glassBg(0x26FFFFFF, dp(18).toFloat())
        val t = TextView(ctx)
        t.text = title
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.gravity = Gravity.CENTER
        col.addView(t)
        if (sub != null) {
            val s = TextView(ctx)
            s.text = sub
            s.setTextColor(0xFF64B5FF.toInt())
            s.textSize = 13f
            s.gravity = Gravity.CENTER
            s.setPadding(0, dp(6), 0, 0)
            col.addView(s)
        }
        if (click != null) col.setOnClickListener { click() }
        col.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        return col
    }

    private fun card(sbn: StatusBarNotification): View {
        val n = sbn.notification
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val pm = ctx.packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        val icon = try { pm.getApplicationIcon(sbn.packageName) } catch (e: Exception) { null }

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(12), dp(8), dp(12))
        row.background = glassBg(0x2EFFFFFF, dp(18).toFloat())

        val iv = android.widget.ImageView(ctx)
        iv.setImageDrawable(icon)
        row.addView(iv, LinearLayout.LayoutParams(dp(36), dp(36)))

        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, dp(6), 0)

        val a = TextView(ctx)
        a.text = appName.uppercase()
        a.setTextColor(0x99FFFFFF.toInt())
        a.textSize = 10.5f
        a.letterSpacing = 0.06f
        a.maxLines = 1
        col.addView(a)

        if (title.isNotEmpty()) {
            val t = TextView(ctx)
            t.text = title
            t.setTextColor(Color.WHITE)
            t.textSize = 14.5f
            t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            t.maxLines = 1
            t.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(t)
        }
        if (text.isNotEmpty()) {
            val b = TextView(ctx)
            b.text = text
            b.setTextColor(0xCCFFFFFF.toInt())
            b.textSize = 13f
            b.maxLines = 3
            b.ellipsize = android.text.TextUtils.TruncateAt.END
            col.addView(b)
        }
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        if (sbn.isClearable) {
            val x = TextView(ctx)
            x.text = "\u2715"
            x.setTextColor(0x99FFFFFF.toInt())
            x.textSize = 15f
            x.gravity = Gravity.CENTER
            x.setOnClickListener {
                try { NotifService.instance?.cancelNotification(sbn.key) } catch (e: Exception) { }
                row.animate().alpha(0f).translationX(row.width * 0.4f).setDuration(160)
                    .withEndAction { list.removeView(row) }.start()
            }
            row.addView(x, LinearLayout.LayoutParams(dp(36), dp(36)))
        }

        row.setOnClickListener {
            try {
                val pi = n.contentIntent
                if (pi != null) {
                    val opts = ActivityOptions.makeBasic()
                    if (Build.VERSION.SDK_INT >= 34) {
                        opts.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                    pi.send(ctx, 0, null, null, null, null, opts.toBundle())
                    if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) {
                        NotifService.instance?.cancelNotification(sbn.key)
                    }
                }
            } catch (e: Exception) { }
            svc.closePanel()
        }
        row.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        return row
    }
}

// =====================================================================================
//  Control center  (swipe down from the top-RIGHT)
// =====================================================================================

class ControlPanel(private val svc: StatusBarService, private val screenW: Int) {

    private val ctx: Context = svc
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val main = Handler(Looper.getMainLooper())

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val view = LinearLayout(ctx)

    private lateinit var tAir: TileView
    private lateinit var tData: TileView
    private lateinit var tWifi: TileView
    private lateinit var tBt: TileView
    private lateinit var tRot: TileView
    private lateinit var tTorch: TileView
    private lateinit var mediaTitle: TextView
    private var torchId: String? = null
    private var torchOn = false

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) {
                torchOn = enabled
                tTorch.active = enabled
            }
        }
    }

    init {
        val pad = dp(20)
        val gap = dp(12)
        val cell = (screenW - 2 * pad - 3 * gap) / 4
        val gridW = 4 * cell + 3 * gap
        val gridH = 5 * cell + 4 * gap

        view.orientation = LinearLayout.VERTICAL
        val r = dp(28).toFloat()
        view.background = GradientDrawable().apply {
            setColor(0xEB101014.toInt())
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        view.setPadding(pad, dp(20), pad, 0)
        view.isClickable = true
        view.setOnTouchListener(svc.closeTouch(false))

        val grid = FrameLayout(ctx)

        fun place(v: View, col: Int, row: Int, sc: Int = 1, sr: Int = 1) {
            val lp = FrameLayout.LayoutParams(cell * sc + gap * (sc - 1), cell * sr + gap * (sr - 1))
            lp.leftMargin = col * (cell + gap)
            lp.topMargin = row * (cell + gap)
            grid.addView(v, lp)
        }

        // ---- connectivity block (2 x 2)
        val block = FrameLayout(ctx)
        block.background = glassBg(0x33FFFFFF, dp(26).toFloat())
        val bs = cell * 2 + gap
        val inset = dp(10)
        val cs = (bs - 3 * inset) / 2
        tAir = TileView(ctx, Icons.AIRPLANE, true, 0xFFFF9F0A.toInt())
        tData = TileView(ctx, Icons.DATA, true, 0xFF30D158.toInt())
        tWifi = TileView(ctx, Icons.WIFI, true, 0xFF0A84FF.toInt())
        tBt = TileView(ctx, Icons.BT, true, 0xFF0A84FF.toInt())
        fun bplace(v: View, c: Int, rr: Int) {
            val lp = FrameLayout.LayoutParams(cs, cs)
            lp.leftMargin = inset + c * (cs + inset)
            lp.topMargin = inset + rr * (cs + inset)
            block.addView(v, lp)
        }
        bplace(tAir, 0, 0); bplace(tData, 1, 0); bplace(tWifi, 0, 1); bplace(tBt, 1, 1)
        tAir.setOnClickListener { svc.startSafe(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }
        tData.setOnClickListener { svc.startSafe(internetIntent()) }
        tWifi.setOnClickListener {
            if (Build.VERSION.SDK_INT < 29) {
                wifi.isWifiEnabled = !wifi.isWifiEnabled
                main.postDelayed({ refreshStates() }, 600)
            } else {
                svc.startSafe(internetIntent())
            }
        }
        tBt.setOnClickListener { svc.startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        place(block, 0, 0, 2, 2)

        // ---- media (2 x 2)
        val media = FrameLayout(ctx)
        media.background = glassBg(0x33FFFFFF, dp(26).toFloat())
        mediaTitle = TextView(ctx)
        mediaTitle.text = "Not Playing"
        mediaTitle.setTextColor(0xB3FFFFFF.toInt())
        mediaTitle.textSize = 15f
        mediaTitle.gravity = Gravity.CENTER
        mediaTitle.maxLines = 2
        mediaTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        mediaTitle.setPadding(dp(12), 0, dp(12), 0)
        media.addView(mediaTitle, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(32)
        })
        val ctl = LinearLayout(ctx)
        ctl.orientation = LinearLayout.HORIZONTAL
        ctl.gravity = Gravity.CENTER
        val prev = TileView(ctx, Icons.PREV, false, Color.WHITE, true)
        val play = TileView(ctx, Icons.PLAY, false, Color.WHITE, true)
        val next = TileView(ctx, Icons.NEXT, false, Color.WHITE, true)
        prev.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        play.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        next.setOnClickListener { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        val bsz = dp(46)
        ctl.addView(prev, LinearLayout.LayoutParams(bsz, bsz))
        ctl.addView(play, LinearLayout.LayoutParams(bsz, bsz).apply { marginStart = dp(6); marginEnd = dp(6) })
        ctl.addView(next, LinearLayout.LayoutParams(bsz, bsz))
        media.addView(ctl, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(12) })
        place(media, 2, 0, 2, 2)

        // ---- rotation lock + screen mirroring
        tRot = TileView(ctx, Icons.ROTATE)
        tRot.setOnClickListener { toggleRotation() }
        place(tRot, 0, 2)
        val mirror = TileView(ctx, Icons.MIRROR)
        mirror.setOnClickListener { svc.startSafe(Intent(Settings.ACTION_CAST_SETTINGS)) }
        place(mirror, 1, 2)

        // ---- brightness + volume sliders
        val bright = VSlider(ctx, Icons.SUN)
        bright.value = currentBrightness()
        bright.onStart = {
            if (Settings.System.canWrite(ctx)) true
            else {
                Toast.makeText(ctx, "Allow \"Modify system settings\" for brightness", Toast.LENGTH_LONG).show()
                svc.startSafe(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))
                )
                false
            }
        }
        bright.onChange = { v -> setBrightness(v) }
        place(bright, 2, 2, 1, 2)

        val vol = VSlider(ctx, Icons.SPEAKER)
        val vmax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        vol.value = if (vmax > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / vmax else 0f
        vol.onChange = { v ->
            try { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * vmax), 0) } catch (e: Exception) { }
        }
        place(vol, 3, 2, 1, 2)

        // ---- Focus (wide)
        val focus = LinearLayout(ctx)
        focus.orientation = LinearLayout.HORIZONTAL
        focus.gravity = Gravity.CENTER_VERTICAL
        focus.setPadding(dp(12), 0, dp(12), 0)
        focus.background = glassBg(0x33FFFFFF, dp(26).toFloat())
        val moon = TileView(ctx, Icons.MOON, true, Color.WHITE, false)
        focus.addView(moon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val ft = TextView(ctx)
        ft.text = "Focus"
        ft.setTextColor(Color.WHITE)
        ft.textSize = 15f
        ft.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        ft.setPadding(dp(12), 0, 0, 0)
        focus.addView(ft, LinearLayout.LayoutParams(WRAP, WRAP))
        focus.setOnClickListener {
            val i = Intent("android.settings.ZEN_MODE_SETTINGS")
            if (!svc.startSafe(i)) svc.startSafe(Intent(Settings.ACTION_SOUND_SETTINGS))
        }
        place(focus, 0, 3, 2, 1)

        // ---- bottom row: flashlight, timer, calculator, camera
        tTorch = TileView(ctx, Icons.FLASH)
        tTorch.setOnClickListener { toggleTorch() }
        place(tTorch, 0, 4)

        val timer = TileView(ctx, Icons.TIMER)
        timer.setOnClickListener {
            svc.startSafe(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("open_timer", true)
            )
        }
        place(timer, 1, 4)

        val calc = TileView(ctx, Icons.CALC)
        calc.setOnClickListener {
            val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            if (!svc.startSafe(i)) Toast.makeText(ctx, "No calculator app found", Toast.LENGTH_SHORT).show()
        }
        place(calc, 2, 4)

        val camera = TileView(ctx, Icons.CAMERA)
        camera.setOnClickListener { svc.startSafe(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        place(camera, 3, 4)

        view.addView(grid, LinearLayout.LayoutParams(gridW, gridH))
        view.addView(svc.makeHandle(), LinearLayout.LayoutParams(MATCH, dp(40)))

        // torch tracking
        torchId = try {
            cam.cameraIdList.firstOrNull { id ->
                val ch = cam.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) { null }
        try { cam.registerTorchCallback(torchCb, main) } catch (e: Exception) { }

        refreshStates()
        loadNowPlaying()
    }

    fun release() {
        try { cam.unregisterTorchCallback(torchCb) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ actions

    private fun internetIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        else Intent(Settings.ACTION_WIRELESS_SETTINGS)

    private fun toggleTorch() {
        val id = torchId
        if (id == null) {
            Toast.makeText(ctx, "No flashlight found", Toast.LENGTH_SHORT).show()
            return
        }
        try { cam.setTorchMode(id, !torchOn) } catch (e: Exception) { }
    }

    private fun toggleRotation() {
        if (!Settings.System.canWrite(ctx)) {
            Toast.makeText(ctx, "Allow \"Modify system settings\" first", Toast.LENGTH_LONG).show()
            svc.startSafe(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))
            )
            return
        }
        val locked = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (locked) 1 else 0)
        refreshStates()
    }

    private fun currentBrightness(): Float = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    } catch (e: Exception) { 0.5f }

    private fun setBrightness(v: Float) {
        try {
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
                Math.round(v * 255f).coerceIn(1, 255)
            )
        } catch (e: Exception) { }
    }

    private fun mediaKey(code: Int) {
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { }
        main.postDelayed({ loadNowPlaying() }, 600)
    }

    // ------------------------------------------------------------------ state

    private fun refreshStates() {
        try {
            tAir.active = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (e: Exception) { }
        try {
            tRot.active = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        } catch (e: Exception) { }
        try { tWifi.active = wifi.isWifiEnabled } catch (e: Exception) { }
        try {
            tBt.active = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
        } catch (e: Exception) { }
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            tData.active = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) { }
    }

    private fun loadNowPlaying() {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = msm.getActiveSessions(ComponentName(ctx, NotifService::class.java))
            val md = sessions.firstOrNull()?.metadata
            val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            mediaTitle.text = if (t.isNullOrBlank()) "Not Playing" else t
        } catch (e: Exception) {
            // needs notification access; without it we cannot read the song name
            mediaTitle.text = "Not Playing"
        }
    }
}
