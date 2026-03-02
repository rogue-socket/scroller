package com.scroller.agent.executor

sealed class OrchestrationResult {
    data object Success : OrchestrationResult()
    data class Failed(val subgoal: String) : OrchestrationResult()
}
