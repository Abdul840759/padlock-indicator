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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class LockIndicatorService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var km: KeyguardManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var cm: CameraManager
    private lateinit var dm: DisplayManager
    private val adminName by lazy { ComponentName(this, LockDeviceAdmin::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    private var pill: LockPillView? = null
    private var pillAddedAt = 0L
    private var touchView: View? = null
    private var touchAddedAt = 0L
    private var registered = false

    private var awake = false
    private var screenLocked = false
    private var unlocking = false
    private var wakeAt = 0L
    private var presentAt = 0L

    // unlock-method signals, reset on every wake
    @Volatile
    private var touches = 0
    private var typingSeen = false

    // front camera tracking (face unlock opens it)
    private val frontIds = HashSet<String>()
    private var camBusy = false
    private var camBusyAt = 0L
    private var camFreeAt = 0L

    private val hints = listOf(
        "numpadkey", "passwordtextview", "lockpatternview", "edittext",
        "keyguardpin", "keyguardpassword", "keyguardpattern"
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onWake("broadcast")
                Intent.ACTION_USER_PRESENT -> {
                    presentAt = SystemClock.elapsedRealtime()
                    screenLocked = false
                    // small wait so a late admin callback or camera release can still arrive
                    handler.postDelayed({ playUnlock() }, 250)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    awake = false
                    screenLocked = false
                }
            }
        }
    }

    // fires when the display panel itself turns on/off, earlier than the broadcasts
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val state = dm.getDisplay(displayId)?.state ?: return
            if (state == Display.STATE_ON) {
                onWake("display")
            } else if (state == Display.STATE_OFF) {
                onSleep()
            }
        }
    }

    private val camCb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            if (cameraId !in frontIds) return
            camBusy = true
            val now = SystemClock.elapsedRealtime()
            if (awake && screenLocked) {
                camBusyAt = now
                camFreeAt = 0L
            }
            Log.d("PadlockCam", "front camera busy at +" + (now - wakeAt) + "ms")
        }

        override fun onCameraAvailable(cameraId: String) {
            if (cameraId !in frontIds) return
            camBusy = false
            val now = SystemClock.elapsedRealtime()
            if (awake && screenLocked && camBusyAt != 0L) camFreeAt = now
            Log.d("PadlockCam", "front camera free at +" + (now - wakeAt) + "ms")
        }
    }

    override fun onServiceConnected() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

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
            dm.registerDisplayListener(displayListener, handler)
            try {
                for (id in cm.cameraIdList) {
                    val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) frontIds.add(id)
                }
                cm.registerAvailabilityCallback(camCb, handler)
            } catch (e: Exception) {
                Log.d("Padlock", "camera watch failed: $e")
            }
            registered = true
        }

        // load the overlay window now so it is ready before the first wake
        ensureWindow()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        awake = false
        if (pm.isInteractive) onWake("connect") else onSleep()
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
            try {
                dm.unregisterDisplayListener(displayListener)
            } catch (_: Exception) {
            }
            try {
                cm.unregisterAvailabilityCallback(camCb)
            } catch (_: Exception) {
            }
            registered = false
        }
        handler.removeCallbacksAndMessages(null)
        pill?.let {
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

    // ---------- wake / sleep ----------

    private fun onWake(source: String) {
        if (awake) return
        awake = true
        unlocking = false
        wakeAt = SystemClock.elapsedRealtime()
        touches = 0
        typingSeen = false
        camBusyAt = if (camBusy) wakeAt else 0L
        camFreeAt = 0L
        screenLocked = km.isKeyguardLocked
        Log.d("Padlock", "wake via $source locked=$screenLocked")
        if (screenLocked) showLock() else hideLock()
    }

    // display is off: park the closed padlock in the window so it is there on the first frame of the next wake
    private fun onSleep() {
        awake = false
        screenLocked = false
        unlocking = false
        handler.removeCallbacksAndMessages(null)
        val v = ensureWindow() ?: return
        v.animate().cancel()
        v.alpha = 1f
        v.play(LockPillView.Mode.LOCKED)
        v.visibility = View.VISIBLE
    }

    // ---------- overlay windows (created once, kept alive) ----------

    @Suppress("DEPRECATION")
    private fun ensureWindow(): LockPillView? {
        pill?.let {
            if (it.isAttachedToWindow || SystemClock.elapsedRealtime() - pillAddedAt < 1000L) {
                addTouchWatcher()
                return it
            }
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
        view.visibility = View.INVISIBLE
        return try {
            wm.addView(view, params)
            pill = view
            pillAddedAt = SystemClock.elapsedRealtime()
            addTouchWatcher()
            view
        } catch (e: Exception) {
            Log.d("Padlock", "addView failed: $e")
            null
        }
    }

    // 1px invisible window that counts taps anywhere else on the screen while locked
    @Suppress("DEPRECATION")
    private fun addTouchWatcher() {
        touchView?.let {
            if (it.isAttachedToWindow || SystemClock.elapsedRealtime() - touchAddedAt < 1000L) return
        }
        val v = View(this)
        v.setOnTouchListener { _, ev ->
            if (screenLocked && ev.actionMasked == MotionEvent.ACTION_OUTSIDE) touches++
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
            touchAddedAt = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
        }
    }

    private fun showLock() {
        handler.removeCallbacksAndMessages(null)
        unlocking = false
        val v = ensureWindow() ?: return
        v.animate().cancel()
        v.alpha = 1f
        v.visibility = View.VISIBLE
        v.play(LockPillView.Mode.IDLE)
    }

    private fun hideLock() {
        handler.removeCallbacksAndMessages(null)
        pill?.let {
            it.animate().cancel()
            it.stop()
            it.alpha = 1f
            it.visibility = View.INVISIBLE
        }
    }

    // ---------- unlock ----------

    private fun playUnlock() {
        val v = pill ?: return
        if (v.visibility != View.VISIBLE || unlocking) return
        unlocking = true

        val adminOn = dpm.isAdminActive(adminName)
        val sinceCb = presentAt - UnlockInfo.lastPasswordAt
        val byCallback = adminOn && sinceCb in 0L..6000L
        val byTyping = typingSeen
        val byTouches = touches >= TOUCH_THRESHOLD
        val credential = byCallback || byTyping || byTouches

        // face unlock: front camera was used after wake and released just before the unlock
        val camGap = if (camFreeAt != 0L) presentAt - camFreeAt else Long.MIN_VALUE
        val byFace = !credential && camBusyAt != 0L && camFreeAt != 0L &&
            camGap in (-FACE_SLACK_MS)..FACE_WINDOW_MS

        val mode = when {
            credential -> LockPillView.Mode.UNLOCK_PIN
            byFace -> LockPillView.Mode.UNLOCK_FACE
            else -> LockPillView.Mode.UNLOCK_BIO
        }

        val camBusyMs = if (camBusyAt != 0L) camBusyAt - wakeAt else -1L
        val camFreeMs = if (camFreeAt != 0L) camGap else -1L
        Log.d(
            "Padlock",
            "unlock: callback=$byCallback typing=$byTyping touches=$touches " +
                "camBusyAfterWake=${camBusyMs}ms camFreeBeforeUnlock=${camFreeMs}ms -> $mode"
        )

        handler.removeCallbacksAndMessages(null)
        v.play(mode) {
            handler.postDelayed({
                v.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { hideLock() }
                    .start()
            }, 150)
        }
    }

    companion object {
        private const val CHANNEL_ID = "padlock_keepalive"
        private const val TOUCH_THRESHOLD = 3
        private const val FACE_WINDOW_MS = 1500L
        private const val FACE_SLACK_MS = 0L
    }
}
