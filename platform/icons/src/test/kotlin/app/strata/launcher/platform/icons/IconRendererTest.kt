package app.strata.launcher.platform.icons

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strata.launcher.core.model.IconShapeKind
import app.strata.launcher.core.model.IconSource
import app.strata.launcher.core.model.IconStyle
import app.strata.launcher.core.model.IconTintMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * T-THEME-11 / T-THEME-12 (S halves as pixel checks on Robolectric native graphics; Roborazzi goldens are not in the
 * build): shapes mask adaptive icons, the legacy plate, pack icons as supplied, monochrome and desaturated tints.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconRendererTest {
    private val size = 96
    private val red = Color.rgb(220, 30, 30)
    private val plate = Color.rgb(40, 40, 60)
    private val tint = Color.rgb(250, 240, 200)

    private fun adaptive(monochrome: Drawable? = null): AdaptiveIconDrawable =
        if (monochrome != null) {
            AdaptiveIconDrawable(ColorDrawable(red), ColorDrawable(Color.TRANSPARENT), monochrome)
        } else {
            AdaptiveIconDrawable(ColorDrawable(red), ColorDrawable(Color.TRANSPARENT))
        }

    /** A legacy (non-adaptive) icon: a red dot. */
    private fun legacy(): Drawable = ShapeDrawable(OvalShape()).apply { paint.color = red }

    private fun Bitmap.alphaAt(
        x: Int,
        y: Int,
    ) = Color.alpha(getPixel(x, y))

    private fun render(
        style: IconStyle,
        icon: Drawable?,
        source: IconSource = IconSource.PLAIN,
        layer: Drawable? = null,
        supplied: Drawable? = null,
    ): Bitmap = checkNotNull(IconRenderer.render(source, size, style, icon, layer, supplied))

    @Test
    fun tTheme11_shapesMaskAdaptiveIcons() {
        val circle = render(IconStyle(shape = IconShapeKind.CIRCLE), adaptive())
        assertThat(circle.alphaAt(1, 1)).isEqualTo(0)
        assertThat(circle.getPixel(size / 2, size / 2)).isEqualTo(red)
        // Mid-edge is inside a circle.
        assertThat(circle.alphaAt(size / 2, 2)).isEqualTo(255)

        val square = render(IconStyle(shape = IconShapeKind.SQUARE), adaptive())
        assertThat(square.getPixel(1, 1)).isEqualTo(red)

        val squircle = render(IconStyle(shape = IconShapeKind.SQUIRCLE), adaptive())
        assertThat(squircle.alphaAt(0, 0)).isEqualTo(0)
        // A squircle covers more of the corner diagonal than a circle.
        val d = (size * 0.12).toInt()
        assertThat(squircle.alphaAt(d, d)).isGreaterThan(circle.alphaAt(d, d))

        val rounded0 = render(IconStyle(shape = IconShapeKind.ROUNDED_SQUARE, cornerRadiusPercent = 0), adaptive())
        assertThat(rounded0.getPixel(1, 1)).isEqualTo(red)
        val rounded50 = render(IconStyle(shape = IconShapeKind.ROUNDED_SQUARE, cornerRadiusPercent = 50), adaptive())
        assertThat(rounded50.alphaAt(1, 1)).isEqualTo(0)
    }

    @Test
    fun tTheme11_legacyPlateOnAndOff() {
        val on = render(IconStyle(shape = IconShapeKind.SQUARE, legacyPlate = true, plateColor = plate), legacy())
        // The plate fills the square; the legacy icon sits inset in the middle.
        assertThat(on.getPixel(2, 2)).isEqualTo(plate)
        assertThat(on.getPixel(size / 2, size / 2)).isEqualTo(red)

        val off = render(IconStyle(shape = IconShapeKind.SQUARE, legacyPlate = false, plateColor = plate), legacy())
        assertThat(off.alphaAt(2, 2)).isEqualTo(0)
        assertThat(off.getPixel(size / 2, size / 2)).isEqualTo(red)

        val circlePlate =
            render(IconStyle(shape = IconShapeKind.CIRCLE, legacyPlate = true, plateColor = plate), legacy())
        assertThat(circlePlate.alphaAt(1, 1)).isEqualTo(0)
        assertThat(circlePlate.getPixel(size / 2, 4)).isEqualTo(plate)
    }

    @Test
    fun tTheme11_packIconsAreDrawnAsSupplied() {
        val supplied = ColorDrawable(Color.BLUE)
        val style = IconStyle(shape = IconShapeKind.CIRCLE, tint = IconTintMode.MONOCHROME, tintColor = tint)
        for (source in listOf(IconSource.PACK, IconSource.OVERRIDE)) {
            val b = render(style, adaptive(), source = source, supplied = supplied)
            // No shape mask, no tint: the corner keeps the supplied colour.
            assertThat(b.getPixel(0, 0)).isEqualTo(Color.BLUE)
        }
    }

    /** T-THEME-12 (S half): the monochrome layer is drawn in the role colour on the plate. */
    @Test
    fun tTheme12_monochromeLayerIsTinted() {
        val glyph = ColorDrawable(Color.BLACK)
        val style =
            IconStyle(
                shape = IconShapeKind.SQUARE,
                plateColor = plate,
                tint = IconTintMode.MONOCHROME,
                tintColor = tint,
            )
        val b = render(style, adaptive(glyph), source = IconSource.PLATFORM_MONOCHROME, layer = glyph)
        assertThat(b.getPixel(size / 2, size / 2)).isEqualTo(tint)
    }

    /** T-THEME-12 (S half): without a monochrome layer the icon is desaturated and tinted. */
    @Test
    fun tTheme12_desaturatedFallback() {
        val style =
            IconStyle(
                shape = IconShapeKind.SQUARE,
                plateColor = plate,
                tint = IconTintMode.ACCENT,
                tintColor = Color.WHITE,
            )
        val b = render(style, adaptive(), source = IconSource.DESATURATED)
        val p = b.getPixel(size / 2, size / 2)
        // White tint × luminance: a grey (r = g = b), no hue left.
        assertThat(Color.red(p)).isEqualTo(Color.green(p))
        assertThat(Color.green(p)).isEqualTo(Color.blue(p))
        assertThat(Color.alpha(p)).isEqualTo(255)
    }

    @Test
    fun systemShapeUsesThePlatformMask() {
        val b = render(IconStyle(shape = IconShapeKind.SYSTEM), adaptive())
        assertThat(b.getPixel(size / 2, size / 2)).isEqualTo(red)
    }
}
