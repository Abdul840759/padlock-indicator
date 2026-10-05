package com.padlock.indicator

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
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
    private lateinit var prefs: Prefs
    private lateinit var stats: Stats
    private val adminName by lazy { ComponentName(this, LockDeviceAdmin::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    // separate queue so UI cleanups never cancel the face-result check
    private val checkHandler = Handler(Looper.getMainLooper())

    private var pill: LockPillView? = null
    private var pillAddedAt = 0L
    private var touchView: View? = null
    private var touchAddedAt = 0L
    private var registered = false

    private var awake = false
    private var screenLocked = false
    private var unlocking = false
    private var idleRunning = false
    private var wakeAt = 0L
    private var presentAt = 0L
    private var lastSleepAt = 0L

    private var wallpaperLight = false

    // charging pill bookkeeping
    private var pendingCharge = false
    private var pendingLow = false
    private var chargeConnectedAt = 0L

    // unlock-method signals, reset on every wake
    @Volatile
    private var touches = 0
    private var typingSeen = false
    private var faceVerified = false
    private var faceTextSeen = false
    private var lastFaceFailAt = 0L
    private val lastFail = HashMap<String, Long>()

    // front camera tracking: always on, never reset on wake (face unlock can finish before the wake is noticed)
    private val frontIds = HashSet<String>()
    private var camBusy = false
    private var camSessionStart = 0L
    private var camSessionEnd = 0L

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
                    // small wait so a late camera release or admin callback can still arrive
                    handler.postDelayed({ playUnlock() }, 250)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    awake = false
                    screenLocked = false
                }
                Intent.ACTION_POWER_CONNECTED -> onPower(false)
                Intent.ACTION_BATTERY_LOW -> onPower(true)
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
            val now = SystemClock.elapsedRealtime()
            if (!camBusy) {
                camSessionStart = now
                camSessionEnd = 0L
            }
            camBusy = true
            Log.d("PadlockCam", "front busy at wake+" + (now - wakeAt) + "ms awake=" + awake)
        }

        override fun onCameraAvailable(cameraId: String) {
            if (cameraId !in frontIds) return
            val now = SystemClock.elapsedRealtime()
            val wasBusy = camBusy
            camBusy = false
            if (!wasBusy) return
            camSessionEnd = now
            Log.d(
                "PadlockCam",
                "front free after " + (now - camSessionStart) + "ms awake=" + awake + " locked=" + screenLocked
            )
            if (awake && screenLocked) scheduleFaceCheck(camSessionStart, now)
        }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        stats = Stats(this)
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
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
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

        refreshWallpaperTone()
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

        // read lockscreen messages, but never anything from the PIN / password entry
        if (!hint) {
            val text = e.text?.joinToString(" ")?.lowercase() ?: ""
            if (text.isNotBlank()) handleText(text)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
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
        checkHandler.removeCallbacksAndMessages(null)
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

    // ---------- lockscreen text, face / fingerprint / PIN failures ----------

    private fun handleText(text: String) {
        Log.d("PadlockEv", "txt=" + text.take(80))
        val notRecognized = text.contains("not recogni") || text.contains("couldn't recogni") ||
            text.contains("can't recogni") || text.contains("didn't recogni") ||
            text.contains("try again") || text.contains("unable")
        when {
            text.contains("face") -> {
                if (notRecognized) {
                    onFaceFail("text")
                } else if (text.contains("unlocked by face") || text.contains("face unlocked") ||
                    text.contains("face recognized") || text.contains("recognized face")
                ) {
                    faceTextSeen = true
                }
            }
            text.contains("finger") -> {
                if (notRecognized || text.contains("not match")) recordFail("FP_FAIL", text.take(30))
            }
            text.contains("wrong") || text.contains("incorrect") -> {
                recordFail("PIN_FAIL", text.take(30))
            }
        }
    }

    private fun recordFail(kind: String, note: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastFail[kind] ?: 0L) < 1500L) return
        lastFail[kind] = now
        stats.add(kind, 0L, note.replace(',', ' ').replace('\n', ' '))
    }

    private fun onFaceFail(source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFaceFailAt < 3000L) return
        lastFaceFailAt = now
        Log.d("Padlock", "face not recognized ($source)")
        stats.add("FACE_FAIL", 0L, "src=$source")
        shakeFail()
    }

    // a face scan ended: if the phone is still locked shortly after, it did not recognize the face
    private fun scheduleFaceCheck(start: Long, end: Long) {
        if (end - start < 500L) return // too short to be a real scan
        checkHandler.removeCallbacksAndMessages(null)
        checkHandler.postDelayed({ checkFaceResult(start, end) }, 1300L)
    }

    private fun checkFaceResult(start: Long, end: Long) {
        if (camSessionStart != start || camSessionEnd != end) return // newer session started
        if (!awake || !screenLocked || unlocking) return
        val deviceLocked = km.isDeviceLocked
        Log.d("Padlock", "face scan ended, still on lockscreen: deviceLocked=$deviceLocked")
        if (!deviceLocked) {
            // face worked, the lockscreen is only waiting for a swipe
            faceVerified = true
            return
        }
        onFaceFail("camera")
    }

    private fun shakeFail() {
        if (!prefs.failShake) return
        val v = pill ?: return
        if (!awake || !screenLocked || unlocking || v.visibility != View.VISIBLE) return
        idleRunning = false
        v.animate().cancel()
        v.alpha = 1f
        v.lockVisible = true
        v.play(LockPillView.Mode.FAIL) {
            v.play(LockPillView.Mode.LOCKED)
            runPendingCharge()
        }
        if (prefs.haptics) buzzFail()
    }

    // ---------- settings, called from the app screen ----------

    fun onSettingsChanged() {
        pill?.let { applySettings(it) }
    }

    // keeps the closed padlock on screen while a slider is being dragged
    fun showStatic() {
        if (screenLocked) return
        val v = ensureWindow() ?: return
        applySettings(v)
        handler.removeCallbacksAndMessages(null)
        idleRunning = false
        v.animate().cancel()
        v.alpha = 1f
        v.lockVisible = true
        v.visibility = View.VISIBLE
        v.play(LockPillView.Mode.LOCKED)
    }

    // plays any animation over the current screen, then hides it
    fun preview(mode: LockPillView.Mode) {
        if (screenLocked) return
        val v = ensureWindow() ?: return
        applySettings(v)
        handler.removeCallbacksAndMessages(null)
        idleRunning = false
        v.animate().cancel()
        v.alpha = 1f
        v.lockVisible = mode != LockPillView.Mode.CHARGE
        v.chargeLevel = batteryLevel()
        v.chargeLow = false
        v.visibility = View.VISIBLE
        v.play(mode) {
            handler.postDelayed({
                v.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { hideLock() }
                    .start()
            }, 400)
        }
    }

    private fun applySettings(v: LockPillView) {
        v.scale = prefs.sizePct / 100f
        v.speed = prefs.speedPct / 100f
        v.showFace = prefs.showFace
        v.showFp = prefs.showFingerprint
        v.adaptiveColor = if (prefs.autoColor && wallpaperLight) 0xFF1C1C1E.toInt() else Color.WHITE
        updatePosition(v)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun targetY(): Int =
        statusBarHeight() + (prefs.yOffset * resources.displayMetrics.density).toInt()

    private fun updatePosition(v: LockPillView) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val y = targetY()
        if (lp.y != y) {
            lp.y = y
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshWallpaperTone() {
        if (Build.VERSION.SDK_INT < 27) return
        try {
            val wmgr = WallpaperManager.getInstance(this)
            val colors = wmgr.getWallpaperColors(WallpaperManager.FLAG_LOCK)
                ?: wmgr.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            if (colors != null) {
                wallpaperLight = if (Build.VERSION.SDK_INT >= 29) {
                    (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                } else {
                    Color.luminance(colors.primaryColor.toArgb()) > 0.6f
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun batteryLevel(): Int {
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0
        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) level * 100 / scale else 0
    }

    // ---------- wake / sleep ----------

    private fun onWake(source: String) {
        if (awake) return
        awake = true
        unlocking = false
        wakeAt = SystemClock.elapsedRealtime()
        touches = 0
        typingSeen = false
        faceVerified = false
        faceTextSeen = false
        checkHandler.removeCallbacksAndMessages(null)
        screenLocked = km.isKeyguardLocked
        Log.d("Padlock", "wake via $source locked=$screenLocked")

        // plugged in a moment ago while the screen was off
        if (prefs.charging && wakeAt - chargeConnectedAt < 4000L) {
            pendingCharge = true
            pendingLow = false
            chargeConnectedAt = 0L
        }

        if (screenLocked) {
            showLock()
        } else {
            hideLock()
            runPendingCharge()
        }
    }

    // display is off: park the closed padlock in the window so it is there on the first frame of the next wake
    private fun onSleep() {
        awake = false
        screenLocked = false
        unlocking = false
        idleRunning = false
        pendingCharge = false
        lastSleepAt = SystemClock.elapsedRealtime()
        handler.removeCallbacksAndMessages(null)
        checkHandler.removeCallbacksAndMessages(null)
        refreshWallpaperTone()
        val v = ensureWindow() ?: return
        applySettings(v)
        v.animate().cancel()
        v.alpha = 1f
        v.lockVisible = true
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
            y = targetY()
        }

        val view = LockPillView(this)
        view.visibility = View.INVISIBLE
        applySettings(view)
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
        applySettings(v)
        v.animate().cancel()
        v.alpha = 1f
        v.lockVisible = true
        v.visibility = View.VISIBLE
        if (!prefs.showFace && !prefs.showFingerprint) {
            idleRunning = false
            v.play(LockPillView.Mode.LOCKED)
            runPendingCharge()
        } else {
            idleRunning = true
            v.play(LockPillView.Mode.IDLE) {
                idleRunning = false
                runPendingCharge()
            }
        }
    }

    private fun hideLock() {
        handler.removeCallbacksAndMessages(null)
        idleRunning = false
        pill?.let {
            it.animate().cancel()
            it.stop()
            it.alpha = 1f
            it.visibility = View.INVISIBLE
        }
    }

    // ---------- charging pill ----------

    private fun onPower(low: Boolean) {
        if (!prefs.charging) return
        if (awake) {
            showCharge(low)
        } else if (!low) {
            chargeConnectedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun runPendingCharge() {
        if (pendingCharge) {
            pendingCharge = false
            showCharge(pendingLow)
        }
    }

    private fun showCharge(low: Boolean) {
        if (!awake || unlocking) return
        if (idleRunning) {
            pendingCharge = true
            pendingLow = low
            return
        }
        val v = ensureWindow() ?: return
        applySettings(v)
        handler.removeCallbacksAndMessages(null)
        v.animate().cancel()
        v.alpha = 1f
        v.chargeLevel = batteryLevel()
        v.chargeLow = low
        v.lockVisible = screenLocked
        v.visibility = View.VISIBLE
        v.play(LockPillView.Mode.CHARGE) {
            if (screenLocked) {
                v.lockVisible = true
                v.play(LockPillView.Mode.LOCKED)
            } else {
                hideLock()
            }
        }
    }

    // ---------- unlock ----------

    private fun playUnlock() {
        if (unlocking) return
        unlocking = true
        idleRunning = false
        pendingCharge = false
        checkHandler.removeCallbacksAndMessages(null)

        val adminOn = dpm.isAdminActive(adminName)
        val sinceCb = presentAt - UnlockInfo.lastPasswordAt
        val byCallback = adminOn && sinceCb in 0L..6000L
        val byTyping = typingSeen
        val byTouches = touches >= TOUCH_THRESHOLD
        val credential = byCallback || byTyping || byTouches

        // face unlock: a front-camera scan happened since the screen went off and ended just before the unlock,
        // or the lockscreen already confirmed the face and only waited for a swipe
        val sessionOk = camSessionStart != 0L &&
            camSessionStart >= lastSleepAt - 300L &&
            presentAt - camSessionStart <= 10000L
        val ended = camSessionEnd != 0L && camSessionEnd >= camSessionStart
        val freedInTime = ended &&
            camSessionEnd <= presentAt + FACE_SLACK_MS &&
            presentAt - camSessionEnd <= FACE_WINDOW_MS
        val byFace = !credential && (faceVerified || faceTextSeen || (sessionOk && freedInTime))

        val mode = when {
            credential -> LockPillView.Mode.UNLOCK_PIN
            byFace -> LockPillView.Mode.UNLOCK_FACE
            else -> LockPillView.Mode.UNLOCK_BIO
        }
        val kind = when (mode) {
            LockPillView.Mode.UNLOCK_PIN -> "PIN"
            LockPillView.Mode.UNLOCK_FACE -> "FACE"
            else -> "FINGERPRINT"
        }

        val camStartRel = if (camSessionStart != 0L) camSessionStart - wakeAt else -1L
        val camEndRel = if (camSessionEnd != 0L) camSessionEnd - presentAt else 0L
        Log.d(
            "Padlock",
            "unlock: callback=$byCallback typing=$byTyping touches=$touches " +
                "camSession=$sessionOk freedInTime=$freedInTime camStart=${camStartRel}ms " +
                "camEndVsUnlock=${camEndRel}ms faceVerified=$faceVerified faceText=$faceTextSeen -> $mode"
        )

        val latency = (presentAt - wakeAt).coerceAtLeast(0L)
        stats.add(kind, latency, "t=$touches;ty=$byTyping;cam=$sessionOk/$freedInTime;fv=$faceVerified")

        val v = pill ?: return
        if (v.visibility != View.VISIBLE) return

        handler.removeCallbacksAndMessages(null)
        applySettings(v)
        v.lockVisible = true

        if (prefs.haptics) {
            val strong = mode != LockPillView.Mode.UNLOCK_PIN
            handler.postDelayed({ tick(strong) }, (300f / (prefs.speedPct / 100f)).toLong())
        }

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

    private fun tick(strong: Boolean) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vib.hasVibrator()) return
            val ms = if (strong) 22L else 12L
            val amp = if (strong) 160 else 90
            vib.vibrate(VibrationEffect.createOneShot(ms, amp))
        } catch (_: Exception) {
        }
    }

    // double buzz, like a "no" nod
    private fun buzzFail() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (!vib.hasVibrator()) return
            vib.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 25, 70, 25),
                    intArrayOf(0, 180, 0, 180),
                    -1
                )
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        var instance: LockIndicatorService? = null

        private const val CHANNEL_ID = "padlock_keepalive"
        private const val TOUCH_THRESHOLD = 3
        private const val FACE_WINDOW_MS = 1500L
        private const val FACE_SLACK_MS = 0L
    }
}
