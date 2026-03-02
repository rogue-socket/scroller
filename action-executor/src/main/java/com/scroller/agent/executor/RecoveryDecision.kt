package com.scroller.agent.executor

sealed class RecoveryDecision {
    data object Abort : RecoveryDecision()
    data class InjectAction(val action: AgentAction) : RecoveryDecision()
    data object RetryLastGoal : RecoveryDecision()
    data object Continue : RecoveryDecision()
}
