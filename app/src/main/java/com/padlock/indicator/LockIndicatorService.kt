package com.padlock.indicator

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class LockIndicatorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var km: KeyguardManager
    private lateinit var dpm: DevicePolicyManager
    private val adminName by lazy { ComponentName(this, LockDeviceAdmin::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var pill: LockPillView? = null
    private var unlocking = false
    private var registered = false

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
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        goForeground()

        if (!registered) {
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
            registered = true
        }

        if (km.isKeyguardLocked) showLock()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (registered) {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            registered = false
        }
        removeLock()
        super.onDestroy()
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Padlock Indicator", NotificationManager.IMPORTANCE_MIN)
            )
            val n = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Padlock Indicator is running")
                .setOngoing(true)
                .build()
            startForeground(1, n)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun showLock() {
        handler.removeCallbacksAndMessages(null)
        unlocking = false

        pill?.let {
            it.animate().cancel()
            it.alpha = 1f
            it.play(LockPillView.Mode.IDLE)
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
            y = statusBar + (2 * density).toInt()
        }

        val view = LockPillView(this)
        try {
            wm.addView(view, params)
            pill = view
            view.play(LockPillView.Mode.IDLE)
        } catch (_: Exception) {
        }
    }

    private fun playUnlock() {
        val v = pill ?: return
        if (unlocking) return
        unlocking = true

        val adminOn = dpm.isAdminActive(adminName)
        val viaPassword = adminOn &&
            SystemClock.elapsedRealtime() - UnlockInfo.lastPasswordAt < 4000L
        val mode = if (adminOn && !viaPassword) {
            LockPillView.Mode.UNLOCK_BIO
        } else {
            LockPillView.Mode.UNLOCK_PIN
        }
        Log.d("Padlock", "unlock: admin=$adminOn password=$viaPassword mode=$mode")

        handler.removeCallbacksAndMessages(null)
        v.play(mode) {
            handler.postDelayed({
                v.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { removeLock() }
                    .start()
            }, 150)
        }
    }

    private fun removeLock() {
        handler.removeCallbacksAndMessages(null)
        unlocking = false
        pill?.let {
            it.animate().cancel()
            it.stop()
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        pill = null
    }

    companion object {
        private const val CHANNEL_ID = "padlock_keepalive"
    }
}
