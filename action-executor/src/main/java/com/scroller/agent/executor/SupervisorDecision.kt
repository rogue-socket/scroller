package com.scroller.agent.executor

sealed class SupervisorDecision {
    data object Continue : SupervisorDecision()
    data object RepeatedActionDetected : SupervisorDecision()
    data object StalledScreenDetected : SupervisorDecision()
    data object OscillationDetected : SupervisorDecision()
    data object FailureCascadeDetected : SupervisorDecision()
}
