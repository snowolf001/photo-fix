package com.cleanutilityapps.photofix

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class EnhancementStats(
    val meanLuma: Double,
    val lowPercentile: Int,
    val highPercentile: Int,
    val contrastSpan: Int,
    val exposureGain: Double,
    val shadowLift: Double,
    val highlightCompression: Double,
    val saturationGain: Double,
    val sharpenAmount: Double
)

object ImageEnhancer {

    fun enhance(source: Bitmap): Pair<Bitmap, EnhancementStats> {
        val stats = analyze(source)
        val firstPass = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val src = IntArray(source.width * source.height)
        val dst = IntArray(src.size)
        source.getPixels(src, 0, source.width, 0, 0, source.width, source.height)

        val black = stats.lowPercentile.toDouble()
        val white = max(black + 16.0, stats.highPercentile.toDouble())
        val span = white - black

        for (i in src.indices) {
            val c = src[i]
            val a = Color.alpha(c)
            var r = Color.red(c) / 255.0
            var g = Color.green(c) / 255.0
            var b = Color.blue(c) / 255.0

            val avg0 = (r + g + b) / 3.0
            if (avg0 > 0.0001) {
                val wbStrength = 0.035
                fun scale(channel: Double): Double {
                    val desired = if (channel <= 0.0001) 1.0 else avg0 / channel
                    return (1.0 + (desired - 1.0) * wbStrength).coerceIn(0.96, 1.04)
                }
                r *= scale(r)
                g *= scale(g)
                b *= scale(b)
            }

            r = ((r * 255.0 - black) / span).coerceIn(0.0, 1.0)
            g = ((g * 255.0 - black) / span).coerceIn(0.0, 1.0)
            b = ((b * 255.0 - black) / span).coerceIn(0.0, 1.0)

            r = tone(r, stats)
            g = tone(g, stats)
            b = tone(b, stats)

            val avg = (r + g + b) / 3.0
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = mx - mn
            val localSat = 1.0 + (stats.saturationGain - 1.0) * (1.0 - sat)
            r = avg + (r - avg) * localSat
            g = avg + (g - avg) * localSat
            b = avg + (b - avg) * localSat

            dst[i] = Color.argb(
                a,
                (r.coerceIn(0.0, 1.0) * 255.0).toInt(),
                (g.coerceIn(0.0, 1.0) * 255.0).toInt(),
                (b.coerceIn(0.0, 1.0) * 255.0).toInt()
            )
        }

        firstPass.setPixels(dst, 0, source.width, 0, 0, source.width, source.height)
        return unsharpMask(firstPass, stats.sharpenAmount) to stats
    }

    private fun tone(x: Double, s: EnhancementStats): Double {
        var y = (x * s.exposureGain).coerceIn(0.0, 1.0)
        val shadowWeight = (1.0 - y).pow(2.2)
        y += s.shadowLift * shadowWeight
        if (y > 0.72) {
            val t = (y - 0.72) / 0.28
            y -= s.highlightCompression * t * t
        }
        return y.coerceIn(0.0, 1.0).pow(0.96)
    }

    private fun analyze(bitmap: Bitmap): EnhancementStats {
        val sampleStep = max(1, min(bitmap.width, bitmap.height) / 220)
        val histogram = IntArray(256)
        var total = 0L
        var sum = 0.0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val l = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c))
                    .toInt().coerceIn(0, 255)
                histogram[l]++
                total++
                sum += l
                x += sampleStep
            }
            y += sampleStep
        }

        val mean = if (total == 0L) 128.0 else sum / total
        val low = percentile(histogram, total, 0.01)
        val high = percentile(histogram, total, 0.99)
        val span = high - low

        val exposure = when {
            mean < 75 -> 1.22
            mean < 105 -> 1.12
            mean > 185 -> 0.96
            else -> 1.04
        }
        val shadows = when {
            mean < 85 -> 0.12
            mean < 120 -> 0.07
            else -> 0.035
        }
        val highlights = when {
            high > 245 -> 0.06
            high > 232 -> 0.035
            else -> 0.015
        }
        val saturation = when {
            span < 100 -> 1.14
            span < 145 -> 1.10
            else -> 1.06
        }
        val sharpen = if (span < 90) 0.24 else 0.18

        return EnhancementStats(mean, low, high, span, exposure, shadows, highlights, saturation, sharpen)
    }

    private fun percentile(hist: IntArray, total: Long, fraction: Double): Int {
        if (total <= 0) return 0
        val target = (total * fraction).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return 255
    }

    private fun unsharpMask(bitmap: Bitmap, amount: Double): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3 || amount <= 0.0) return bitmap

        val src = IntArray(w * h)
        val dst = IntArray(src.size)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        src.copyInto(dst)

        fun channel(c: Int, shift: Int) = (c shr shift) and 0xff

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val center = src[idx]
                var rr = 0
                var gg = 0
                var bb = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val c = src[(y + dy) * w + (x + dx)]
                        rr += channel(c, 16)
                        gg += channel(c, 8)
                        bb += channel(c, 0)
                    }
                }
                val br = rr / 9.0
                val bg = gg / 9.0
                val bbv = bb / 9.0

                val nr = (channel(center, 16) + amount * (channel(center, 16) - br)).toInt().coerceIn(0, 255)
                val ng = (channel(center, 8) + amount * (channel(center, 8) - bg)).toInt().coerceIn(0, 255)
                val nb = (channel(center, 0) + amount * (channel(center, 0) - bbv)).toInt().coerceIn(0, 255)
                dst[idx] = Color.argb(Color.alpha(center), nr, ng, nb)
            }
        }

        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }
}
