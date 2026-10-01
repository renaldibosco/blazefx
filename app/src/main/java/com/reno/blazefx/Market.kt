package com.reno.blazefx

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double)

data class Series(
    val candles: List<Candle>,
    val gmtOffset: Long,
    val price: Double,
    val open: Boolean,
    val hasVolume: Boolean
)

/** Live forex / metals / crypto candles from Yahoo Finance (free, no key). */
object Market {

    /** App name -> Yahoo symbol */
    val symbols: LinkedHashMap<String, String> = linkedMapOf(
        // Majors
        "EURUSD" to "EURUSD=X",
        "GBPUSD" to "GBPUSD=X",
        "USDJPY" to "JPY=X",
        "AUDUSD" to "AUDUSD=X",
        "USDCAD" to "CAD=X",
        "USDCHF" to "CHF=X",
        "NZDUSD" to "NZDUSD=X",
        // Crosses + INR
        "EURJPY" to "EURJPY=X",
        "GBPJPY" to "GBPJPY=X",
        "USDINR" to "INR=X",
        // Metals + crypto
        "XAUUSD" to "GC=F",
        "XAGUSD" to "SI=F",
        "BTCUSD" to "BTC-USD",
        "ETHUSD" to "ETH-USD"
    )

    /** Size of one pip for each market. */
    fun pip(name: String): Double = when {
        name.endsWith("JPY") -> 0.01
        name == "XAUUSD" -> 0.1
        name == "XAGUSD" -> 0.01
        name == "BTCUSD" || name == "ETHUSD" -> 1.0
        name == "USDINR" -> 0.01
        else -> 0.0001
    }

    fun digits(p: Double): Int {
        val a = kotlin.math.abs(p)
        return when {
            a < 10 -> 5
            a < 200 -> 3
            a < 1000 -> 2
            else -> 1
        }
    }

    /** Timeframe -> (interval, range, higher-timeframe interval, seconds) */
    data class Tf(val interval: String, val range: String, val htf: String, val seconds: Long)

    val timeframes: Map<String, Tf> = mapOf(
        "5m" to Tf("5m", "5d", "15m", 300),
        "15m" to Tf("15m", "1mo", "60m", 900),
        "1h" to Tf("60m", "1mo", "1d", 3600)
    )

    fun fetch(yahoo: String, interval: String, range: String): Series {
        val enc = URLEncoder.encode(yahoo, "UTF-8")
        var last: Exception? = null
        for (host in listOf("query1", "query2")) {
            try {
                val url = URL(
                    "https://$host.finance.yahoo.com/v8/finance/chart/$enc" +
                            "?interval=$interval&range=$range&includePrePost=false"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
                )
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val code = conn.responseCode
                if (code != 200) throw Exception("Market data error $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                return parse(body)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: Exception("Could not load market data")
    }

    private fun parse(body: String): Series {
        val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        val ts = result.optJSONArray("timestamp") ?: JSONArray()
        val q = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val o = q.getJSONArray("open")
        val h = q.getJSONArray("high")
        val l = q.getJSONArray("low")
        val c = q.getJSONArray("close")
        val v = q.optJSONArray("volume")

        val list = ArrayList<Candle>(ts.length())
        for (i in 0 until ts.length()) {
            if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
            val vol = if (v == null || v.isNull(i)) 0.0 else v.getDouble(i)
            list.add(Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i), vol))
        }

        val gmt = meta.optLong("gmtoffset", 0)
        val price = meta.optDouble("regularMarketPrice", list.lastOrNull()?.c ?: 0.0)
        val now = System.currentTimeMillis() / 1000
        val regular = meta.optJSONObject("currentTradingPeriod")?.optJSONObject("regular")
        // Forex trades nearly 24h: treat it as open if the last candle is recent
        val recent = list.isNotEmpty() && now - list.last().t < 75 * 60
        val inPeriod = regular != null &&
                now >= regular.optLong("start", 0) && now <= regular.optLong("end", 0)
        val hasVolume = list.isNotEmpty() && list.count { it.v > 0 } > list.size / 2
        return Series(list, gmt, price, recent || inPeriod, hasVolume)
    }
}
