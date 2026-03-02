package com.scroller.agent.executor

interface ActionExecutor {
    suspend fun execute(action: AgentAction): ExecutionResult
}
