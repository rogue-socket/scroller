package com.scroller.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.scroller.agent.executor.AccessibilityServiceStatus
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.scroller.agent.executor.AgentLoopController
import com.scroller.agent.executor.AgentMemory
import com.scroller.agent.executor.GeminiLlmClient
import com.scroller.agent.executor.LoopConfig
import com.scroller.agent.executor.LoopResult
import com.scroller.agent.executor.RecoveryConfig
import com.scroller.agent.executor.RecoveryPolicy
import com.scroller.agent.executor.Supervisor
import com.scroller.agent.executor.SupervisorConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var openSettingsButton: Button
    private lateinit var projectionStatusView: TextView
    private lateinit var requestProjectionButton: Button
    private lateinit var llmStatusView: TextView
    private lateinit var nextStepView: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var applyApiKeyButton: Button
    private lateinit var goalInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var runLoopButton: Button
    private lateinit var loopStatusView: TextView
    private lateinit var statusChecker: AccessibilityServiceStatus

    private var loopJob: Job? = null

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(result.resultCode, result.data!!)
            (application as ScrollerApp).mediaProjectionController.initialize(projection)
            Log.i(LOG_TAG, "{\"event\":\"projection_permission_granted\"}")
        } else {
            Log.i(LOG_TAG, "{\"event\":\"projection_permission_denied\"}")
        }
        updateProjectionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.service_status)
        openSettingsButton = findViewById(R.id.open_settings)
        projectionStatusView = findViewById(R.id.projection_status)
        requestProjectionButton = findViewById(R.id.request_projection)
        llmStatusView = findViewById(R.id.llm_status)
        nextStepView = findViewById(R.id.next_step)
        apiKeyInput = findViewById(R.id.api_key_input)
        applyApiKeyButton = findViewById(R.id.apply_api_key)
        goalInput = findViewById(R.id.goal_input)
        modelInput = findViewById(R.id.model_input)
        runLoopButton = findViewById(R.id.run_loop)
        loopStatusView = findViewById(R.id.loop_status)
        statusChecker = AccessibilityServiceStatus(this)

        val app = application as ScrollerApp
        app.getLlmModel()?.let { modelInput.setText(it) }
        app.getLastGoal()?.let { goalInput.setText(it) }

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        requestProjectionButton.setOnClickListener {
            val serviceIntent = Intent(this, MediaProjectionForegroundService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            val intent = (application as ScrollerApp).mediaProjectionController.createProjectionIntent()
            projectionLauncher.launch(intent)
        }

        applyApiKeyButton.setOnClickListener {
            val key = apiKeyInput.text?.toString()
            (application as ScrollerApp).setLlmApiKey(key)
            apiKeyInput.text?.clear()
            updateProjectionStatus()
        }

        runLoopButton.setOnClickListener {
            if (!canRunLoop()) {
                loopStatusView.text = "Loop status: NOT READY"
                return@setOnClickListener
            }
            startLoop()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateProjectionStatus()
    }

    private fun updateStatus() {
        val enabled = statusChecker.isServiceEnabled()
        statusView.text = if (enabled) {
            "Accessibility service: ENABLED"
        } else {
            "Accessibility service: DISABLED"
        }
        updateNextStep()
    }

    private fun updateProjectionStatus() {
        val active = (application as ScrollerApp).mediaProjectionController.isActive()
        projectionStatusView.text = if (active) {
            "Screen capture: READY"
        } else {
            "Screen capture: NOT READY"
        }
        val hasKey = (application as ScrollerApp).getLlmApiKey() != null
        llmStatusView.text = if (hasKey) {
            "Gemini API key: CONFIGURED"
        } else {
            "Gemini API key: NOT CONFIGURED"
        }
        updateNextStep()
    }

    private fun updateNextStep() {
        val accessibilityEnabled = statusChecker.isServiceEnabled()
        val projectionReady = (application as ScrollerApp).mediaProjectionController.isActive()
        val hasKey = (application as ScrollerApp).getLlmApiKey() != null
        val next = when {
            !accessibilityEnabled -> "Next step: Enable Accessibility service"
            !projectionReady -> "Next step: Grant Screen Capture permission"
            !hasKey -> "Next step: Configure Gemini API key in this session"
            else -> "Next step: Ready to run loop"
        }
        nextStepView.text = next
    }

    private fun canRunLoop(): Boolean {
        val accessibilityEnabled = statusChecker.isServiceEnabled()
        val projectionReady = (application as ScrollerApp).mediaProjectionController.isActive()
        val hasKey = (application as ScrollerApp).getLlmApiKey() != null
        return accessibilityEnabled && projectionReady && hasKey
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopStatusView.text = "Loop status: RUNNING"
        val goal = goalInput.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: "Open Settings"

        val app = application as ScrollerApp
        app.setLastGoal(goal)
        val model = modelInput.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
            ?: "gemini-1.5-flash"
        app.setLlmModel(model)
        val llmClient = GeminiLlmClient(
            apiKeyProvider = { app.getLlmApiKey() ?: "" },
            model = model
        )
        val supervisor = Supervisor(SupervisorConfig())
        val recovery = RecoveryPolicy(RecoveryConfig())
        val memory = AgentMemory()
        val loop = AgentLoopController(
            screenCapture = app.screenCaptureManager,
            llmClient = llmClient,
            executor = app.actionExecutor,
            supervisor = supervisor,
            recoveryPolicy = recovery,
            memory = memory,
            config = LoopConfig()
        )

        loopJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { loop.run(goal) }
            loopStatusView.text = when (result) {
                is LoopResult.Success -> "Loop status: SUCCESS (${result.steps} steps)"
                is LoopResult.MaxStepsExceeded -> "Loop status: MAX STEPS (${result.steps})"
                is LoopResult.Failed -> "Loop status: FAILED (${result.reason})"
                is LoopResult.Cancelled -> "Loop status: CANCELLED"
            }
        }
    }

    companion object {
        private const val LOG_TAG = "ScreenCapture"
    }
}
