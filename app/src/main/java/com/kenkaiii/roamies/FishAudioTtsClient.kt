package com.kenkaiii.roamies

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sin

class FishAudioTtsClient(
    private val context: Context,
    private val auth: AuthStorage,
    private val onAudioLevel: (speaking: Boolean, amp: Float) -> Unit
) {
    private var localTts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSpeaking = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        initLocalTts()
    }

    private fun initLocalTts() {
        localTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                localTts?.language = Locale.US
                isTtsReady = true
                setupUtteranceListener()
            }
        }
    }

    private fun setupUtteranceListener() {
        localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            private var animRunnable: Runnable? = null
            private var phase = 0f

            override fun onStart(utteranceId: String?) {
                isSpeaking = true
                animRunnable = object : Runnable {
                    override fun run() {
                        if (!isSpeaking) return
                        phase += 0.35f
                        val fakeAmp = (0.35f + 0.35f * abs(sin(phase))).coerceIn(0f, 1f)
                        onAudioLevel(true, fakeAmp)
                        mainHandler.postDelayed(this, 30L)
                    }
                }
                mainHandler.post(animRunnable!!)
            }

            override fun onDone(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post { onAudioLevel(false, 0f) }
            }

            override fun onError(utteranceId: String?) {
                isSpeaking = false
                mainHandler.post { onAudioLevel(false, 0f) }
            }
        })
    }

    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        val apiKey = auth.fishAudioKey
        if (apiKey.isNotBlank()) {
            val success = tryFishAudio(text, apiKey)
            if (!success) {
                speakLocal(text)
            }
        } else {
            speakLocal(text)
        }
    }

    private fun speakLocal(text: String) {
        if (!isTtsReady || localTts == null) return
        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roamie_${System.currentTimeMillis()}")
    }

    private fun tryFishAudio(text: String, apiKey: String): Boolean {
        return try {
            val json = org.json.JSONObject().apply {
                put("text", text)
                put("format", "pcm")
            }
            val request = Request.Builder()
                .url("https://api.fish.audio/v1/tts")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return false

            val pcmBytes = response.body?.bytes() ?: return false
            playPcmWithMetering(pcmBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun playPcmWithMetering(pcmBytes: ByteArray) {
        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()
        isSpeaking = true

        val chunkSize = 2048
        var offset = 0
        while (offset < pcmBytes.size && isSpeaking) {
            val length = (pcmBytes.size - offset).coerceAtMost(chunkSize)
            audioTrack.write(pcmBytes, offset, length)

            // Calculate instantaneous RMS amplitude from 16-bit PCM samples
            var sumSquare = 0.0
            var i = 0
            while (i < length) {
                val sample = (pcmBytes[offset + i].toInt() and 0xFF) or (pcmBytes[offset + i + 1].toInt() shl 8)
                sumSquare += sample * sample
                i += 2
            }
            val rms = Math.sqrt(sumSquare / (length / 2))
            val normalized = (rms / 12000.0).coerceIn(0.0, 1.0).toFloat()

            mainHandler.post { onAudioLevel(true, normalized) }
            offset += length
        }

        audioTrack.stop()
        audioTrack.release()
        isSpeaking = false
        mainHandler.post { onAudioLevel(false, 0f) }
    }

    fun stop() {
        isSpeaking = false
        localTts?.stop()
        mainHandler.post { onAudioLevel(false, 0f) }
    }

    fun destroy() {
        stop()
        localTts?.shutdown()
    }
}
