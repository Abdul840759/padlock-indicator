package com.padlock.indicator

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log

object UnlockInfo {
    @Volatile
    var lastPasswordAt: Long = 0L
}

class LockDeviceAdmin : DeviceAdminReceiver() {

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        UnlockInfo.lastPasswordAt = SystemClock.elapsedRealtime()
        Log.d("Padlock", "admin callback: password succeeded")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        UnlockInfo.lastPasswordAt = SystemClock.elapsedRealtime()
        Log.d("Padlock", "admin callback: password succeeded (user)")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        Log.d("Padlock", "admin callback: password failed")
    }
}
