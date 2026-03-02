package com.scroller.agent.executor

data class LoopState(
    val goal: String,
    val stepCount: Int,
    val actionHistory: List<AgentAction>,
    val failureCount: Int,
    val lastAction: AgentAction?,
    val startedAtMs: Long
)
