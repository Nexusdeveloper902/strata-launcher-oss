package app.strata.launcher.platform.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * Sanity check of an app's platform monochrome layer (D-020, THEME_SYSTEM §4.6). Some apps ship a layer that is
 * effectively empty (transparent) or fully opaque (a full-bleed square); such a layer counts as absent, so the Strata
 * glyph (else the desaturated icon) is used. Cheap: the layer is drawn once into a [GRID] x [GRID] alpha bitmap, on the
 * render or I/O dispatcher only, and the result is part of the rendered (cached) bitmap.
 */
object MonochromeLayers {
    /** Sample grid edge, in pixels. */
    const val GRID = 36

    /** Below this share of inked pixels in the visible area the layer is empty. */
    const val MIN_INK = 0.005

    /** Above this share of opaque pixels in the visible area (the inner 2/3, inside the adaptive mask) it is a block. */
    const val MAX_SOLID = 0.95

    private const val INK_ALPHA = 32
    private const val SOLID_ALPHA = 224

    /** Pure decision over [GRID] x [GRID] alpha values (0..255), row-major. */
    fun isUsable(alpha: IntArray): Boolean {
        require(alpha.size == GRID * GRID) { "expected ${GRID * GRID} samples" }
        // The adaptive icon shows only the inner 72 of 108 dp: both tests look at that area alone. Ink outside it is
        // never seen, and a layer that fills it is a block, not an icon.
        val lo = GRID / 6
        val hi = GRID - lo
        var ink = 0
        var solid = 0
        var visible = 0
        for (y in lo until hi) {
            for (x in lo until hi) {
                visible++
                val a = alpha[y * GRID + x]
                if (a > INK_ALPHA) ink++
                if (a > SOLID_ALPHA) solid++
            }
        }
        return ink >= MIN_INK * visible && solid < MAX_SOLID * visible
    }

    /**
     * Draws [layer] once at [GRID] px and applies [isUsable]. Not on main. A layer that fails to draw counts as unusable,
     * so the glyph fallback still applies.
     */
    fun isUsable(layer: Drawable): Boolean =
        try {
            val bitmap = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888)
            try {
                val old = layer.copyBounds()
                layer.setBounds(0, 0, GRID, GRID)
                layer.draw(Canvas(bitmap))
                layer.bounds = old
                val pixels = IntArray(GRID * GRID)
                bitmap.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
                isUsable(IntArray(pixels.size) { pixels[it] ushr 24 })
            } finally {
                bitmap.recycle()
            }
        } catch (e: RuntimeException) {
            false
        } catch (e: OutOfMemoryError) {
            false
        }
}
