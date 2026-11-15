// Android Host Bridge for Roamies
// Bridges Tauri window calls to Android JavaScriptInterface

window.ROAMIE_LINUX = true; // Use bounding rects hit detection

window.__TAURI__ = {
  core: {
    invoke: async (cmd, args) => {
      try {
        if (window.AndroidBridge) {
          if (cmd === 'set_hit_rects') {
            window.AndroidBridge.setHitRects(JSON.stringify(args?.rects || []));
            return true;
          }
          if (cmd === 'set_hot') {
            window.AndroidBridge.setHot(!!args?.hot);
            return true;
          }
          if (cmd === 'sidecar_send') {
            window.AndroidBridge.onSidecarMessage(JSON.stringify(args?.msg || {}));
            return true;
          }
          if (cmd === 'set_typing') {
            window.AndroidBridge.setTyping(!!args?.on);
            return true;
          }
        }
      } catch (e) {
        console.error('Android bridge invoke error:', e);
      }
      return false;
    }
  }
};

(() => {
  const canvas = document.getElementById('stage') || document.body;
  let touchStart = 0;
  let touchPos = [0, 0];
  let moved = false;

  canvas.addEventListener('touchstart', (e) => {
    if (e.touches.length > 0) {
      const t = e.touches[0];
      touchPos = [t.clientX, t.clientY];
      touchStart = Date.now();
      moved = false;
      window.__roamiePtr?.(t.clientX, t.clientY, performance.now());
    }
  }, { passive: true });

  canvas.addEventListener('touchmove', (e) => {
    if (e.touches.length > 0) {
      const t = e.touches[0];
      const dx = Math.abs(t.clientX - touchPos[0]);
      const dy = Math.abs(t.clientY - touchPos[1]);
      if (dx > 5 || dy > 5) moved = true;
      touchPos = [t.clientX, t.clientY];
      window.__roamiePtr?.(t.clientX, t.clientY, performance.now());
    }
  }, { passive: true });

  canvas.addEventListener('touchend', (e) => {
    const elapsed = Date.now() - touchStart;
    if (elapsed > 450 && !moved) {
      // Long press: push to talk
      window.AndroidBridge?.startVoiceInput();
    } else if (!moved) {
      // Tap: click interaction
      window.__roamieClick?.(touchPos[0], touchPos[1]);
    }
    moved = false;
  }, { passive: true });

  // Direct helpers callable from Android WebView
  window.RoamieAndroid = {
    setLook(look) {
      window.__roamieHost?.({ kind: 'look', look });
    },
    setSize(size) {
      window.__roamieHost?.({ kind: 'size', size });
    },
    setSpeechRipple(speaking, amp) {
      window.__roamieHost?.({ kind: 'speech', speaking, amp });
    },
    showHandsBadge(badgeText) {
      window.__roamieHost?.({ kind: 'hands', active: !!badgeText, text: badgeText || '' });
    },
    updatePointer(x, y) {
      window.__roamiePtr?.(x, y, performance.now());
    },
    triggerClick(x, y) {
      window.__roamieClick?.(x, y);
    }
  };

  console.log('Roamies Android Bridge initialized');
})();
