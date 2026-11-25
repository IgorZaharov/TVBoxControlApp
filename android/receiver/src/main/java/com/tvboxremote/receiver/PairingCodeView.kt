package com.tvboxremote.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** A card that shows the pairing code at the top of the TV screen. */
@SuppressLint("ViewConstructor")
class PairingCodeView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density

    private val codeText = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.25f
        gravity = Gravity.CENTER
    }

    private val phoneText = TextView(context).apply {
        setTextColor(Color.rgb(0xE8, 0xEA, 0xF0))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val padding = (24 * density).toInt()
        setPadding(padding, padding, padding, padding)
        background = GradientDrawable().apply {
            setColor(Color.argb(0xF0, 0x1C, 0x1E, 0x24))
            cornerRadius = 20 * density
            setStroke((2 * density).toInt(), Color.rgb(0x4F, 0x8C, 0xFF))
        }
        addView(TextView(context).apply {
            text = context.getString(R.string.pairing_title)
            setTextColor(Color.rgb(0x8A, 0x8F, 0x9C))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
        })
        addView(codeText)
        addView(phoneText)
    }

    fun show(code: String, phoneName: String) {
        codeText.text = code
        phoneText.text = context.getString(R.string.pairing_hint, phoneName)
    }
}
