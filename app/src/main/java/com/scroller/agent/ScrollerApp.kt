package com.scroller.agent

import android.app.Application
import com.scroller.agent.executor.ActionCommandBus
import com.scroller.agent.executor.ActionExecutorDependencies
import com.scroller.agent.executor.AndroidActionExecutor
import com.scroller.agent.executor.MediaProjectionController
import com.scroller.agent.executor.ScreenCaptureManager

class ScrollerApp : Application(), ActionExecutorDependencies {
    override val commandBus: ActionCommandBus by lazy { ActionCommandBus() }
    val actionExecutor by lazy { AndroidActionExecutor(this, commandBus) }
    val mediaProjectionController by lazy { MediaProjectionController(this) }
    val screenCaptureManager by lazy { ScreenCaptureManager(this, mediaProjectionController) }
}
