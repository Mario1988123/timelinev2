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

    // ── Offset ──
    private var offsetMs = 0.0
    private var pendingSign: Char? = null
    private var returnStartTime = 0L
    private var returnStartOffset = 0.0

    // ── Settings ──
    private var styleiOS = true
    private var triggerTap = true
    private var triggerShake = true
    private var triggerDelay = 0
    private var returnSpeed = 30
    private var shakeSens = 15f

    // ── Shake ──
    private var sensorManager: SensorManager? = null
    private var lastAx = 0f; private var lastAy = 0f; private var lastAz = 0f
    private var shakeDebounce = 0L

    // ── Keypad fade ──
    private var bootTime = 0L
    private val KEYPAD_SHOW_MS = 2000L
    private val KEYPAD_FADE_MS = 600L

    // ── Two-finger ──
    private var twoFingerStartY = 0f
    private var twoFingerActive = false

    // ── Custom wallpaper ──
    private var customWallpaper: Bitmap? = null
    private val PICK_IMAGE = 42

    // ── Misc ──
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var clockView: ClockView
    private var currentMenuDialog: Dialog? = null

    // ══════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        prefs = getSharedPreferences("mc", Context.MODE_PRIVATE)
        loadSettings()
        loadCustomWallpaper()

        phase = Phase.BOOT
        bootTime = System.currentTimeMillis()

        clockView = ClockView(this)
        setContentView(clockView)
        hideSystemUI()

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
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ══════════════════════════════════════════
    //  TIME — Europe/Madrid
    // ══════════════════════════════════════════

    private fun madridCal(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid"))

    private fun madridTimeMs(): Long {
        val c = madridCal()
        return c.get(Calendar.HOUR_OF_DAY) * 3600000L + c.get(Calendar.MINUTE) * 60000L +
               c.get(Calendar.SECOND) * 1000L + c.get(Calendar.MILLISECOND)
    }

    private fun msToHM(ms: Long): Pair<Int, Int> {
        val d = 86400000L; val w = ((ms % d) + d) % d; val s = (w / 1000).toInt()
        return Pair(s / 3600, (s % 3600) / 60)
    }

    private fun madridDateStr(): String {
        val c = madridCal()
        val days = arrayOf("domingo","lunes","martes","miércoles","jueves","viernes","sábado")
        val months = arrayOf("enero","febrero","marzo","abril","mayo","junio","julio","agosto","septiembre","octubre","noviembre","diciembre")
        return "${days[c.get(Calendar.DAY_OF_WEEK)-1].replaceFirstChar{it.uppercase()}}, ${c.get(Calendar.DAY_OF_MONTH)} de ${months[c.get(Calendar.MONTH)]}"
    }

    // ══════════════════════════════════════════
    //  TICK
    // ══════════════════════════════════════════

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
        else { val t = elapsed.toDouble() / dur; offsetMs = returnStartOffset * (1.0 - (1.0 - (1.0 - t).pow(3.0))) }
    }

    // ══════════════════════════════════════════
    //  KEY HANDLING
    // ══════════════════════════════════════════

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

    // ══════════════════════════════════════════
    //  SHAKE
    // ══════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = abs(e.values[0] - lastAx) + abs(e.values[1] - lastAy) + abs(e.values[2] - lastAz)
        lastAx = e.values[0]; lastAy = e.values[1]; lastAz = e.values[2]
        val now = System.currentTimeMillis()
        if (mag > shakeSens && now - shakeDebounce > 800) {
            shakeDebounce = now
            if (triggerShake && phase == Phase.LIVE) triggerReturn()
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ══════════════════════════════════════════
    //  CUSTOM WALLPAPER
    // ══════════════════════════════════════════

    private fun pickWallpaper() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        try { startActivityForResult(intent, PICK_IMAGE) } catch (_: Exception) {}
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            loadBitmapFromUri(data.data!!)
        }
    }

    private fun loadBitmapFromUri(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()
            // Resize to save memory
            val maxW = 1080
            val scale = if (original.width > maxW) maxW.toFloat() / original.width else 1f
            val w = (original.width * scale).toInt()
            val h = (original.height * scale).toInt()
            customWallpaper = Bitmap.createScaledBitmap(original, w, h, true)
            if (original != customWallpaper) original.recycle()
            saveCustomWallpaper()
            // Reopen menu to show updated state
            currentMenuDialog?.dismiss()
            openSecretMenu()
        } catch (_: Exception) {}
    }

    private fun saveCustomWallpaper() {
        val bmp = customWallpaper ?: run { prefs.edit().remove("wallpaper").apply(); return }
        try {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            prefs.edit().putString("wallpaper", Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)).apply()
        } catch (_: Exception) {}
    }

    private fun loadCustomWallpaper() {
        val b64 = prefs.getString("wallpaper", null) ?: return
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            customWallpaper = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {}
    }

    private fun removeCustomWallpaper() {
        customWallpaper?.recycle()
        customWallpaper = null
        prefs.edit().remove("wallpaper").apply()
    }

    // ══════════════════════════════════════════
    //  SETTINGS
    // ══════════════════════════════════════════

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

    // ══════════════════════════════════════════
    //  SECRET MENU — rebuilt each time for live updates
    // ══════════════════════════════════════════

    private fun openSecretMenu() {
        currentMenuDialog?.dismiss()
        val dlg = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        currentMenuDialog = dlg
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dlg.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.window?.setDimAmount(0.88f)

        val scroll = ScrollView(this).apply { setPadding(dp(24), dp(50), dp(24), dp(40)) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // Title
        root.addView(mkText("⚙  Configuración", 20f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(20))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        // ── Style ──
        root.addView(mkSection("ESTILO"))
        root.addView(mkToggle(listOf("iOS" to styleiOS, "Android" to !styleiOS)) { i ->
            styleiOS = i == 0; saveSettings(); rebuildMenu()
        })

        // ── Trigger mode ──
        root.addView(mkSection("ACTIVAR RETORNO CON"))
        root.addView(mkToggle(listOf("Tocar" to triggerTap, "Agitar" to triggerShake)) { i ->
            if (i == 0) triggerTap = !triggerTap
            if (i == 1) triggerShake = !triggerShake
            if (!triggerTap && !triggerShake) triggerTap = true
            saveSettings(); rebuildMenu()
        })

        // ── Delay ──
        root.addView(mkSection("DELAY ANTES DEL RETORNO"))
        root.addView(mkToggle(listOf("0s" to (triggerDelay==0), "3s" to (triggerDelay==3), "5s" to (triggerDelay==5), "10s" to (triggerDelay==10))) { i ->
            triggerDelay = listOf(0,3,5,10)[i]; saveSettings(); rebuildMenu()
        })

        // ── Return speed ──
        root.addView(mkSection("VELOCIDAD RETORNO: ${returnSpeed}s"))
        root.addView(SeekBar(this).apply {
            max = 23; progress = (returnSpeed - 5) / 5
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { returnSpeed = 5 + p * 5; saveSettings() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { rebuildMenu() }
            })
        })

        // ── Shake sens ──
        root.addView(mkSection("SENSIBILIDAD SHAKE: ${shakeSens.toInt()}"))
        root.addView(SeekBar(this).apply {
            max = 35; progress = shakeSens.toInt() - 5
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { shakeSens = (5 + p).toFloat(); saveSettings() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { rebuildMenu() }
            })
        })

        // ── Wallpaper ──
        root.addView(mkSection("FONDO DE PANTALLA"))
        if (customWallpaper != null) {
            root.addView(mkText("✓ Fondo personalizado activo", 13f, Color.argb(180, 150, 255, 150)).apply {
                setPadding(0, dp(4), 0, dp(8))
            })
            root.addView(mkBtn("Cambiar foto") { pickWallpaper() })
            root.addView(mkBtn("Quitar fondo personalizado") { removeCustomWallpaper(); rebuildMenu() })
        } else {
            root.addView(mkBtn("Subir foto de galería") { pickWallpaper() })
        }

        // ── Actions ──
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(20)) })
        root.addView(mkBtn("Calibrar hora (reset offset)") { offsetMs = 0.0; phase = Phase.DARK })
        root.addView(mkBtn("Reiniciar (pantalla negra)") {
            offsetMs = 0.0; pendingSign = null; phase = Phase.BOOT; bootTime = System.currentTimeMillis()
            handler.postDelayed({ if (phase == Phase.BOOT) phase = Phase.DARK }, KEYPAD_SHOW_MS + KEYPAD_FADE_MS + 200)
            dlg.dismiss()
        })
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) })
        root.addView(mkBtn("Cerrar") { dlg.dismiss() })

        scroll.addView(root)
        dlg.setContentView(scroll)
        dlg.show()
    }

    private fun rebuildMenu() { openSecretMenu() }

    // ── Menu helpers ──
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun mkText(text: String, size: Float, color: Int): TextView {
        return TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }
    }

    private fun mkSection(text: String): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 11f; setTextColor(Color.argb(110, 255, 255, 255))
            letterSpacing = 0.1f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(16), 0, dp(6))
        }
    }

    private fun mkToggle(items: List<Pair<String, Boolean>>, onClick: (Int) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(2))
            items.forEachIndexed { i, (label, on) ->
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(4)
                    }
                    setTextColor(if (on) Color.WHITE else Color.argb(130, 255, 255, 255))
                    setBackgroundColor(if (on) Color.argb(45, 255, 255, 255) else Color.argb(10, 255, 255, 255))
                    setOnClickListener { onClick(i) }
                })
            }
        }
    }

    private fun mkBtn(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text; textSize = 14f; setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER; setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundColor(Color.argb(20, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            setOnClickListener { action() }
        }
    }

    // ══════════════════════════════════════════
    //  CLOCK VIEW — all native Canvas drawing
    // ══════════════════════════════════════════

    inner class ClockView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            val elapsed = System.currentTimeMillis() - bootTime
            return when {
                elapsed < KEYPAD_SHOW_MS -> 0.14f
                elapsed < KEYPAD_SHOW_MS + KEYPAD_FADE_MS -> 0.14f * (1f - (elapsed - KEYPAD_SHOW_MS).toFloat() / KEYPAD_FADE_MS)
                else -> 0f
            }
        }

        private fun drawLive(canvas: Canvas, w: Float, h: Float) {
            // Background
            val wp = customWallpaper
            if (wp != null && !wp.isRecycled) {
                val src = Rect(0, 0, wp.width, wp.height)
                val dst = Rect(0, 0, w.toInt(), h.toInt())
                canvas.drawBitmap(wp, src, dst, null)
                // Slight dark overlay for readability
                p.color = Color.argb(60, 0, 0, 0); p.style = Paint.Style.FILL; p.shader = null
                canvas.drawRect(0f, 0f, w, h, p)
            } else {
                val grad = LinearGradient(0f, 0f, w * 0.3f, h,
                    intArrayOf(Color.rgb(10,10,46), Color.rgb(26,10,58), Color.rgb(10,26,46)),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                p.shader = grad; p.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, w, h, p); p.shader = null
            }

            // Lock icon — white outline only
            drawLockIcon(canvas, w / 2f, h * 0.06f, w * 0.022f)

            // Time
            val (hr, mn) = msToHM(madridTimeMs() + offsetMs.toLong())
            val timeStr = String.format(Locale.US, "%02d:%02d", hr, mn)
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.shader = null; p.style = Paint.Style.FILL
            p.setShadowLayer(24f, 0f, 3f, Color.argb(70, 0, 0, 0))
            if (styleiOS) { p.typeface = Typeface.create("sans-serif", Typeface.BOLD); p.textSize = w * 0.22f }
            else { p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); p.textSize = w * 0.20f }
            val timeY = if (styleiOS) h * 0.22f else h * 0.26f
            canvas.drawText(timeStr, w / 2f, timeY, p)
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

        private fun drawLockIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            lockPaint.color = Color.WHITE; lockPaint.alpha = 120
            lockPaint.style = Paint.Style.STROKE; lockPaint.strokeWidth = r * 0.22f
            lockPaint.strokeCap = Paint.Cap.ROUND; lockPaint.shader = null
            lockPaint.clearShadowLayer()
            // Shackle
            canvas.drawArc(RectF(cx - r * 0.6f, cy - r * 1.8f, cx + r * 0.6f, cy), 180f, 180f, false, lockPaint)
            // Body
            canvas.drawRoundRect(RectF(cx - r * 0.85f, cy, cx + r * 0.85f, cy + r * 1.3f), r * 0.15f, r * 0.15f, lockPaint)
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

        // ── TOUCH ──
        private var downTime = 0L; private var downX = 0f; private var downY = 0f

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            // Two-finger
            if (event.pointerCount == 2) {
                when (action) {
                    MotionEvent.ACTION_POINTER_DOWN -> { twoFingerActive = true; twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f }
                    MotionEvent.ACTION_MOVE -> if (twoFingerActive) {
                        val d = (event.getY(0) + event.getY(1)) / 2f - twoFingerStartY
                        if (d > 100) { twoFingerActive = false; openSecretMenu() }
                        else if (d < -100) { twoFingerActive = false; finishAndRemoveTask() }
                    }
                }
                return true
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
            }
            return true
        }
    }
}
