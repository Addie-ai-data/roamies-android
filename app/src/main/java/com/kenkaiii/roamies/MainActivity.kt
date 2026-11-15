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
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

    // System voice recognition fallback launcher (guaranteed to work across all Android versions/OEM skins)
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
                if (auth.apiKey.isBlank()) {
                    ttsClient.speak("J.A.R.V.I.S. online. Tap the settings gear icon at the top right to paste your Gemini API key, sir!")
                } else {
                    ttsClient.speak("J.A.R.V.I.S. online. Arc Reactor calibrated. How may I assist you, sir?")
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
                        // If in-process recognition encounters an error, fallback to system voice popup
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            // User remained silent, no need to fallback
                        } else {
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
        val etModel: EditText = dialogView.findViewById(R.id.dialogEtModel)
        val etKey: EditText = dialogView.findViewById(R.id.dialogEtApiKey)

        val providers = listOf("Gemini", "OpenAI", "Anthropic")
        spinnerProv.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        val provIndex = providers.indexOfFirst { it.equals(auth.provider, ignoreCase = true) }.coerceAtLeast(0)
        spinnerProv.setSelection(provIndex)

        // Ensure clean model name is presented
        var cleanModel = auth.model.trim().removePrefix("models/")
        if (cleanModel.contains("2.5") || cleanModel.isBlank()) {
            cleanModel = "gemini-1.5-flash"
        }
        etModel.setText(cleanModel)
        etKey.setText(auth.apiKey)

        AlertDialog.Builder(this)
            .setTitle("Companion Intelligence Settings")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                auth.provider = spinnerProv.selectedItem.toString().lowercase()
                var modelInput = etModel.text.toString().trim().removePrefix("models/")
                if (modelInput.contains("2.5") || modelInput.isBlank()) {
                    modelInput = "gemini-1.5-flash"
                }
                auth.model = modelInput
                auth.apiKey = etKey.text.toString().trim()
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
