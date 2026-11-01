package io.github.makealauncher.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.makealauncher.core.runtime.Measurer
import io.github.makealauncher.core.runtime.RuntimeNode
import io.github.makealauncher.core.runtime.Size
import io.github.makealauncher.core.scene.ContentSpec
import io.github.makealauncher.core.scene.TextAlign
import kotlin.math.ceil
import kotlin.math.max

/**
 * Builds text layouts with Android's StaticLayout, which handles line breaking, emoji and
 * right-to-left scripts such as Hebrew and Arabic. The layout built while measuring is kept
 * on the node and reused for drawing, so text is shaped once per change.
 */
class TextLayouts(private val assets: PresetAssets?) {
    private val typefaces = HashMap<String, Typeface>()

    fun build(node: RuntimeNode, maxWidth: Float?): StaticLayout {
        val spec = node.content as ContentSpec.Text
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = node.textSize
            color = node.color
            typeface = typeface(spec.font, spec.bold)
        }
        val text = node.text
        val width = if (maxWidth != null) {
            max(1, ceil(maxWidth).toInt())
        } else {
            max(1, ceil(Layout.getDesiredWidth(text, paint)).toInt())
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(
                when (spec.align) {
                    TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
                    TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setIncludePad(false)
        if (spec.maxLines > 0) {
            builder.setMaxLines(spec.maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }

    private fun typeface(font: String?, bold: Boolean): Typeface {
        val key = "${font ?: ""}|$bold"
        return typefaces.getOrPut(key) {
            val base = font?.let { assets?.typeface(it) } ?: Typeface.DEFAULT
            if (bold) Typeface.create(base, Typeface.BOLD) else base
        }
    }
}

/**
 * Decodes preset images at the size they're shown, as hardware bitmaps whose pixels live in
 * GPU memory instead of the app's Java heap.
 */
class Images(private val assets: PresetAssets?) {
    private val bounds = HashMap<String, Size?>()
    private val decoded = HashMap<String, Bitmap?>()

    fun intrinsicSize(src: String): Size? = bounds.getOrPut(src) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets?.open(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) Size(opts.outWidth.toFloat(), opts.outHeight.toFloat()) else null
    }

    fun bitmap(src: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val natural = intrinsicSize(src) ?: return null
        var sample = 1
        while (natural.width / (sample * 2) >= targetWidth && natural.height / (sample * 2) >= targetHeight) sample *= 2
        val key = "$src@$sample"
        return decoded.getOrPut(key) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.HARDWARE
            }
            assets?.open(src)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }
}

class AndroidMeasurer(private val text: TextLayouts, private val images: Images) : Measurer {
    override fun measureText(node: RuntimeNode, maxWidth: Float?): Size {
        val layout = text.build(node, maxWidth)
        node.measureCache = layout
        return Size(maxWidth ?: layout.width.toFloat(), layout.height.toFloat())
    }

    override fun imageSize(node: RuntimeNode): Size? {
        val spec = node.content as ContentSpec.Image
        return images.intrinsicSize(spec.src)
    }
}
