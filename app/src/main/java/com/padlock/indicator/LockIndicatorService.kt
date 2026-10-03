package com.padlock.indicator

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.OvershootInterpolator

class LockIndicatorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var km: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())

    private var lockView: LockView? = null
    private var unlocking = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> if (km.isKeyguardLocked) showLock()
                Intent.ACTION_USER_PRESENT -> playUnlock()
                Intent.ACTION_SCREEN_OFF -> removeLock()
            }
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        if (km.isKeyguardLocked) showLock()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        removeLock()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun showLock() {
        handler.removeCallbacksAndMessages(null)
        unlocking = false

        lockView?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.openProgress = 0f
            return
        }

        val density = resources.displayMetrics.density
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (sbId > 0) resources.getDimensionPixelSize(sbId) else 0

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBar + (4 * density).toInt()
        }

        val view = LockView(this)
        try {
            wm.addView(view, params)
            lockView = view
        } catch (_: Exception) {
        }
    }

    private fun playUnlock() {
        val view = lockView ?: return
        if (unlocking) return
        unlocking = true

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { view.openProgress = it.animatedValue as Float }
            start()
        }

        handler.postDelayed({
            view.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { removeLock() }
                .start()
        }, 650)
    }

    private fun removeLock() {
        handler.removeCallbacksAndMessages(null)
        unlocking = false
        lockView?.let {
            it.animate().cancel()
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        lockView = null
    }
}
