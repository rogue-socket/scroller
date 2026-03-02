package com.scroller.agent.executor

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class AndroidActionExecutor(
    context: Context,
    private val commandBus: ActionCommandBus
) : ActionExecutor {

    private val appContext = context.applicationContext
    private val status = AccessibilityServiceStatus(appContext)

    override suspend fun execute(action: AgentAction): ExecutionResult {
        if (!status.isServiceEnabled()) {
            return ExecutionResult.Failure(action, FailureReason.SERVICE_NOT_ENABLED)
        }

        val connected = try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                commandBus.state.first { it is ServiceState.Connected }
            }
            true
        } catch (_: TimeoutCancellationException) {
            false
        }

        if (!connected) {
            return ExecutionResult.Failure(action, FailureReason.SERVICE_NOT_CONNECTED)
        }

        val deferred = CompletableDeferred<ExecutionResult>()
        val command = ActionCommand(action, deferred, System.currentTimeMillis())

        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                commandBus.channel.send(command)
            }
        } catch (_: TimeoutCancellationException) {
            return ExecutionResult.Failure(action, FailureReason.DISPATCH_FAILED, "Timed out sending command")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ExecutionResult.Failure(action, FailureReason.INTERNAL_ERROR, e.message)
        }

        return try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            ExecutionResult.Failure(action, FailureReason.DISPATCH_FAILED, "Timed out awaiting result")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExecutionResult.Failure(action, FailureReason.INTERNAL_ERROR, e.message)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
