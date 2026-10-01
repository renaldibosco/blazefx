package com.reno.blazefx

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Level(val name: String, val price: Double, val from: Int = 0)
data class Factor(val name: String, val ok: Boolean)

data class Signal(
    val index: Int,
    val t: Long,
    val bullish: Boolean,
    val level: Level,
    val factors: List<Factor>,
    val entry: Double,
    val stop: Double,
    val target: Double,
    val session: String
) {
    val score: Int get() = factors.count { it.ok }
}

data class Analysis(
    val series: Series,
    val todayStart: Int,
    val levels: List<Level>,
    val signals: List<Signal>,
    val prevClose: Double
)

/**
 * BlazeFX forex Breakout Failure (BOF) engine.
 *
 * BOF = price breaks a key level, then closes back on the other side (a liquidity sweep).
 *   Bearish: sweeps ABOVE a level, closes back BELOW.
 *   Bullish: sweeps BELOW a level, closes back ABOVE.
 *
 * Forex levels: PDH/PDL, PWH/PWL (previous week), Asian range high/low (00:00-07:00 UTC),
 * Camarilla H3/H4/L3/L4, recent swing high/low.
 *
 * 6-factor score:
 *   1. Higher timeframe also rejected the level
 *   2. Weak push: swept less than 0.5 ATR past the level
 *   3. RSI divergence or extreme
 *   4. Rejection candle
 *   5. Killzone timing (London 07-10 UTC, New York 12-15 UTC)
 *   6. Major level (PWH/PWL, PDH/PDL, Asian range or Camarilla H4/L4)
 */
object Engine {
    private const val TOL = 0.00005 // 0.005% beyond the level counts as a sweep

    fun dayKey(t: Long, gmt: Long): Long = Math.floorDiv(t + gmt, 86400L)
    private fun weekKey(day: Long): Long = Math.floorDiv(day + 3, 7L) // weeks start Monday
    private fun utcHour(t: Long): Int = (Math.floorMod(t, 86400L) / 3600).toInt()

    fun session(t: Long): String {
        val h = utcHour(t)
        return when {
            h in 7..9 -> "London killzone"
            h in 12..14 -> "New York killzone"
            h in 7..15 -> "London"
            h in 12..20 -> "New York"
            h in 0..8 -> "Tokyo"
            else -> "Sydney"
        }
    }

    private val major = setOf("PWH", "PWL", "PDH", "PDL", "ASH", "ASL", "H4", "L4")

