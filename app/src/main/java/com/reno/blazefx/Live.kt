package com.reno.blazefx

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time prices.
 *  - Forex, gold, silver: Swissquote public spot quotes (bid/ask, updated live)
 *  - BTC, ETH: Binance (live price + real candles)
 * Yahoo history is extended with live ticks, and gold/silver futures history is shifted to spot.
 */
object Live {
    private val swissquote = mapOf(
        "XAUUSD" to "XAU/USD", "XAGUSD" to "XAG/USD",
        "EURUSD" to "EUR/USD", "GBPUSD" to "GBP/USD", "USDJPY" to "USD/JPY",
        "AUDUSD" to "AUD/USD", "USDCAD" to "USD/CAD", "USDCHF" to "USD/CHF",
        "NZDUSD" to "NZD/USD", "EURJPY" to "EUR/JPY", "GBPJPY" to "GBP/JPY",
        "USDINR" to "USD/INR"
    )
    private val binance = mapOf("BTCUSD" to "BTCUSDT", "ETHUSD" to "ETHUSDT")
    private val futures = setOf("XAUUSD", "XAGUSD")

    private val ticks = HashMap<String, ArrayDeque<Pair<Long, Double>>>() // seconds, price
    private val cache = HashMap<String, Pair<Long, Double>>()             // millis, price
    private val offsets = HashMap<String, Double>()                        // futures -> spot shift

    /** Shift a futures-based series (e.g. daily candles) onto spot prices, using the latest known offset. */
    @Synchronized
    fun toSpot(name: String, s: Series?): Series? {
        if (s == null || name !in futures) return s
        val off = offsets[name] ?: return s
        return Series(s.candles.map { Candle(it.t, it.o + off, it.h + off, it.l + off, it.c + off, it.v) }, s.gmtOffset, s.price + off, s.open, s.hasVolume)
    }

    fun isCrypto(name: String) = name in binance

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) BlazeFX")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    @Synchronized
    private fun record(name: String, t: Long, p: Double) {
        val q = ticks.getOrPut(name) { ArrayDeque() }
        q.addLast(t to p)
        while (q.size > 6000 || (q.isNotEmpty() && q.first().first < t - 8 * 3600)) q.removeFirst()
    }

    @Synchronized
    private fun ticksSince(name: String, t: Long): List<Pair<Long, Double>> =
        ticks[name]?.filter { it.first >= t } ?: emptyList()

    /** Latest real-time price, or null if unavailable / market closed. */
    fun spot(name: String): Double? {
        val now = System.currentTimeMillis()
        synchronized(this) { cache[name]?.let { if (now - it.first < 1500) return it.second } }
        val p = try {
            when (name) {
                in binance -> JSONObject(get("https://api.binance.com/api/v3/ticker/price?symbol=${binance[name]}"))
                    .getString("price").toDouble()
                in swissquote -> {
                    val arr = JSONArray(get("https://forex-data-feed.swissquote.com/public-quotes/bboquotes/instrument/${swissquote[name]}"))
                    val first = arr.getJSONObject(0)
                    val ts = first.optLong("ts", now)
                    if (now - ts > 10 * 60 * 1000L) null // stale quote = market closed
                    else {
                        val pr = first.getJSONArray("spreadProfilePrices").getJSONObject(0)
                        (pr.getDouble("bid") + pr.getDouble("ask")) / 2
                    }
                }
                else -> null
            }
        } catch (e: Exception) { null } ?: return null
        synchronized(this) { cache[name] = now to p }
        record(name, now / 1000, p)
        return p
    }

    /** Real Binance candles for crypto (interval: 5m, 15m, 60m, 1d). */
    fun cryptoSeries(name: String, interval: String): Series? {
        val sym = binance[name] ?: return null
        val iv = when (interval) { "60m" -> "1h"; else -> interval }
        return try {
            val arr = JSONArray(get("https://api.binance.com/api/v3/klines?symbol=$sym&interval=$iv&limit=1000"))
            val list = ArrayList<Candle>(arr.length())
            for (i in 0 until arr.length()) {
                val k = arr.getJSONArray(i)
                list.add(
                    Candle(
                        k.getLong(0) / 1000, k.getString(1).toDouble(), k.getString(2).toDouble(),
                        k.getString(3).toDouble(), k.getString(4).toDouble(), k.getString(5).toDouble()
                    )
                )
            }
            if (list.isEmpty()) null else Series(list, 0, list.last().c, true, true)
        } catch (e: Exception) { null }
    }

    /**
     * Make a series real-time: shift futures history to spot, then extend the
     * latest candles with live ticks. Returns the series and whether live data was used.
     */
    fun patch(name: String, s: Series, tfSec: Long): Pair<Series, Boolean> {
        val price = spot(name) ?: return s to false
        if (s.candles.isEmpty()) return s to false
        var cs = s.candles
        if (name in futures) {
            val off = price - cs.last().c
            synchronized(this) { offsets[name] = off }
            cs = cs.map { Candle(it.t, it.o + off, it.h + off, it.l + off, it.c + off, it.v) }
        }
        val out = ArrayList(cs)
        for ((t, p) in ticksSince(name, out.last().t)) {
            val start = Math.floorDiv(t, tfSec) * tfSec
            val last = out.last()
            if (start <= last.t) {
                out[out.size - 1] = Candle(last.t, last.o, max(last.h, p), min(last.l, p), p, last.v)
            } else {
                out.add(Candle(start, last.c, max(last.c, p), min(last.c, p), p, 0.0))
            }
        }
        return Series(out, s.gmtOffset, price, true, s.hasVolume) to true
    }
}
