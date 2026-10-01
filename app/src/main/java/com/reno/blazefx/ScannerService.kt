package com.reno.blazefx

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("blazefx", Context.MODE_PRIVATE)
    fun alerts(c: Context) = sp(c).getBoolean("alerts", false)
    fun setAlerts(c: Context, on: Boolean) = sp(c).edit().putBoolean("alerts", on).apply()
    fun threshold(c: Context) = sp(c).getInt("threshold", 4)
    fun setThreshold(c: Context, n: Int) = sp(c).edit().putInt("threshold", n).apply()
    fun killzoneOnly(c: Context) = sp(c).getBoolean("killzoneOnly", true)
    fun setKillzoneOnly(c: Context, on: Boolean) = sp(c).edit().putBoolean("killzoneOnly", on).apply()
    fun wasNotified(c: Context, key: String) = sp(c).getStringSet("sent", emptySet())!!.contains(key)
    fun markNotified(c: Context, key: String) {
        val set = HashSet(sp(c).getStringSet("sent", emptySet())!!)
        if (set.size > 300) set.clear()
        set.add(key)
        sp(c).edit().putStringSet("sent", set).apply()
    }
}

/** Runs in the background and sends a phone alert when a strong BOF appears. */
class ScannerService : Service() {

    companion object {
        const val CH_LIVE = "scanner_live"
        const val CH_ALERT = "bof_alerts"

        fun start(c: Context) {
            val i = Intent(c, ScannerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ScannerService::class.java))
        }

        fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_LIVE, "Live scanner", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "BOF alerts", NotificationManager.IMPORTANCE_HIGH)
                    .apply { enableVibration(true) }
            )
        }
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val n = liveNotification("Starting scanner…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        releaseLock()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun liveNotification(text: String): Notification =
        Notification.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("BlazeFX scanner")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private val dailyCache = HashMap<String, Pair<Long, Series>>()

    private fun daily(sym: String): Series? {
        val now = System.currentTimeMillis()
        dailyCache[sym]?.let { if (now - it.first < 60 * 60 * 1000L) return it.second }
        return try {
            Market.fetch(sym, "1d", "3mo").also { dailyCache[sym] = now to it }
        } catch (e: Exception) { dailyCache[sym]?.second }
    }

    private fun loop() {
        val nm = getSystemService(NotificationManager::class.java)
        while (running) {
            var anyOpen = false
            var status: String
            val kzOnly = Prefs.killzoneOnly(this)
            val inKz = Engine.session(System.currentTimeMillis() / 1000).endsWith("killzone")
            try {
                if (kzOnly && !inKz) {
                    status = "Waiting for London / NY killzone"
                } else {
                    val tf = Market.timeframes.getValue("5m")
                    for ((name, sym) in Market.symbols) {
                        if (!running) break
                        val s = try { Market.fetch(sym, tf.interval, tf.range) } catch (e: Exception) { continue }
                        if (!s.open) continue
                        anyOpen = true
                        val htf = try { Market.fetch(sym, tf.htf, tf.range) } catch (e: Exception) { null }
                        val an = Engine.analyze(s, htf, daily(sym))
                        val lastT = s.candles.lastOrNull()?.t ?: continue
                        for (g in an.signals) {
                            if (g.t < lastT - 2 * tf.seconds) continue
                            if (g.score < Prefs.threshold(this)) continue
                            val key = "$name-${g.t}-${g.level.name}-${g.bullish}"
                            if (Prefs.wasNotified(this, key)) continue
                            Prefs.markNotified(this, key)
                            if (canNotify()) nm.notify(key.hashCode(), alert(name, g))
                        }
                    }
                    status = if (anyOpen) "🔥 Scanning ${Market.symbols.size} markets · alerts at ${Prefs.threshold(this)}+/6"
                    else "Markets closed (weekend) · waiting"
                }
            } catch (e: Exception) {
                status = "Network issue, retrying…"
            }
            if (canNotify()) nm.notify(1, liveNotification(status))
            if (anyOpen) holdLock() else releaseLock()
            try {
                Thread.sleep(if (anyOpen) 60_000L else 3 * 60_000L)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun alert(name: String, g: Signal): Notification {
        val dir = if (g.bullish) "🟢 BULLISH" else "🔴 BEARISH"
        val nuclear = if (g.score == 6) "☢️ NUCLEAR " else ""
        val title = "$nuclear$name $dir BOF · ${g.score}/6"
        val digits = Market.digits(g.entry)
        fun f(x: Double) = String.format("%.${digits}f", x)
        val pip = Market.pip(name)
        val slP = Math.round(Math.abs(g.entry - g.stop) / pip)
        val tpP = Math.round(Math.abs(g.target - g.entry) / pip)
        val text = "Swept ${g.level.name} ${f(g.level.price)} · ${g.session}\nEntry ${f(g.entry)} · SL ${f(g.stop)} ($slP pips) · TP ${f(g.target)} ($tpP pips)"
        return Notification.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun holdLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlazeFX:scan").apply {
            acquire(3 * 60 * 60 * 1000L)
        }
    }

    private fun releaseLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
    }
}

/** Restart the scanner after the phone reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.alerts(context)) {
            try { ScannerService.start(context) } catch (_: Exception) {}
        }
    }
}
