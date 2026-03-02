package com.scroller.agent.executor

sealed class ServiceState {
    data object Disconnected : ServiceState()
    data object Connected : ServiceState()
}
