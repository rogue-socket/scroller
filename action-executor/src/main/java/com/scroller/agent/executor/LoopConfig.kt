package com.scroller.agent.executor

data class LoopConfig(
    val maxSteps: Int = 25,
    val stepDelayMs: Long = 500,
    val llmTimeoutMs: Long = 15_000,
    val executionTimeoutMs: Long = 5_000,
    val captureTimeoutMs: Long = 2_000
)
