package com.padlock.indicator

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val text = TextView(this).apply {
            text = "1. Tap the button below\n" +
                "2. Open Installed apps / Downloaded apps\n" +
                "3. Turn on Padlock Indicator\n\n" +
                "Then lock your phone and wake it up."
            textSize = 16f
        }
        val button = Button(this).apply {
            this.text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            addView(text)
            addView(button)
        })
    }
}
