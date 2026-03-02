package com.scroller.agent.executor

interface LlmClient {
    suspend fun decideNextAction(
        goal: String,
        screen: ScreenFrame,
        actionHistory: List<AgentAction>,
        memorySummary: String
    ): LlmDecision
}
