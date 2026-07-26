package com.fife.sa05.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code, or nothing when it cannot be encoded.
 *
 * Colours are passed in rather than read from the theme so the code stays high-contrast:
 * a QR rendered in themed low-contrast colours is decorative, not scannable.
 */
@Composable
internal fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    sizePx: Int = 512,
    foreground: Color = Color.Black,
    background: Color = Color.White
) {
    val bitmap: ImageBitmap? = remember(content, sizePx, foreground, background) {
        encodeQr(content, sizePx, foreground.toArgb(), background.toArgb())
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}

private fun encodeQr(
    content: String,
    sizePx: Int,
    foregroundArgb: Int,
    backgroundArgb: Int
): ImageBitmap? = runCatching {
    if (content.isBlank()) return null
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
    )
    val bitmap = createBitmap(matrix.width, matrix.height)
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val offset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[offset + x] = if (matrix[x, y]) foregroundArgb else backgroundArgb
        }
    }
    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    bitmap.asImageBitmap()
}.getOrNull()
