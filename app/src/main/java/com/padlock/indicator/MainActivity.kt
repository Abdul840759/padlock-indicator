package com.padlock.indicator

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var prefs: Prefs
    private val adminName by lazy { ComponentName(this, LockDeviceAdmin::class.java) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun svc() = LockIndicatorService.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(status)

        addButton(root, "1. Turn on the accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(root, "2. Enable password / PIN detection") { requestAdmin() }
        addButton(root, "3. Allow background running") { requestBattery() }
        addButton(root, "4. Open app info (autostart and lock tips)") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        addTitle(root, "Customize")
        addSlider(root, "Position from top", 80, prefs.yOffset + 10, { "${it - 10} dp" }) {
            prefs.yOffset = it - 10
        }
        addSlider(root, "Size", 80, prefs.sizePct - 70, { "${it + 70}%" }) {
            prefs.sizePct = it + 70
        }
        addSlider(root, "Animation speed", 100, prefs.speedPct - 50, { "${it + 50}%" }) {
            prefs.speedPct = it + 50
        }
        addSwitch(root, "Face scan animation", prefs.showFace) { prefs.showFace = it }
        addSwitch(root, "Fingerprint animation", prefs.showFingerprint) { prefs.showFingerprint = it }
        addSwitch(root, "Haptic tick on unlock", prefs.haptics) { prefs.haptics = it }
        addSwitch(root, "Charging pill", prefs.charging) { prefs.charging = it }
        addSwitch(root, "Adapt lock color to wallpaper", prefs.autoColor) { prefs.autoColor = it }

        addTitle(root, "Preview on this screen")
        addPreview(root, "Lock screen sequence", LockPillView.Mode.IDLE)
        addPreview(root, "Unlock: fingerprint", LockPillView.Mode.UNLOCK_BIO)
        addPreview(root, "Unlock: face", LockPillView.Mode.UNLOCK_FACE)
        addPreview(root, "Unlock: PIN / password", LockPillView.Mode.UNLOCK_PIN)
        addPreview(root, "Charging pill", LockPillView.Mode.CHARGE)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        fun yn(b: Boolean) = if (b) "ON" else "OFF"

        status.text = "Accessibility service: " + yn(enabled.contains(packageName)) + "\n" +
            "Password/PIN detection: " + yn(dpm.isAdminActive(adminName)) + "\n" +
            "Battery unrestricted: " + yn(pm.isIgnoringBatteryOptimizations(packageName))
    }

    private fun addTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(24), 0, dp(4))
        })
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun addPreview(parent: LinearLayout, label: String, mode: LockPillView.Mode) {
        addButton(parent, label) {
            val s = svc()
            if (s == null) {
                Toast.makeText(this, "Turn on the accessibility service first", Toast.LENGTH_SHORT).show()
            } else {
                s.preview(mode)
            }
        }
    }

    private fun addSwitch(parent: LinearLayout, name: String, start: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            text = name
            isChecked = start
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
                svc()?.onSettingsChanged()
            }
        })
    }

    private fun addSlider(
        parent: LinearLayout,
        name: String,
        maxValue: Int,
        start: Int,
        fmt: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val label = TextView(this).apply {
            textSize = 14f
            text = name + ": " + fmt(start)
            setPadding(0, dp(12), 0, 0)
        }
        val bar = SeekBar(this).apply {
            this.max = maxValue
            progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = name + ": " + fmt(p)
                    if (fromUser) {
                        onChange(p)
                        svc()?.onSettingsChanged()
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    svc()?.showStatic()
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    svc()?.preview(LockPillView.Mode.IDLE)
                }
            })
        }
        parent.addView(label)
        parent.addView(bar)
    }

    private fun requestAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminName)
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Only used to tell whether you unlocked with a password or PIN. It cannot lock, wipe or read anything."
            )
        startActivity(i)
    }

    private fun requestBattery() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
