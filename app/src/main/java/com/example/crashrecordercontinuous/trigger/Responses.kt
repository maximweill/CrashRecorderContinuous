package com.example.crashrecordercontinuous.trigger

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Factory for side-effect trigger responses.
 */
object Responses {
    /**
     * Plays a beep using ToneGenerator.
     */
    val sound: TriggerResponse = { context ->
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Briefly toggles the camera flash.
     */
    val flash: TriggerResponse = { context ->
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, true)
                // Turn off after 100ms
                Handler(Looper.getMainLooper()).postDelayed({
                    try { cameraManager.setTorchMode(cameraId, false) } catch (e: Exception) {}
                }, 100)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * No-operation response.
     */
    val none: TriggerResponse = {}
}
