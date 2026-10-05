package com.padlock.indicator

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("padlock_prefs", Context.MODE_PRIVATE)

    // extra distance below the status bar, in dp (-10..70)
    var yOffset: Int
        get() = sp.getInt("y", 2)
        set(v) = sp.edit().putInt("y", v).apply()

    // 70..150
    var sizePct: Int
        get() = sp.getInt("size", 100)
        set(v) = sp.edit().putInt("size", v).apply()

    // 50..150
    var speedPct: Int
        get() = sp.getInt("speed", 100)
        set(v) = sp.edit().putInt("speed", v).apply()

    var showFace: Boolean
        get() = sp.getBoolean("face", true)
        set(v) = sp.edit().putBoolean("face", v).apply()

    var showFingerprint: Boolean
        get() = sp.getBoolean("fp", true)
        set(v) = sp.edit().putBoolean("fp", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var charging: Boolean
        get() = sp.getBoolean("charging", true)
        set(v) = sp.edit().putBoolean("charging", v).apply()

    var autoColor: Boolean
        get() = sp.getBoolean("autocolor", true)
        set(v) = sp.edit().putBoolean("autocolor", v).apply()

    var failShake: Boolean
        get() = sp.getBoolean("failshake", true)
        set(v) = sp.edit().putBoolean("failshake", v).apply()
}
