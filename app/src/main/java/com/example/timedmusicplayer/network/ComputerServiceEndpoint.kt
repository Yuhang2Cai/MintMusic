package com.example.timedmusicplayer.network

import android.os.Build
import com.example.timedmusicplayer.BuildConfig

/**
 * Resolves the development computer service.
 *
 * A physical device reaches DEVICE_LOOPBACK_URL through `adb reverse tcp:8000 tcp:8000`.
 * Use scripts/install-device-debug.ps1 before installing a debug build on USB-connected devices.
 */
object ComputerServiceEndpoint {
    val baseUrl: String
        get() = if (isEmulator()) BuildConfig.LYRICS_API_BASE_URL else DEVICE_LOOPBACK_URL

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")

    private const val DEVICE_LOOPBACK_URL = "http://127.0.0.1:8000"
}
