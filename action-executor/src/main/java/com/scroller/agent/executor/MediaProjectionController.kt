package com.scroller.agent.executor

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicReference

class MediaProjectionController(context: Context) {

    private val appContext = context.applicationContext
    private val projectionRef = AtomicReference<MediaProjection?>(null)
    private val callbackRef = AtomicReference<MediaProjection.Callback?>(null)
    private val projectionManager =
        appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    @Volatile private var onStopListener: (() -> Unit)? = null

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
                onStopListener?.invoke()
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
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        return projection.createVirtualDisplay(
            name,
            width,
            height,
            densityDpi,
            flags,
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
        onStopListener?.invoke()
    }

    fun setOnStopListener(listener: (() -> Unit)?) {
        onStopListener = listener
    }

    companion object {
        private const val LOG_TAG = "ScreenCapture"
    }
}