    fun analyze(s: Series, htf: Series?, daily: Series?): Analysis {
        val cs = s.candles
        val n = cs.size
        if (n < 20) return Analysis(s, 0, emptyList(), emptyList(), cs.firstOrNull()?.o ?: s.price)

        val rsi = rsi(cs)
        val atr = atr(cs)

        val days = ArrayList<IntRange>()
        var start = 0
        for (i in 1..n) {
            if (i == n || dayKey(cs[i].t, s.gmtOffset) != dayKey(cs[start].t, s.gmtOffset)) {
                days.add(start until i)
                start = i
            }
        }
        // Skip tiny weekend stubs
        val tradingDays = days.filter { it.count() >= 6 }.ifEmpty { days }

        val signals = ArrayList<Signal>()
        var todayLevels: List<Level> = emptyList()
        var prevClose = cs.first().o

        for (d in tradingDays.indices) {
            if (d == 0) continue
            val range = tradingDays[d]
            val prev = tradingDays[d - 1]
            var pH = Double.NEGATIVE_INFINITY
            var pL = Double.POSITIVE_INFINITY
            for (i in prev) { pH = max(pH, cs[i].h); pL = min(pL, cs[i].l) }
            val pC = cs[prev.last].c
            val rng = pH - pL
            prevClose = pC

            val levels = arrayListOf(
                Level("PDH", pH), Level("PDL", pL),
                Level("H4", pC + rng * 1.1 / 2), Level("L4", pC - rng * 1.1 / 2),
                Level("H3", pC + rng * 1.1 / 4), Level("L3", pC - rng * 1.1 / 4)
            )

            // Previous week high / low from daily candles
            if (daily != null && daily.candles.isNotEmpty()) {
                val today = dayKey(cs[range.first].t, s.gmtOffset)
                val wk = weekKey(today)
                var wH = Double.NEGATIVE_INFINITY
                var wL = Double.POSITIVE_INFINITY
                for (dc in daily.candles) {
                    if (weekKey(dayKey(dc.t, daily.gmtOffset)) == wk - 1) {
                        wH = max(wH, dc.h); wL = min(wL, dc.l)
                    }
                }
                if (wH.isFinite() && wL.isFinite()) {
                    levels.add(Level("PWH", wH)); levels.add(Level("PWL", wL))
                }
            }

            // Asian session range (00:00-07:00 UTC), active once London opens
            val asian = range.filter { utcHour(cs[it].t) < 7 }
            val firstAfter = range.firstOrNull { utcHour(cs[it].t) >= 7 }
            if (asian.size >= 3 && firstAfter != null) {
                levels.add(Level("ASH", asian.maxOf { cs[it].h }, firstAfter))
                levels.add(Level("ASL", asian.minOf { cs[it].l }, firstAfter))
            }

            if (d == tradingDays.lastIndex) todayLevels = levels
            val h4 = levels[2].price
            val l4 = levels[3].price

            val lastSig = HashMap<String, Int>()
            for (i in range) {
                if (i < 5) continue
                val cands = ArrayList<Pair<Level, Int>>()
                for (lv in levels) if (i >= lv.from) cands.add(lv to 0)
                val a = max(0, i - 30)
                val b = i - 3
                if (b > a) {
                    var sh = Double.NEGATIVE_INFINITY
                    var sl = Double.POSITIVE_INFINITY
                    for (k in a..b) { sh = max(sh, cs[k].h); sl = min(sl, cs[k].l) }
                    cands.add(Level("Swing H", sh) to -1)
                    cands.add(Level("Swing L", sl) to 1)
                }
                for ((lv, dir) in cands) {
                    for (bull in booleanArrayOf(false, true)) {
                        if (dir == -1 && bull) continue
                        if (dir == 1 && !bull) continue
                        val bo = detect(cs, i, lv.price, bull, range.first) ?: continue
                        val key = lv.name + bull
                        val lastI = lastSig[key]
                        if (lastI != null && i - lastI < 6) continue
                        lastSig[key] = i
                        signals.add(score(cs, i, bo, lv, bull, rsi, atr, htf, h4, l4))
                    }
                }
            }
        }

        val best = signals.groupBy { it.t to it.bullish }.map { (_, v) -> v.maxBy { it.score } }
            .sortedBy { it.t }

        return Analysis(s, tradingDays.last().first, todayLevels, best, prevClose)
    }

    private fun detect(cs: List<Candle>, i: Int, L: Double, bull: Boolean, dayFirst: Int): Int? {
        val c = cs[i]
        if (!bull) {
            if (c.h > L * (1 + TOL) && c.c < L && cs[i - 1].c < L) return i
            if (c.c < L) {
                for (k in 1..3) {
                    val j = i - k
                    if (j - 1 < dayFirst) break
                    var held = true
                    for (m in j until i) if (cs[m].c <= L) { held = false; break }
                    if (held && cs[j].c > L * (1 + TOL / 2) && cs[j - 1].c <= L) return j
                }
            }
        } else {
            if (c.l < L * (1 - TOL) && c.c > L && cs[i - 1].c > L) return i
            if (c.c > L) {
                for (k in 1..3) {
                    val j = i - k
                    if (j - 1 < dayFirst) break
                    var held = true
                    for (m in j until i) if (cs[m].c >= L) { held = false; break }
                    if (held && cs[j].c < L * (1 - TOL / 2) && cs[j - 1].c >= L) return j
                }
            }
        }
        return null
    }

