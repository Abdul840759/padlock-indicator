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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class LockIndicatorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var km: KeyguardManager
    private lateinit var dpm: DevicePolicyManager
    private val adminName by lazy { ComponentName(this, LockDeviceAdmin::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var pill: LockPillView? = null
    private var touchView: View? = null
    private var unlocking = false
    private var registered = false

    // unlock-method signals, reset on every screen-on
    @Volatile
    private var touches = 0
    private var typingSeen = false
    private var screenLocked = false

    private val hints = listOf(
        "numpadkey", "passwordtextview", "lockpatternview", "edittext",
        "keyguardpin", "keyguardpassword", "keyguardpattern"
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    touches = 0
                    typingSeen = false
                    screenLocked = km.isKeyguardLocked
                    if (screenLocked) showLock()
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenLocked = false
                    // small wait so a late admin callback can still arrive
                    handler.postDelayed({ playUnlock() }, 250)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenLocked = false
                    removeLock()
                }
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

        screenLocked = km.isKeyguardLocked
        if (screenLocked) showLock()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (!screenLocked) return

        val cls = e.className?.toString() ?: ""
        val lower = cls.lowercase()
        val hint = hints.any { lower.contains(it) }
        val type = e.eventType

        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || hint) {
            Log.d("PadlockEv", AccessibilityEvent.eventTypeToString(type) + " cls=" + cls)
        }

        if (hint &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            typingSeen = true
        }
    }

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

    // 1px invisible window that counts taps anywhere else on the screen
    @Suppress("DEPRECATION")
    private fun addTouchWatcher() {
        if (touchView != null) return
        val v = View(this)
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) touches++
            false
        }
        val p = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        try {
            wm.addView(v, p)
            touchView = v
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun showLock() {
        handler.removeCallbacksAndMessages(null)
        unlocking = false
        addTouchWatcher()

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

        val now = SystemClock.elapsedRealtime()
        val byCallback = dpm.isAdminActive(adminName) &&
            now - UnlockInfo.lastPasswordAt < 6000L
        val byTyping = typingSeen
        val byTouches = touches >= TOUCH_THRESHOLD
        val credential = byCallback || byTyping || byTouches

        val mode = if (credential) {
            LockPillView.Mode.UNLOCK_PIN
        } else {
            LockPillView.Mode.UNLOCK_BIO
        }
        Log.d(
            "Padlock",
            "unlock: callback=$byCallback typing=$byTyping touches=$touches -> $mode"
        )

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
        touchView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        touchView = null
    }

    companion object {
        private const val CHANNEL_ID = "padlock_keepalive"
        private const val TOUCH_THRESHOLD = 3
    }
}
