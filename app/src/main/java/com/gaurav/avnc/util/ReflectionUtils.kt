package com.gaurav.avnc.util

import android.content.res.Configuration
import android.util.Log

/**
 * A utility object that uses Java Reflection to access hidden Android framework APIs.
 * This is used to force a system-wide configuration update after changing secure settings.
 */
object ReflectionUtils {
    private const val TAG = "ReflectionUtils"

    /**
     * Uses reflection to call the hidden IActivityManager.updateConfiguration method.
     * This forces the system to re-evaluate its configuration, applying changes
     * like a new display size or density that have been written to Settings.Global.
     *
     * This is a powerful but fragile technique, as it relies on internal APIs
     * that could change in future Android versions.
     *
     * @return True if the method was found and called successfully, false otherwise.
     */
    fun triggerSystemConfigurationChange(): Boolean {
        try {
            // Step 1: Get the IActivityManager class.
            // Note: The class name has changed over Android versions.
            // For modern Android, it's often in android.app.IActivityManager.
            // Let's get the class for ActivityManager first, which is public.
            val activityManagerClass = Class.forName("android.app.ActivityManager")

            // Step 2: Get the static getService() method. This is the standard way
            // to get a handle to the internal service object.
            val getServiceMethod = activityManagerClass.getMethod("getService")

            // Step 3: Call getService() to get the actual IActivityManager object.
            // Since it's a static method, the first argument to invoke() is null.
            val iActivityManager = getServiceMethod.invoke(null)

            // Step 4: Get the updateConfiguration(Configuration) method from the
            // IActivityManager's class.
            val updateConfigurationMethod = iActivityManager.javaClass.getMethod(
                    "updateConfiguration",
                    Configuration::class.java
            )

            // Step 5: Create a new, empty Configuration object. We don't need to put
            // anything in it. The simple act of calling the method is what triggers
            // the system-wide refresh. The system will then re-read values from the
            // settings database on its own.
            val newConfiguration = Configuration()

            // Step 6: Call the updateConfiguration method on the IActivityManager instance.
            updateConfigurationMethod.invoke(iActivityManager, newConfiguration)

            Log.d(TAG, "Successfully triggered system configuration update via reflection.")
            return true

        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Failed to find internal class", e)
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "Failed to find internal method", e)
        } catch (e: Exception) {
            // Catch other reflection-related exceptions like IllegalAccessException
            // and InvocationTargetException.
            Log.e(TAG, "Failed to trigger system configuration update", e)
        }

        return false
    }
}