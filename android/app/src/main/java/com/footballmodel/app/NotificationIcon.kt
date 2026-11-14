package com.footballmodel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/** Uses the same full-color football mark as the app launcher in expanded notifications. */
internal object NotificationIcon {
    fun large(context: Context): Bitmap {
        val drawable: Drawable = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher))
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
