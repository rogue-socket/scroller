package com.scroller.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.scroller.agent.executor.AccessibilityServiceStatus

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var openSettingsButton: Button
    private lateinit var projectionStatusView: TextView
    private lateinit var requestProjectionButton: Button
    private lateinit var statusChecker: AccessibilityServiceStatus

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
        statusChecker = AccessibilityServiceStatus(this)

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        requestProjectionButton.setOnClickListener {
            val intent = (application as ScrollerApp).mediaProjectionController.createProjectionIntent()
            projectionLauncher.launch(intent)
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
    }

    private fun updateProjectionStatus() {
        val active = (application as ScrollerApp).mediaProjectionController.isActive()
        projectionStatusView.text = if (active) {
            "Screen capture: READY"
        } else {
            "Screen capture: NOT READY"
        }
    }

    companion object {
        private const val LOG_TAG = "ScreenCapture"
    }
}
