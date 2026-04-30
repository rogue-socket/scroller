package com.scroller.agent

import android.app.Application
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    @Volatile private var llmApiKey: String? = null
    @Volatile private var llmModel: String? = null
    @Volatile private var lastGoal: String? = null

    fun setLlmApiKey(value: String?) {
        llmApiKey = value?.trim()?.takeIf { it.isNotEmpty() }
        securePrefs().edit().putString(KEY_API, llmApiKey).apply()
    }

    fun getLlmApiKey(): String? = llmApiKey

    fun setLlmModel(value: String?) {
        llmModel = value?.trim()?.takeIf { it.isNotEmpty() }
        securePrefs().edit().putString(KEY_MODEL, llmModel).apply()
    }

    fun getLlmModel(): String? = llmModel

    fun setLastGoal(value: String?) {
        lastGoal = value?.trim()?.takeIf { it.isNotEmpty() }
        securePrefs().edit().putString(KEY_GOAL, lastGoal).apply()
    }

    fun getLastGoal(): String? = lastGoal

    override fun onCreate() {
        super.onCreate()
        llmApiKey = securePrefs().getString(KEY_API, null)
        llmModel = securePrefs().getString(KEY_MODEL, null)
        lastGoal = securePrefs().getString(KEY_GOAL, null)
    }

    private fun securePrefs() = EncryptedSharedPreferences.create(
        this,
        PREFS_NAME,
        MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val PREFS_NAME = "scroller_secure_prefs"
        private const val KEY_API = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
        private const val KEY_GOAL = "last_goal"
    }
}
