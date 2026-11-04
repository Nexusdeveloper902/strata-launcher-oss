package app.strata.launcher.core.data.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.os.Build
import app.strata.launcher.core.common.log.Logger
import app.strata.launcher.core.theme.importing.FontFormat
import app.strata.launcher.core.theme.importing.ImageCheck
import app.strata.launcher.core.theme.importing.ImageHeader
import app.strata.launcher.core.theme.importing.ImageOutcome
import app.strata.launcher.core.theme.importing.ThemeMedia
import app.strata.launcher.core.theme.importing.WebpStrip
import app.strata.launcher.core.theme.model.ImageMediaType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Platform half of the bundle media checks (THEME_SYSTEM §10.3.3), run on the theme dispatcher:
 *
 * - fonts: written to `cacheDir/import/<random>/` under a hash-free random name (never a name from the file), loaded
 *   with `Typeface.Builder(file).build()` (no fallback), measured with a test string; the directory is deleted in
 *   `finally`;
 * - images: bounds decoded first and checked against the pixel limits; a clean WebP within them is kept byte for
 *   byte; anything else is decoded with sampling to ≤ [maxDecodeSide] (display size × 2) and re-encoded to WebP
 *   (lossless for PNG, quality 90 otherwise), which also drops EXIF/GPS and other metadata.
 */
class AndroidThemeMedia(
    private val importCacheDir: () -> File,
    private val maxDecodeSide: () -> Int,
    private val logger: Logger,
) : ThemeMedia {
    override fun fontLoads(
        bytes: ByteArray,
        format: FontFormat,
    ): Boolean {
        val dir = File(importCacheDir(), UUID.randomUUID().toString())
        return try {
            if (!dir.mkdirs()) return false
            val file = File(dir, "font.${format.extension}")
            file.writeBytes(bytes)
            val face = Typeface.Builder(file).build() ?: return false
            // Without a fallback set, build() returns null on failure; the default face is never a valid result either.
            if (face === Typeface.DEFAULT) return false
            val paint = Paint().apply { typeface = face }
            paint.measureText(TEST_STRING) > 0f
        } catch (e: Exception) {
            logger.w(TAG, "font check failed", e)
            false
        } finally {
            dir.deleteRecursively()
        }
    }

    override fun prepareImage(
        bytes: ByteArray,
        header: ImageHeader,
    ): ImageOutcome {
        if (bytes.size > ImageCheck.MAX_BYTES || !ImageCheck.withinLimits(header)) return ImageOutcome.Rejected
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return ImageOutcome.Rejected
            if (!ImageCheck.withinLimits(header.copy(width = w, height = h))) return ImageOutcome.Rejected
            val limit = maxDecodeSide().coerceAtLeast(1)
            var sample = 1
            while (w / sample > limit || h / sample > limit) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            // Decoded even when kept as is: a WebP whose header is fine but whose data is not must not be stored.
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return ImageOutcome.Rejected
            if (header.type == ImageMediaType.WEBP && !header.hasMetadata) {
                decoded.recycle()
                return ImageOutcome.AsIs
            }
            // Re-encoding drops EXIF, so its orientation is applied to the pixels first (a portrait photo stays upright).
            val bitmap = upright(decoded, orientation(bytes))
            try {
                val out = ByteArrayOutputStream()
                val lossless = header.type == ImageMediaType.PNG
                if (!bitmap.compress(webp(lossless), if (lossless) LOSSLESS_QUALITY else LOSSY_QUALITY, out)) {
                    return ImageOutcome.Rejected
                }
                // The encoder may embed an sRGB ICC profile: strip it, so the stored file is "already stripped WebP".
                ImageOutcome.Reencoded(WebpStrip.strip(out.toByteArray()) ?: return ImageOutcome.Rejected)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            logger.w(TAG, "image check failed", e)
            ImageOutcome.Rejected
        } catch (e: OutOfMemoryError) {
            ImageOutcome.Rejected
        }
    }

    override fun samplePixels(
        bytes: ByteArray,
        header: ImageHeader,
        maxSide: Int,
    ): IntArray? {
        if (bytes.size > ImageCheck.MAX_BYTES || !ImageCheck.withinLimits(header)) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0 || !ImageCheck.withinLimits(header.copy(width = w, height = h))) return null
            var sample = 1
            while (w / sample > maxSide * 2 || h / sample > maxSide * 2) sample *= 2
            val opts =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
            val small =
                if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }
            try {
                IntArray(small.width * small.height).also {
                    small.getPixels(it, 0, small.width, 0, 0, small.width, small.height)
                }
            } finally {
                if (small !== decoded) small.recycle()
                decoded.recycle()
            }
        } catch (e: Exception) {
            logger.w(TAG, "image sample failed", e)
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** EXIF orientation of [bytes] (`ORIENTATION_NORMAL` when absent or unreadable). */
    private fun orientation(bytes: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    /** [src] rotated/flipped per the EXIF [orientation]; recycles [src] when a new bitmap is made. */
    private fun upright(
        src: Bitmap,
        orientation: Int,
    ): Bitmap {
        val m = exifMatrix(orientation) ?: return src
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun webp(lossless: Boolean): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            // Before API 30, WEBP at quality 100 is lossless (API 29) or the closest available.
            Bitmap.CompressFormat.WEBP
        }

    companion object {
        /** The transform for an EXIF orientation value (1..8), or null when none is needed. */
        fun exifMatrix(orientation: Int): Matrix? {
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                    m.setScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_180 -> {
                    m.setRotate(180f)
                }

                ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                    m.setScale(1f, -1f)
                }

                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    m.setRotate(90f)
                    m.postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_90 -> {
                    m.setRotate(90f)
                }

                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    m.setRotate(-90f)
                    m.postScale(-1f, 1f)
                }

                ExifInterface.ORIENTATION_ROTATE_270 -> {
                    m.setRotate(270f)
                }

                else -> {
                    return null
                }
            }
            return m
        }

        private const val TAG = "ThemeMedia"
        private const val TEST_STRING = "Strata Aa 0123"
        private const val LOSSY_QUALITY = 90
        private const val LOSSLESS_QUALITY = 100
        const val IMPORT_DIR = "import"
    }
}
