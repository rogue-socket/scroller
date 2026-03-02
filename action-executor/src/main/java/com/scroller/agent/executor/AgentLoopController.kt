package com.scroller.agent.executor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AgentLoopController(
    private val screenCapture: ScreenCapture,
    private val llmClient: LlmClient,
    private val executor: ActionExecutor,
    private val supervisor: Supervisor,
    private val recoveryPolicy: RecoveryPolicy,
    private val memory: AgentMemory,
    private val config: LoopConfig
) {

    @Volatile
    private var activeJob: Job? = null

    suspend fun run(goal: String): LoopResult {
        return try {
            val parentJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            val job = SupervisorJob(parentJob)
            activeJob = job
            try {
                withContext(job + Dispatchers.Default) {
                    runInternal(goal)
                }
            } finally {
                activeJob = null
            }
        } catch (_: CancellationException) {
            LoopResult.Cancelled
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    private suspend fun runInternal(goal: String): LoopResult {
        val startedAt = System.currentTimeMillis()
        var state = LoopState(
            goal = goal,
            stepCount = 0,
            actionHistory = emptyList(),
            failureCount = 0,
            lastAction = null,
            startedAtMs = startedAt
        )
        var recoveryAttempts = 0

        for (step in 0 until config.maxSteps) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            val screen = try {
                withTimeout(config.captureTimeoutMs) {
                    screenCapture.capture()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(
                    stepCount = step + 1,
                    failureCount = state.failureCount + 1
                )
                memory.recordEvent("Capture failed: ${e.message ?: "unknown"}")
                if (state.failureCount >= FAILURE_THRESHOLD) {
                    return LoopResult.Failed("Screen capture failed: ${e.message ?: "unknown"}")
                }
                delay(config.stepDelayMs)
                continue
            }
            val fingerprint = ScreenFingerprinting.fromBitmap(screen.bitmap)
            memory.recordScreenFingerprint(fingerprint)

            val decision = try {
                withTimeout(config.llmTimeoutMs) {
                    val summary = memory.compressedSummary()
                    llmClient.decideNextAction(goal, screen, state.actionHistory, summary)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(
                    stepCount = step + 1,
                    failureCount = state.failureCount + 1
                )
                memory.recordEvent("LLM failed: ${e.message ?: "unknown"}")
                if (state.failureCount >= FAILURE_THRESHOLD) {
                    return LoopResult.Failed("LLM failure: ${e.message ?: "unknown"}")
                }
                delay(config.stepDelayMs)
                continue
            }
            memory.recordDecision(decision)

            when (decision) {
                is LlmDecision.Done -> {
                    val duration = System.currentTimeMillis() - startedAt
                    return LoopResult.Success(steps = step + 1, durationMs = duration)
                }
                is LlmDecision.Action -> {
                    val action = decision.action
                    val updatedHistory = (state.actionHistory + action).takeLast(ACTION_HISTORY_LIMIT)
                    state = state.copy(
                        stepCount = step + 1,
                        actionHistory = updatedHistory,
                        lastAction = action
                    )
                    memory.recordAction(action)

                    val executionResult = try {
                        withTimeout(config.executionTimeoutMs) {
                            executor.execute(action)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        state = state.copy(failureCount = state.failureCount + 1)
                        memory.recordEvent("Execution failed: ${e.message ?: "unknown"}")
                        val supervisorDecision = supervisor.evaluate(
                            currentFingerprint = fingerprint,
                            lastAction = state.lastAction,
                            actionHistory = state.actionHistory,
                            executionResult = null
                        )
                        val recoveryOutcome = handleRecovery(supervisorDecision, state, fingerprint, recoveryAttempts)
                        if (recoveryOutcome.result != null) return recoveryOutcome.result
                        recoveryAttempts = recoveryOutcome.updatedAttempts
                        if (state.failureCount >= FAILURE_THRESHOLD) {
                            return LoopResult.Failed("Execution failed: ${e.message ?: "unknown"}")
                        }
                        delay(config.stepDelayMs)
                        continue
                    }

                    if (executionResult is ExecutionResult.Failure) {
                        state = state.copy(failureCount = state.failureCount + 1)
                        memory.recordEvent("Action failed: ${executionResult.reason}")
                        if (state.failureCount >= FAILURE_THRESHOLD) {
                            return LoopResult.Failed("Action failed: ${executionResult.reason}")
                        }
                    }

                    val supervisorDecision = supervisor.evaluate(
                        currentFingerprint = fingerprint,
                        lastAction = state.lastAction,
                        actionHistory = state.actionHistory,
                        executionResult = executionResult
                    )
                    val recoveryOutcome = handleRecovery(supervisorDecision, state, fingerprint, recoveryAttempts)
                    if (recoveryOutcome.result != null) return recoveryOutcome.result
                    recoveryAttempts = recoveryOutcome.updatedAttempts
                }
            }

            if (step < config.maxSteps - 1) {
                delay(config.stepDelayMs)
            }
        }

        return LoopResult.MaxStepsExceeded(steps = config.maxSteps)
    }

    private suspend fun handleRecovery(
        supervisorDecision: SupervisorDecision,
        state: LoopState,
        screenFingerprint: ScreenFingerprint,
        recoveryAttempts: Int
    ): RecoveryOutcome {
        if (supervisorDecision == SupervisorDecision.Continue) {
            return RecoveryOutcome(null, recoveryAttempts)
        }
        if (recoveryAttempts >= recoveryPolicy.maxRecoveryAttempts()) {
            return RecoveryOutcome(LoopResult.Failed(supervisorDecision.toString()), recoveryAttempts)
        }

        return when (val decision = recoveryPolicy.decide(supervisorDecision, state)) {
            is RecoveryDecision.Abort -> RecoveryOutcome(LoopResult.Failed(supervisorDecision.toString()), recoveryAttempts)
            is RecoveryDecision.Continue -> RecoveryOutcome(null, recoveryAttempts)
            is RecoveryDecision.RetryLastGoal -> RecoveryOutcome(null, recoveryAttempts + 1)
            is RecoveryDecision.InjectAction -> {
                memory.recordEvent("Recovery injected: ${decision.action}")
                val result = try {
                    withTimeout(config.executionTimeoutMs) {
                        executor.execute(decision.action)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return RecoveryOutcome(LoopResult.Failed("Recovery action failed: ${e.message ?: "unknown"}"), recoveryAttempts + 1)
                }
                memory.recordAction(decision.action)
                if (result is ExecutionResult.Failure) {
                    return RecoveryOutcome(LoopResult.Failed("Recovery action failed: ${result.reason}"), recoveryAttempts + 1)
                }
                val followUp = supervisor.evaluate(
                    currentFingerprint = screenFingerprint,
                    lastAction = decision.action,
                    actionHistory = state.actionHistory + decision.action,
                    executionResult = result
                )
                if (followUp != SupervisorDecision.Continue) {
                    return RecoveryOutcome(LoopResult.Failed(followUp.toString()), recoveryAttempts + 1)
                }
                RecoveryOutcome(null, recoveryAttempts + 1)
            }
        }
    }

    private data class RecoveryOutcome(val result: LoopResult?, val updatedAttempts: Int)

    companion object {
        private const val ACTION_HISTORY_LIMIT = 20
        private const val FAILURE_THRESHOLD = 3
    }
}
