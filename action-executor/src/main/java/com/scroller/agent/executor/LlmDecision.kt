package com.scroller.agent.executor

sealed class LlmDecision {
    data class Action(val action: AgentAction) : LlmDecision()
    data object Done : LlmDecision()
}
