package com.scroller.agent.executor

import kotlinx.coroutines.CompletableDeferred

internal data class ActionCommand(
    val action: AgentAction,
    val result: CompletableDeferred<ExecutionResult>,
    val createdAtMs: Long
)
