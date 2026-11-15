package com.kenkaiii.roamies

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RoamieOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var auth: AuthStorage
    private lateinit var memoryStore: SidekickMemoryStore
    private lateinit var ttsClient: FishAudioTtsClient
    private lateinit var llmClient: LlmClient

    private lateinit var overlayContainer: TouchOverlayView
    private lateinit var webView: WebView
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        auth = AuthStorage(this)
        memoryStore = SidekickMemoryStore(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startForegroundNotification()

        ttsClient = FishAudioTtsClient(this, auth) { speaking, amp ->
            evalJs("RoamieAndroid.setSpeechRipple($speaking, $amp);")
        }

        llmClient = LlmClient(this, auth, memoryStore)

        initOverlayView()
        initSpeechRecognizer()
        auth.isOverlayActive = true
    }

    private fun startForegroundNotification() {
        val channelId = "roamies_overlay"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Roamies Active Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that your Roamie companion is roaming the screen"
            }
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Roamies is on your screen")
            .setContentText("Tap or hold Roamie to talk")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initOverlayView() {
        overlayContainer = TouchOverlayView(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyCompanionSettings()
                }
            }
        }

        val bridge = AndroidBridge(
            onHitRectsUpdated = { rects ->
                overlayContainer.hitRects = rects
            },
            onHotChanged = { hot ->
                updateTouchPassthrough(hot)
            },
            onSidecarMessageReceived = { msgJson ->
                handleSidecarMessage(msgJson)
            },
            onVoiceRequested = {
                startVoiceListening()
            }
        )

        webView.addJavascriptInterface(bridge, "AndroidBridge")
        overlayContainer.addView(
            webView,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayContainer, overlayParams)

        // Load the 1:1 Roamies desktop canvas engine
        webView.loadUrl("file:///android_asset/roamies/desktop.html")
    }

    private fun updateTouchPassthrough(isHot: Boolean) {
        mainHandler.post {
            // When hot is false and no interactive card is up, allow clicks to flow through freely
            if (isHot) {
                overlayParams.flags = overlayParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else if (overlayContainer.hitRects.isEmpty()) {
                overlayParams.flags = overlayParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            try {
                windowManager.updateViewLayout(overlayContainer, overlayParams)
            } catch (e: Exception) {
                // Ignore if view detached
            }
        }
    }

    private fun applyCompanionSettings() {
        val characterId = auth.character.id
        val sizeId = auth.size.id
        evalJs("RoamieAndroid.setLook('$characterId'); RoamieAndroid.setSize('$sizeId');")
    }

    private fun initSpeechRecognizer() {
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
                        processUserUtterance(text)
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

    private fun processUserUtterance(userText: String) {
        serviceScope.launch {
            evalJs("RoamieAndroid.showHandsBadge('Thinking...');")
            val turn = llmClient.executeTurn(userText)
            evalJs("RoamieAndroid.showHandsBadge(null);")

            // Execute tools first
            for (tool in turn.toolCalls) {
                executeTool(tool)
            }

            // Speak response
            if (turn.spokenReply.isNotBlank()) {
                ttsClient.speak(turn.spokenReply)
            }
        }
    }

    private fun executeTool(tool: LlmToolCall) {
        when (tool.tool.lowercase()) {
            "ring_button", "show_how_walkthrough" -> {
                val label = tool.targetLabel ?: return
                val element = RoamieHandsAccessibilityService.instance?.findElementMatching(label)
                if (element != null) {
                    val b = element.bounds
                    evalJs("RoamieAndroid.ringTarget(${b.left}, ${b.top}, ${b.width()}, ${b.height()}, '${tool.text ?: label}');")
                }
            }
            "click_element" -> {
                val label = tool.targetLabel ?: return
                val element = RoamieHandsAccessibilityService.instance?.findElementMatching(label)
                if (element != null) {
                    val cx = element.bounds.centerX().toFloat()
                    val cy = element.bounds.centerY().toFloat()
                    RoamieHandsAccessibilityService.instance?.performTapAt(cx, cy)
                }
            }
            "type_in_app" -> {
                val textToType = tool.text ?: return
                RoamieHandsAccessibilityService.instance?.typeTextIntoFocusedNode(textToType)
            }
            "draw_ink_card" -> {
                val title = tool.cardTitle ?: "Note"
                val bulletsJson = org.json.JSONArray(tool.cardBullets).toString()
                evalJs("RoamieAndroid.drawCard('$title', $bulletsJson, '${tool.diagramType ?: "CARD"}');")
            }
            "save_memory" -> {
                val fact = tool.text ?: return
                memoryStore.addMemory(fact)
            }
            "schedule_reminder" -> {
                val reminderText = tool.text ?: return
                val delay = if (tool.delaySeconds > 0) tool.delaySeconds else 60L
                memoryStore.scheduleReminder(reminderText, delay)
            }
            "clear_ink" -> {
                evalJs("RoamieAndroid.clearInk();")
            }
        }
    }

    private fun handleSidecarMessage(msgJson: String) {
        // Handle sidecar messages from web code
    }

    private fun evalJs(code: String) {
        mainHandler.post {
            webView.evaluateJavascript(code, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        auth.isOverlayActive = false
        ttsClient.destroy()
        speechRecognizer?.destroy()
        serviceScope.cancel()
        try {
            windowManager.removeView(overlayContainer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
