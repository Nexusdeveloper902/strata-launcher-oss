package app.strata.launcher.core.theme.color

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HCT colour (hue and chroma from CAM16, tone = CIE L*), THEME_SYSTEM §5.2 / §7.5.
 *
 * Written from the published CAM16 (Li et al. 2017) and CIELAB equations; no colour library
 * at runtime (ARCHITECTURE §15). The HCT → sRGB direction uses a bounded bisection search
 * (tone exact to ±0.2 L*, chroma reduced until the colour fits the sRGB gamut), which is
 * deterministic and allocation-light. Results may differ by a unit or two per channel from
 * other HCT implementations; nothing in the spec depends on bit-exact agreement.
 */
data class Hct(val hue: Double, val chroma: Double, val tone: Double) {
    fun toArgb(): Int = HctSolver.solve(hue, chroma, tone)

    /** Same hue and chroma, new tone (re-fitted into the gamut). */
    fun withTone(newTone: Double): Hct = fromArgb(HctSolver.solve(hue, chroma, newTone))

    companion object {
        fun fromArgb(argb: Int): Hct {
            val cam = Cam16.fromArgb(argb)
            return Hct(cam.hue, cam.chroma, Lab.lstarFromArgb(argb))
        }

        fun from(
            hue: Double,
            chroma: Double,
            tone: Double,
        ): Hct = fromArgb(HctSolver.solve(hue, chroma, tone))
    }
}

/** CIE L* helpers (D65, Y in 0..100). */
internal object Lab {
    private const val E = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    fun yFromArgb(argb: Int): Double {
        val r = Rgb.linearize(Rgb.red(argb) / 255.0) * 100
        val g = Rgb.linearize(Rgb.green(argb) / 255.0) * 100
        val b = Rgb.linearize(Rgb.blue(argb) / 255.0) * 100
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun lstarFromY(y: Double): Double = 116.0 * labF(y / 100.0) - 16.0

    fun lstarFromArgb(argb: Int): Double = lstarFromY(yFromArgb(argb))

    fun yFromLstar(lstar: Double): Double = 100.0 * labInvF((lstar + 16.0) / 116.0)

    fun argbFromLstar(lstar: Double): Int {
        val c = Rgb.unitToChannel(Rgb.delinearize(yFromLstar(lstar) / 100.0))
        return Rgb.of(c, c, c)
    }

    private fun labF(t: Double): Double = if (t > E) cbrt(t) else (KAPPA * t + 16) / 116

    private fun labInvF(ft: Double): Double {
        val ft3 = ft * ft * ft
        return if (ft3 > E) ft3 else (116 * ft - 16) / KAPPA
    }
}

/** CAM16 default viewing conditions (sRGB D65 white, adapting luminance from L* 50 grey, average surround). */
internal object ViewingConditions {
    private val WHITE = doubleArrayOf(95.047, 100.0, 108.883)
    val XYZ_TO_CAT16 =
        arrayOf(
            doubleArrayOf(0.401288, 0.650173, -0.051461),
            doubleArrayOf(-0.250268, 1.204414, 0.045854),
            doubleArrayOf(-0.002079, 0.048952, 0.953127),
        )
    val CAT16_TO_XYZ =
        arrayOf(
            doubleArrayOf(1.8620678, -1.0112547, 0.14918678),
            doubleArrayOf(0.38752654, 0.62144744, -0.00897398),
            doubleArrayOf(-0.0158415, -0.03412294, 1.0499644),
        )

    val n: Double
    val aw: Double
    val nbb: Double
    val ncb: Double
    val c: Double
    val nc: Double
    val rgbD: DoubleArray
    val fl: Double
    val flRoot: Double
    val z: Double

