package com.scroller.agent.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilityActionExecutorService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var consumerJob: Job? = null

    private val activeGestureCancelled = AtomicBoolean(false)
    @Volatile private var activeCommand: ActionCommand? = null

    private val commandBus: ActionCommandBus by lazy {
        val deps = application as? ActionExecutorDependencies
            ?: throw IllegalStateException("Application must implement ActionExecutorDependencies")
        deps.commandBus
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        commandBus.open()
        commandBus.state.value = ServiceState.Connected
        consumerJob = serviceScope.launch {
            consumeCommands()
        }
        serviceScope.coroutineContext[Job]?.invokeOnCompletion { throwable ->
            if (throwable is CancellationException) {
                cancelActiveGestureAndFail(FailureReason.INTERNAL_ERROR, "Service scope cancelled")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: this service is command-driven.
    }

    override fun onInterrupt() {
        cancelActiveGestureAndFail(FailureReason.INTERNAL_ERROR, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        commandBus.state.value = ServiceState.Disconnected
        cancelActiveGestureAndFail(FailureReason.INTERNAL_ERROR, "Service destroyed")
        commandBus.closeAndFailPending(FailureReason.SERVICE_NOT_CONNECTED, "Service destroyed")
        consumerJob?.cancel()
        serviceScope.cancel()
    }

    private suspend fun consumeCommands() {
        try {
            for (command in commandBus.channel) {
                val startMs = SystemClock.elapsedRealtime()
                logEvent(action = command.action, timestampMs = System.currentTimeMillis(), durationMs = 0L, result = "RECEIVED", reason = null)
                activeCommand = command
                val result = try {
                    executeAction(command.action, startMs)
                } catch (e: CancellationException) {
                    ExecutionResult.Failure(command.action, FailureReason.INTERNAL_ERROR, "Execution cancelled")
                } catch (e: Exception) {
                    ExecutionResult.Failure(command.action, FailureReason.INTERNAL_ERROR, e.message)
                }
                if (!command.result.isCompleted) {
                    command.result.complete(result)
                }
                activeCommand = null
            }
        } catch (_: ClosedReceiveChannelException) {
            // Channel closed during shutdown; pending commands are handled elsewhere.
        }
    }

    private suspend fun executeAction(action: AgentAction, startMs: Long): ExecutionResult {
        return when (action) {
            is AgentAction.Tap -> performTap(action, startMs)
            is AgentAction.Scroll -> performScroll(action, startMs)
            is AgentAction.PressBack -> performBack(action, startMs)
            is AgentAction.Type -> performType(action, startMs)
            is AgentAction.OpenApp -> performOpenApp(action, startMs)
        }
    }

    private suspend fun performTap(action: AgentAction.Tap, startMs: Long): ExecutionResult {
        val bounds = currentBounds()
        if (!bounds.contains(action.x, action.y)) {
            return fail(action, startMs, FailureReason.INVALID_COORDINATES, "Tap out of bounds")
        }
        val path = Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0L, 1L))
            .build()

        return dispatchGestureForResult(action, startMs, gesture)
    }

    private suspend fun performScroll(action: AgentAction.Scroll, startMs: Long): ExecutionResult {
        val bounds = currentBounds()
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()

        val startRatio = 0.7f
        val endRatio = 0.3f

        val (startX, startY, endX, endY) = when (action.direction) {
            ScrollDirection.UP -> {
                val x = left + width * 0.5f
                val startY = top + height * startRatio
                val endY = top + height * endRatio
                listOf(x, startY, x, endY)
            }
            ScrollDirection.DOWN -> {
                val x = left + width * 0.5f
                val startY = top + height * endRatio
                val endY = top + height * startRatio
                listOf(x, startY, x, endY)
            }
            ScrollDirection.LEFT -> {
                val y = top + height * 0.5f
                val startX = left + width * startRatio
                val endX = left + width * endRatio
                listOf(startX, y, endX, y)
            }
            ScrollDirection.RIGHT -> {
                val y = top + height * 0.5f
                val startX = left + width * endRatio
                val endX = left + width * startRatio
                listOf(startX, y, endX, y)
            }
        }

        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(StrokeDescription(path, 0L, 300L))
            .build()

        return dispatchGestureForResult(action, startMs, gesture)
    }

    private suspend fun performBack(action: AgentAction.PressBack, startMs: Long): ExecutionResult {
        val performed = withContext(Dispatchers.Main.immediate) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        return if (performed) {
            success(action, startMs)
        } else {
            fail(action, startMs, FailureReason.PERFORM_ACTION_FAILED, "GLOBAL_ACTION_BACK failed")
        }
    }

    private suspend fun performType(action: AgentAction.Type, startMs: Long): ExecutionResult {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return fail(action, startMs, FailureReason.NO_FOCUSED_INPUT, "No focused input")

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
        }

        val performed = withContext(Dispatchers.Main.immediate) {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        return if (performed) {
            success(action, startMs)
        } else {
            fail(action, startMs, FailureReason.PERFORM_ACTION_FAILED, "ACTION_SET_TEXT failed")
        }
    }

    private suspend fun performOpenApp(action: AgentAction.OpenApp, startMs: Long): ExecutionResult {
        val intent = packageManager.getLaunchIntentForPackage(action.packageName)
            ?: return fail(action, startMs, FailureReason.APP_NOT_FOUND, "Package not found")

        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            withContext(Dispatchers.Main.immediate) {
                startActivity(intent)
            }
            success(action, startMs)
        } catch (e: Exception) {
            fail(action, startMs, FailureReason.PERFORM_ACTION_FAILED, e.message)
        }
    }

    private suspend fun dispatchGestureForResult(
        action: AgentAction,
        startMs: Long,
        gesture: GestureDescription
    ): ExecutionResult {
        activeGestureCancelled.set(false)
        val completion = CompletableDeferred<ExecutionResult>()

        val dispatched = withContext(Dispatchers.Main.immediate) {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (!activeGestureCancelled.get()) {
                            completion.complete(success(action, startMs))
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (!activeGestureCancelled.get()) {
                            completion.complete(
                                fail(action, startMs, FailureReason.DISPATCH_FAILED, "Gesture cancelled")
                            )
                        }
                    }
                },
                null
            )
        }

        if (!dispatched) {
            return fail(action, startMs, FailureReason.DISPATCH_FAILED, "dispatchGesture returned false")
        }

        return completion.await()
    }

    private fun cancelActiveGestureAndFail(reason: FailureReason, message: String) {
        activeGestureCancelled.set(true)
        val pending = activeCommand
        if (pending != null && !pending.result.isCompleted) {
            pending.result.complete(ExecutionResult.Failure(pending.action, reason, message))
        }
    }

    private fun currentBounds(): Rect {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this)
        return metrics.bounds
    }

    private fun success(action: AgentAction, startMs: Long): ExecutionResult {
        val duration = SystemClock.elapsedRealtime() - startMs
        logEvent(action, System.currentTimeMillis(), duration, "SUCCESS", null)
        return ExecutionResult.Success(action, System.currentTimeMillis(), duration)
    }

    private fun fail(action: AgentAction, startMs: Long, reason: FailureReason, message: String?): ExecutionResult {
        val duration = SystemClock.elapsedRealtime() - startMs
        logEvent(action, System.currentTimeMillis(), duration, "FAILURE", reason.name)
        return ExecutionResult.Failure(action, reason, message)
    }

    private fun logEvent(
        action: AgentAction,
        timestampMs: Long,
        durationMs: Long,
        result: String,
        reason: String?
    ) {
        val json = JSONObject()
            .put("actionType", action::class.simpleName)
            .put("timestamp", timestampMs)
            .put("durationMs", durationMs)
            .put("result", result)
            .put("reason", reason ?: JSONObject.NULL)
        Log.i("ActionExecutor", json.toString())
    }
}
