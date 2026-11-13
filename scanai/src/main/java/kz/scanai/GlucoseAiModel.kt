package kz.scanai

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device port of the Ridge model trained in glucose-50/train_public.py
 * (open Zenodo 20577959 set, leave-one-person-out error about 0.7 mmol/L, practically the
 * same as always answering the population mean). Output is an ESTIMATE in mmol/L.
 */
object GlucoseAiModel {
    const val ERROR_MMOL = 0.7
    const val MIN_SECONDS = 28.0
    private const val MIN_BEATS = 14

    private val MU = doubleArrayOf(2.5668, 0.4747, -1.3673, 0.7329, 0.4774, 0.4673, 68.3197, 0.416, 0.4643,
        0.1002, 0.0159, 0.3951, 259.6875, 184.9141, 0.9211, 5.204, 0.3978)
    private val SD = doubleArrayOf(0.6158, 0.3582, 0.2156, 0.291, 0.061, 0.0922, 10.6435, 0.2755, 0.2537,
        0.0929, 0.1028, 0.0878, 199.669, 125.526, 0.0926, 1.7827, 0.1196)
    private val W = doubleArrayOf(-0.0367, 0.0256, 0.0292, 0.0628, -0.0204, -0.053, 0.051, -0.0537, 0.0521,
        -0.0078, -0.0338, -0.0708, 0.0226, 0.0055, -0.0182, 0.0082, -0.0116)
    private const val BIAS = 5.6182
    private val NAMES = listOf("amp", "amp_cv", "apg_ba", "apg_da", "area_ratio", "fall", "hr", "p_05_1", "p_1_2",
        "p_2_4", "p_4_8", "rise", "rmssd", "sdnn", "sqi", "vpg_peak", "w50")

    data class Result(val mmol: Double, val heartRate: Double, val quality: Double,
                      val features: Map<String, Double> = emptyMap())

    /** green: raw PPG samples (uniform rate fs, Hz). Returns null when the signal is not good enough. */
    fun estimate(green: DoubleArray, fs: Double): Result? = try { compute(green, fs) } catch (_: Exception) { null }

    private fun compute(x: DoubleArray, fs: Double): Result? {
        if (x.size < MIN_SECONDS * fs) return null
        val filtered = bandpass(x, fs, 0.7, 8.0)
        val trim = (2 * fs).toInt()
        if (filtered.size <= trim * 2 + 10) return null
        val xb = filtered.copyOfRange(trim, filtered.size - trim)
        val sd = stdev(xb)
        if (sd < 1e-9) return null
        val f = beatFeatures(xb, fs) ?: return null
        f["amp"] = (f["amp"] ?: return null) / sd
        val bands = bandPowers(xb, fs)
        f["p_05_1"] = bands[0]; f["p_1_2"] = bands[1]; f["p_2_4"] = bands[2]; f["p_4_8"] = bands[3]
        var y = BIAS
        for (i in NAMES.indices) {
            val v = f[NAMES[i]]?.takeIf { !it.isNaN() } ?: MU[i]
            y += ((v - MU[i]) / SD[i]) * W[i]
        }
        return Result(y.coerceIn(2.5, 12.0), f["hr"] ?: 0.0, f["sqi"] ?: 0.0, HashMap(f))
    }

    private fun stdev(a: DoubleArray): Double {
        if (a.isEmpty()) return 0.0
        val m = a.average()
        return sqrt(a.sumOf { (it - m) * (it - m) } / a.size)
    }

    /** Zero-phase band-pass through a direct DFT (windows are short, ~1500 samples). */
    private fun bandpass(x: DoubleArray, fs: Double, lo: Double, hi: Double): DoubleArray {
        val n = x.size
        val mean = x.average()
        val cosT = DoubleArray(n) { cos(2 * PI * it / n) }
        val sinT = DoubleArray(n) { sin(2 * PI * it / n) }
        val kLo = max(1, kotlin.math.ceil(lo * n / fs).toInt())
        val kHi = min(n / 2, kotlin.math.floor(hi * n / fs).toInt())
        val re = DoubleArray(kHi + 1); val im = DoubleArray(kHi + 1)
        for (k in kLo..kHi) {
            var r = 0.0; var i = 0.0
            for (t in 0 until n) {
                val idx = ((k.toLong() * t) % n).toInt()
                val v = x[t] - mean
                r += v * cosT[idx]; i -= v * sinT[idx]
            }
            re[k] = r; im[k] = i
        }
        val out = DoubleArray(n)
        for (t in 0 until n) {
            var s = 0.0
            for (k in kLo..kHi) {
                val idx = ((k.toLong() * t) % n).toInt()
                s += re[k] * cosT[idx] - im[k] * sinT[idx]
            }
            out[t] = 2.0 * s / n
        }
        return out
    }

    private fun bandPowers(x: DoubleArray, fs: Double): DoubleArray {
        val n = x.size
        val w = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        val cosT = DoubleArray(n) { cos(2 * PI * it / n) }
        val sinT = DoubleArray(n) { sin(2 * PI * it / n) }
        val edges = doubleArrayOf(0.5, 1.0, 2.0, 4.0, 8.0)
        val p = DoubleArray(4); var total = 1e-12
        val kLo = kotlin.math.ceil(0.5 * n / fs).toInt(); val kHi = min(n / 2, kotlin.math.floor(8.0 * n / fs).toInt())
        for (k in kLo..kHi) {
            var r = 0.0; var i = 0.0
            for (t in 0 until n) {
                val idx = ((k.toLong() * t) % n).toInt()
                r += w[t] * cosT[idx]; i -= w[t] * sinT[idx]
            }
            val pw = r * r + i * i
            val f = k * fs / n
            total += pw
            for (b in 0 until 4) if (f >= edges[b] && f < edges[b + 1]) p[b] += pw
        }
        return DoubleArray(4) { p[it] / total }
    }

