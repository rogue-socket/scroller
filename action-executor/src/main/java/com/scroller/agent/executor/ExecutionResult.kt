package com.scroller.agent.executor

sealed class ExecutionResult {
    data class Success(
        val action: AgentAction,
        val timestampMs: Long,
        val durationMs: Long,
        val metadata: Map<String, String> = emptyMap()
    ) : ExecutionResult()

    data class Failure(
        val action: AgentAction,
        val reason: FailureReason,
        val message: String? = null
    ) : ExecutionResult()
}

enum class FailureReason {
    SERVICE_NOT_ENABLED,
    SERVICE_NOT_CONNECTED,
    INVALID_COORDINATES,
    NO_FOCUSED_INPUT,
    APP_NOT_FOUND,
    PERFORM_ACTION_FAILED,
    DISPATCH_FAILED,
    INTERNAL_ERROR
}
