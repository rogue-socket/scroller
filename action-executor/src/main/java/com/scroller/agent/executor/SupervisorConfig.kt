package com.scroller.agent.executor

data class SupervisorConfig(
    val maxRepeatedActions: Int = 3,
    val maxIdenticalScreens: Int = 3,
    val maxOscillationCount: Int = 4,
    val maxConsecutiveFailures: Int = 3,
    val screenDiffThreshold: Float = 0.98f,
    val fingerprintHistorySize: Int = 5
)
