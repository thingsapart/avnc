package com.gaurav.avnc.ui.vnc.xr

import android.app.Activity // Add this import
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gaurav.avnc.util.AppPreferences // Added import
import com.gaurav.avnc.ui.vnc.PanningInputDevice
import com.gaurav.avnc.ui.vnc.PanningListener
import com.viture.sdk.ArCallback
import com.viture.sdk.ArManager
import com.viture.sdk.Constants
import java.nio.ByteBuffer

class ViturePanningInputDevice(private val activity: Activity) : PanningInputDevice {

    private val TAG = "ViturePanningDevice"
    private var mArManager: ArManager? = null
    private var mSdkInitSuccess = -1
    private var isEventIdInitReceived: Boolean = false
    private var isPendingEnable: Boolean = false
    private var listener: PanningListener? = null
    private var enabled = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: AppPreferences // Added field

    private var mLastYaw: Float? = null
    private var mLastPitch: Float? = null

    init {
        prefs = AppPreferences(activity.applicationContext) // Initialize prefs
        try {
            mArManager = ArManager.getInstance(activity)
            val initReturnCode = mArManager?.init() ?: -1 // Store return code
            mSdkInitSuccess = initReturnCode // Keep this assignment
            Log.i(TAG, "ArManager.init() called. Synchronous return code: $initReturnCode. mSdkInitSuccess set to this value initially.") // Enhanced log
            if (mSdkInitSuccess != Constants.ERROR_INIT_SUCCESS) {
                Log.e(TAG, "Viture SDK synchronous init failed. Error code: $mSdkInitSuccess. Check logs from ArManager if any, and ensure USB permissions if applicable.")
                mArManager = null
            } else {
                Log.i(TAG, "Viture SDK synchronous init appears successful. Waiting for potential EVENT_ID_INIT in onEvent for final confirmation or updates.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Viture SDK ArManager.getInstance or init(): ${e.message}", e)
            mArManager = null
            mSdkInitSuccess = -99 // Indicate exception during init
        }
    }

    private val mCallback = object : ArCallback() {
        override fun onEvent(eventId: Int, event: ByteArray?, l: Long) {
            Log.d(TAG, "onEvent: eventId=$eventId, eventData: ${event?.joinToString { "%02x".format(it) }}")
            if (eventId == Constants.EVENT_ID_INIT) {
                isEventIdInitReceived = true
                if (event != null) {
                    val initResult = byteArrayToInt(event, 0, event.size)
                    mSdkInitSuccess = initResult // Update mSdkInitSuccess
                    Log.i(TAG, "EVENT_ID_INIT received. mSdkInitSuccess: $mSdkInitSuccess, isPendingEnable: $isPendingEnable")
                    if (mSdkInitSuccess != Constants.ERROR_INIT_SUCCESS) {
                        Log.e(TAG, "Viture SDK onEvent reported non-success initialization status: $mSdkInitSuccess")
                    }
                    if (mSdkInitSuccess == Constants.ERROR_INIT_SUCCESS && isPendingEnable) {
                        Log.i(TAG, "EVENT_ID_INIT successful and enable was pending. Attempting IMU activation.")
                        if (performImuActivation()) {
                            isPendingEnable = false // Clear flag only if activation succeeded
                        } else {
                            Log.w(TAG, "performImuActivation failed after EVENT_ID_INIT. IMU may not be active.")
                        }
                    }
                } else {
                    Log.w(TAG, "Received EVENT_ID_INIT with null event data.")
                }
            }
            // Add other eventId handling here if needed in the future.
        }

        override fun onImu(ts: Long, imu: ByteArray?) {
            if (imu == null || !enabled) return

            val byteBuffer = ByteBuffer.wrap(imu)
            // Euler angles are big-endian floats
            // byteBuffer.order(ByteOrder.BIG_ENDIAN) // Default is BIG_ENDIAN for ByteBuffer

            // float eulerRoll = byteBuffer.getFloat(0) // roll --> front-axis
            val eulerPitch = byteBuffer.getFloat(4) // pitch -> right-axis (used as Y)
            val eulerYaw = byteBuffer.getFloat(8)   // yaw --> up-axis (used as X)

            // Run on UI thread if the listener might update UI
            // For now, assume listener handles threading if necessary, or is safe.
            // uiHandler.post {
            // }

            if (mLastYaw == null || mLastPitch == null) {
                mLastYaw = eulerYaw
                mLastPitch = eulerPitch
                return
            }

            val deltaYaw = eulerYaw - mLastYaw!!
            val deltaPitch = eulerPitch - mLastPitch!!

            mLastYaw = eulerYaw
            mLastPitch = eulerPitch

            val sensitivityX = prefs.xr.viturePanSensitivityX
            val sensitivityY = prefs.xr.viturePanSensitivityY

            val adjustedDeltaYaw = deltaYaw * sensitivityX
            val adjustedDeltaPitch = deltaPitch * sensitivityY

            // Positive yaw from SDK is typically right, positive pitch is typically up.
            // Adjust signs if needed based on how PanningController expects input.
            // Assuming PanningController expects:
            // - positive deltaYaw for panning right
            // - positive deltaPitch for panning up
            // The Viture SDK sample's move(eulerYaw, eulerPitch) implies:
            // - eulerYaw: positive for right rotation (around up-axis)
            // - eulerPitch: positive for upward rotation (around right-axis)
            // So, direct mapping might be fine, but needs testing.
            // The example app uses `moveX = (int)(-xStep * deltaX)` for yaw.
            // and `moveY = (int)(yStep * deltaY)` for pitch.
            // This suggests that positive eulerYaw change means looking left if not negated.
            // Let's assume for now that the listener expects standard Cartesian changes:
            // deltaYaw > 0 means pan right, deltaPitch > 0 means pan up.
            // The Viture example implies `deltaX` (yaw change) is negated for `moveX`.
            // So, we should probably negate `deltaYaw`.
            listener?.onPan(-adjustedDeltaYaw, adjustedDeltaPitch)
        }
    }

    private fun performImuActivation(): Boolean {
        if (mArManager == null) {
            Log.e(TAG, "performImuActivation: mArManager is null. Cannot activate IMU.")
            return false
        }

        // Always register the callback before performing operations that might rely on it
        // or if it was unregistered by a previous disable() call.
        mArManager?.registerCallback(mCallback)
        Log.i(TAG, "performImuActivation: Callback registered.")

        val imuOnResult = mArManager?.setImuOn(true)
        Log.i(TAG, "performImuActivation: mArManager.setImuOn(true) called. Return code: $imuOnResult")

        if (imuOnResult == Constants.ERR_SET_SUCCESS) {
            enabled = true
            mLastYaw = null
            mLastPitch = null
            Log.i(TAG, "performImuActivation: Viture IMU enabled successfully via setImuOn(true).")

            val set3DResult = mArManager?.set3D(false)
            Log.i(TAG, "performImuActivation: mArManager.set3D(false) called. Return code: $set3DResult")

            if (set3DResult != Constants.ERR_SET_SUCCESS) {
                Log.e(TAG, "performImuActivation: Failed to set 3D mode to false. Error code: $set3DResult. This might affect IMU data, but IMU is considered enabled.")
                // Depending on strictness, you could return false here or unregister callback and set enabled = false
                // For now, we'll consider IMU 'enabled' but log the error for set3D.
            } else {
                Log.i(TAG, "performImuActivation: Successfully set 3D mode to false.")
            }
            return true // IMU successfully turned on, set3D attempted.
        } else {
            Log.e(TAG, "performImuActivation: Failed to enable Viture IMU via setImuOn(true). Error code: $imuOnResult.")
            // Since setImuOn failed, ensure 'enabled' is false and unregister the callback as we likely won't get IMU data.
            enabled = false
            mArManager?.unregisterCallback(mCallback)
            Log.i(TAG, "performImuActivation: Callback unregistered due to setImuOn failure.")
            return false
        }
    }

    override fun setPanningListener(listener: PanningListener?) {
        this.listener = listener
    }

    override fun enable() {
        if (mArManager == null) {
            Log.w(TAG, "Cannot enable: Viture SDK (mArManager) is null.")
            return
        }

        if (enabled) {
            Log.i(TAG, "Enable called, but IMU is already enabled.")
            return
        }

        // Clear any previous pending state, as we are now explicitly trying to enable.
        // isPendingEnable = false; // This will be set to true only if we defer.

        Log.i(TAG, "Enable called. isEventIdInitReceived: $isEventIdInitReceived, mSdkInitSuccess: $mSdkInitSuccess")

        if (isEventIdInitReceived) {
            if (mSdkInitSuccess == Constants.ERROR_INIT_SUCCESS) {
                Log.i(TAG, "Enable: EVENT_ID_INIT already received and was successful. Attempting IMU activation directly.")
                performImuActivation() // This function now handles setting 'enabled' flag.
            } else {
                Log.e(TAG, "Enable: EVENT_ID_INIT already received but indicated failure (code: $mSdkInitSuccess). Cannot enable IMU.")
                // Ensure callback is not left registered if we are in a failed state and not going to pend.
                // However, performImuActivation failure or onEvent failure should handle unregistration.
                // For safety, if we know init failed, we can unregister.
                // mArManager?.unregisterCallback(mCallback) // Consider if this is needed or if disable() path handles it.
            }
        } else {
            // EVENT_ID_INIT has not been received yet.
            // This could be because init() is still ongoing, or permission dialog is up,
            // or init() failed silently before and we are waiting for an event that might never come if it was a hard fail.
            Log.i(TAG, "Enable: EVENT_ID_INIT not yet received. Setting isPendingEnable = true and ensuring callback is registered.")
            isPendingEnable = true
            // Ensure callback is registered to catch the EVENT_ID_INIT.
            // If ArManager.init() failed very early (e.g. ArManager is null), this won't be reached.
            // If ArManager.init() returned an error code synchronously, mSdkInitSuccess would reflect that.
            // This path is primarily for when ArManager.init() returned success or a pending code,
            // and we are truly waiting for the async EVENT_ID_INIT.
            mArManager?.registerCallback(mCallback)
        }
    }

    override fun disable() {
        Log.i(TAG, "Disable called. Current 'enabled' state: $enabled, 'isPendingEnable' state: $isPendingEnable")
        isPendingEnable = false // Always clear pending enable on disable

        if (mArManager == null) {
            Log.w(TAG, "Cannot disable: mArManager is null. Ensuring 'enabled' is false.")
            enabled = false // Ensure our internal state is consistent
            return
        }

        if (enabled) { // Only try to turn off IMU if our flag says it was on
            val result = mArManager?.setImuOn(false)
            Log.i(TAG, "mArManager.setImuOn(false) called. Return code: $result")
            if (result != Constants.ERR_SET_SUCCESS) {
                Log.w(TAG, "Failed to turn off IMU via setImuOn(false). Error code: $result")
            }
        } else {
            // If not 'enabled', but mArManager exists, one might still want to ensure IMU is off
            // as a safety measure, though it shouldn't be necessary if logic is correct.
            // For now, only acting if 'enabled' was true.
            Log.i(TAG, "Disable called, but IMU was not marked as 'enabled'. Skipping setImuOn(false).")
        }

        enabled = false // Set our flag regardless of mArManager state or setImuOn result

        // Always try to unregister the callback, as it might have been registered by 'enable()'
        // even if 'performImuActivation' didn't complete or if 'isPendingEnable' was set.
        mArManager?.unregisterCallback(mCallback)
        Log.i(TAG, "Callback unregistered in disable(). Viture IMU should be disabled.")
    }

    override fun isEnabled(): Boolean {
        return enabled
    }

    // Consider adding a method to release SDK resources when this device is no longer needed.
    fun releaseSdk() {
        Log.i(TAG, "releaseSdk called. Current 'enabled' state: $enabled")
        if (mArManager != null) {
            // Call disable() to ensure IMU is turned off, callback is unregistered,
            // and isPendingEnable is cleared.
            disable() // This will also set 'enabled' to false.

            mArManager?.release()
            Log.i(TAG, "mArManager.release() called.")
            mArManager = null
        } else {
            Log.w(TAG, "releaseSdk called but mArManager was already null.")
        }

        // Reset all relevant state flags to their initial values
        mSdkInitSuccess = -1 // Mark as uninitialized
        isEventIdInitReceived = false
        // isPendingEnable is already cleared by disable()
        // enabled is already set to false by disable()

        Log.i(TAG, "Viture SDK resources released and state reset.")
    }

    private fun byteArrayToInt(bytes: ByteArray?, offset: Int = 0, length: Int = bytes?.size ?: 0): Int {
        if (bytes == null || offset < 0 || offset + length > bytes.size) {
            Log.e(TAG, "byteArrayToInt: Invalid input. Bytes null: ${bytes == null}, offset: $offset, length: $length, bytes.size: ${bytes?.size}")
            return if (bytes == null) 0 else -1 // Or throw an IllegalArgumentException
        }
        var value = 0
        // Viture SDK docs state event data is little-endian for EVENT_ID_INIT
        for (i in 0 until length) {
            value += (bytes[offset + i].toInt() and 0xFF) shl (i * 8)
        }
        return value
    }
}
