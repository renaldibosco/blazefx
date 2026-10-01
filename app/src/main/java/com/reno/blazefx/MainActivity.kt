package com.reno.blazefx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.parseColor("#0A0503"))
        }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0503"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // Links (e.g. the TradingView logo) open in the browser
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val url = req.url.toString()
                    if (url.startsWith("file:")) return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        web.loadUrl("file:///android_asset/index.html")

        ScannerService.channels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Prefs.serviceWanted(this)) {
            try { ScannerService.start(this) } catch (_: Exception) {}
        }
    }

    private fun build(name: String, tf: String): String {
        val sym = Market.symbols[name] ?: throw Exception("Unknown symbol")
        val t = Market.timeframes[tf] ?: Market.timeframes.getValue("5m")
        val crypto = Live.isCrypto(name)
        val raw = (if (crypto) Live.cryptoSeries(name, t.interval) else null) ?: Market.fetch(sym, t.interval, t.range)
        val (s, live) = Live.patch(name, raw, t.seconds)
        val htfRaw = try {
            (if (crypto) Live.cryptoSeries(name, t.htf) else null)
                ?: Market.fetch(sym, t.htf, if (t.htf == "1d") "3mo" else t.range)
        } catch (e: Exception) { null }
        val htf = if (htfRaw != null && t.htf != "1d") Live.patch(name, htfRaw, if (t.htf == "60m") 3600 else 900).first else htfRaw
        val daily = try { (if (crypto) Live.cryptoSeries(name, "1d") else null) ?: Market.fetch(sym, "1d", "3mo") } catch (e: Exception) { null }
        return Engine.toJson(name, tf, Engine.analyze(s, htf, daily), live)
    }

    private fun syncService() {
        runOnUiThread {
            try {
                if (Prefs.serviceWanted(this)) ScannerService.start(this) else ScannerService.stop(this)
            } catch (_: Exception) {}
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun load(name: String, tf: String, cb: String) {
            Thread {
                val json = try {
                    build(name, tf)
                } catch (e: Exception) {
                    JSONObject().put("error", e.message ?: "No internet").toString()
                }
                web.post { web.evaluateJavascript("window.onData(${JSONObject.quote(cb)}, $json)", null) }
            }.start()
        }

        @JavascriptInterface
        fun tick(name: String, cb: String) {
            Thread {
                val p = Live.spot(name)
                val js = if (p == null) "null" else p.toString()
                web.post { web.evaluateJavascript("window.onTick(${JSONObject.quote(cb)}, $js)", null) }
            }.start()
        }

        @JavascriptInterface
        fun alertsOn(): Boolean = Prefs.alerts(this@MainActivity)

        @JavascriptInterface
        fun setAlerts(on: Boolean) {
            Prefs.setAlerts(this@MainActivity, on)
            syncService()
        }

        @JavascriptInterface
        fun tvOn(): Boolean = Prefs.tvOn(this@MainActivity)

        @JavascriptInterface
        fun setTvOn(on: Boolean) {
            Prefs.setTvOn(this@MainActivity, on)
            syncService()
        }

        @JavascriptInterface
        fun topic(): String = Prefs.topic(this@MainActivity)

        @JavascriptInterface
        fun setTopic(t: String): Boolean {
            val ok = Prefs.setTopic(this@MainActivity, t)
            if (ok) syncService()
            return ok
        }

        @JavascriptInterface
        fun tvList(): String = Prefs.tvList(this@MainActivity)

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("BlazeFX", text))
                android.widget.Toast.makeText(this@MainActivity, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun threshold(): Int = Prefs.threshold(this@MainActivity)

        @JavascriptInterface
        fun setThreshold(n: Int) = Prefs.setThreshold(this@MainActivity, n)

        @JavascriptInterface
        fun killzoneOnly(): Boolean = Prefs.killzoneOnly(this@MainActivity)

        @JavascriptInterface
        fun setKillzoneOnly(on: Boolean) = Prefs.setKillzoneOnly(this@MainActivity, on)

        @JavascriptInterface
        fun batterySettings() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
    }
}
