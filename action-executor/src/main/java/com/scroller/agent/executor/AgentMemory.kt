package com.scroller.agent.executor

import java.util.ArrayDeque

class AgentMemory(
    private val maxActions: Int = 20,
    private val maxFingerprints: Int = 5,
    private val maxDecisions: Int = 10,
    private val maxEvents: Int = 10
) {
    private val recentActions = ArrayDeque<AgentAction>()
    private val recentFingerprints = ArrayDeque<ScreenFingerprint>()
    private val recentDecisions = ArrayDeque<LlmDecision>()
    private val recentEvents = ArrayDeque<String>()

    fun recordAction(action: AgentAction) {
        pushBounded(recentActions, action, maxActions)
    }

    fun recordScreenFingerprint(fingerprint: ScreenFingerprint) {
        pushBounded(recentFingerprints, fingerprint, maxFingerprints)
    }

    fun recordDecision(decision: LlmDecision) {
        pushBounded(recentDecisions, decision, maxDecisions)
    }

    fun recordEvent(event: String) {
        pushBounded(recentEvents, event, maxEvents)
    }

    fun snapshot(): MemorySnapshot {
        return MemorySnapshot(
            recentActions = recentActions.toList(),
            recentEvents = recentEvents.toList()
        )
    }

    fun compressedSummary(maxChars: Int = 600): String {
        val builder = StringBuilder()
        builder.append("Recent actions:\n")
        if (recentActions.isEmpty()) {
            builder.append("- (none)\n")
        } else {
            for (action in recentActions) {
                builder.append("- ").append(formatAction(action)).append('\n')
            }
        }

        if (recentEvents.isNotEmpty()) {
            builder.append("Recent events:\n")
            for (event in recentEvents) {
                builder.append("- ").append(event).append('\n')
            }
        }

        if (builder.length > maxChars) {
            return builder.substring(0, maxChars).trimEnd()
        }

        return builder.toString().trimEnd()
    }

    private fun formatAction(action: AgentAction): String {
        return when (action) {
            is AgentAction.Tap -> "Tap (x=${action.x}, y=${action.y})"
            is AgentAction.Scroll -> "Scroll ${action.direction}"
            is AgentAction.PressBack -> "PressBack"
            is AgentAction.Type -> "Type(\"${action.text}\")"
            is AgentAction.OpenApp -> "OpenApp(${action.packageName})"
        }
    }

    private fun <T> pushBounded(deque: ArrayDeque<T>, value: T, limit: Int) {
        deque.addLast(value)
        while (deque.size > limit) {
            deque.removeFirst()
        }
    }
}
