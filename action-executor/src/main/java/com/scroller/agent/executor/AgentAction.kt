package com.scroller.agent.executor

sealed class AgentAction {
    data class Tap(val x: Int, val y: Int) : AgentAction()
    data class Scroll(val direction: ScrollDirection) : AgentAction()
    data class Type(val text: String) : AgentAction()
    data class OpenApp(val packageName: String) : AgentAction()
    data object PressBack : AgentAction()
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT
}
