package com.guidelens.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("guidelens", Context.MODE_PRIVATE)

    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) { sp.edit().putBoolean("audio", v).apply() }

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) { sp.edit().putBoolean("haptics", v).apply() }

    var verbosity: String
        get() = sp.getString("verbosity", "standard") ?: "standard"
        set(v) { sp.edit().putString("verbosity", v).apply() }

    var sensitivity: String
        get() = sp.getString("sensitivity", "standard") ?: "standard"
        set(v) { sp.edit().putString("sensitivity", v).apply() }

    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) { sp.edit().putFloat("rate", v).apply() }

    var contact: String
        get() = sp.getString("contact", "") ?: ""
        set(v) { sp.edit().putString("contact", v).apply() }

    var themeDark: Boolean
        get() = sp.getBoolean("themeDark", true)
        set(v) { sp.edit().putBoolean("themeDark", v).apply() }

    var simple: Boolean
        get() = sp.getBoolean("simple", false)
        set(v) { sp.edit().putBoolean("simple", v).apply() }

    var agreed: Boolean
        get() = sp.getBoolean("agreed", false)
        set(v) { sp.edit().putBoolean("agreed", v).apply() }

    var tutorialDone: Boolean
        get() = sp.getBoolean("tutorialDone", false)
        set(v) { sp.edit().putBoolean("tutorialDone", v).apply() }

    /**
     * Horizontal field of view of the rear camera, in degrees, for the
     * monocular distance math. Phones vary (~55-80+ deg); override per device
     * via calibration (see docs/CALIBRATION.md). Default matches the old
     * hardcoded value so behavior is unchanged until calibrated.
     */
    var cameraHfovDeg: Float
        get() = sp.getFloat("cameraHfovDeg", 62f)
        set(v) { sp.edit().putFloat("cameraHfovDeg", v.coerceIn(40f, 100f)).apply() }
}