    private fun findPeaks(s: DoubleArray, minDist: Int): List<Int> {
        val thr = 0.25 * stdev(s)
        val cand = ArrayList<Int>()
        for (i in 1 until s.size - 1) if (s[i] > s[i - 1] && s[i] >= s[i + 1]) cand.add(i)
        val picked = ArrayList<Int>()
        for (i in cand.sortedByDescending { s[it] }) {
            if (s[i] < thr) break
            if (picked.all { abs(i - it) >= minDist }) picked.add(i)
        }
        return picked.sorted()
    }

    private fun gradient(a: DoubleArray): DoubleArray {
        val n = a.size
        if (n < 2) return DoubleArray(n)
        return DoubleArray(n) { i ->
            when (i) { 0 -> a[1] - a[0]; n - 1 -> a[n - 1] - a[n - 2]; else -> (a[i + 1] - a[i - 1]) / 2.0 }
        }
    }

    private fun median(v: List<Double>): Double {
        val a = v.filter { !it.isNaN() }.sorted()
        if (a.isEmpty()) return Double.NaN
        return if (a.size % 2 == 1) a[a.size / 2] else (a[a.size / 2 - 1] + a[a.size / 2]) / 2
    }

    private fun beatFeatures(sig: DoubleArray, fs: Double): MutableMap<String, Double>? {
        var best: Triple<Double, DoubleArray, List<Int>>? = null
        for (pol in doubleArrayOf(1.0, -1.0)) {
            val s = DoubleArray(sig.size) { sig[it] * pol }
            val pk = findPeaks(s, (0.35 * fs).toInt())
            if (pk.size < 4) continue
            val iv = DoubleArray(pk.size - 1) { (pk[it + 1] - pk[it]) / fs }
            val okFrac = iv.count { it > 0.4 && it < 1.6 }.toDouble() / iv.size
            val score = okFrac - stdev(iv) / max(iv.average(), 1e-6)
            if (best == null || score > best.first) best = Triple(score, s, pk)
        }
        val (_, s, pk) = best ?: return null
        val iv = DoubleArray(pk.size - 1) { (pk[it + 1] - pk[it]) / fs }
        val ok = iv.map { it > 0.4 && it < 1.6 }
        val good = iv.filterIndexed { i, _ -> ok[i] }
        if (pk.size < MIN_BEATS || good.size < MIN_BEATS - 1) { android.util.Log.i("GlucoseAi", "reject: peaks=${pk.size} good=${good.size}"); return null }
        val amps = ArrayList<Double>(); val rise = ArrayList<Double>(); val fall = ArrayList<Double>()
        val w50 = ArrayList<Double>(); val area = ArrayList<Double>(); val vpg = ArrayList<Double>()
        val ba = ArrayList<Double>(); val da = ArrayList<Double>()
        for (j in 0 until pk.size - 1) {
            val a = pk[j]; val b = pk[j + 1]
            if ((b - a) / fs < 0.4 || (b - a) / fs > 1.6) continue
            var tr = a; for (i in a..b) if (s[i] < s[tr]) tr = i
            if (j + 2 >= pk.size) continue
            val nxt = pk[j + 2]
            var endTr = b; for (i in b..nxt) if (s[i] < s[endTr]) endTr = i
            if (endTr <= b || b <= tr) continue
            val beat = s.copyOfRange(tr, endTr + 1)
            val bMin = beat.min(); val bMax = beat.max(); val amp = bMax - bMin
            if (amp <= 0) continue
            val pkpos = beat.indices.maxByOrNull { beat[it] } ?: continue
            val nb = DoubleArray(beat.size) { (beat[it] - bMin) / amp }
            val half = nb.indices.filter { nb[it] >= 0.5 }
            val d1 = gradient(nb); val d2 = gradient(d1)
            val head = d2.copyOfRange(0, min(max(pkpos, 2), d2.size))
            val a2 = head.max(); val a2i = head.indices.maxByOrNull { head[it] } ?: 0
            val b2 = d2.copyOfRange(a2i, d2.size).minOrNull() ?: 0.0
            amps.add(amp); rise.add(pkpos / fs); fall.add((beat.size - 1 - pkpos) / fs)
            w50.add(if (half.isNotEmpty()) (half.last() - half.first()) / fs else Double.NaN)
            area.add(nb.copyOfRange(0, pkpos + 1).sum() / max(nb.sum(), 1e-9))
            vpg.add(d1.max() * fs)
            ba.add(if (a2 > 0) b2 / a2 else Double.NaN)
            da.add(if (a2 > 0) d2.copyOfRange(pkpos, d2.size).max() / a2 else Double.NaN)
        }
        if (amps.size < 6) { android.util.Log.i("GlucoseAi", "reject: beats=${amps.size}"); return null }
        val out = HashMap<String, Double>()
        out["amp"] = median(amps); out["rise"] = median(rise); out["fall"] = median(fall)
        out["w50"] = median(w50); out["area_ratio"] = median(area); out["vpg_peak"] = median(vpg)
        out["apg_ba"] = median(ba); out["apg_da"] = median(da)
        out["amp_cv"] = stdev(amps.toDoubleArray()) / max(amps.average(), 1e-9)
        out["hr"] = 60.0 / good.average()
        out["sdnn"] = stdev(good.toDoubleArray()) * 1000
        val dg = good.zipWithNext { p, q -> (q - p) * (q - p) }
        out["rmssd"] = if (good.size > 2) sqrt(dg.average()) * 1000 else Double.NaN
        out["sqi"] = ok.count { it }.toDouble() / ok.size
        return out
    }
}
