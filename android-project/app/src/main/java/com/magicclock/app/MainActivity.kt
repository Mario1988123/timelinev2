package com.magicclock.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : Activity(), SensorEventListener {

    enum class Phase { BOOT, DARK, LIVE, RETURNING }
    private var phase = Phase.BOOT

    private var offsetMs = 0.0
    private var pendingSign: Char? = null
    private var returnStartTime = 0L
    private var returnStartOffset = 0.0

    private var styleiOS = true
    private var triggerTap = true
    private var triggerShake = true
    private var triggerDelay = 0
    private var returnSpeed = 30
    private var shakeSens = 15f

    private var sensorManager: SensorManager? = null
    private var lastAx = 0f; private var lastAy = 0f; private var lastAz = 0f
    private var shakeDebounce = 0L

    private var bootTime = 0L
    private val KEYPAD_SHOW_MS = 2000L
    private val KEYPAD_FADE_MS = 600L

    private var twoFingerStartY = 0f
    private var twoFingerActive = false

    private var customWallpaper: Bitmap? = null
    private val PICK_IMAGE = 42

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var clockView: ClockView

    // ══════════════ LIFECYCLE ══════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        prefs = getSharedPreferences("mc", Context.MODE_PRIVATE)
        loadSettings(); loadCustomWallpaper()
        phase = Phase.BOOT; bootTime = System.currentTimeMillis()
        clockView = ClockView(this); setContentView(clockView); hideSystemUI()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        @Suppress("DEPRECATION")
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "mc:wake")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)
        handler.postDelayed({ if (phase == Phase.BOOT) phase = Phase.DARK }, KEYPAD_SHOW_MS + KEYPAD_FADE_MS + 200)
        startTick()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideSystemUI() }
    override fun onResume() { super.onResume(); hideSystemUI() }
    override fun onDestroy() {
        super.onDestroy(); sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ══════════════ TIME ══════════════

    private fun madridCal(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid"))
    private fun madridTimeMs(): Long {
        val c = madridCal()
        return c.get(Calendar.HOUR_OF_DAY)*3600000L + c.get(Calendar.MINUTE)*60000L + c.get(Calendar.SECOND)*1000L + c.get(Calendar.MILLISECOND)
    }
    private fun msToHM(ms: Long): Pair<Int,Int> {
        val d = 86400000L; val w = ((ms%d)+d)%d; val s = (w/1000).toInt()
        return Pair(s/3600, (s%3600)/60)
    }
    private fun madridDateStr(): String {
        val c = madridCal()
        val days = arrayOf("domingo","lunes","martes","miércoles","jueves","viernes","sábado")
        val months = arrayOf("enero","febrero","marzo","abril","mayo","junio","julio","agosto","septiembre","octubre","noviembre","diciembre")
        return "${days[c.get(Calendar.DAY_OF_WEEK)-1].replaceFirstChar{it.uppercase()}}, ${c.get(Calendar.DAY_OF_MONTH)} de ${months[c.get(Calendar.MONTH)]}"
    }

    // ══════════════ TICK ══════════════

    private fun startTick() {
        handler.post(object : Runnable {
            override fun run() {
                if (phase == Phase.RETURNING) updateReturn()
                clockView.invalidate()
                handler.postDelayed(this, 16)
            }
        })
    }
    private fun updateReturn() {
        val elapsed = System.currentTimeMillis() - returnStartTime
        val dur = returnSpeed * 1000L
        if (elapsed >= dur) { offsetMs = 0.0; phase = Phase.LIVE }
        else { val t = elapsed.toDouble()/dur; offsetMs = returnStartOffset * (1.0 - (1.0-(1.0-t).pow(3.0))) }
    }

    // ══════════════ KEYS ══════════════

    private fun handleKey(key: String) {
        if (phase != Phase.BOOT && phase != Phase.DARK) return
        if (key == "+" || key == "-") { pendingSign = key[0]; return }
        val digit = key.toIntOrNull() ?: return
        if (digit == 0) { pendingSign = null; offsetMs = 0.0; goLive(); return }
        if (pendingSign != null && digit in 1..9) {
            offsetMs = (if (pendingSign == '+') 1 else -1) * digit * 60000.0
            pendingSign = null; goLive()
        }
    }
    private fun goLive() { phase = Phase.LIVE }
    private fun triggerReturn() {
        if (phase != Phase.LIVE) return
        if (triggerDelay > 0) handler.postDelayed({ startReturn() }, triggerDelay * 1000L)
        else startReturn()
    }
    private fun startReturn() {
        if (abs(offsetMs) < 500) { offsetMs = 0.0; return }
        phase = Phase.RETURNING; returnStartTime = System.currentTimeMillis(); returnStartOffset = offsetMs
    }

    // ══════════════ SHAKE ══════════════

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = abs(e.values[0]-lastAx) + abs(e.values[1]-lastAy) + abs(e.values[2]-lastAz)
        lastAx = e.values[0]; lastAy = e.values[1]; lastAz = e.values[2]
        val now = System.currentTimeMillis()
        if (mag > shakeSens && now - shakeDebounce > 800) {
            shakeDebounce = now; if (triggerShake && phase == Phase.LIVE) triggerReturn()
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ══════════════ WALLPAPER ══════════════

    private fun pickWallpaper() {
        try { startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }, PICK_IMAGE) } catch (_: Exception) {}
    }
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK_IMAGE && res == RESULT_OK && data?.data != null) loadBitmapFromUri(data.data!!)
    }
    private fun loadBitmapFromUri(uri: Uri) {
        try {
            val inp = contentResolver.openInputStream(uri) ?: return
            val orig = BitmapFactory.decodeStream(inp); inp.close()
            val s = if (orig.width > 1080) 1080f / orig.width else 1f
            customWallpaper = Bitmap.createScaledBitmap(orig, (orig.width*s).toInt(), (orig.height*s).toInt(), true)
            if (orig != customWallpaper) orig.recycle()
            saveCustomWallpaper()
        } catch (_: Exception) {}
    }
    private fun saveCustomWallpaper() {
        val bmp = customWallpaper ?: run { prefs.edit().remove("wallpaper").apply(); return }
        try { val b = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 75, b)
            prefs.edit().putString("wallpaper", Base64.encodeToString(b.toByteArray(), Base64.DEFAULT)).apply()
        } catch (_: Exception) {}
    }
    private fun loadCustomWallpaper() {
        val b64 = prefs.getString("wallpaper", null) ?: return
        try { val bytes = Base64.decode(b64, Base64.DEFAULT); customWallpaper = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (_: Exception) {}
    }
    private fun removeCustomWallpaper() {
        customWallpaper?.recycle(); customWallpaper = null; prefs.edit().remove("wallpaper").apply()
    }

    // ══════════════ SETTINGS ══════════════

    private fun loadSettings() {
        styleiOS = prefs.getBoolean("ios", true)
        triggerTap = prefs.getBoolean("triggerTap", true)
        triggerShake = prefs.getBoolean("triggerShake", true)
        triggerDelay = prefs.getInt("triggerDelay", 0)
        returnSpeed = prefs.getInt("returnSpeed", 30)
        shakeSens = prefs.getFloat("shakeSens", 15f)
    }
    private fun saveSettings() {
        prefs.edit().putBoolean("ios", styleiOS).putBoolean("triggerTap", triggerTap)
            .putBoolean("triggerShake", triggerShake).putInt("triggerDelay", triggerDelay)
            .putInt("returnSpeed", returnSpeed).putFloat("shakeSens", shakeSens).apply()
    }

    // ══════════════════════════════════════════════════
    //  SECRET MENU — no flicker, updates buttons in-place
    // ══════════════════════════════════════════════════

    private fun openSecretMenu() {
        val dlg = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.window?.setDimAmount(0.90f)

        val scroll = ScrollView(this).apply { setPadding(dp(24), dp(48), dp(24), dp(36)); isVerticalScrollBarEnabled = false }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // Title
        root.addView(TextView(this@MainActivity).apply {
            text = "⚙  Configuración"; textSize = 20f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(6))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        // by elitemagic
        root.addView(TextView(this@MainActivity).apply {
            text = "by elitemagic"; textSize = 11f; setTextColor(Color.argb(80, 255, 255, 255))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20))
            typeface = Typeface.create("sans-serif-light", Typeface.ITALIC)
        })

        // ── Style (single select) ──
        root.addView(mkSection("ESTILO"))
        val styleRow = mkSingleSelect(listOf("iOS", "Android"), if (styleiOS) 0 else 1) { i ->
            styleiOS = i == 0; saveSettings()
        }
        root.addView(styleRow)

        // ── Trigger (multi select) ──
        root.addView(mkSection("ACTIVAR RETORNO CON"))
        val triggerRow = mkMultiSelect(listOf("Tocar" to triggerTap, "Agitar" to triggerShake)) { states ->
            triggerTap = states[0]; triggerShake = states[1]
            if (!triggerTap && !triggerShake) { triggerTap = true; updateMultiSelect(triggerRow, listOf(true, states[1])) }
            saveSettings()
        }
        root.addView(triggerRow)

        // ── Delay (single select) ──
        root.addView(mkSection("DELAY ANTES DEL RETORNO"))
        val delayOpts = listOf("0s", "3s", "5s", "10s")
        val delayVals = listOf(0, 3, 5, 10)
        val delayIdx = delayVals.indexOf(triggerDelay).coerceAtLeast(0)
        root.addView(mkSingleSelect(delayOpts, delayIdx) { i ->
            triggerDelay = delayVals[i]; saveSettings()
        })

        // ── Return speed ──
        val speedLabel = mkSection("VELOCIDAD RETORNO: ${returnSpeed}s")
        root.addView(speedLabel)
        root.addView(SeekBar(this).apply {
            max = 23; progress = (returnSpeed - 5) / 5
            setPadding(dp(4), dp(8), dp(4), dp(14))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    returnSpeed = 5 + p * 5; speedLabel.text = "VELOCIDAD RETORNO: ${returnSpeed}s"; saveSettings()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // ── Shake sensitivity ──
        val shakeLabel = mkSection("SENSIBILIDAD SHAKE: ${shakeSens.toInt()}")
        root.addView(shakeLabel)
        root.addView(SeekBar(this).apply {
            max = 35; progress = shakeSens.toInt() - 5
            setPadding(dp(4), dp(8), dp(4), dp(14))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                    shakeSens = (5 + p).toFloat(); shakeLabel.text = "SENSIBILIDAD SHAKE: ${shakeSens.toInt()}"; saveSettings()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // ── Wallpaper ──
        root.addView(mkSection("FONDO DE PANTALLA"))
        val wpStatus = TextView(this).apply {
            textSize = 12f; setPadding(0, dp(2), 0, dp(6))
            if (customWallpaper != null) { text = "✓ Fondo personalizado activo"; setTextColor(Color.argb(180, 130, 255, 130)) }
            else { text = "Degradado por defecto"; setTextColor(Color.argb(100, 255, 255, 255)) }
        }
        root.addView(wpStatus)
        root.addView(mkBtn(if (customWallpaper != null) "Cambiar foto" else "Subir foto de galería") { pickWallpaper(); dlg.dismiss() })
        if (customWallpaper != null) {
            root.addView(mkBtn("Quitar fondo personalizado") {
                removeCustomWallpaper(); wpStatus.text = "Degradado por defecto"; wpStatus.setTextColor(Color.argb(100, 255, 255, 255))
            })
        }

        // ── Actions ──
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(20)) })
        root.addView(mkBtn("Reiniciar (pantalla negra)") {
            offsetMs = 0.0; pendingSign = null; phase = Phase.BOOT; bootTime = System.currentTimeMillis()
            handler.postDelayed({ if (phase == Phase.BOOT) phase = Phase.DARK }, KEYPAD_SHOW_MS + KEYPAD_FADE_MS + 200)
            dlg.dismiss()
        })
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(14)) })
        root.addView(mkBtn("Cerrar") { dlg.dismiss() })

        scroll.addView(root); dlg.setContentView(scroll); dlg.show()
    }

    // ── Single select: only one active at a time, updates visually in-place ──
    private fun mkSingleSelect(labels: List<String>, activeIdx: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        val buttons = mutableListOf<TextView>()
        labels.forEachIndexed { i, label ->
            val btn = TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(4)
                }
            }
            buttons.add(btn)
            row.addView(btn)
        }
        fun applyState(sel: Int) {
            buttons.forEachIndexed { i, b ->
                if (i == sel) { b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.argb(50, 255, 255, 255)) }
                else { b.setTextColor(Color.argb(120, 255, 255, 255)); b.setBackgroundColor(Color.argb(10, 255, 255, 255)) }
            }
        }
        applyState(activeIdx)
        buttons.forEachIndexed { i, b -> b.setOnClickListener { applyState(i); onSelect(i) } }
        return row
    }

    // ── Multi select: toggle each independently, updates visually in-place ──
    private fun mkMultiSelect(items: List<Pair<String, Boolean>>, onChanged: (List<Boolean>) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2)) }
        val states = items.map { it.second }.toMutableList()
        val buttons = mutableListOf<TextView>()
        items.forEachIndexed { i, (label, _) ->
            val btn = TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(4)
                }
            }
            buttons.add(btn); row.addView(btn)
        }
        fun applyStates() {
            buttons.forEachIndexed { i, b ->
                if (states[i]) { b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.argb(50, 255, 255, 255)) }
                else { b.setTextColor(Color.argb(120, 255, 255, 255)); b.setBackgroundColor(Color.argb(10, 255, 255, 255)) }
            }
        }
        applyStates()
        buttons.forEachIndexed { i, b -> b.setOnClickListener { states[i] = !states[i]; applyStates(); onChanged(states) } }
        return row
    }

    private fun updateMultiSelect(row: LinearLayout, newStates: List<Boolean>) {
        for (i in 0 until row.childCount) {
            val b = row.getChildAt(i) as? TextView ?: continue
            if (i < newStates.size) {
                if (newStates[i]) { b.setTextColor(Color.WHITE); b.setBackgroundColor(Color.argb(50, 255, 255, 255)) }
                else { b.setTextColor(Color.argb(120, 255, 255, 255)); b.setBackgroundColor(Color.argb(10, 255, 255, 255)) }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun mkSection(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 11f; setTextColor(Color.argb(100, 255, 255, 255))
            letterSpacing = 0.08f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(6))
        }
    }

    private fun mkBtn(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(18, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            setOnClickListener { action() }
        }
    }

    // ══════════════════════════════════════════
    //  CLOCK VIEW
    // ══════════════════════════════════════════

    inner class ClockView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lockP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val keys = arrayOf(arrayOf("1","2","3"), arrayOf("4","5","6"), arrayOf("7","8","9"), arrayOf("+","0","-"))

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            when (phase) {
                Phase.BOOT -> { canvas.drawColor(Color.BLACK); drawKeypad(canvas, w, h, calcBootAlpha()) }
                Phase.DARK -> canvas.drawColor(Color.BLACK)
                Phase.LIVE, Phase.RETURNING -> drawLive(canvas, w, h)
            }
        }

        private fun calcBootAlpha(): Float {
            val e = System.currentTimeMillis() - bootTime
            return when {
                e < KEYPAD_SHOW_MS -> 0.14f
                e < KEYPAD_SHOW_MS + KEYPAD_FADE_MS -> 0.14f * (1f - (e - KEYPAD_SHOW_MS).toFloat() / KEYPAD_FADE_MS)
                else -> 0f
            }
        }

        private fun drawLive(canvas: Canvas, w: Float, h: Float) {
            val wp = customWallpaper
            if (wp != null && !wp.isRecycled) {
                canvas.drawBitmap(wp, Rect(0, 0, wp.width, wp.height), Rect(0, 0, w.toInt(), h.toInt()), null)
                p.color = Color.argb(50, 0, 0, 0); p.style = Paint.Style.FILL; p.shader = null
                canvas.drawRect(0f, 0f, w, h, p)
            } else {
                p.shader = LinearGradient(0f, 0f, w*0.3f, h,
                    intArrayOf(Color.rgb(10,10,46), Color.rgb(26,10,58), Color.rgb(10,26,46)),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                p.style = Paint.Style.FILL; canvas.drawRect(0f, 0f, w, h, p); p.shader = null
            }

            // Lock icon — iOS style
            drawLockIcon(canvas, w / 2f, h * 0.065f, w * 0.028f)

            // Time
            val (hr, mn) = msToHM(madridTimeMs() + offsetMs.toLong())
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.shader = null; p.style = Paint.Style.FILL
            p.setShadowLayer(24f, 0f, 3f, Color.argb(70, 0, 0, 0))
            if (styleiOS) { p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textSize = w * 0.22f }
            else { p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); p.textSize = w * 0.20f }
            val timeY = if (styleiOS) h * 0.23f else h * 0.27f
            canvas.drawText(String.format(Locale.US, "%02d:%02d", hr, mn), w / 2f, timeY, p)
            p.clearShadowLayer()

            // Date
            p.color = Color.argb(210, 255, 255, 255)
            p.typeface = if (styleiOS) Typeface.create("sans-serif", Typeface.NORMAL) else Typeface.create("sans-serif-light", Typeface.NORMAL)
            p.textSize = if (styleiOS) w * 0.048f else w * 0.042f
            canvas.drawText(madridDateStr(), w / 2f, timeY + w * 0.075f, p)

            // Bottom pill
            p.color = Color.argb(65, 255, 255, 255)
            val bw = w * 0.35f; val bh = h * 0.005f
            canvas.drawRoundRect(w/2f - bw/2f, h - h*0.025f, w/2f + bw/2f, h - h*0.025f + bh, bh, bh, p)
        }

        // iOS-style lock icon: filled body + shackle arc
        private fun drawLockIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            lockP.color = Color.WHITE; lockP.alpha = 180; lockP.shader = null; lockP.clearShadowLayer()

            // Shackle (thick arc on top)
            lockP.style = Paint.Style.STROKE; lockP.strokeWidth = r * 0.32f; lockP.strokeCap = Paint.Cap.ROUND
            val shackleW = r * 0.65f; val shackleH = r * 1.0f
            canvas.drawArc(RectF(cx - shackleW, cy - shackleH * 1.6f, cx + shackleW, cy - shackleH * 0.1f), 180f, 180f, false, lockP)

            // Body (filled rounded rect)
            lockP.style = Paint.Style.FILL
            val bodyW = r * 1.0f; val bodyH = r * 1.1f
            val bodyTop = cy - r * 0.15f
            canvas.drawRoundRect(RectF(cx - bodyW, bodyTop, cx + bodyW, bodyTop + bodyH), r * 0.18f, r * 0.18f, lockP)

            // Keyhole (dark circle + line)
            lockP.color = Color.argb(180, 30, 30, 60)
            val holeY = bodyTop + bodyH * 0.38f
            canvas.drawCircle(cx, holeY, r * 0.16f, lockP)
            lockP.strokeWidth = r * 0.12f; lockP.style = Paint.Style.STROKE; lockP.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx, holeY + r * 0.12f, cx, holeY + r * 0.35f, lockP)
        }

        private fun drawKeypad(canvas: Canvas, w: Float, h: Float, alpha: Float) {
            if (alpha < 0.001f) return
            p.color = Color.WHITE; p.alpha = (alpha * 255).toInt()
            p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            p.style = Paint.Style.FILL; p.clearShadowLayer(); p.shader = null
            val padT = h * 0.08f; val padB = h * 0.05f; val rowH = (h - padT - padB) / 4f
            for (r in 0..3) for (c in 0..2) {
                val cx = w * (c + 0.5f) / 3f; val cy = padT + rowH * r + rowH / 2f
                p.textSize = if (keys[r][c] == "+" || keys[r][c] == "-") w * 0.10f else w * 0.08f
                canvas.drawText(keys[r][c], cx, cy + p.textSize * 0.35f, p)
            }
        }

        private fun getKeyAt(x: Float, y: Float): String? {
            val w = width.toFloat(); val h = height.toFloat()
            val padT = h * 0.08f; val padB = h * 0.05f; val rowH = (h - padT - padB) / 4f
            val row = ((y - padT) / rowH).toInt(); val col = (x / (w / 3f)).toInt()
            return if (row in 0..3 && col in 0..2) keys[row][col] else null
        }

        private var downTime = 0L; private var downX = 0f; private var downY = 0f

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            if (event.pointerCount == 2) {
                when (action) {
                    MotionEvent.ACTION_POINTER_DOWN -> { twoFingerActive = true; twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f }
                    MotionEvent.ACTION_MOVE -> if (twoFingerActive) {
                        val d = (event.getY(0) + event.getY(1)) / 2f - twoFingerStartY
                        if (d > 100) { twoFingerActive = false; openSecretMenu() }
                        else if (d < -100) { twoFingerActive = false; finishAndRemoveTask() }
                    }
                }; return true
            }
            twoFingerActive = false
            when (action) {
                MotionEvent.ACTION_DOWN -> { downTime = System.currentTimeMillis(); downX = event.x; downY = event.y }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - downTime
                    if (dt >= 2000L && abs(event.y - downY) < 60 && downY < height * 0.1f) { openSecretMenu(); return true }
                    if (dt < 400 && abs(event.x - downX) < 40 && abs(event.y - downY) < 40) {
                        when (phase) {
                            Phase.BOOT, Phase.DARK -> getKeyAt(event.x, event.y)?.let { handleKey(it) }
                            Phase.LIVE -> if (triggerTap) triggerReturn()
                            Phase.RETURNING -> {}
                        }
                    }
                }
            }; return true
        }
    }
}
