package com.scroller.agent.executor

sealed class LoopResult {
    data class Success(val steps: Int, val durationMs: Long) : LoopResult()
    data class MaxStepsExceeded(val steps: Int) : LoopResult()
    data class Failed(val reason: String) : LoopResult()
    data object Cancelled : LoopResult()
}