    private fun score(
        cs: List<Candle>, i: Int, bo: Int, lv: Level, bull: Boolean,
        rsi: DoubleArray, atr: DoubleArray, htf: Series?, h4: Double, l4: Double
    ): Signal {
        val c = cs[i]
        val L = lv.price
        var ext = if (bull) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        var extI = bo
        for (k in bo..i) {
            if (!bull && cs[k].h > ext) { ext = cs[k].h; extI = k }
            if (bull && cs[k].l < ext) { ext = cs[k].l; extI = k }
        }

        // 1. Higher timeframe rejection
        var f1 = false
        if (htf != null) {
            val hc = htf.candles.lastOrNull { it.t <= c.t }
            if (hc != null) f1 = if (bull) hc.l < L && hc.c > L else hc.h > L && hc.c < L
        }

        // 2. Weak push past the level
        val f2 = abs(ext - L) < 0.5 * atr[i]

        // 3. RSI divergence / extreme
        var f3 = if (bull) rsi[extI] <= 30 else rsi[extI] >= 70
        if (!f3) {
            val a = max(0, extI - 30)
            val b = extI - 3
            if (b > a) {
                var p = a
                for (k in a..b) {
                    if (!bull && cs[k].h > cs[p].h) p = k
                    if (bull && cs[k].l < cs[p].l) p = k
                }
                f3 = if (bull) cs[extI].l <= cs[p].l && rsi[extI] > rsi[p] + 1
                else cs[extI].h >= cs[p].h && rsi[extI] < rsi[p] - 1
            }
        }

        // 4. Rejection candle
        val body = abs(c.c - c.o)
        val rng = c.h - c.l
        val f4 = rng > 0 && (if (bull) {
            val wick = min(c.o, c.c) - c.l
            (wick >= 0.5 * rng && wick >= 1.5 * body) || (c.c > c.o && body >= 0.6 * rng)
        } else {
            val wick = c.h - max(c.o, c.c)
            (wick >= 0.5 * rng && wick >= 1.5 * body) || (c.c < c.o && body >= 0.6 * rng)
        })

        // 5. Killzone timing
        val sess = session(c.t)
        val f5 = sess.endsWith("killzone")

        // 6. Major level
        val f6 = lv.name in major || (if (bull) ext < l4 && c.c > l4 else ext > h4 && c.c < h4)

        val factors = listOf(
            Factor("Higher timeframe rejected", f1),
            Factor("Weak sweep (< 0.5 ATR)", f2),
            Factor("RSI divergence / extreme", f3),
            Factor("Rejection candle", f4),
            Factor("Killzone timing", f5),
            Factor("Major level (PW/PD/Asian/H4L4)", f6)
        )

        val buffer = 0.1 * atr[i]
        val stop = if (bull) ext - buffer else ext + buffer
        val risk = abs(c.c - stop)
        val target = if (bull) c.c + 2 * risk else c.c - 2 * risk
        return Signal(i, c.t, bull, lv, factors, c.c, stop, target, sess)
    }

    private fun rsi(cs: List<Candle>, p: Int = 14): DoubleArray {
        val r = DoubleArray(cs.size) { 50.0 }
        var g = 0.0
        var l = 0.0
        for (i in 1 until cs.size) {
            val ch = cs[i].c - cs[i - 1].c
            val up = max(ch, 0.0)
            val dn = max(-ch, 0.0)
            if (i <= p) {
                g += up; l += dn
                if (i == p) { g /= p; l /= p; r[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l) }
            } else {
                g = (g * (p - 1) + up) / p
                l = (l * (p - 1) + dn) / p
                r[i] = if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
            }
        }
        return r
    }

    private fun atr(cs: List<Candle>, p: Int = 14): DoubleArray {
        val a = DoubleArray(cs.size)
        var cur = 0.0
        for (i in cs.indices) {
            val tr = if (i == 0) cs[i].h - cs[i].l else max(
                cs[i].h - cs[i].l,
                max(abs(cs[i].h - cs[i - 1].c), abs(cs[i].l - cs[i - 1].c))
            )
            cur = if (i < p) (cur * i + tr) / (i + 1) else (cur * (p - 1) + tr) / p
            a[i] = cur
        }
        return a
    }

    /** Everything the screen needs, as JSON (times shown in IST). */
    fun toJson(name: String, tf: String, an: Analysis): String {
        val s = an.series
        val off = 19800L
        val root = JSONObject()
        root.put("name", name).put("tf", tf).put("open", s.open).put("pip", Market.pip(name))
        val price = if (s.candles.isNotEmpty()) s.candles.last().c else s.price
        root.put("price", price).put("prevClose", an.prevClose).put("updated", System.currentTimeMillis())

        val candles = JSONArray()
        for (c in s.candles) {
            candles.put(
                JSONObject().put("time", c.t + off).put("open", c.o).put("high", c.h)
                    .put("low", c.l).put("close", c.c)
            )
        }
        root.put("candles", candles)

        val lv = JSONArray()
        for (l in an.levels) lv.put(JSONObject().put("name", l.name).put("price", l.price))
        root.put("levels", lv)

        val sigs = JSONArray()
        val todayT = if (s.candles.isNotEmpty()) s.candles[an.todayStart].t else 0L
        for (g in an.signals) {
            val f = JSONArray()
            for (x in g.factors) f.put(JSONObject().put("name", x.name).put("ok", x.ok))
            sigs.put(
                JSONObject().put("time", g.t + off).put("bull", g.bullish)
                    .put("level", g.level.name).put("levelPrice", g.level.price)
                    .put("score", g.score).put("factors", f).put("session", g.session)
                    .put("entry", g.entry).put("stop", g.stop).put("target", g.target)
                    .put("today", g.t >= todayT)
            )
        }
        root.put("signals", sigs)
        return root.toString()
    }
}
