package com.jarvis.assistant.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding

internal object AyhaSettingsUi {
    private const val INK = 0xFF1F2937.toInt()
    private const val MUTED = 0xFF738096.toInt()
    private const val PURPLE = 0xFF7C3AED.toInt()
    private const val BORDER = 0xFFE7DDF8.toInt()

    fun page(activity: AppCompatActivity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(activity.dp(20), activity.dp(10), activity.dp(20), activity.dp(12))
        setBackgroundColor(Color.WHITE)
    }

    fun header(activity: AppCompatActivity, parent: LinearLayout, title: String, subtitle: String) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, activity.dp(14))
        }
        row.addView(ImageButton(activity).apply {
            contentDescription = "Back"
            setImageResource(com.jarvis.assistant.R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(INK)
            background = rounded(Color.WHITE, BORDER, activity.dp(18), activity.dp(1))
            setPadding(activity.dp(10))
            setOnClickListener { activity.finish() }
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        val titles = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), 0, 0, 0)
        }
        titles.addView(TextView(activity).apply {
            text = title
            textSize = 20f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(activity).apply {
            text = subtitle
            textSize = 12f
            setTextColor(MUTED)
            maxLines = 2
        })
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(activity).apply {
            setImageResource(com.jarvis.assistant.R.drawable.ayha_brand_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "AYHA AI logo"
        }, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))
        parent.addView(row)
    }

    fun scroll(activity: AppCompatActivity, root: LinearLayout): LinearLayout {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, activity.dp(4), 0, activity.dp(18))
        }
        root.addView(ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return content
    }

    fun sectionTitle(activity: AppCompatActivity, parent: LinearLayout, title: String) {
        parent.addView(TextView(activity).apply {
            text = title
            textSize = 13f
            setTextColor(MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(2, activity.dp(10), 0, activity.dp(8))
        })
    }

    fun card(activity: AppCompatActivity, parent: LinearLayout, block: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16))
            background = rounded(Color.WHITE, BORDER, activity.dp(22), activity.dp(1))
            elevation = activity.dp(1).toFloat()
            block()
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) })
        }

    fun label(activity: AppCompatActivity, parent: LinearLayout, text: String) {
        parent.addView(TextView(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(MUTED)
            setPadding(2, activity.dp(10), 0, activity.dp(4))
        })
    }

    fun edit(activity: AppCompatActivity, hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(INK)
        setHintTextColor(0xFF9AA4B2.toInt())
        setSingleLine(true)
        setPadding(activity.dp(14), 0, activity.dp(14), 0)
        background = rounded(Color.WHITE, 0xFFD9D1E8.toInt(), activity.dp(16), activity.dp(1))
        minHeight = activity.dp(54)
    }

    fun spinner(activity: AppCompatActivity): Spinner = Spinner(activity).apply {
        background = rounded(Color.WHITE, 0xFFD9D1E8.toInt(), activity.dp(16), activity.dp(1))
        minimumHeight = activity.dp(54)
        setPadding(activity.dp(10), 0, activity.dp(10), 0)
    }

    fun button(activity: AppCompatActivity, text: String, primary: Boolean = false, onClick: (Button) -> Unit): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else PURPLE)
        background = if (primary) rounded(PURPLE, PURPLE, activity.dp(18), 0)
            else rounded(Color.WHITE, 0xFFBDA8F1.toInt(), activity.dp(16), activity.dp(1))
        minHeight = activity.dp(if (primary) 56 else 48)
        setOnClickListener { onClick(this) }
    }

    fun status(activity: AppCompatActivity, parent: LinearLayout): TextView = TextView(activity).apply {
        textSize = 13f
        setTextColor(MUTED)
        setPadding(activity.dp(2), activity.dp(6), activity.dp(2), activity.dp(8))
        parent.addView(this)
    }

    fun info(activity: AppCompatActivity, parent: LinearLayout, text: String) {
        parent.addView(TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextColor(MUTED)
            setPadding(activity.dp(2), activity.dp(8), activity.dp(2), activity.dp(6))
        })
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int, strokeWidth: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    private fun AppCompatActivity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
