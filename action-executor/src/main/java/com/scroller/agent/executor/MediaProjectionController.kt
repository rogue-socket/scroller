package com.scroller.agent.executor

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

class MediaProjectionController(context: Context) {

    private val appContext = context.applicationContext
    private val projectionRef = AtomicReference<MediaProjection?>(null)
    private val callbackRef = AtomicReference<MediaProjection.Callback?>(null)

    fun createProjectionIntent(): android.content.Intent {
        val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    fun initialize(projection: MediaProjection) {
        stopInternal("Reinitializing projection")
        projectionRef.set(projection)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionRef.set(null)
                Log.i(LOG_TAG, "{\"event\":\"projection_stopped\",\"reason\":\"callback\"}")
            }
        }
        projection.registerCallback(callback, null)
        callbackRef.set(callback)
        Log.i(LOG_TAG, "{\"event\":\"projection_started\"}")
    }

    fun isActive(): Boolean = projectionRef.get() != null

    fun requireProjection(): MediaProjection {
        return projectionRef.get() ?: throw NoActiveProjectionException("MediaProjection not initialized")
    }

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface
    ): VirtualDisplay {
        val projection = requireProjection()
        return projection.createVirtualDisplay(
            name,
            width,
            height,
            densityDpi,
            0,
            surface,
            null,
            null
        )
    }

    fun stop() {
        stopInternal("Stopped by client")
    }

    private fun stopInternal(reason: String) {
        val projection = projectionRef.getAndSet(null)
        val callback = callbackRef.getAndSet(null)
        if (projection != null && callback != null) {
            try {
                projection.unregisterCallback(callback)
            } catch (_: IllegalStateException) {
                // Projection already stopped.
            }
        }
        projection?.stop()
        if (projection != null) {
            Log.i(LOG_TAG, "{\"event\":\"projection_stopped\",\"reason\":\"${reason}\"}")
        }
    }

    companion object {
        private const val LOG_TAG = "ScreenCapture"
    }
}
