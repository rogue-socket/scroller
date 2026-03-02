package com.scroller.agent.executor

sealed class LlmException(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    data object NetworkError : LlmException("Network error")
    data object Timeout : LlmException("Request timed out")
    data object InvalidResponse : LlmException("Invalid response")
    data object SchemaViolation : LlmException("Response schema violation")
}
