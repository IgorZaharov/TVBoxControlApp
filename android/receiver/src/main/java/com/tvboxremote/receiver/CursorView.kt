package com.tvboxremote.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * A full-screen transparent view that draws an arrow cursor. The tip of the
 * arrow is the hot spot at ([cursorX], [cursorY]).
 */
@SuppressLint("ViewConstructor")
class CursorView(context: Context) : View(context) {
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorVisible = false

    private val density = resources.displayMetrics.density

    /** The arrow outline. The source shape is 30 units high, scaled to about 32 dp. */
    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 26f)
        lineTo(7f, 20f)
        lineTo(11.5f, 29.5f)
        lineTo(15.5f, 27.5f)
        lineTo(11f, 18.5f)
        lineTo(19.5f, 18.5f)
        close()
        val scale = 32f / 30f * density
        transform(Matrix().apply { setScale(scale, scale) })
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x16, 0x17, 0x1B)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }

    fun update(x: Float, y: Float, visible: Boolean) {
        if (x == cursorX && y == cursorY && visible == cursorVisible) return
        cursorX = x
        cursorY = y
        cursorVisible = visible
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!cursorVisible) return
        val save = canvas.save()
        canvas.translate(cursorX, cursorY)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
        canvas.restoreToCount(save)
    }
}
