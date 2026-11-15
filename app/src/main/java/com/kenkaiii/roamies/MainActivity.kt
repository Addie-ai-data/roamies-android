package com.kenkaiii.roamies

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var auth: AuthStorage
    private lateinit var memoryStore: SidekickMemoryStore
    private lateinit var ttsClient: FishAudioTtsClient
    private lateinit var llmClient: LlmClient

    private lateinit var webView: WebView
    private lateinit var tvActiveBuddy: TextView
    private lateinit var btnToggleSize: MaterialButton
    private lateinit var btnOpenSettings: ImageButton
    private lateinit var etUserPrompt: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton

    private lateinit var chipJarvis: MaterialButton
    private lateinit var chipRoamie: MaterialButton
    private lateinit var chipAnime: MaterialButton
    private lateinit var chipSarah: MaterialButton
    private lateinit var chipAdrian: MaterialButton

    private var speechRecognizer: SpeechRecognizer? = null

    // System voice recognition fallback launcher (guaranteed across all Android skins)
    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        setMicActive(false)
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                sendUserMessage(spokenText)
            }
        }
        evalJs("RoamieAndroid.showHandsBadge(null); RoamieAndroid.setSpeechRipple(false, 0);")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = AuthStorage(this)
        memoryStore = SidekickMemoryStore(this)
        llmClient = LlmClient(this, auth, memoryStore)

        ttsClient = FishAudioTtsClient(this, auth) { speaking, amp ->
            evalJs("RoamieAndroid.setSpeechRipple($speaking, $amp);")
        }

        bindViews()
        setupWebView()
        setupCharacterChips()
        setupChatBar()
        setupSpeechRecognizer()

        // Welcome utterance on startup
        mainHandler.postDelayed({
            activityScope.launch {
                if (auth.apiKey.isBlank() && !auth.provider.contains("local")) {
                    ttsClient.speak("J.A.R.V.I.S. online. Tap the settings gear icon at the top right to select your AI model, sir!")
                } else {
                    ttsClient.speak("J.A.R.V.I.S. online. Arc Reactor calibrated and ${auth.model} standing by. How may I assist you, sir?")
                }
            }
        }, 1500L)
    }

    private fun bindViews() {
        webView = findViewById(R.id.webViewRoamie)
        tvActiveBuddy = findViewById(R.id.tvActiveBuddy)
        btnToggleSize = findViewById(R.id.btnToggleSize)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        etUserPrompt = findViewById(R.id.etUserPrompt)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)

        chipJarvis = findViewById(R.id.chipJarvis)
        chipRoamie = findViewById(R.id.chipRoamie)
        chipAnime = findViewById(R.id.chipAnime)
        chipSarah = findViewById(R.id.chipSarah)
        chipAdrian = findViewById(R.id.chipAdrian)

        btnToggleSize.setOnClickListener {
            val sizes = RoamieSize.values()
            val nextIdx = (auth.size.ordinal + 1) % sizes.size
            auth.size = sizes[nextIdx]
            btnToggleSize.text = "Size: ${auth.size.displayName}"
            evalJs("RoamieAndroid.setSize('${auth.size.id}');")
        }

        btnOpenSettings.setOnClickListener {
            showSettingsDialog()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        val bridge = AndroidBridge(
            onHitRectsUpdated = {},
            onHotChanged = {},
            onSidecarMessageReceived = {},
            onVoiceRequested = { startVoiceListening() },
            onPomodoroDone = { label ->
                vibratePhone()
                activityScope.launch {
                    ttsClient.speak("Focus session complete, sir. 25 minutes concluded. May I suggest a 5-minute break?")
                }
            }
        )
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                mainHandler.postDelayed({
                    evalJs("RoamieAndroid.setLook('${auth.character.id}'); RoamieAndroid.setSize('${auth.size.id}');")
                }, 300L)
            }
        }

        webView.loadUrl("file:///android_asset/roamies/desktop.html")
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(500)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupCharacterChips() {
        val updateChipHighlight = {
            chipJarvis.strokeColor = ContextCompat.getColorStateList(this, if (auth.character == RoamieCharacter.JARVIS) R.color.accent_cyan else R.color.border_color)
            chipRoamie.strokeColor = ContextCompat.getColorStateList(this, if (auth.character == RoamieCharacter.ROAMIE) R.color.accent_cyan else R.color.border_color)
            chipAnime.strokeColor = ContextCompat.getColorStateList(this, if (auth.character == RoamieCharacter.ANIME) R.color.accent_cyan else R.color.border_color)
            chipSarah.strokeColor = ContextCompat.getColorStateList(this, if (auth.character == RoamieCharacter.SARAH) R.color.accent_cyan else R.color.border_color)
            chipAdrian.strokeColor = ContextCompat.getColorStateList(this, if (auth.character == RoamieCharacter.ADRIAN) R.color.accent_cyan else R.color.border_color)
        }

        val selectChar = { character: RoamieCharacter ->
            auth.character = character
            tvActiveBuddy.text = character.displayName
            updateChipHighlight()
            evalJs("RoamieAndroid.setLook('${character.id}');")
        }

        chipJarvis.setOnClickListener { selectChar(RoamieCharacter.JARVIS) }
        chipRoamie.setOnClickListener { selectChar(RoamieCharacter.ROAMIE) }
        chipAnime.setOnClickListener { selectChar(RoamieCharacter.ANIME) }
        chipSarah.setOnClickListener { selectChar(RoamieCharacter.SARAH) }
        chipAdrian.setOnClickListener { selectChar(RoamieCharacter.ADRIAN) }

        updateChipHighlight()
    }

    private fun setupChatBar() {
        btnSend.setOnClickListener {
            val text = etUserPrompt.text.toString().trim()
            if (text.isNotEmpty()) {
                etUserPrompt.setText("")
                sendUserMessage(text)
            }
        }

        etUserPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = etUserPrompt.text.toString().trim()
                if (text.isNotEmpty()) {
                    etUserPrompt.setText("")
                    sendUserMessage(text)
                }
                true
            } else {
                false
            }
        }

        btnMic.setOnClickListener {
            checkAndStartVoice()
        }
    }

    private fun checkAndStartVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        } else {
            startVoiceListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceListening()
            } else {
                Toast.makeText(this, "Microphone permission required for voice interaction.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setMicActive(active: Boolean) {
        if (active) {
            btnMic.setColorFilter(ContextCompat.getColor(this, R.color.accent_cyan))
            btnMic.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
        } else {
            btnMic.clearColorFilter()
            btnMic.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }
    }

    private fun sendUserMessage(userText: String) {
        activityScope.launch {
            val lower = userText.lowercase()

            // Quick client-side intercept for Pomodoro focus commands
            if (lower.contains("pomodoro") || lower.contains("focus timer") || lower.contains("start focus") || lower.contains("focus sprint")) {
                var seconds = 1500L // 25 min
                if (lower.contains("5 min") || lower.contains("break")) seconds = 300L
                else if (lower.contains("10 min")) seconds = 600L
                else if (lower.contains("15 min")) seconds = 900L
                else if (lower.contains("30 min")) seconds = 1800L
                else if (lower.contains("45 min")) seconds = 2700L
                else if (lower.contains("60 min") || lower.contains("1 hour")) seconds = 3600L

                evalJs("window.RoamieAndroid?.startPomodoro($seconds, 'Focus Sprint');")
                val reply = "Focus sprint initiated, sir. ${seconds / 60} minutes on the clock. All distractions filtered."
                evalJs("window.Roamie?.agent?.say('$reply');")
                ttsClient.speak(reply)
                return@launch
            } else if (lower.contains("pause timer") || lower.contains("pause pomodoro")) {
                evalJs("window.RoamieAndroid?.pausePomodoro();")
                val reply = "Timer paused, sir."
                evalJs("window.Roamie?.agent?.say('$reply');")
                ttsClient.speak(reply)
                return@launch
            } else if (lower.contains("reset timer") || lower.contains("stop pomodoro") || lower.contains("cancel pomodoro")) {
                evalJs("window.RoamieAndroid?.resetPomodoro();")
                val reply = "Pomodoro timer reset, sir."
                evalJs("window.Roamie?.agent?.say('$reply');")
                ttsClient.speak(reply)
                return@launch
            }

            evalJs("RoamieAndroid.showHandsBadge('Thinking...');")
            val turn = llmClient.executeTurn(userText)
            evalJs("RoamieAndroid.showHandsBadge(null);")

            // Show speech bubble on Roamie canvas
            val safeReply = turn.spokenReply.replace("'", "\\'").replace("\n", " ")
            evalJs("window.Roamie?.agent?.say('$safeReply');")

            // Speak response via TTS
            ttsClient.speak(turn.spokenReply)

            // Execute structured tools
            for (tool in turn.toolCalls) {
                if (tool.tool.equals("start_pomodoro", true)) {
                    val sec = if (tool.delaySeconds > 0) tool.delaySeconds else 1500L
                    val lbl = tool.text ?: "Focus Sprint"
                    evalJs("window.RoamieAndroid?.startPomodoro($sec, '$lbl');")
                } else if (tool.tool.equals("pause_pomodoro", true)) {
                    evalJs("window.RoamieAndroid?.pausePomodoro();")
                } else if (tool.tool.equals("reset_pomodoro", true)) {
                    evalJs("window.RoamieAndroid?.resetPomodoro();")
                } else if (tool.tool.equals("draw_ink_card", true)) {
                    val title = tool.cardTitle ?: "Insight"
                    val bulletsJson = org.json.JSONArray(tool.cardBullets).toString()
                    evalJs("RoamieAndroid.drawCard('$title', $bulletsJson, '${tool.diagramType ?: "CARD"}');")
                } else if (tool.tool.equals("save_memory", true) && tool.text != null) {
                    memoryStore.addMemory(tool.text)
                }
            }
        }
    }

    private fun setupSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        setMicActive(true)
                        evalJs("RoamieAndroid.showHandsBadge('Listening...');")
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        evalJs("RoamieAndroid.setSpeechRipple(false, $normalized);")
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        setMicActive(false)
                        evalJs("RoamieAndroid.showHandsBadge('Thinking...');")
                    }
                    override fun onError(error: Int) {
                        setMicActive(false)
                        evalJs("RoamieAndroid.showHandsBadge(null); RoamieAndroid.setSpeechRipple(false, 0);")
                        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            launchSystemVoiceFallback()
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        setMicActive(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            sendUserMessage(text)
                        } else {
                            evalJs("RoamieAndroid.showHandsBadge(null);")
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            speechRecognizer = null
        }
    }

    private fun launchSystemVoiceFallback() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to J.A.R.V.I.S...")
            }
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice input service unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceListening() {
        mainHandler.post {
            ttsClient.stop()
            setMicActive(true)
            evalJs("RoamieAndroid.showHandsBadge('Listening...');")

            if (speechRecognizer == null) {
                setupSpeechRecognizer()
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to J.A.R.V.I.S...")
            }

            try {
                if (speechRecognizer != null) {
                    speechRecognizer?.startListening(intent)
                } else {
                    launchSystemVoiceFallback()
                }
            } catch (e: Exception) {
                launchSystemVoiceFallback()
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinnerProv: Spinner = dialogView.findViewById(R.id.dialogSpinnerProvider)
        val spinnerModelPreset: Spinner = dialogView.findViewById(R.id.dialogSpinnerModelPreset)
        val etModel: EditText = dialogView.findViewById(R.id.dialogEtModel)
        val etEndpoint: EditText = dialogView.findViewById(R.id.dialogEtEndpoint)
        val etKey: EditText = dialogView.findViewById(R.id.dialogEtApiKey)
        val btnTestConn: MaterialButton = dialogView.findViewById(R.id.dialogBtnTestConnection)
        val tvTestStatus: TextView = dialogView.findViewById(R.id.dialogTvTestStatus)

        val providers = listOf(
            "Google Gemini (Free Tier)",
            "OpenRouter (200+ models, Free tier)",
            "Groq (Ultra-Fast 300+ tok/s)",
            "OpenAI (ChatGPT)",
            "Anthropic Claude",
            "Local LLM / Ollama (Mobile / Termux / LAN)",
            "Custom / OpenCode"
        )
        val providerKeys = listOf("gemini", "openrouter", "groq", "openai", "anthropic", "local", "custom")

        val modelPresets = mapOf(
            "gemini" to listOf("gemini-1.5-flash", "gemini-2.0-flash-exp", "gemini-1.5-flash-8b", "gemini-1.5-pro", "Custom model..."),
            "openrouter" to listOf("google/gemini-2.0-flash-exp:free", "meta-llama/llama-3.3-70b-instruct:free", "deepseek/deepseek-chat", "openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet", "Custom model..."),
            "groq" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768", "Custom model..."),
            "openai" to listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo", "Custom model..."),
            "anthropic" to listOf("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "Custom model..."),
            "local" to listOf("llama3.2", "qwen2.5", "mistral", "phi3", "gemma2", "Custom model..."),
            "custom" to listOf("Custom model...")
        )

        val defaultEndpoints = mapOf(
            "gemini" to "",
            "openrouter" to "https://openrouter.ai/api/v1/chat/completions",
            "groq" to "https://api.groq.com/openai/v1/chat/completions",
            "openai" to "https://api.openai.com/v1/chat/completions",
            "anthropic" to "https://api.anthropic.com/v1/messages",
            "local" to "http://127.0.0.1:11434/v1/chat/completions",
            "custom" to "http://127.0.0.1:8000/v1/chat/completions"
        )

        spinnerProv.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        val currentProvKey = auth.provider.lowercase()
        val initProvIdx = providerKeys.indexOfFirst { currentProvKey.contains(it) }.coerceAtLeast(0)
        spinnerProv.setSelection(initProvIdx)

        fun updateModelPresets(provKey: String) {
            val presets = modelPresets[provKey] ?: listOf("Custom model...")
            spinnerModelPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets)
            val currentModel = auth.model.trim()
            val presetIdx = presets.indexOf(currentModel)
            if (presetIdx >= 0) {
                spinnerModelPreset.setSelection(presetIdx)
            } else {
                spinnerModelPreset.setSelection(presets.size - 1)
            }

            // Update endpoint display
            if (auth.endpoint.isNotBlank()) {
                etEndpoint.setText(auth.endpoint)
            } else {
                etEndpoint.setText(defaultEndpoints[provKey].orEmpty())
            }

            // Local LLM does not strictly require an API key
            if (provKey == "local") {
                etKey.hint = "Not required for local models"
            } else {
                etKey.hint = "Paste your API key here"
            }
        }

        updateModelPresets(providerKeys[initProvIdx])

        spinnerProv.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedKey = providerKeys[position]
                updateModelPresets(selectedKey)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerModelPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = spinnerModelPreset.selectedItem.toString()
                if (selected != "Custom model...") {
                    etModel.setText(selected)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        var cleanModel = auth.model.trim().removePrefix("models/")
        if (cleanModel.contains("2.5") || cleanModel.isBlank()) cleanModel = "gemini-1.5-flash"
        etModel.setText(cleanModel)
        etKey.setText(auth.apiKey)
        if (auth.endpoint.isNotBlank()) etEndpoint.setText(auth.endpoint)

        // Smart API key auto-detection on paste
        etKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val key = s?.toString()?.trim().orEmpty()
                if (key.startsWith("AIzaSy")) {
                    spinnerProv.setSelection(providerKeys.indexOf("gemini"))
                } else if (key.startsWith("sk-or-")) {
                    spinnerProv.setSelection(providerKeys.indexOf("openrouter"))
                } else if (key.startsWith("gsk_")) {
                    spinnerProv.setSelection(providerKeys.indexOf("groq"))
                } else if (key.startsWith("sk-ant-")) {
                    spinnerProv.setSelection(providerKeys.indexOf("anthropic"))
                } else if (key.startsWith("sk-") && !key.startsWith("sk-or-") && !key.startsWith("sk-ant-")) {
                    spinnerProv.setSelection(providerKeys.indexOf("openai"))
                }
            }
        })

        // Test Connection button
        btnTestConn.setOnClickListener {
            val selectedKey = providerKeys[spinnerProv.selectedItemPosition]
            auth.provider = selectedKey
            var m = etModel.text.toString().trim().removePrefix("models/")
            if (m.isBlank()) m = if (selectedKey == "gemini") "gemini-1.5-flash" else "gpt-4o-mini"
            auth.model = m
            auth.apiKey = etKey.text.toString().trim()
            auth.endpoint = etEndpoint.text.toString().trim()

            tvTestStatus.visibility = View.VISIBLE
            tvTestStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan))
            tvTestStatus.text = "⚡ Testing connection to $m..."

            activityScope.launch {
                val (ok, msg) = llmClient.testConnection()
                tvTestStatus.text = msg
                tvTestStatus.setTextColor(if (ok) Color.parseColor("#4ade80") else Color.parseColor("#f87171"))
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Companion Intelligence Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val selectedKey = providerKeys[spinnerProv.selectedItemPosition]
                auth.provider = selectedKey
                var modelInput = etModel.text.toString().trim().removePrefix("models/")
                if (modelInput.contains("2.5") || modelInput.isBlank()) {
                    modelInput = if (selectedKey == "gemini") "gemini-1.5-flash" else "gpt-4o-mini"
                }
                auth.model = modelInput
                auth.apiKey = etKey.text.toString().trim()
                auth.endpoint = etEndpoint.text.toString().trim()

                Toast.makeText(this, "Settings saved! Using $modelInput", Toast.LENGTH_SHORT).show()
                activityScope.launch {
                    ttsClient.speak("Settings updated, sir. Arc Reactor and $modelInput are calibrated.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun evalJs(script: String) {
        mainHandler.post {
            webView.evaluateJavascript(script, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        ttsClient.destroy()
        activityScope.cancel()
    }
}
