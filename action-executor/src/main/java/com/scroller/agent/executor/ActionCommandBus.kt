package com.scroller.agent.executor

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

class ActionCommandBus {
    // Capacity = 1 ensures only one pending UI command at a time for deterministic sequencing and backpressure.
    @Volatile internal var channel = Channel<ActionCommand>(capacity = 1)
        private set
    internal val state = MutableStateFlow<ServiceState>(ServiceState.Disconnected)

    internal fun open() {
        if (channel.isClosedForSend) {
            channel = Channel(capacity = 1)
        }
    }

    internal fun closeAndFailPending(reason: FailureReason, message: String?) {
        channel.close()
        while (true) {
            val received = channel.tryReceive().getOrNull() ?: break
            received.result.complete(ExecutionResult.Failure(received.action, reason, message))
        }
    }
}
