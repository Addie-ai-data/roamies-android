package com.kenkaiii.roamies

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        // Welcome utterance if first launch
        mainHandler.postDelayed({
            if (auth.apiKey.isBlank()) {
                ttsClient.speak("Hello! I am ${auth.character.displayName}. Tap the settings icon at the top right to paste your Gemini API key so we can chat!")
            } else {
                ttsClient.speak("At your service.")
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

        tvActiveBuddy.text = auth.character.displayName
        btnToggleSize.text = "Size: ${auth.size.displayName}"

        btnToggleSize.setOnClickListener {
            val sizes = RoamieSize.entries
            val nextIndex = (auth.size.ordinal + 1) % sizes.size
            auth.size = sizes[nextIndex]
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
            onVoiceRequested = { startVoiceListening() }
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

    private fun setupCharacterChips() {
        val updateChipHighlight = {
            val cyan = ContextCompat.getColor(this, R.color.accent_cyan)
            val dim = ContextCompat.getColor(this, R.color.border_color)

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
                btnSend.performClick()
                true
            } else {
                false
            }
        }

        btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
            } else {
                startVoiceListening()
            }
        }
    }

    private fun sendUserMessage(userText: String) {
        activityScope.launch {
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
                if (tool.tool.equals("draw_ink_card", true)) {
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    evalJs("RoamieAndroid.showHandsBadge('Listening...');")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    evalJs("RoamieAndroid.setSpeechRipple(false, $normalized);")
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    evalJs("RoamieAndroid.showHandsBadge('Thinking...');")
                }
                override fun onError(error: Int) {
                    evalJs("RoamieAndroid.showHandsBadge(null); RoamieAndroid.setSpeechRipple(false, 0);")
                }
                override fun onResults(results: Bundle?) {
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
    }

    private fun startVoiceListening() {
        mainHandler.post {
            ttsClient.stop()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val spinnerProv: Spinner = dialogView.findViewById(R.id.dialogSpinnerProvider)
        val etModel: EditText = dialogView.findViewById(R.id.dialogEtModel)
        val etApiKey: EditText = dialogView.findViewById(R.id.dialogEtApiKey)
        val btnSave: MaterialButton = dialogView.findViewById(R.id.dialogBtnSave)

        val providers = listOf("gemini", "openai", "anthropic", "ollama")
        spinnerProv.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            providers.map { it.uppercase() }
        )
        val pIndex = providers.indexOf(auth.provider.lowercase()).coerceAtLeast(0)
        spinnerProv.setSelection(pIndex)

        etModel.setText(auth.modelId)
        etApiKey.setText(auth.apiKey)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnSave.setOnClickListener {
            auth.provider = providers[spinnerProv.selectedItemPosition]
            auth.modelId = etModel.text.toString().trim()
            auth.apiKey = etApiKey.text.toString().trim()
            dialog.dismiss()
            Toast.makeText(this, "API Key Saved! Say hello to your Roamie.", Toast.LENGTH_SHORT).show()
            ttsClient.speak("Settings saved! What would you like to explore today?")
        }

        dialog.show()
    }

    private fun evalJs(code: String) {
        mainHandler.post {
            webView.evaluateJavascript(code, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsClient.destroy()
        speechRecognizer?.destroy()
        activityScope.cancel()
    }
}
