package com.padlock.indicator

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object UnlockInfo {
    @Volatile
    var lastPasswordAt: Long = 0L
}

class LockDeviceAdmin : DeviceAdminReceiver() {
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        UnlockInfo.lastPasswordAt = SystemClock.elapsedRealtime()
    }
}
