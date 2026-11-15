# Roamies for Android (1:1 Native Android Port)

<p align="center">
  <a href="https://github.com/Addie-ai-data/roamies-android/releases/latest"><img src="https://img.shields.io/github/v/release/Addie-ai-data/roamies-android?style=for-the-badge&label=Download%20APK&color=00F5D4" alt="Download APK"></a>
  <a href="https://github.com/Addie-ai-data/roamies-android/releases"><img src="https://img.shields.io/badge/Platform-Android%208.0%2B-blue?style=for-the-badge" alt="Android Compatible"></a>
</p>

An identical, 1:1 port of [KenKaiii/Roamies](https://github.com/KenKaiii/Roamies) built natively for Android devices.

> 📲 **1-Click Download**: [**Download roamies-v0.2.0.apk**](https://github.com/Addie-ai-data/roamies-android/releases/download/v0.2.0/roamies-v0.2.0.apk)

---

## What Makes It "Same-to-Same"

- **All 5 Official Roamies**:
  - **JARVIS**: The glowing, rippling 3D LED point-sphere with audio reactivity.
  - **Roamie**: The classic glowing pixel companion with physics and dithering.
  - **Anime Girl**: Head-and-shoulders hologram with swinging twintails and expressive blinking.
  - **Sarah**: Hologram with long swaying hair and side parting.
  - **Adrian**: Hologram with bouncy quiff.
- **All 3 Sizes**: Default, Small, and Cursor / Reticle pointer.
- **Identical Visual & Audio Engine**: Runs the exact HTML5 Canvas + WebGL shaders, retro 8-bit Web Audio SFX, and Martian Mono / Doto typography.
- **Glowing Ink On Screen**:
  - Rings the exact button to tap ("Shows You How").
  - Explanatory holographic cards, discovery timelines, and turning 3D models.

---

## Android Mobile Superpowers

1. **Floating Window Overlay (`SYSTEM_ALERT_WINDOW`)**:
   Roamie roams across your Android screen above all your apps (Chrome, WhatsApp, YouTube, Instagram, Home Screen).
2. **Smart Touch Passthrough**:
   - Touch & Drag Roamie to reposition him anywhere.
   - Long-press Roamie (350ms) to trigger Push-to-Talk (replaces desktop Ctrl+Alt / ⌃⌥).
   - Tap Roamie for quick actions.
   - Touching anywhere else passes straight through to whatever app is underneath — no interference with normal phone usage.
3. **Android Hands Accessibility Service (`AccessibilityService`)**:
   - Inspects UI element hierarchies on screen (`AccessibilityNodeInfo`).
   - Locates target buttons and draws glowing cyan rings around them.
   - Automates taps and typing when permitted.
4. **Voice & Neural TTS**:
   - Push-to-talk speech recognition using Android's built-in `SpeechRecognizer`.
   - Voice replies using [Fish Audio](https://fish.audio) or offline Android `TextToSpeech`, with real-time waveform metering driving JARVIS's ripples and character speech.
5. **Sidekick Memory & Reminders**:
   - Persistent memory storage for facts and preferences.
   - Second-exact reminders scheduled with Android `AlarmManager`.
6. **Multi-Provider AI Brain**:
   - Direct native support for Google Gemini (e.g. `gemini-2.5-flash`), Anthropic Claude, OpenAI, Grok, and local Ollama.

---

## How to Build the APK

### Option 1: Android Studio (Recommended)
1. Open **Android Studio**.
2. Select **File > Open...** and choose `roamies-android`.
3. Wait for Gradle sync to complete.
4. Go to **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. The generated APK will be at:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Transfer to your phone and install!

### Option 2: Command Line (Gradle)
```bash
cd roamies-android
./gradlew assembleDebug
# Generated APK: app/build/outputs/apk/debug/app-debug.apk
```

### Option 3: GitHub Actions (Zero Local Setup)
If you push this project to a GitHub repository, the included `.github/workflows/build-apk.yml` will automatically build the APK on GitHub's cloud runners.
- Go to your repository's **Actions** tab.
- Click **Build Roamies Android APK** -> **Run workflow**.
- Download the built `Roamies-Android-Debug-APK` directly to your phone from the workflow artifacts!

---

## Permissions Setup on Phone

When launching the app for the first time:
1. **Display over other apps**: Toggle ON to let Roamie float on your screen.
2. **Microphone**: Allow permission to enable Push-to-Talk voice recognition.
3. **Accessibility (Roamies Hands Helper)**: Enable in Android Accessibility settings to allow screen element detection and button ringing.
4. Pick your Roamie (JARVIS, Roamie, Anime Girl, Sarah, Adrian), enter your Gemini or AI provider API key, and tap **Start Floating Roamie**!
