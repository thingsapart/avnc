package com.gaurav.avnc.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresPermission

object DisplayUtils {

    const val DISPLAY_SIZE_KEY = "display_size_forced" // Or Settings.Global.DISPLAY_SIZE_FORCED
    const val XR_DISPLAY_WIDTH = 1920
    const val XR_DISPLAY_HEIGHT = 1080

    /**
     * Sets the display size override.
     * Requires the WRITE_SECURE_SETTINGS permission.
     * The change is applied immediately.
     *
     * @param context The application context.
     * @param width The desired width in pixels.
     * @param height The desired height in pixels.
     * @return True if the value was set, false otherwise (e.g., due to a security exception).
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    fun setDisplaySize(context: Context, width: Int, height: Int): Boolean {
        return try {
            val sizeString = "${width}x${height}"
            Settings.Global.putString(context.contentResolver, DISPLAY_SIZE_KEY, sizeString)
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Resets the display size override to the device's physical default.
     * Requires the WRITE_SECURE_SETTINGS permission.
     * The change is applied immediately.
     *
     * @param context The application context.
     * @return True if the value was reset, false otherwise.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    fun resetDisplaySize(context: Context): Boolean {
        return try {
            // Setting the value to null or an empty string resets it.
            Settings.Global.putString(context.contentResolver, DISPLAY_SIZE_KEY, null)
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Gets the real physical screen resolution of the device's default display.
     * This is production-ready and handles different Android API levels.
     *
     * @param context The application context.
     * @return A string in the format "widthxheight", e.g., "1080x1920", or null if it fails.
     */
    fun getPhysicalDisplaySize(context: Context): String? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            // Should not happen on a standard device.
            return null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11 (API 30) and newer
            val windowMetrics = windowManager.maximumWindowMetrics
            val bounds = windowMetrics.bounds
            "${bounds.width()}x${bounds.height()}"
        } else {
            // For older versions (below Android 11)
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") // getRealMetrics is deprecated but needed for older APIs
            display.getRealMetrics(metrics)
            "${metrics.widthPixels}x${metrics.heightPixels}"
        }
    }
}
