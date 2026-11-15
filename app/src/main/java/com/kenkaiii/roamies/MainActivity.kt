package com.kenkaiii.roamies

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var auth: AuthStorage

    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnPermOverlay: Button
    private lateinit var btnPermMic: Button
    private lateinit var btnPermAccessibility: Button
    private lateinit var spinnerChar: Spinner
    private lateinit var spinnerSize: Spinner
    private lateinit var spinnerProvider: Spinner
    private lateinit var etModel: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: MaterialButton

    private val providers = listOf("gemini", "anthropic", "openai", "grok", "ollama")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = AuthStorage(this)
        bindViews()
        setupSpinners()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateServiceStatus()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvServiceStatus)
        btnToggle = findViewById(R.id.btnToggleOverlay)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)
        btnPermMic = findViewById(R.id.btnPermMic)
        btnPermAccessibility = findViewById(R.id.btnPermAccessibility)
        spinnerChar = findViewById(R.id.spinnerCharacter)
        spinnerSize = findViewById(R.id.spinnerSize)
        spinnerProvider = findViewById(R.id.spinnerProvider)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)
        btnSave = findViewById(R.id.btnSaveSettings)
    }

    private fun setupSpinners() {
        spinnerChar.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            RoamieCharacter.entries.map { it.displayName }
        )
        spinnerChar.setSelection(auth.character.ordinal)

        spinnerSize.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            RoamieSize.entries.map { it.displayName }
        )
        spinnerSize.setSelection(auth.size.ordinal)

        spinnerProvider.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.uppercase() }
        )
        val provIndex = providers.indexOf(auth.provider.lowercase()).coerceAtLeast(0)
        spinnerProvider.setSelection(provIndex)

        etModel.setText(auth.modelId)
        etApiKey.setText(auth.apiKey)
    }

    private fun setupButtons() {
        btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        btnPermMic.setOnClickListener {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 102)
        }

        btnPermAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnSave.setOnClickListener {
            auth.character = RoamieCharacter.entries[spinnerChar.selectedItemPosition]
            auth.size = RoamieSize.entries[spinnerSize.selectedItemPosition]
            auth.provider = providers[spinnerProvider.selectedItemPosition]
            auth.modelId = etModel.text.toString().trim()
            auth.apiKey = etApiKey.text.toString().trim()
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            if (auth.isOverlayActive) {
                stopService(Intent(this, RoamieOverlayService::class.java))
                auth.isOverlayActive = false
                updateServiceStatus()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Please grant 'Display Over Other Apps' first!", Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                    return@setOnClickListener
                }

                // Save selected settings first
                btnSave.performClick()

                val serviceIntent = Intent(this, RoamieOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                updateServiceStatus()
            }
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        btnPermOverlay.text = if (hasOverlay) "Granted" else "Grant"
        btnPermOverlay.isEnabled = !hasOverlay

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        btnPermMic.text = if (hasMic) "Granted" else "Grant"
        btnPermMic.isEnabled = !hasMic

        val hasHands = RoamieHandsAccessibilityService.instance != null
        btnPermAccessibility.text = if (hasHands) "Active" else "Enable"
        btnPermAccessibility.isEnabled = !hasHands
    }

    private fun updateServiceStatus() {
        if (auth.isOverlayActive) {
            tvStatus.text = "Status: ACTIVE (Floating on Screen)"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            btnToggle.text = "Stop Floating Roamie"
        } else {
            tvStatus.text = "Status: Inactive"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btnToggle.text = "Start Floating Roamie"
        }
    }
}