    init {
        val adaptingLuminance = 200.0 / PI * Lab.yFromLstar(50.0) / 100.0
        val surround = 2.0
        val rW = WHITE[0] * XYZ_TO_CAT16[0][0] + WHITE[1] * XYZ_TO_CAT16[0][1] + WHITE[2] * XYZ_TO_CAT16[0][2]
        val gW = WHITE[0] * XYZ_TO_CAT16[1][0] + WHITE[1] * XYZ_TO_CAT16[1][1] + WHITE[2] * XYZ_TO_CAT16[1][2]
        val bW = WHITE[0] * XYZ_TO_CAT16[2][0] + WHITE[1] * XYZ_TO_CAT16[2][1] + WHITE[2] * XYZ_TO_CAT16[2][2]
        val f = 0.8 + surround / 10.0
        c = if (f >= 0.9) lerp(0.59, 0.69, (f - 0.9) * 10.0) else lerp(0.525, 0.59, (f - 0.8) * 10.0)
        var d = f * (1.0 - (1.0 / 3.6) * exp((-adaptingLuminance - 42.0) / 92.0))
        d = d.coerceIn(0.0, 1.0)
        nc = f
        rgbD = doubleArrayOf(d * (100.0 / rW) + 1.0 - d, d * (100.0 / gW) + 1.0 - d, d * (100.0 / bW) + 1.0 - d)
        val k = 1.0 / (5.0 * adaptingLuminance + 1.0)
        val k4 = k * k * k * k
        val k4F = 1.0 - k4
        fl = k4 * adaptingLuminance + 0.1 * k4F * k4F * cbrt(5.0 * adaptingLuminance)
        n = Lab.yFromLstar(50.0) / WHITE[1]
        z = 1.48 + sqrt(n)
        nbb = 0.725 / n.pow(0.2)
        ncb = nbb
        val factors =
            doubleArrayOf(
                (fl * rgbD[0] * rW / 100.0).pow(0.42),
                (fl * rgbD[1] * gW / 100.0).pow(0.42),
                (fl * rgbD[2] * bW / 100.0).pow(0.42),
            )
        val rgbA = DoubleArray(3) { 400.0 * factors[it] / (factors[it] + 27.13) }
        aw = (2.0 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb
        flRoot = fl.pow(0.25)
    }

    private fun lerp(
        a: Double,
        b: Double,
        t: Double,
    ): Double = a + (b - a) * t
}

/** CAM16 appearance correlates needed by HCT. */
internal data class Cam16(val hue: Double, val chroma: Double, val j: Double) {
    /** CAM16 → sRGB, channels clamped into the gamut. */
    fun toArgb(): Int {
        val vc = ViewingConditions
        val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / sqrt(j / 100.0)
        val t = (alpha / (1.64 - 0.29.pow(vc.n)).pow(0.73)).pow(1.0 / 0.9)
        val hRad = hue * PI / 180.0
        val eHue = 0.25 * (cos(hRad + 2.0) + 3.8)
        val ac = vc.aw * (j / 100.0).pow(1.0 / vc.c / vc.z)
        val p1 = eHue * (50000.0 / 13.0) * vc.nc * vc.ncb
        val p2 = ac / vc.nbb
        val hSin = sin(hRad)
        val hCos = cos(hRad)
        val gamma = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hCos + 108.0 * t * hSin)
        val a = gamma * hCos
        val b = gamma * hSin
        val rA = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
        val gA = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
        val bA = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0
        val rC = unadapt(rA, vc.fl) / vc.rgbD[0]
        val gC = unadapt(gA, vc.fl) / vc.rgbD[1]
        val bC = unadapt(bA, vc.fl) / vc.rgbD[2]
        val m = ViewingConditions.CAT16_TO_XYZ
        val x = m[0][0] * rC + m[0][1] * gC + m[0][2] * bC
        val y = m[1][0] * rC + m[1][1] * gC + m[1][2] * bC
        val zz = m[2][0] * rC + m[2][1] * gC + m[2][2] * bC
        return argbFromXyz(x, y, zz)
    }

