package io.github.trevarj.motd.invite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.ceramicLogoColorMatrix

/** Local-only QR renderer. Black/white pixels preserve scanner contrast in every app theme. */
fun inviteQrBitmap(
    text: String,
    size: Int = 768,
    foreground: Int = Color.BLACK,
    background: Int = Color.WHITE,
): Bitmap {
    require(size > 0) { "QR size must be positive" }
    val matrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 4,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) foreground else background
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Signal-style invite card: centered motd mark, then its label below the QR. */
fun brandedInviteQrBitmap(
    context: Context,
    text: String,
    label: String,
    accent: Int = Color.rgb(0, 122, 124),
    onAccent: Int = Color.WHITE,
    size: Int = 768,
): Bitmap {
    require(size > 0) { "QR size must be positive" }
    val height = (size * 1.18f).toInt()
    val bitmap = createBitmap(size, height)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), height.toFloat()), size * 0.08f, size * 0.08f, paint)

    val inset = (size * 0.06f).toInt()
    val qrSize = size - inset * 2
    val qrBounds = RectF(inset.toFloat(), inset.toFloat(), (inset + qrSize).toFloat(), (inset + qrSize).toFloat())
    paint.color = Color.WHITE
    canvas.drawRoundRect(qrBounds, qrSize * 0.04f, qrSize * 0.04f, paint)
    canvas.drawBitmap(inviteQrBitmap(text, qrSize, background = Color.TRANSPARENT), inset.toFloat(), inset.toFloat(), paint)
    val qrCenter = size / 2f
    val logoRadius = qrSize * 0.09f
    paint.color = Color.WHITE
    canvas.drawCircle(qrCenter, qrCenter, logoRadius, paint)
    val logoSize = (qrSize * 0.10f).toInt()
    ContextCompat.getDrawable(context, R.drawable.motd_logo_mark)?.mutate()?.apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(ceramicLogoColorMatrix(accent)))
        val left = qrCenter.toInt() - logoSize / 2
        val top = qrCenter.toInt() - logoSize / 2
        bounds = Rect(left, top, left + logoSize, top + logoSize)
        draw(canvas)
    }

    val textPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onAccent
            textSize = size * 0.05f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    val footerCenterY = (inset + qrSize + height) / 2f
    val availableTextWidth = size * 0.78f
    val visibleLabel = TextUtils.ellipsize(label, textPaint, availableTextWidth, TextUtils.TruncateAt.END).toString()
    val textWidth = textPaint.measureText(visibleLabel)
    val x = qrCenter - textWidth / 2f
    val baseline = footerCenterY - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(visibleLabel, x, baseline, textPaint)
    return bitmap
}
