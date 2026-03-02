package com.scroller.agent.executor

data class MemorySnapshot(
    val recentActions: List<AgentAction>,
    val recentEvents: List<String>
)