    companion object {
        fun fromArgb(argb: Int): Cam16 {
            val r = Rgb.linearize(Rgb.red(argb) / 255.0) * 100
            val g = Rgb.linearize(Rgb.green(argb) / 255.0) * 100
            val b = Rgb.linearize(Rgb.blue(argb) / 255.0) * 100
            val x = 0.41233895 * r + 0.35762064 * g + 0.18051042 * b
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val z = 0.01932141 * r + 0.11916382 * g + 0.95034478 * b
            return fromXyz(x, y, z)
        }

        fun fromXyz(
            x: Double,
            y: Double,
            z: Double,
        ): Cam16 {
            val vc = ViewingConditions
            val m = ViewingConditions.XYZ_TO_CAT16
            val rD = vc.rgbD[0] * (m[0][0] * x + m[0][1] * y + m[0][2] * z)
            val gD = vc.rgbD[1] * (m[1][0] * x + m[1][1] * y + m[1][2] * z)
            val bD = vc.rgbD[2] * (m[2][0] * x + m[2][1] * y + m[2][2] * z)
            val rA = adapt(rD, vc.fl)
            val gA = adapt(gD, vc.fl)
            val bA = adapt(bD, vc.fl)
            val a = (11.0 * rA + -12.0 * gA + bA) / 11.0
            val bb = (rA + gA - 2.0 * bA) / 9.0
            val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
            val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0
            var hue = atan2(bb, a) * 180.0 / PI
            if (hue < 0) hue += 360.0
            if (hue >= 360) hue -= 360.0
            val ac = p2 * vc.nbb
            val j = 100.0 * (ac / vc.aw).coerceAtLeast(0.0).pow(vc.c * vc.z)
            val huePrime = if (hue < 20.14) hue + 360 else hue
            val eHue = 0.25 * (cos(huePrime * PI / 180.0 + 2.0) + 3.8)
            val p1 = 50000.0 / 13.0 * eHue * vc.nc * vc.ncb
            val t = p1 * hypot(a, bb) / (u + 0.305)
            val alpha = t.pow(0.9) * (1.64 - 0.29.pow(vc.n)).pow(0.73)
            val chroma = alpha * sqrt(j / 100.0)
            return Cam16(hue, chroma, j)
        }

        private fun adapt(
            component: Double,
            fl: Double,
        ): Double {
            val af = (fl * abs(component) / 100.0).pow(0.42)
            return sign(component) * 400.0 * af / (af + 27.13)
        }

        private fun unadapt(
            adapted: Double,
            fl: Double,
        ): Double {
            val base = (27.13 * abs(adapted) / (400.0 - abs(adapted))).coerceAtLeast(0.0)
            return sign(adapted) * (100.0 / fl) * base.pow(1.0 / 0.42)
        }

        private fun argbFromXyz(
            x: Double,
            y: Double,
            z: Double,
        ): Int {
            val lr = 3.2413774792388685 * x - 1.5376652402851851 * y - 0.49885366846268053 * z
            val lg = -0.9691452513005321 * x + 1.8758853451067872 * y + 0.04156585616912061 * z
            val lb = 0.05562093689691305 * x - 0.20395524564742123 * y + 1.0571799111220335 * z
            return Rgb.of(channel(lr), channel(lg), channel(lb))
        }

        private fun channel(linear100: Double): Int {
            val v = Rgb.delinearize((linear100 / 100.0).coerceIn(0.0, 1.0))
            return (v * 255.0).roundToInt().coerceIn(0, 255)
        }
    }
}

/**
 * HCT → sRGB: for the requested tone, bisect CAM16 J until the clipped colour has that L*;
 * if the requested chroma does not fit the gamut at that tone, bisect chroma downwards.
 */
internal object HctSolver {
    private const val CHROMA_STEP_END = 0.4
    private const val J_STEP_END = 0.01
    private const val MAX_DL = 0.2
    private const val MAX_DE = 1.0

    fun solve(
        hue: Double,
        chroma: Double,
        tone: Double,
    ): Int {
        if (chroma < 1.0 || tone.roundToInt() <= 0 || tone.roundToInt() >= 100) return Lab.argbFromLstar(tone.coerceIn(0.0, 100.0))
        val h = ((hue % 360.0) + 360.0) % 360.0
        findByJ(h, chroma, tone)?.let { return it }
        var high = chroma
        var low = 0.0
        var mid = (high + low) / 2
        var answer: Int? = null
        while (abs(high - low) >= CHROMA_STEP_END) {
            val candidate = findByJ(h, mid, tone)
            if (candidate == null) high = mid else {
                answer = candidate
                low = mid
            }
            mid = low + (high - low) / 2
        }
        return answer ?: Lab.argbFromLstar(tone)
    }

    private fun findByJ(
        hue: Double,
        chroma: Double,
        tone: Double,
    ): Int? {
        var low = 0.0
        var high = 100.0
        var bestDl = Double.MAX_VALUE
        var bestDe = Double.MAX_VALUE
        var best: Int? = null
        while (abs(low - high) > J_STEP_END) {
            val mid = low + (high - low) / 2
            val clipped = Cam16(hue, chroma, mid).toArgb()
            val clippedL = Lab.lstarFromArgb(clipped)
            val dl = abs(tone - clippedL)
            if (dl < MAX_DL) {
                val cam = Cam16.fromArgb(clipped)
                val de = camDistance(cam, Cam16(hue, cam.chroma, cam.j))
                if (de <= MAX_DE && de <= bestDe) {
                    bestDl = dl
                    bestDe = de
                    best = clipped
                }
            }
            if (bestDl == 0.0 && bestDe == 0.0) break
            if (clippedL < tone) low = mid else high = mid
        }
        return best
    }

    /** CAM16-UCS distance. */
    private fun camDistance(
        a: Cam16,
        b: Cam16,
    ): Double {
        fun ucs(c: Cam16): Triple<Double, Double, Double> {
            val jstar = (1.0 + 100.0 * 0.007) * c.j / (1.0 + 0.007 * c.j)
            val m = c.chroma * ViewingConditions.flRoot
            val mstar = 1.0 / 0.0228 * kotlin.math.ln(1.0 + 0.0228 * m)
            val hr = c.hue * PI / 180.0
            return Triple(jstar, mstar * cos(hr), mstar * sin(hr))
        }
        val (j1, a1, b1) = ucs(a)
        val (j2, a2, b2) = ucs(b)
        val de = sqrt((j1 - j2) * (j1 - j2) + (a1 - a2) * (a1 - a2) + (b1 - b2) * (b1 - b2))
        return 1.41 * de.pow(0.63)
    }
}
