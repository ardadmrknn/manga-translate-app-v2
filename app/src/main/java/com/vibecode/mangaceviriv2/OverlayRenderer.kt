package com.vibecode.mangaceviriv2

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.graphics.Typeface
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb

@Composable
fun OverlayTranslationRenderer(
    blocks: List<TranslationBlock>,
    offsetX: Int = 0,
    offsetY: Int = 0,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val textPaint = TextPaint().apply {
            isAntiAlias = true
            color = Color.Black.toArgb()
            style = android.graphics.Paint.Style.FILL
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        blocks.forEach { block ->
            val rect = block.toRect()
            if (rect.width() <= 0 || rect.height() <= 0) return@forEach

            val screenRect = Rect(
                rect.left + offsetX,
                rect.top + offsetY,
                rect.right + offsetX,
                rect.bottom + offsetY
            )
            if (screenRect.width() <= 0 || screenRect.height() <= 0) return@forEach

            val displayText = block.translated.ifBlank { block.original }.take(MAX_RENDER_TEXT_CHARS)
            val horizontalPadding = 8f
            val verticalPadding = 6f
            val boundsRect = Rect(0, 0, size.width.toInt(), size.height.toInt())
            val renderSpec = buildRenderSpec(
                text = displayText,
                initialRect = screenRect,
                boundsRect = boundsRect,
                paint = textPaint,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
                minTextPx = 13.sp.toPx(),
                maxTextPx = 36.sp.toPx()
            )

            // Okunakli panel: beyaz zemin + siyah metin.
            drawRect(
                color = Color.White,
                topLeft = Offset(renderSpec.drawRect.left.toFloat(), renderSpec.drawRect.top.toFloat()),
                size = Size(renderSpec.drawRect.width().toFloat(), renderSpec.drawRect.height().toFloat()),
                blendMode = BlendMode.SrcOver
            )

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.save()
                canvas.nativeCanvas.clipRect(renderSpec.drawRect)
                canvas.nativeCanvas.translate(
                    renderSpec.drawRect.left.toFloat() + horizontalPadding,
                    renderSpec.drawRect.top.toFloat() + verticalPadding
                )
                renderSpec.layout.draw(canvas.nativeCanvas)
                canvas.nativeCanvas.restore()
            }
        }
    }
}

private data class RenderSpec(
    val drawRect: Rect,
    val layout: StaticLayout
)

private fun buildRenderSpec(
    text: String,
    initialRect: Rect,
    boundsRect: Rect,
    paint: TextPaint,
    horizontalPadding: Float,
    verticalPadding: Float,
    minTextPx: Float,
    maxTextPx: Float
): RenderSpec {
    var drawRect = Rect(initialRect)
    var textSize = calculateTextSize(drawRect, minTextPx, maxTextPx)
    var layout = createLayout(
        text = text,
        paint = paint,
        width = (drawRect.width() - horizontalPadding * 2f).toInt().coerceAtLeast(1),
        textSize = textSize,
        maxHeight = (drawRect.height() - verticalPadding * 2f).toInt().coerceAtLeast(1),
        maxLinesCap = 8
    )

    var attempts = 0
    while (attempts < 5) {
        val availableHeight = (drawRect.height() - verticalPadding * 2f).toInt().coerceAtLeast(1)
        val needsExpand = layout.height > availableHeight || hasEllipsis(layout)
        if (!needsExpand) break

        val expandW = (drawRect.width() * 0.24f).toInt().coerceAtLeast(28)
        val expandH = (drawRect.height() * 0.34f).toInt().coerceAtLeast(24)
        drawRect = expandRectClamped(drawRect, boundsRect, expandW, expandH)
        textSize = calculateTextSize(drawRect, minTextPx, maxTextPx)
        layout = createLayout(
            text = text,
            paint = paint,
            width = (drawRect.width() - horizontalPadding * 2f).toInt().coerceAtLeast(1),
            textSize = textSize,
            maxHeight = (drawRect.height() - verticalPadding * 2f).toInt().coerceAtLeast(1),
            maxLinesCap = 10
        )
        attempts++
    }

    return RenderSpec(drawRect = drawRect, layout = layout)
}

private fun calculateTextSize(rect: Rect, minTextPx: Float, maxTextPx: Float): Float {
    val heightBased = rect.height().toFloat() * 0.34f
    val widthBased = rect.width().toFloat() * 0.11f
    return minOf(heightBased, widthBased).coerceIn(minTextPx, maxTextPx)
}

private fun hasEllipsis(layout: StaticLayout): Boolean {
    for (line in 0 until layout.lineCount) {
        if (layout.getEllipsisCount(line) > 0) return true
    }
    return false
}

private fun expandRectClamped(rect: Rect, bounds: Rect, expandW: Int, expandH: Int): Rect {
    val halfW = expandW / 2
    val halfH = expandH / 2

    val left = (rect.left - halfW).coerceAtLeast(bounds.left)
    val top = (rect.top - halfH).coerceAtLeast(bounds.top)
    val right = (rect.right + halfW).coerceAtMost(bounds.right)
    val bottom = (rect.bottom + halfH).coerceAtMost(bounds.bottom)
    return Rect(left, top, right, bottom)
}

private fun createLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    textSize: Float,
    maxHeight: Int,
    maxLinesCap: Int
): StaticLayout {
    paint.textSize = textSize
    val maxLines = (maxHeight / (textSize * 1.12f)).toInt().coerceIn(1, maxLinesCap)
    return StaticLayout.Builder
        .obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.0f)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setMaxLines(maxLines)
        .build()
}

private const val MAX_RENDER_TEXT_CHARS = 420
