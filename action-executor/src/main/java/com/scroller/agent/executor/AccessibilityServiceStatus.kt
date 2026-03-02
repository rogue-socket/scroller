package com.scroller.agent.executor

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

class AccessibilityServiceStatus(private val context: Context) {
    fun isServiceEnabled(): Boolean {
        val component = ComponentName(context, AccessibilityActionExecutorService::class.java)
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (accessibilityEnabled != 1) {
            return false
        }
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(component.flattenToString(), ignoreCase = true) }
    }
}
