package com.classic.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

class Prefs(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var timerEnd: Long
        get() = sp.getLong("tEnd", 0L)
        set(v) { sp.edit().putLong("tEnd", v).apply() }

    var timerLabel: String
        get() = sp.getString("tLabel", "Timer") ?: "Timer"
        set(v) { sp.edit().putString("tLabel", v).apply() }

    var timerSound: String?
        get() = sp.getString("tSound", null)
        set(v) { sp.edit().putString("tSound", v).apply() }

    var timerDone: Boolean
        get() = sp.getBoolean("tDone", false)
        set(v) { sp.edit().putBoolean("tDone", v).apply() }

    // null = never customised (use defaults). Empty list = user removed everything.
    var dock: List<String>?
        get() = sp.getString("dock", null)?.split("|")?.filter { it.isNotEmpty() }
        set(v) { sp.edit().putString("dock", v?.joinToString("|")).apply() }

    var note: String
        get() = sp.getString("note", "") ?: ""
        set(v) { sp.edit().putString("note", v).apply() }

    var pages: String
        get() = sp.getString("pages", "0") ?: "0"
        set(v) { sp.edit().putString("pages", v).apply() }

    var home: String?
        get() = sp.getString("home", null)
        set(v) { sp.edit().putString("home", v).apply() }

    var hidden: Set<String>
        get() = (sp.getString("hidden", "") ?: "").split("|").filter { it.isNotEmpty() }.toSet()
        set(v) { sp.edit().putString("hidden", v.joinToString("|")).apply() }

    var iconDp: Int
        get() = sp.getInt("iconDp", 52)
        set(v) { sp.edit().putInt("iconDp", v).apply() }

    var cols: Int
        get() = sp.getInt("cols", 4)
        set(v) { sp.edit().putInt("cols", v).apply() }

    var accent: Int
        get() = sp.getInt("accent", 0xFFFF3B30.toInt())
        set(v) { sp.edit().putInt("accent", v).apply() }

    var showYear: Boolean
        get() = sp.getBoolean("showYear", true)
        set(v) { sp.edit().putBoolean("showYear", v).apply() }

    var showNotes: Boolean
        get() = sp.getBoolean("showNotes", true)
        set(v) { sp.edit().putBoolean("showNotes", v).apply() }

    var labels: Boolean
        get() = sp.getBoolean("labels", true)
        set(v) { sp.edit().putBoolean("labels", v).apply() }

    var blackBg: Boolean
        get() = sp.getBoolean("blackBg", false)
        set(v) { sp.edit().putBoolean("blackBg", v).apply() }
}

class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, i: Intent) {
        if (i.action == ACTION_STOP) { stop(); return }
        val label = i.getStringExtra("label") ?: "Timer"
        val sound = i.getStringExtra("sound")
        val p = Prefs(c)
        p.timerEnd = 0L
        p.timerDone = true
        play(c, sound)
        vibrate(c)
        notifyDone(c, label)
    }

    private fun vibrate(c: Context) {
        val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        @Suppress("DEPRECATION")
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 300, 700, 300, 700), -1))
    }

    private fun notifyDone(c: Context, label: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel("timer", "Timer", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
        val open = PendingIntent.getActivity(
            c, 3,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getBroadcast(
            c, 4,
            Intent(c, TimerReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, "timer")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Time's up")
            .setContentIntent(open)
            .setDeleteIntent(stopPi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        nm.notify(42, n)
    }

    companion object {
        const val ACTION_FIRE = "com.classic.launcher.TIMER_FIRE"
        const val ACTION_STOP = "com.classic.launcher.TIMER_STOP"

        private var player: MediaPlayer? = null
        private val main = Handler(Looper.getMainLooper())
        private val autoStop = Runnable { stop() }

        fun play(c: Context, sound: String?) {
            stop()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val candidates = listOfNotNull(
                sound?.let { Uri.parse(it) },
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
            for (u in candidates) {
                var mp: MediaPlayer? = null
                try {
                    mp = MediaPlayer()
                    mp.setAudioAttributes(attrs)
                    mp.setDataSource(c.applicationContext, u)
                    mp.isLooping = true
                    mp.prepare()
                    mp.start()
                    player = mp
                    main.postDelayed(autoStop, 30_000L)
                    return
                } catch (e: Exception) {
                    try { mp?.release() } catch (e2: Exception) { }
                }
            }
        }

        fun stop() {
            main.removeCallbacks(autoStop)
            val p = player ?: return
            player = null
            try { if (p.isPlaying) p.stop() } catch (e: Exception) { }
            try { p.release() } catch (e: Exception) { }
        }
    }
}
