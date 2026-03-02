package com.scroller.agent.executor

sealed class LoopException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class CaptureFailed(message: String, cause: Throwable? = null) : LoopException(message, cause)
    class LlmFailed(message: String, cause: Throwable? = null) : LoopException(message, cause)
    class ExecutionFailed(message: String, cause: Throwable? = null) : LoopException(message, cause)
}
