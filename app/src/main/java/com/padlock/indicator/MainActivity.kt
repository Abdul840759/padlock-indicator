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
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val adminName by lazy { ComponentName(this, LockDeviceAdmin::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 0, 0, pad)
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

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        })
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
