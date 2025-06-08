package com.gaurav.avnc.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import java.lang.reflect.InvocationTargetException


object DisplayUtils {

    const val DISPLAY_SIZE_KEY = "display_size_forced"
    const val DENSITY_KEY = "display_density_forced"
    const val XR_DISPLAY_WIDTH = 1920
    const val XR_DISPLAY_HEIGHT = 1080

    private const val TAG = "DisplayUtils"

    private fun getCurrentDensity(context: Context): Int {
        return context.resources.displayMetrics.densityDpi
    }

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
        //val sizeString = "${width},${height}"
        val sizeString = "${height},${width}"
        Log.d(TAG, "Attempting to set display size to: $sizeString")
        return try {
            Settings.Global.putString(context.contentResolver, DISPLAY_SIZE_KEY, sizeString)

            val currentDensity = getCurrentDensity(context)
            Settings.Global.putString(context.contentResolver, DENSITY_KEY, currentDensity.toString())

            val success = ReflectionUtils.triggerSystemConfigurationChange()
            Log.d(TAG, "Triggered config change, success: $success")

            Log.d(TAG, "Successfully set display size to: $sizeString")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to set display size due to SecurityException. Check WRITE_SECURE_SETTINGS permission.", e)
            // e.printStackTrace() // Log.e with exception object is usually enough for logcat
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set display size due to an unexpected error.", e)
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
        Log.d(TAG, "Attempting to reset display size.")
        return try {
            // Setting the value to null or an empty string resets it.
            Settings.Global.putString(context.contentResolver, DISPLAY_SIZE_KEY, null)
            Settings.Global.putString(context.contentResolver, DENSITY_KEY, null)

            Log.d(TAG, "Successfully reset display size.")

            return ReflectionUtils.triggerSystemConfigurationChange()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to reset display size due to SecurityException. Check WRITE_SECURE_SETTINGS permission.", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset display size due to an unexpected error.", e)
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
            "${bounds.width()},${bounds.height()}"
        } else {
            // For older versions (below Android 11)
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") // getRealMetrics is deprecated but needed for older APIs
            display.getRealMetrics(metrics)
            "${metrics.widthPixels},${metrics.heightPixels}"
        }
    }

    /**
     * Sets a forced display size for the default display.
     *
     *
     * This method uses reflection to call the hidden IWindowManager.setForcedDisplaySize() method.
     * It requires the a normal app to have the WRITE_SECURE_SETTINGS permission.
     *
     * @param context The application context.
     * @param width   The desired width in pixels.
     * @param height  The desired height in pixels.
     * @return true if the method was invoked successfully, false otherwise.
     */
    @SuppressLint("PrivateApi")
    fun setForcedDisplaySize(context: Context, width: Int, height: Int): Boolean {
        if (context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denial: " +
                       "App needs android.permission.WRITE_SECURE_SETTINGS permission.")
            return false
        }

        try {
            // Get the IWindowManager service
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val windowManagerBinder = getServiceMethod.invoke(null, "window") as IBinder

            if (windowManagerBinder == null) {
                Log.e(TAG, "Failed to get window manager binder.")
                return false
            }

            val windowManagerStubClass = Class.forName("android.view.IWindowManager\$Stub")
            val asInterfaceMethod = windowManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val windowManager = asInterfaceMethod.invoke(null, windowManagerBinder)

            // Get the setForcedDisplaySize method
            val setForcedDisplaySizeMethod = windowManager!!.javaClass.getMethod(
                    "setForcedDisplaySize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

            // Invoke the method on the default display
            Log.d(TAG, "Setting forced display size to " + width + "x" + height)
            setForcedDisplaySizeMethod.invoke(windowManager, Display.DEFAULT_DISPLAY, width, height)

            return true
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Failed to set display size via reflection", e)
            return false
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Failed to set display size via reflection", e)
            return false
        } catch (e: IllegalAccessException) {
            Log.e(TAG, "Failed to set display size via reflection", e)
            return false
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "Failed to set display size via reflection", e)
            return false
        }
    }

    /**
     * Clears the forced display size, reverting to the physical display size.
     *
     *
     * This method uses reflection to call the hidden IWindowManager.clearForcedDisplaySize() method.
     * It requires the a normal app to have the WRITE_SECURE_SETTINGS permission.
     *
     * @param context The application context.
     * @return true if the method was invoked successfully, false otherwise.
     */
    @SuppressLint("PrivateApi")
    fun clearForcedDisplaySize(context: Context): Boolean {
        if (context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denial: " +
                       "App needs android.permission.WRITE_SECURE_SETTINGS permission.")
            return false
        }

        try {
            // Get the IWindowManager service
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val windowManagerBinder = getServiceMethod.invoke(null, "window") as IBinder

            if (windowManagerBinder == null) {
                Log.e(TAG, "Failed to get window manager binder.")
                return false
            }

            val windowManagerStubClass = Class.forName("android.view.IWindowManager\$Stub")
            val asInterfaceMethod = windowManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val windowManager = asInterfaceMethod.invoke(null, windowManagerBinder)

            // Get the clearForcedDisplaySize method
            val clearForcedDisplaySizeMethod = windowManager!!.javaClass.getMethod(
                    "clearForcedDisplaySize", Int::class.javaPrimitiveType)


            // Invoke the method on the default display
            Log.d(TAG, "Clearing forced display size.")
            clearForcedDisplaySizeMethod.invoke(windowManager, Display.DEFAULT_DISPLAY)

            return true
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Failed to clear display size via reflection", e)
            return false
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Failed to clear display size via reflection", e)
            return false
        } catch (e: IllegalAccessException) {
            Log.e(TAG, "Failed to clear display size via reflection", e)
            return false
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "Failed to clear display size via reflection", e)
            return false
        }
    }

    /**
     * Resets the display cutout emulation developer setting.
     * Requires the WRITE_SECURE_SETTINGS permission.
     * @param context The application context.
     * @return true if successful, false otherwise.
     */
    fun resetDisplayCutout(context: Context): Boolean {
        if (context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission denial: App needs android.permission.WRITE_SECURE_SETTINGS")
            return false
        }
        try {
            val resolver = context.contentResolver
            // The key for the developer setting for display cutout emulation.
            val settingKey = "display_cutout_emulation"
            // Setting it to an empty string resets it to the device default.
            Settings.Global.putString(resolver, settingKey, "")
            Log.d(TAG, "Reset display cutout emulation.")
            return true
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Failed to reset display cutout", e)
            return false
        }
    }

    /**
     * Resets all common display overrides (size, density, and cutout) to the device default.
     * This is the most effective way to fix visual artifacts.
     * Requires the WRITE_SECURE_SETTINGS permission.
     * @param context The application context.
     * @return true if all reset operations were attempted successfully, false otherwise.
     */
    fun resetAllDisplayOverrides(context: Context): Boolean {
        // The order matters slightly. It's best to reset the emulation settings
        // and then trigger the system-wide reconfiguration by resetting size/density.
        val cutoutSuccess = resetDisplayCutout(context)
        val densitySuccess: Boolean = clearForcedDisplayDensity(context)
        val sizeSuccess = clearForcedDisplaySize(context)

        // A reboot can sometimes be necessary for all SystemUI elements to fully redraw correctly.
        // You can inform the user about this.
        Log.i(TAG, "All display overrides have been reset. A reboot may be required to clear all visual artifacts.")

        return cutoutSuccess && densitySuccess && sizeSuccess
    }
}
