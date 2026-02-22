/* ========================================
   MAGIC CLOCK PWA — APP.JS
   ======================================== */
(function () {
  'use strict';

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').catch(function () {});
  }

  /* ==================================================
     STATE
     ================================================== */
  var state = {
    style: 'ios',
    bg: 'gradient',
    returnMode: 'shake',
    delayMode: '3',
    returnSpeed: 30,
    shakeSens: 15,
    debug: false,
    motionGranted: false
  };

  var offsetMs = 0;
  var isReturning = false;
  var returnStartTime = 0;
  var returnStartOffset = 0;
  var pendingSign = null;       // '+' or '-' — waiting for digit
  var returnDelayTimer = null;
  var waitingForTapToReturn = false;

  /* ---- Persistence ---- */
  function loadState() {
    try {
      var s = JSON.parse(localStorage.getItem('mc_state'));
      if (s) { for (var k in s) { if (state.hasOwnProperty(k)) state[k] = s[k]; } }
    } catch (e) {}
  }
  function saveState() {
    try { localStorage.setItem('mc_state', JSON.stringify(state)); } catch (e) {}
  }

  /* ---- Debug ---- */
  var debugLogEl = null;
  var logLines = [];
  function dbg(msg) {
    if (!state.debug) return;
    logLines.push('[' + new Date().toLocaleTimeString() + '] ' + msg);
    if (logLines.length > 80) logLines.shift();
    if (debugLogEl) {
      debugLogEl.textContent = logLines.join('\n');
      debugLogEl.scrollTop = debugLogEl.scrollHeight;
    }
  }

  /* ==================================================
     TIME — Europe/Madrid via Intl (works offline)
     ================================================== */
  var fmtTime = null, fmtDate = null;
  try {
    fmtTime = new Intl.DateTimeFormat('es-ES', {
      timeZone: 'Europe/Madrid', hour: '2-digit', minute: '2-digit',
      second: '2-digit', hour12: false
    });
    fmtDate = new Intl.DateTimeFormat('es-ES', {
      timeZone: 'Europe/Madrid', weekday: 'long', day: 'numeric', month: 'long'
    });
  } catch (e) {}

  function getMadridNow() {
    var now = new Date();
    if (fmtTime) {
      var p = fmtTime.formatToParts(now), h = 0, m = 0, s = 0;
      for (var i = 0; i < p.length; i++) {
        if (p[i].type === 'hour') h = parseInt(p[i].value, 10);
        if (p[i].type === 'minute') m = parseInt(p[i].value, 10);
        if (p[i].type === 'second') s = parseInt(p[i].value, 10);
      }
      return { hours: h, minutes: m, seconds: s, ms: now.getMilliseconds() };
    }
    return { hours: now.getHours(), minutes: now.getMinutes(),
             seconds: now.getSeconds(), ms: now.getMilliseconds() };
  }

  function getMadridDate() {
    var now = new Date();
    if (fmtDate) {
      var s = fmtDate.format(now);
      return s.charAt(0).toUpperCase() + s.slice(1);
    }
    var days = ['domingo','lunes','martes',
      'mi\u00e9rcoles','jueves','viernes','s\u00e1bado'];
    var months = ['enero','febrero','marzo','abril','mayo','junio',
      'julio','agosto','septiembre','octubre','noviembre','diciembre'];
    var d = days[now.getDay()] + ', ' + now.getDate() + ' de ' + months[now.getMonth()];
    return d.charAt(0).toUpperCase() + d.slice(1);
  }

  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function toMs(t) {
    return ((t.hours * 3600) + (t.minutes * 60) + t.seconds) * 1000 + t.ms;
  }
  function fromMs(ms) {
    var day = 86400000;
    ms = ((ms % day) + day) % day;
    var ts = Math.floor(ms / 1000);
    return {
      hours: Math.floor(ts / 3600),
      minutes: Math.floor((ts % 3600) / 60),
      seconds: ts % 60
    };
  }

  /* ==================================================
     CLOCK RENDERING
     ================================================== */
  var elHours, elMinutes, elDate, elTimeDisplay;

  function updateClock() {
    var real = getMadridNow();
    var realMs = toMs(real);
    var eff = offsetMs;

    if (isReturning) {
      var elapsed = Date.now() - returnStartTime;
      var dur = state.returnSpeed * 1000;
      if (elapsed >= dur) {
        offsetMs = 0; eff = 0; isReturning = false;
        dbg('Retorno completado');
      } else {
        var t = elapsed / dur;
        t = 1 - Math.pow(1 - t, 3);
        eff = returnStartOffset * (1 - t);
        offsetMs = eff;
      }
    }

    var disp = fromMs(realMs + eff);
    elHours.textContent = pad2(disp.hours);
    elMinutes.textContent = pad2(disp.minutes);
    elDate.textContent = getMadridDate();
  }

  /* ==================================================
     BLACKOUT — screen goes black when +/- pressed,
     stays black until digit chosen, then reveals clock
     ================================================== */
  var blackoutEl = null;

  function blackoutOn() {
    if (blackoutEl) blackoutEl.classList.add('active');
  }

  function blackoutOff() {
    if (blackoutEl) blackoutEl.classList.remove('active');
  }

  /* ==================================================
     KEYPAD — show briefly then fade
     ================================================== */
  var keypadEl = null;
  var keypadTimer = null;
  var KEYPAD_VISIBLE_MS = 1200;

  function showKeypadBriefly() {
    if (!keypadEl) return;
    keypadEl.classList.remove('faded');
    clearTimeout(keypadTimer);
    keypadTimer = setTimeout(function () {
      keypadEl.classList.add('faded');
    }, KEYPAD_VISIBLE_MS);
  }

  function handleKeyPress(key) {
    showKeypadBriefly();

    /* ---- SIGN KEYS: +/- ---- */
    if (key === '+' || key === '-') {
      pendingSign = key;
      // BLACK OUT immediately — hides clock from audience
      blackoutOn();
      dbg('Signo: ' + key + ' (blackout ON)');
      return;
    }

    var digit = parseInt(key, 10);

    /* ---- ZERO: reset to real ---- */
    if (key === '0') {
      pendingSign = null;
      cancelReturn();
      offsetMs = 0;
      blackoutOff();
      dbg('Reset hora real');
      flashClock();
      return;
    }

    /* ---- DIGIT 1-9 after sign ---- */
    if (pendingSign && digit >= 1 && digit <= 9) {
      var sign = pendingSign === '+' ? 1 : -1;
      var ms = sign * digit * 60000;
      pendingSign = null;
      cancelReturn();
      offsetMs = ms;
      dbg('Offset: ' + (sign > 0 ? '+' : '') + digit + 'min');

      // Apply offset, THEN reveal clock with new time
      // Small delay so clock renders the new time before blackout lifts
      setTimeout(function () {
        blackoutOff();
        flashClock();
      }, 80);

      scheduleReturn();
      return;
    }

    // If digit pressed without sign, just ignore (no blackout change)
  }

  function flashClock() {
    elTimeDisplay.classList.add('flash');
    setTimeout(function () { elTimeDisplay.classList.remove('flash'); }, 150);
  }

  /* ==================================================
     RETURN LOGIC
     ================================================== */
  function cancelReturn() {
    isReturning = false;
    waitingForTapToReturn = false;
    if (returnDelayTimer) {
      clearTimeout(returnDelayTimer);
      returnDelayTimer = null;
    }
  }

  function scheduleReturn() {
    if (state.delayMode === 'tap') {
      waitingForTapToReturn = true;
      dbg('Esperando tap/shake');
      return;
    }
    var sec = parseInt(state.delayMode, 10) || 0;
    dbg('Retorno en ' + sec + 's');
    returnDelayTimer = setTimeout(startReturn, sec * 1000);
  }

  function startReturn() {
    if (Math.abs(offsetMs) < 100) { offsetMs = 0; return; }
    isReturning = true;
    returnStartTime = Date.now();
    returnStartOffset = offsetMs;
    waitingForTapToReturn = false;
    dbg('Retorno desde ' + offsetMs + 'ms');
  }

  function triggerReturn() {
    if (waitingForTapToReturn) { startReturn(); return; }
    if (Math.abs(offsetMs) > 100 && !isReturning) startReturn();
  }

  /* ==================================================
     SHAKE DETECTION
     ================================================== */
  var lastAcc = { x: 0, y: 0, z: 0 };
  var shakeDebounce = null;

  function handleMotion(e) {
    var a = e.accelerationIncludingGravity || e.acceleration;
    if (!a) return;
    var mag = Math.abs((a.x || 0) - lastAcc.x) +
              Math.abs((a.y || 0) - lastAcc.y) +
              Math.abs((a.z || 0) - lastAcc.z);
    lastAcc = { x: a.x || 0, y: a.y || 0, z: a.z || 0 };
    if (mag > state.shakeSens && !shakeDebounce) {
      dbg('Shake mag=' + mag.toFixed(1));
      shakeDebounce = setTimeout(function () { shakeDebounce = null; }, 800);
      if (state.returnMode === 'shake') triggerReturn();
    }
  }

  function requestMotionPermission() {
    if (typeof DeviceMotionEvent !== 'undefined' &&
        typeof DeviceMotionEvent.requestPermission === 'function') {
      DeviceMotionEvent.requestPermission().then(function (p) {
        if (p === 'granted') {
          state.motionGranted = true; saveState();
          window.addEventListener('devicemotion', handleMotion);
          dbg('Motion granted');
          document.getElementById('motion-permission').classList.add('hidden');
        }
      }).catch(function () {});
    }
  }

  function initMotion() {
    if (typeof DeviceMotionEvent === 'undefined') return;
    if (typeof DeviceMotionEvent.requestPermission === 'function') {
      document.getElementById('motion-permission').classList.remove('hidden');
      document.getElementById('btn-motion-perm').addEventListener('click',
        requestMotionPermission);
      if (state.motionGranted) {
        try { window.addEventListener('devicemotion', handleMotion); } catch (e) {}
      }
    } else {
      window.addEventListener('devicemotion', handleMotion);
    }
  }

  /* ==================================================
     TAP HANDLER (return mode = tap)
     ================================================== */
  function handleBodyTap(e) {
    if (e.target.closest('#secret-menu') || e.target.closest('#menu-panel')) return;
    if (state.returnMode === 'tap') triggerReturn();
  }

  /* ==================================================
     TWO-FINGER SWIPE DOWN → CLOSE PWA
     ================================================== */
  var twoFingerStartY = null;
  var twoFingerActive = false;

  function onTouchStartGlobal(e) {
    // Don't intercept in secret menu
    if (!document.getElementById('secret-menu').classList.contains('hidden')) return;
    if (e.touches.length === 2) {
      twoFingerActive = true;
      twoFingerStartY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
    } else {
      twoFingerActive = false;
    }
  }

  function onTouchMoveGlobal(e) {
    if (!twoFingerActive || e.touches.length !== 2) {
      twoFingerActive = false; return;
    }
    var currentY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
    var delta = currentY - twoFingerStartY;
    // Two fingers DOWN (delta > 80) → open secret menu
    if (delta > 80) {
      twoFingerActive = false;
      twoFingerStartY = null;
      openMenu();
    }
    // Two fingers UP (delta < -80) → close PWA
    if (delta < -80) {
      twoFingerActive = false;
      twoFingerStartY = null;
      closePWA();
    }
  }

  function onTouchEndGlobal() {
    twoFingerActive = false;
    twoFingerStartY = null;
  }

  function closePWA() {
    dbg('Cerrando PWA...');
    // Try multiple methods to close
    // 1) window.close() — works in some standalone PWA contexts
    // 2) Navigate away as fallback
    try {
      window.close();
    } catch (e) {}
    // If window.close didn't work (iOS), go to blank and let user close
    setTimeout(function () {
      // Still here? Show black screen so it looks "closed"
      document.body.innerHTML = '';
      document.body.style.background = '#000';
      // Try once more
      try { window.close(); } catch (e) {}
    }, 300);
  }

  /* ==================================================
     BACKGROUND & CUSTOM WALLPAPER
     ================================================== */
  function applyBackground() {
    var el = document.getElementById('bg-layer');
    el.classList.remove('wall');
    el.style.backgroundImage = '';
    el.style.background = '';

    if (state.bg === 'custom') {
      var dataUrl = getCustomBg();
      if (dataUrl) {
        el.classList.add('wall');
        el.style.backgroundImage = 'url(' + dataUrl + ')';
        return;
      }
      // Fallback if custom missing
      state.bg = 'gradient';
    }

    if (state.bg === 'gradient') {
      el.style.background =
        'linear-gradient(160deg, #0a0a2e 0%, #1a0a3a 40%, #0a1a2e 100%)';
    } else {
      el.classList.add('wall');
      el.style.backgroundImage = 'url(assets/' + state.bg + '.jpg)';
    }
  }

  function getCustomBg() {
    try { return localStorage.getItem('mc_custom_bg'); } catch (e) { return null; }
  }

  function setCustomBg(dataUrl) {
    try { localStorage.setItem('mc_custom_bg', dataUrl); } catch (e) {}
  }

  function removeCustomBg() {
    try { localStorage.removeItem('mc_custom_bg'); } catch (e) {}
  }

  function applyStyle() {
    document.body.className = 'style-' + state.style;
  }

  /* ==================================================
     SECRET MENU
     ================================================== */
  function openMenu() {
    document.getElementById('secret-menu').classList.remove('hidden');
    syncMenuUI();
  }

  function closeMenu() {
    document.getElementById('secret-menu').classList.add('hidden');
    saveState();
  }

  function syncMenuUI() {
    setActive('style-group', state.style);
    // For bg group, only highlight presets (not custom)
    if (state.bg === 'custom') {
      clearActive('bg-group');
    } else {
      setActive('bg-group', state.bg);
    }
    setActive('return-group', state.returnMode);
    setActive('delay-group', state.delayMode);
    document.getElementById('return-speed').value = state.returnSpeed;
    document.getElementById('return-speed-val').textContent = state.returnSpeed + 's';
    document.getElementById('shake-sens').value = state.shakeSens;
    document.getElementById('shake-sens-val').textContent = state.shakeSens;
    document.getElementById('debug-toggle').checked = state.debug;
    document.getElementById('debug-log').classList.toggle('hidden', !state.debug);

    // Custom BG preview
    var hasCustom = !!getCustomBg();
    document.getElementById('custom-bg-preview').classList.toggle('hidden', !hasCustom);
  }

  function setActive(id, val) {
    var btns = document.getElementById(id).querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      btns[i].classList.toggle('active',
        btns[i].getAttribute('data-val') === val);
    }
  }

  function clearActive(id) {
    var btns = document.getElementById(id).querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) btns[i].classList.remove('active');
  }

  function initMenu() {
    // Close button
    document.getElementById('btn-close-menu').addEventListener('click', closeMenu);

    // Long-press lock icon 2s → open menu
    var lockIcon = document.getElementById('lock-icon');
    var lpt = null;
    lockIcon.addEventListener('touchstart', function (e) {
      e.preventDefault(); e.stopPropagation();
      lpt = setTimeout(openMenu, 2000);
    }, { passive: false });
    lockIcon.addEventListener('touchend', function () { clearTimeout(lpt); });
    lockIcon.addEventListener('touchmove', function () { clearTimeout(lpt); });
    lockIcon.addEventListener('touchcancel', function () { clearTimeout(lpt); });
    lockIcon.addEventListener('mousedown', function (e) {
      e.stopPropagation(); lpt = setTimeout(openMenu, 2000);
    });
    lockIcon.addEventListener('mouseup', function () { clearTimeout(lpt); });
    lockIcon.addEventListener('mouseleave', function () { clearTimeout(lpt); });

    // Button groups
    bindGroup('style-group', function (v) { state.style = v; applyStyle(); });
    bindGroup('bg-group', function (v) { state.bg = v; applyBackground(); });
    bindGroup('return-group', function (v) { state.returnMode = v; });
    bindGroup('delay-group', function (v) { state.delayMode = v; });

    // Custom wallpaper upload
    var fileInput = document.getElementById('file-bg');
    document.getElementById('btn-custom-bg').addEventListener('click', function () {
      fileInput.click();
    });

    fileInput.addEventListener('change', function () {
      var file = this.files && this.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function (e) {
        // Resize to save localStorage space (max ~1200px wide)
        resizeImage(e.target.result, 1200, function (resized) {
          setCustomBg(resized);
          state.bg = 'custom';
          saveState();
          applyBackground();
          syncMenuUI();
          dbg('Fondo personalizado guardado');
        });
      };
      reader.readAsDataURL(file);
      // Reset input so same file can be re-selected
      this.value = '';
    });

    document.getElementById('btn-remove-custom-bg').addEventListener('click',
      function () {
        removeCustomBg();
        state.bg = 'gradient';
        saveState();
        applyBackground();
        syncMenuUI();
        dbg('Fondo personalizado eliminado');
      }
    );

    // Sliders
    document.getElementById('return-speed').addEventListener('input', function () {
      state.returnSpeed = parseInt(this.value, 10);
      document.getElementById('return-speed-val').textContent =
        state.returnSpeed + 's';
    });
    document.getElementById('shake-sens').addEventListener('input', function () {
      state.shakeSens = parseInt(this.value, 10);
      document.getElementById('shake-sens-val').textContent = state.shakeSens;
    });

    // Debug
    document.getElementById('debug-toggle').addEventListener('change', function () {
      state.debug = this.checked;
      document.getElementById('debug-log').classList.toggle('hidden', !state.debug);
    });

    // Calibrate
    document.getElementById('btn-calibrate').addEventListener('click', function () {
      offsetMs = 0; cancelReturn(); dbg('Calibrado');
    });
  }

  function bindGroup(id, cb) {
    var btns = document.getElementById(id).querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      btns[i].addEventListener('click', function () {
        var v = this.getAttribute('data-val');
        var all = this.parentElement.querySelectorAll('button');
        for (var j = 0; j < all.length; j++) all[j].classList.remove('active');
        this.classList.add('active');
        cb(v); saveState();
      });
    }
  }

  /* ---- Resize helper for custom wallpaper ---- */
  function resizeImage(dataUrl, maxW, callback) {
    var img = new Image();
    img.onload = function () {
      var w = img.width, h = img.height;
      if (w > maxW) {
        h = Math.round(h * (maxW / w));
        w = maxW;
      }
      var canvas = document.createElement('canvas');
      canvas.width = w; canvas.height = h;
      var ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, w, h);
      callback(canvas.toDataURL('image/jpeg', 0.82));
    };
    img.onerror = function () { callback(dataUrl); };
    img.src = dataUrl;
  }

  /* ==================================================
     KEYPAD INIT
     ================================================== */
  function initKeypad() {
    keypadEl = document.getElementById('keypad-overlay');
    var btns = document.querySelectorAll('.kp-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].addEventListener('click', function (e) {
        e.stopPropagation();
        handleKeyPress(this.getAttribute('data-key'));
      });
    }
    showKeypadBriefly();
  }

  /* ==================================================
     PREVENT DEFAULTS
     ================================================== */
  function preventDefaults() {
    document.body.addEventListener('touchmove', function (e) {
      if (!e.target.closest('#menu-panel')) e.preventDefault();
    }, { passive: false });

    var lastTE = 0;
    document.addEventListener('touchend', function (e) {
      var now = Date.now();
      if (now - lastTE <= 300) e.preventDefault();
      lastTE = now;
    }, false);

    // Wake lock
    if ('wakeLock' in navigator) {
      function reqWake() {
        navigator.wakeLock.request('screen').catch(function () {});
      }
      reqWake();
      document.addEventListener('visibilitychange', function () {
        if (document.visibilityState === 'visible') reqWake();
      });
    }
  }

  /* ==================================================
     RENDER LOOP
     ================================================== */
  function tick() { updateClock(); requestAnimationFrame(tick); }

  /* ==================================================
     INIT
     ================================================== */
  function init() {
    elHours = document.getElementById('hours');
    elMinutes = document.getElementById('minutes');
    elDate = document.getElementById('date-display');
    elTimeDisplay = document.getElementById('time-display');
    debugLogEl = document.getElementById('debug-log');
    blackoutEl = document.getElementById('blackout');

    loadState();
    applyStyle();
    applyBackground();
    initKeypad();
    initMenu();
    initMotion();
    preventDefaults();

    // Two-finger swipe → close PWA
    document.addEventListener('touchstart', onTouchStartGlobal, { passive: true });
    document.addEventListener('touchmove', onTouchMoveGlobal, { passive: true });
    document.addEventListener('touchend', onTouchEndGlobal, { passive: true });

    // Tap for return
    document.addEventListener('click', handleBodyTap);

    tick();
    dbg('Init OK');
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
