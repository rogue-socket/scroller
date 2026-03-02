package com.scroller.agent.executor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

interface ScreenCapture {
    suspend fun capture(): ScreenFrame
}

class ScreenCaptureManager(
    context: Context,
    private val projectionController: MediaProjectionController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ScreenCapture {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    // Reusable scratch bitmap for row-padding cases. The returned bitmap is always a new instance.
    private var scratchBitmap: Bitmap? = null

    override suspend fun capture(): ScreenFrame = withContext(ioDispatcher) {
        mutex.withLock {
            if (!projectionController.isActive()) {
                logFailure("projection_missing")
                throw NoActiveProjectionException("MediaProjection not initialized")
            }

            val bounds = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(appContext)
                .bounds
            val width = bounds.width()
            val height = bounds.height()
            val densityDpi = appContext.resources.displayMetrics.densityDpi
            val timestamp = System.currentTimeMillis()

            logEvent("capture_start", width, height, null)

            val handlerThread = HandlerThread("ScreenCapture")
            handlerThread.start()
            val handler = Handler(handlerThread.looper)

            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val virtualDisplay = projectionController.createVirtualDisplay(
                name = "ScreenCapture",
                width = width,
                height = height,
                densityDpi = densityDpi,
                surface = imageReader.surface
            )

            try {
                val image = try {
                    withTimeout(FRAME_TIMEOUT_MS) {
                        awaitImage(imageReader, handler)
                    }
                } catch (e: TimeoutCancellationException) {
                    logEvent("capture_failure", width, height, "timeout")
                    throw CaptureTimeoutException("Timed out waiting for frame")
                }

                try {
                    val bitmap = imageToBitmap(image, width, height)
                    logEvent("capture_success", width, height, null)
                    return@withLock ScreenFrame(bitmap, width, height, timestamp)
                } finally {
                    image.close()
                }
            } catch (e: ScreenCaptureException) {
                throw e
            } catch (e: Exception) {
                logEvent("capture_failure", width, height, e.message)
                throw CaptureFailedException("Capture failed", e)
            } finally {
                virtualDisplay.release()
                imageReader.close()
                handlerThread.quitSafely()
            }
        }
    }

    private suspend fun awaitImage(reader: ImageReader, handler: Handler): Image {
        return suspendCancellableCoroutine { cont ->
            val listener = ImageReader.OnImageAvailableListener { imageReader ->
                val image = imageReader.acquireLatestImage() ?: return@OnImageAvailableListener
                imageReader.setOnImageAvailableListener(null, null)
                if (cont.isActive) {
                    cont.resume(image)
                } else {
                    image.close()
                }
            }
            reader.setOnImageAvailableListener(listener, handler)
            cont.invokeOnCancellation {
                reader.setOnImageAvailableListener(null, null)
            }
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        // We keep a reusable scratch bitmap for row-padding copies to reduce churn.
        val scratch = ensureScratchBitmap(paddedWidth, height)
        scratch.copyPixelsFromBuffer(buffer)

        // Always return a new bitmap for the exact content area to avoid reusing a bitmap
        // that could be mutated by subsequent captures.
        return Bitmap.createBitmap(scratch, 0, 0, width, height)
    }

    private fun ensureScratchBitmap(width: Int, height: Int): Bitmap {
        val existing = scratchBitmap
        if (existing != null && existing.width == width && existing.height == height) {
            return existing
        }
        val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        scratchBitmap = created
        return created
    }

    private fun logEvent(event: String, width: Int, height: Int, reason: String?) {
        val reasonPart = reason?.let { ",\"reason\":\"$it\"" } ?: ""
        Log.i(LOG_TAG, "{\"event\":\"$event\",\"width\":$width,\"height\":$height$reasonPart}")
    }

    private fun logFailure(reason: String) {
        Log.i(LOG_TAG, "{\"event\":\"capture_failure\",\"reason\":\"$reason\"}")
    }

    companion object {
        const val FRAME_TIMEOUT_MS = 2_000L
        private const val LOG_TAG = "ScreenCapture"
    }
}

sealed class ScreenCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)
class NoActiveProjectionException(message: String) : ScreenCaptureException(message)
class CaptureTimeoutException(message: String) : ScreenCaptureException(message)
class CaptureFailedException(message: String, cause: Throwable? = null) : ScreenCaptureException(message, cause)
