package com.scroller.agent.executor

class RecoveryPolicy(
    private val config: RecoveryConfig
) {
    fun decide(
        supervisorDecision: SupervisorDecision,
        loopState: LoopState
    ): RecoveryDecision {
        return when (supervisorDecision) {
            is SupervisorDecision.RepeatedActionDetected -> RecoveryDecision.InjectAction(AgentAction.PressBack)
            is SupervisorDecision.StalledScreenDetected -> RecoveryDecision.InjectAction(AgentAction.Scroll(ScrollDirection.DOWN))
            is SupervisorDecision.OscillationDetected -> RecoveryDecision.InjectAction(AgentAction.PressBack)
            is SupervisorDecision.FailureCascadeDetected -> RecoveryDecision.Abort
            is SupervisorDecision.Continue -> RecoveryDecision.Continue
        }
    }

    fun maxRecoveryAttempts(): Int = config.maxRecoveryAttempts
}
