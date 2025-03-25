package com.example.nikkuisgoodboy

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nikkuisgoodboy.service.ContentFilterVpnService
import com.example.nikkuisgoodboy.service.ScreenCaptureService
import com.example.nikkuisgoodboy.utils.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var switchMasterToggle: Switch
    private lateinit var textStatus: TextView
    private lateinit var buttonAccessibilityService: Button
    private lateinit var buttonVpnService: Button
    private lateinit var buttonWhitelistApps: Button
    private lateinit var buttonAdvancedSettings: Button

    private lateinit var preferenceManager: PreferenceManager
    private var mediaProjectionPermissionCode = 100

    private val mediaProjectionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            ContextCompat.startForegroundService(this, intent)
            updateServiceStatus()
            Toast.makeText(this, "Screen capture service started", Toast.LENGTH_SHORT).show()
        } else {
            switchMasterToggle.isChecked = false
            Toast.makeText(this, "Permission denied for screen capture", Toast.LENGTH_LONG).show()
        }
    }

    private val vpnServiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
            updateServiceStatus()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferenceManager = PreferenceManager(this)

        initViews()
        setupListeners()
        updateServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun initViews() {
        switchMasterToggle = findViewById(R.id.switchMasterToggle)
        textStatus = findViewById(R.id.textStatus)
        buttonAccessibilityService = findViewById(R.id.buttonAccessibilityService)
        buttonVpnService = findViewById(R.id.buttonVpnService)
        buttonWhitelistApps = findViewById(R.id.buttonWhitelistApps)
        buttonAdvancedSettings = findViewById(R.id.buttonAdvancedSettings)

        // Set initial state based on preferences
        switchMasterToggle.isChecked = preferenceManager.isProtectionEnabled()
    }

    private fun setupListeners() {
        switchMasterToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(this, "Please enable accessibility service first", Toast.LENGTH_LONG).show()
                    switchMasterToggle.isChecked = false
                    return@setOnCheckedChangeListener
                }

                startMediaProjectionRequest()
                preferenceManager.setProtectionEnabled(true)
            } else {
                stopServices()
                preferenceManager.setProtectionEnabled(false)
                updateServiceStatus()
            }
        }

        buttonAccessibilityService.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        buttonVpnService.setOnClickListener {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnServiceLauncher.launch(vpnIntent)
            } else {
                startVpnService()
                updateServiceStatus()
            }
        }

        buttonWhitelistApps.setOnClickListener {
            // TODO: Implement app whitelist activity
            Toast.makeText(this, "Whitelist feature will be implemented soon", Toast.LENGTH_SHORT).show()
        }

        buttonAdvancedSettings.setOnClickListener {
            // TODO: Implement advanced settings activity
            Toast.makeText(this, "Advanced settings will be implemented soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMediaProjectionRequest() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startVpnService() {
        val intent = Intent(this, ContentFilterVpnService::class.java).apply {
            action = ContentFilterVpnService.ACTION_CONNECT
        }
        startService(intent)
    }

    private fun stopServices() {
        // Stop VPN Service
        val vpnIntent = Intent(this, ContentFilterVpnService::class.java).apply {
            action = ContentFilterVpnService.ACTION_DISCONNECT
        }
        startService(vpnIntent)

        // Stop Screen Capture Service
        val screenCaptureIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(screenCaptureIntent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun updateServiceStatus() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isProtectionEnabled = preferenceManager.isProtectionEnabled()

        if (isProtectionEnabled && isAccessibilityEnabled) {
            textStatus.text = "Protection is active"
        } else {
            textStatus.text = "Protection is disabled"
            if (!isAccessibilityEnabled) {
                switchMasterToggle.isChecked = false
                preferenceManager.setProtectionEnabled(false)
            }
        }
    }
}