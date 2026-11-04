package app.strata.launcher.platform.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import app.strata.launcher.core.model.IconShapeKind
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Draws one icon into an ARGB_8888 bitmap (ARCHITECTURE §9 "Render"; ANDROID_PLATFORM §11):
 * - adaptive icons: both layers clipped by our own shape path (the system mask for `SYSTEM`);
 * - legacy icons: on a `surface` plate in the shape when `legacyIconPlate` is on, else as supplied;
 * - pack and override drawables: as supplied (no shape, no tint);
 * - monochrome (platform layer or glyph): the layer tinted with the role colour on a `surface` plate;
 * - desaturated: the plain icon reduced to its luminance and multiplied by the role colour.
 * Masks are drawn with an anti-aliased path and `SRC_IN`, not `clipPath`, so edges stay smooth.
 */
internal object IconRenderer {
    /** A legacy icon fills about 70 % of the visible 72/108 area of the plate (Proposed). */
    private const val LEGACY_INSET = 0.2667f

    fun render(
        source: IconSource,
        size: Int,
        style: IconStyle,
        icon: Drawable?,
        layer: Drawable?,
        supplied: Drawable?,
    ): Bitmap? =
        when (source) {
            IconSource.OVERRIDE, IconSource.PACK -> {
                supplied?.let { asSupplied(it, size) }
            }

            IconSource.GLYPH, IconSource.PLATFORM_MONOCHROME -> {
                layer?.let { themed(it, style, size) }
            }

            IconSource.DESATURATED -> {
                icon?.let {
                    desaturated(
                        plain(it, style.copy(legacyPlate = true), size),
                        style.tintColor,
                    )
                }
            }

            IconSource.PLAIN -> {
                icon?.let { plain(it, style, size) }
            }
        }

    fun plain(
        icon: Drawable,
        style: IconStyle,
        size: Int,
    ): Bitmap =
        when {
            icon is AdaptiveIconDrawable -> adaptive(icon, style, size)
            style.legacyPlate -> adaptive(plated(icon, style.plateColor), style, size)
            else -> asSupplied(icon, size)
        }

    private fun plated(
        legacy: Drawable,
        plateColor: Int,
    ): AdaptiveIconDrawable = AdaptiveIconDrawable(ColorDrawable(plateColor), InsetDrawable(legacy, LEGACY_INSET))

    private fun themed(
        layer: Drawable,
        style: IconStyle,
        size: Int,
    ): Bitmap {
        val tinted = layer.mutate().apply { setTint(style.tintColor) }
        tinted.setTintMode(PorterDuff.Mode.SRC_IN)
        return adaptive(AdaptiveIconDrawable(ColorDrawable(style.plateColor), tinted), style, size)
    }

    private fun adaptive(
        icon: AdaptiveIconDrawable,
        style: IconStyle,
        size: Int,
    ): Bitmap {
        if (style.shape == IconShapeKind.SYSTEM) return asSupplied(icon, size)
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)
        // Layers are 108 × 108 units with the inner 72 visible: draw them 1.5× the icon, centred.
        val inset = size / 4
        for (d in listOfNotNull(icon.background, icon.foreground)) {
            d.setBounds(-inset, -inset, size + inset, size + inset)
            d.draw(canvas)
        }
        return masked(content, shapePath(style.shape, style.cornerRadiusPercent, size.toFloat()))
    }

    private fun masked(
        content: Bitmap,
        path: Path,
    ): Bitmap {
        val out = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(content, 0f, 0f, paint)
        content.recycle()
        return out
    }

    fun asSupplied(
        drawable: Drawable,
        size: Int,
    ): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val old = drawable.copyBounds()
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(out))
        drawable.bounds = old
        return out
    }

    private fun desaturated(
        plain: Bitmap,
        tintColor: Int,
    ): Bitmap {
        val r = (tintColor shr 16 and 0xff) / 255f
        val g = (tintColor shr 8 and 0xff) / 255f
        val b = (tintColor and 0xff) / 255f
        // Rec. 709 luminance, then multiplied by the tint colour; alpha kept.
        val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val matrix =
            ColorMatrix(
                floatArrayOf(
                    lum[0] * r,
                    lum[1] * r,
                    lum[2] * r,
                    0f,
                    0f,
                    lum[0] * g,
                    lum[1] * g,
                    lum[2] * g,
                    0f,
                    0f,
                    lum[0] * b,
                    lum[1] * b,
                    lum[2] * b,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )
        val out = Bitmap.createBitmap(plain.width, plain.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(plain, 0f, 0f, paint)
        plain.recycle()
        return out
    }

    /** Our own shape paths (THEME_SYSTEM §4.6); `SYSTEM` is never passed here (the platform mask is used). */
    fun shapePath(
        shape: IconShapeKind,
        cornerRadiusPercent: Int,
        size: Float,
    ): Path {
        val path = Path()
        when (shape) {
            IconShapeKind.CIRCLE -> {
                path.addCircle(size / 2, size / 2, size / 2, Path.Direction.CW)
            }

            IconShapeKind.SQUARE -> {
                path.addRect(0f, 0f, size, size, Path.Direction.CW)
            }

            IconShapeKind.ROUNDED_SQUARE, IconShapeKind.SYSTEM -> {
                val radius = size * cornerRadiusPercent.coerceIn(0, 50) / 100f
                path.addRoundRect(RectF(0f, 0f, size, size), radius, radius, Path.Direction.CW)
            }

            IconShapeKind.SQUIRCLE -> {
                // Superellipse |x|^4 + |y|^4 = r^4, sampled densely enough for an anti-aliased fill.
                val r = size / 2
                val steps = SQUIRCLE_STEPS
                for (i in 0..steps) {
                    val t = 2 * Math.PI * i / steps
                    val c = cos(t)
                    val s = sin(t)
                    val x = (r + r * sign(c) * abs(c).pow(0.5)).toFloat()
                    val y = (r + r * sign(s) * abs(s).pow(0.5)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
        }
        return path
    }

    private const val SQUIRCLE_STEPS = 128
}
