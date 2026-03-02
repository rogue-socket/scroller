package com.scroller.agent.executor

class Supervisor(
    private val config: SupervisorConfig
) {

    private val fingerprintBuffer = Array<ScreenFingerprint?>(config.fingerprintHistorySize) { null }
    private var fingerprintIndex = 0
    private var fingerprintCount = 0

    private var consecutiveIdenticalScreens = 0
    private var oscillationStreak = 0
    private var consecutiveFailures = 0

    fun evaluate(
        currentFingerprint: ScreenFingerprint,
        lastAction: AgentAction?,
        actionHistory: List<AgentAction>,
        executionResult: ExecutionResult?
    ): SupervisorDecision {
        updateFailureCascade(executionResult)
        if (consecutiveFailures >= config.maxConsecutiveFailures) {
            return SupervisorDecision.FailureCascadeDetected
        }

        val previous = latestFingerprint()
        addFingerprint(currentFingerprint)

        if (previous != null) {
            val similarity = currentFingerprint.similarity(previous)
            consecutiveIdenticalScreens = if (similarity >= config.screenDiffThreshold) {
                (consecutiveIdenticalScreens + 1).coerceAtLeast(2)
            } else {
                1
            }
        } else {
            consecutiveIdenticalScreens = 1
        }

        if (lastAction !is AgentAction.Type && consecutiveIdenticalScreens >= config.maxIdenticalScreens) {
            return SupervisorDecision.StalledScreenDetected
        }

        if (detectRepeatedAction(actionHistory)) {
            return SupervisorDecision.RepeatedActionDetected
        }

        if (detectOscillation(actionHistory)) {
            return SupervisorDecision.OscillationDetected
        }

        return SupervisorDecision.Continue
    }

    private fun updateFailureCascade(executionResult: ExecutionResult?) {
        if (executionResult is ExecutionResult.Failure) {
            consecutiveFailures += 1
        } else {
            consecutiveFailures = 0
        }
    }

    private fun detectRepeatedAction(actionHistory: List<AgentAction>): Boolean {
        val required = config.maxRepeatedActions
        if (required <= 1) return false
        if (actionHistory.size < required) return false
        val last = actionHistory.last()
        val tail = actionHistory.takeLast(required)
        return tail.all { it == last }
    }

    private fun detectOscillation(actionHistory: List<AgentAction>): Boolean {
        if (actionHistory.size < 4) {
            oscillationStreak = 0
            return false
        }
        val lastFour = actionHistory.takeLast(4)
        val a = lastFour[0]
        val b = lastFour[1]
        val c = lastFour[2]
        val d = lastFour[3]
        val isOscillating = a == c && b == d && a != b
        oscillationStreak = if (isOscillating) oscillationStreak + 1 else 0
        return oscillationStreak >= config.maxOscillationCount
    }

    private fun addFingerprint(fingerprint: ScreenFingerprint) {
        fingerprintBuffer[fingerprintIndex] = fingerprint
        fingerprintIndex = (fingerprintIndex + 1) % fingerprintBuffer.size
        if (fingerprintCount < fingerprintBuffer.size) {
            fingerprintCount += 1
        }
    }

    private fun latestFingerprint(): ScreenFingerprint? {
        if (fingerprintCount == 0) return null
        val index = if (fingerprintIndex == 0) fingerprintBuffer.lastIndex else fingerprintIndex - 1
        return fingerprintBuffer[index]
    }
}
