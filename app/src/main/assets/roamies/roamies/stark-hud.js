// J.A.R.V.I.S. Stark HUD, Arc Reactor & Pomodoro Engine for Roamies Android
// Inspired by Iron Man movies & AnaaySampat/jarvis
(() => {
  // ── Web Audio Synthesizer (Stark Sound Effects) ──
  let audioCtx = null;
  function getAudioContext() {
    if (!audioCtx) {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (AudioCtx) audioCtx = new AudioCtx();
    }
    if (audioCtx && audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
    return audioCtx;
  }

  const SFX = {
    beep(freq = 880, dur = 0.05, type = 'sine') {
      try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = type;
        osc.frequency.setValueAtTime(freq, ctx.currentTime);
        gain.gain.setValueAtTime(0.15, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + dur);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + dur);
      } catch (e) {}
    },

    chime() {
      // Iron Man HUD / Pomodoro Completion Fanfare (C5 -> E5 -> G5 -> C6)
      try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const notes = [523.25, 659.25, 783.99, 1046.5];
        notes.forEach((freq, idx) => {
          setTimeout(() => {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'triangle';
            osc.frequency.setValueAtTime(freq, ctx.currentTime);
            gain.gain.setValueAtTime(0.2, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.6);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start();
            osc.stop(ctx.currentTime + 0.6);
          }, idx * 120);
        });
      } catch (e) {}
    },

    overclock() {
      // Repulsor charge / Arc Reactor power-up sweep
      try {
        const ctx = getAudioContext();
        if (!ctx) return;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sawtooth';
        osc.frequency.setValueAtTime(140, ctx.currentTime);
        osc.frequency.exponentialRampToValueAtTime(880, ctx.currentTime + 0.5);
        gain.gain.setValueAtTime(0.12, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.5);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.5);
      } catch (e) {}
    }
  };

  // ── Pomodoro Focus Engine ──
  const Pomodoro = {
    active: false,
    paused: false,
    mode: 'focus', // 'focus' | 'short_break' | 'long_break'
    totalSeconds: 1500, // 25 min
    remainingSeconds: 1500,
    label: 'Focus Sprint',
    timerId: null,

    start(seconds = 1500, label = 'Focus Sprint', mode = 'focus') {
      this.totalSeconds = Math.max(10, seconds);
      this.remainingSeconds = this.totalSeconds;
      this.label = label;
      this.mode = mode;
      this.active = true;
      this.paused = false;

      SFX.beep(1200, 0.08);
      this.updateUI();

      if (this.timerId) clearInterval(this.timerId);
      this.timerId = setInterval(() => this.tick(), 1000);

      const msg = `Pomodoro initiated: ${this.formatTime(this.remainingSeconds)} on ${this.label}.`;
      window.__roamieHost?.({ type: 'stream', text: msg });
      setTimeout(() => window.__roamieHost?.({ type: 'done' }), 2000);
    },

    pause() {
      if (!this.active) return;
      this.paused = !this.paused;
      SFX.beep(600, 0.06);
      this.updateUI();
    },

    reset() {
      this.active = false;
      this.paused = false;
      this.remainingSeconds = this.totalSeconds;
      if (this.timerId) clearInterval(this.timerId);
      this.timerId = null;
      SFX.beep(400, 0.08);
      this.updateUI();
    },

    tick() {
      if (!this.active || this.paused) return;
      this.remainingSeconds--;
      this.updateUI();

      if (this.remainingSeconds <= 0) {
        this.complete();
      }
    },

    complete() {
      this.active = false;
      if (this.timerId) clearInterval(this.timerId);
      this.timerId = null;

      SFX.chime();
      this.updateUI();

      // Notify Android native bridge
      try {
        if (window.AndroidBridge && window.AndroidBridge.onPomodoroCompleted) {
          window.AndroidBridge.onPomodoroCompleted(this.label);
        }
      } catch (e) {}

      // Notify speech bubble
      const doneMsg = `Focus session complete, sir. 25 minutes concluded. May I suggest a 5-minute break?`;
      window.__roamieHost?.({ type: 'stream', text: doneMsg });
      setTimeout(() => window.__roamieHost?.({ type: 'done' }), 3000);
    },

    formatTime(sec) {
      const m = Math.floor(sec / 60);
      const s = sec % 60;
      return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    },

    updateUI() {
      const hudPill = document.getElementById('hudPomodoroPill');
      const hudTimer = document.getElementById('hudPomodoroTime');
      const hudLabel = document.getElementById('hudPomodoroLabel');
      const ring = document.getElementById('hudPomodoroArc');

      if (!hudPill) return;

      if (this.active) {
        hudPill.style.display = 'flex';
        hudTimer.textContent = this.formatTime(this.remainingSeconds);
        hudLabel.textContent = this.paused ? 'PAUSED' : this.label.toUpperCase();

        const fraction = this.remainingSeconds / this.totalSeconds;
        if (ring) {
          const dash = (1 - fraction) * 283; // 2 * PI * 45
          ring.style.strokeDashoffset = String(dash);
        }
      } else {
        hudPill.style.display = 'none';
      }
    }
  };

  // ── Inject HUD Elements into Page ──
  function initHudUI() {
    const overlay = document.createElement('div');
    overlay.id = 'starkHudOverlay';
    overlay.style.cssText = `
      position: fixed; inset: 0; pointer-events: none; z-index: 50;
      font-family: monospace, system-ui, sans-serif;
    `;

    overlay.innerHTML = `
      <!-- Corner Brackets -->
      <div style="position: absolute; top: 12px; left: 12px; width: 24px; height: 24px; border-top: 2px solid #00e5ff; border-left: 2px solid #00e5ff; opacity: 0.8;"></div>
      <div style="position: absolute; top: 12px; right: 12px; width: 24px; height: 24px; border-top: 2px solid #00e5ff; border-right: 2px solid #00e5ff; opacity: 0.8;"></div>
      <div style="position: absolute; bottom: 80px; left: 12px; width: 24px; height: 24px; border-bottom: 2px solid #00e5ff; border-left: 2px solid #00e5ff; opacity: 0.8;"></div>
      <div style="position: absolute; bottom: 80px; right: 12px; width: 24px; height: 24px; border-bottom: 2px solid #00e5ff; border-right: 2px solid #00e5ff; opacity: 0.8;"></div>

      <!-- Telemetry Readouts (Top Center) -->
      <div style="position: absolute; top: 8px; left: 0; right: 0; display: flex; justify-content: center; gap: 16px; font-size: 10px; color: rgba(0, 229, 255, 0.75); letter-spacing: 0.08em; text-transform: uppercase;">
        <span>CORE: <b style="color: #00e5ff;">100%</b></span>
        <span>TEMP: <b style="color: #00e5ff;">37°C</b></span>
        <span>STARK HUD // MARK VII</span>
      </div>

      <!-- Pomodoro Floating HUD Pill (Center Top) -->
      <div id="hudPomodoroPill" style="display: none; position: absolute; top: 28px; left: 50%; transform: translateX(-50%); background: rgba(2, 7, 11, 0.9); border: 1px solid #00e5ff; box-shadow: 0 0 16px rgba(0, 229, 255, 0.4); border-radius: 20px; padding: 4px 14px; align-items: center; gap: 10px; pointer-events: auto;">
        <svg width="24" height="24" viewBox="0 0 100 100" style="transform: rotate(-90deg);">
          <circle cx="50" cy="50" r="45" fill="none" stroke="rgba(0, 229, 255, 0.2)" stroke-width="8"/>
          <circle id="hudPomodoroArc" cx="50" cy="50" r="45" fill="none" stroke="#00e5ff" stroke-width="8" stroke-dasharray="283" stroke-dashoffset="0" style="transition: stroke-dashoffset 0.5s ease;"/>
        </svg>
        <div style="display: flex; flex-direction: column;">
          <span id="hudPomodoroTime" style="font-size: 14px; font-weight: bold; color: #ffffff; line-height: 1.1;">25:00</span>
          <span id="hudPomodoroLabel" style="font-size: 9px; color: #00e5ff; letter-spacing: 0.08em;">FOCUS SPRINT</span>
        </div>
        <button onclick="window.StarkHUD.togglePomodoroPause()" style="background: rgba(0, 229, 255, 0.2); border: 1px solid #00e5ff; border-radius: 6px; color: #00e5ff; font-size: 10px; padding: 2px 6px; cursor: pointer;">⏸</button>
        <button onclick="window.StarkHUD.resetPomodoro()" style="background: rgba(0, 229, 255, 0.1); border: 1px solid rgba(0, 229, 255, 0.4); border-radius: 6px; color: #00e5ff; font-size: 10px; padding: 2px 6px; cursor: pointer;">✕</button>
      </div>

      <!-- 36-Bar Audio Waveform Spectrum (Bottom) -->
      <div style="position: absolute; bottom: 84px; left: 24px; right: 24px; height: 20px; display: flex; align-items: center; justify-content: center; gap: 2.5px;">
        <div id="starkWaveform" style="display: flex; align-items: center; justify-content: center; gap: 2.5px; width: 100%; height: 100%;"></div>
      </div>
    `;

    document.body.appendChild(overlay);

    // Build 36 audio frequency bars
    const waveBox = document.getElementById('starkWaveform');
    if (waveBox) {
      for (let i = 0; i < 36; i++) {
        const bar = document.createElement('span');
        bar.id = 'sh-bar-' + i;
        bar.style.cssText = `
          flex: 1; max-width: 3.5px; height: 3px; background: #00e5ff; border-radius: 2px;
          opacity: 0.4; transition: height 0.08s ease, opacity 0.08s ease;
        `;
        waveBox.appendChild(bar);
      }
    }
  }

  // ── Real-Time Waveform Animation ──
  let speechSpeaking = false;
  let speechAmplitude = 0;
  let waveTimer = null;

  function setSpeechState(speaking, amp = 0) {
    speechSpeaking = speaking;
    speechAmplitude = amp;

    if (speaking) {
      if (!waveTimer) {
        let t = 0;
        waveTimer = setInterval(() => {
          t += 0.12;
          for (let i = 0; i < 36; i++) {
            const bar = document.getElementById('sh-bar-' + i);
            if (bar) {
              const curve = Math.sin(((i + 0.5) / 36) * Math.PI);
              const freq = Math.abs(Math.sin(t * 8 + i * 0.45));
              const h = Math.max(3, curve * (6 + freq * 18 * Math.max(0.4, speechAmplitude)));
              bar.style.height = h.toFixed(1) + 'px';
              bar.style.opacity = '0.9';
              bar.style.boxShadow = '0 0 6px rgba(0, 229, 255, 0.7)';
            }
          }
        }, 50);
      }
    } else {
      if (waveTimer) {
        clearInterval(waveTimer);
        waveTimer = null;
      }
      for (let i = 0; i < 36; i++) {
        const bar = document.getElementById('sh-bar-' + i);
        if (bar) {
          bar.style.height = '3px';
          bar.style.opacity = '0.35';
          bar.style.boxShadow = 'none';
        }
      }
    }
  }

  // ── Public Bridge API ──
  window.StarkHUD = {
    sfx: SFX,
    pomodoro: Pomodoro,
    startPomodoro: (sec, lbl) => Pomodoro.start(sec, lbl),
    togglePomodoroPause: () => Pomodoro.pause(),
    resetPomodoro: () => Pomodoro.reset(),
    setSpeechRipple: (spk, amp) => setSpeechState(spk, amp)
  };

  // Extend window.RoamieAndroid with Pomodoro methods
  if (!window.RoamieAndroid) window.RoamieAndroid = {};
  window.RoamieAndroid.startPomodoro = (sec, lbl) => Pomodoro.start(sec, lbl);
  window.RoamieAndroid.pausePomodoro = () => Pomodoro.pause();
  window.RoamieAndroid.resetPomodoro = () => Pomodoro.reset();

  // Intercept setSpeechRipple to update Stark waveform
  const origRipple = window.RoamieAndroid.setSpeechRipple;
  window.RoamieAndroid.setSpeechRipple = function(spk, amp) {
    if (origRipple) origRipple(spk, amp);
    setSpeechState(spk, amp);
  };

  // Auto-init once DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initHudUI);
  } else {
    initHudUI();
  }

  console.log('Stark HUD & Pomodoro Engine loaded.');
})();
