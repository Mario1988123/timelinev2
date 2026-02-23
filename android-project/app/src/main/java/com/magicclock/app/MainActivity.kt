package com.magicclock.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : Activity(), SensorEventListener {

    // ══════════════ APP STATES ══════════════
    // Phase 1: BOOT   → black screen, keypad flashes briefly
    // Phase 2: DARK   → black screen, mago taps +/- and digit (invisible)
    // Phase 3: LIVE   → clock visible with wallpaper, offset applied
    //                    waiting for trigger (tap/shake) to start return
    // Phase 4: RETURNING → clock counting back to real time
    enum class Phase { BOOT, DARK, LIVE, RETURNING }

    private var phase = Phase.BOOT

    // ── Offset ──
    private var offsetMs = 0.0
    private var pendingSign: Char? = null
    private var returnStartTime = 0L
    private var returnStartOffset = 0.0

    // ── Settings ──
    private var styleiOS = true
    private var triggerTap = true       // tap screen to start return
    private var triggerShake = true     // shake to start return
    private var triggerDelay = 0        // seconds to wait after trigger before return starts
    private var returnSpeed = 30        // seconds for full return animation
    private var shakeSens = 15f

    // ── Shake ──
    private var sensorManager: SensorManager? = null
    private var lastAx = 0f; private var lastAy = 0f; private var lastAz = 0f
    private var shakeDebounce = 0L

    // ── Keypad fade ──
    private var keypadAlpha = 0f
    private var bootTime = 0L
    private val KEYPAD_SHOW_MS = 2000L
    private val KEYPAD_FADE_MS = 600L

    // ── Two-finger ──
    private var twoFingerStartY = 0f
    private var twoFingerActive = false

    // ── Misc ──
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var clockView: ClockView

    // ══════════════ LIFECYCLE ══════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        prefs = getSharedPreferences("mc", Context.MODE_PRIVATE)
        loadSettings()

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

        // After boot keypad fades, transition to DARK
        handler.postDelayed({
            if (phase == Phase.BOOT) phase = Phase.DARK
        }, KEYPAD_SHOW_MS + KEYPAD_FADE_MS + 200)

        startTick()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideSystemUI() }
    override fun onResume() { super.onResume(); hideSystemUI() }
    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ══════════════ TIME ══════════════

    private fun madridCal(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid"))

    private fun madridTimeMs(): Long {
        val c = madridCal()
        return c.get(Calendar.HOUR_OF_DAY) * 3600000L +
               c.get(Calendar.MINUTE) * 60000L +
               c.get(Calendar.SECOND) * 1000L +
               c.get(Calendar.MILLISECOND)
    }

    private fun msToHM(ms: Long): Pair<Int, Int> {
        val d = 86400000L
        val w = ((ms % d) + d) % d
        val s = (w / 1000).toInt()
        return Pair(s / 3600, (s % 3600) / 60)
    }

    private fun madridDateStr(): String {
        val c = madridCal()
        val days = arrayOf("domingo","lunes","martes","miércoles","jueves","viernes","sábado")
        val months = arrayOf("enero","febrero","marzo","abril","mayo","junio",
            "julio","agosto","septiembre","octubre","noviembre","diciembre")
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
        if (elapsed >= dur) {
            offsetMs = 0.0
            phase = Phase.LIVE
        } else {
            val t = elapsed.toDouble() / dur
            val ease = 1.0 - (1.0 - t).pow(3.0)
            offsetMs = returnStartOffset * (1.0 - ease)
        }
    }

    // ══════════════ KEY HANDLING ══════════════

    private fun handleKey(key: String) {
        // Only accept input during BOOT or DARK phase
        if (phase != Phase.BOOT && phase != Phase.DARK) return

        if (key == "+" || key == "-") {
            pendingSign = key[0]
            return
        }

        val digit = key.toIntOrNull() ?: return

        if (digit == 0) {
            // Reset — go to LIVE with no offset
            pendingSign = null
            offsetMs = 0.0
            goLive()
            return
        }

        if (pendingSign != null && digit in 1..9) {
            val sign = if (pendingSign == '+') 1 else -1
            offsetMs = sign * digit * 60000.0
            pendingSign = null
            goLive()
        }
    }

    private fun goLive() {
        phase = Phase.LIVE
    }

    private fun triggerReturn() {
        if (phase != Phase.LIVE) return
        // Apply delay then start return
        if (triggerDelay > 0) {
            handler.postDelayed({
                startReturn()
            }, triggerDelay * 1000L)
        } else {
            startReturn()
        }
    }

    private fun startReturn() {
        if (abs(offsetMs) < 500) { offsetMs = 0.0; return }
        phase = Phase.RETURNING
        returnStartTime = System.currentTimeMillis()
        returnStartOffset = offsetMs
    }

    // ══════════════ SHAKE ══════════════

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
        prefs.edit()
            .putBoolean("ios", styleiOS)
            .putBoolean("triggerTap", triggerTap)
            .putBoolean("triggerShake", triggerShake)
            .putInt("triggerDelay", triggerDelay)
            .putInt("returnSpeed", returnSpeed)
            .putFloat("shakeSens", shakeSens)
            .apply()
    }

    // ══════════════ SECRET MENU ══════════════

    private fun openSecretMenu() {
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.85f)

        val scroll = ScrollView(this).apply {
            setPadding(dp(24), dp(60), dp(24), dp(40))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Title
        root.addView(TextView(this@MainActivity).apply {
            text = "⚙️  Configuración"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        // ── Style ──
        root.addView(makeLabel("ESTILO"))
        root.addView(makeToggleRow(
            listOf("iOS" to styleiOS, "Android" to !styleiOS)
        ) { idx ->
            styleiOS = idx == 0
            saveSettings()
        })

        // ── Trigger mode ──
        root.addView(makeLabel("ACTIVAR RETORNO CON"))
        root.addView(makeToggleRow(
            listOf("Tocar pantalla" to triggerTap, "Agitar" to triggerShake)
        ) { idx ->
            if (idx == 0) triggerTap = !triggerTap
            if (idx == 1) triggerShake = !triggerShake
            // At least one must be active
            if (!triggerTap && !triggerShake) triggerTap = true
            saveSettings()
        })

        // ── Trigger delay ──
        root.addView(makeLabel("ESPERA ANTES DE INICIAR RETORNO"))
        root.addView(makeToggleRow(
            listOf("0s" to (triggerDelay==0), "3s" to (triggerDelay==3),
                   "5s" to (triggerDelay==5), "10s" to (triggerDelay==10))
        ) { idx ->
            triggerDelay = listOf(0, 3, 5, 10)[idx]
            saveSettings()
        })

        // ── Return speed ──
        root.addView(makeLabel("VELOCIDAD DE RETORNO: ${returnSpeed}s"))
        val speedBar = SeekBar(this).apply {
            max = 23  // 5 to 120 in steps of 5
            progress = (returnSpeed - 5) / 5
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        val speedLabel = root.getChildAt(root.childCount - 1) as TextView
        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                returnSpeed = 5 + p * 5
                speedLabel.text = "VELOCIDAD DE RETORNO: ${returnSpeed}s"
                saveSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(speedBar)

        // ── Shake sensitivity ──
        root.addView(makeLabel("SENSIBILIDAD SHAKE: ${shakeSens.toInt()}"))
        val shakeBar = SeekBar(this).apply {
            max = 35  // 5 to 40
            progress = shakeSens.toInt() - 5
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        val shakeLabel = root.getChildAt(root.childCount - 1) as TextView
        shakeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                shakeSens = (5 + p).toFloat()
                shakeLabel.text = "SENSIBILIDAD SHAKE: ${shakeSens.toInt()}"
                saveSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        root.addView(shakeBar)

        // ── Reset / Calibrate ──
        root.addView(makeButton("Calibrar hora (reset)") {
            offsetMs = 0.0
            phase = Phase.DARK
        })

        root.addView(makeButton("Reiniciar (pantalla negra)") {
            offsetMs = 0.0
            pendingSign = null
            phase = Phase.BOOT
            bootTime = System.currentTimeMillis()
            handler.postDelayed({ if (phase == Phase.BOOT) phase = Phase.DARK }, KEYPAD_SHOW_MS + KEYPAD_FADE_MS + 200)
            dialog.dismiss()
        })

        // ── Close ──
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
        })
        root.addView(makeButton("Cerrar") { dialog.dismiss() })

        scroll.addView(root)
        dialog.setContentView(scroll)
        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.argb(120, 255, 255, 255))
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(18), 0, dp(8))
        }
    }

    private fun makeToggleRow(items: List<Pair<String, Boolean>>, onClick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        items.forEachIndexed { idx, (label, active) ->
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.setMargins(if (idx > 0) dp(4) else 0, 0, 0, 0)
                layoutParams = lp
                if (active) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(40, 255, 255, 255))
                } else {
                    setTextColor(Color.argb(140, 255, 255, 255))
                    setBackgroundColor(Color.argb(12, 255, 255, 255))
                }
                setOnClickListener {
                    onClick(idx)
                    // Refresh menu
                    (parent as? View)?.let { p ->
                        val dialog = (p.parent as? View)?.let { findParentDialog(it) }
                    }
                }
            })
        }
        return row
    }

    private fun findParentDialog(v: View): Dialog? = null // unused helper

    private fun makeButton(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.argb(18, 255, 255, 255))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
            setOnClickListener { action() }
        }
    }

    // ══════════════ CLOCK VIEW ══════════════

    inner class ClockView(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        private val keys = arrayOf(
            arrayOf("1","2","3"),
            arrayOf("4","5","6"),
            arrayOf("7","8","9"),
            arrayOf("+","0","-")
        )

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            when (phase) {
                Phase.BOOT -> drawBoot(canvas, w, h)
                Phase.DARK -> drawDark(canvas, w, h)
                Phase.LIVE, Phase.RETURNING -> drawLive(canvas, w, h)
            }
        }

        // ── BOOT: black + fading keypad ──
        private fun drawBoot(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.BLACK)
            val elapsed = System.currentTimeMillis() - bootTime
            val alpha = when {
                elapsed < KEYPAD_SHOW_MS -> 0.14f
                elapsed < KEYPAD_SHOW_MS + KEYPAD_FADE_MS -> {
                    val fade = (elapsed - KEYPAD_SHOW_MS).toFloat() / KEYPAD_FADE_MS
                    0.14f * (1f - fade)
                }
                else -> 0f
            }
            if (alpha > 0.001f) drawKeypad(canvas, w, h, alpha)
        }

        // ── DARK: just black ──
        private fun drawDark(canvas: Canvas, w: Float, h: Float) {
            canvas.drawColor(Color.BLACK)
        }

        // ── LIVE: wallpaper + clock ──
        private fun drawLive(canvas: Canvas, w: Float, h: Float) {
            // Background gradient
            val grad = LinearGradient(0f, 0f, w * 0.3f, h,
                intArrayOf(Color.rgb(10,10,46), Color.rgb(26,10,58), Color.rgb(10,26,46)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            p.shader = grad
            p.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null

            // Lock icon
            p.textSize = w * 0.04f
            p.color = Color.argb(90, 255, 255, 255)
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT
            canvas.drawText("🔒", w / 2f, h * 0.055f, p)

            // Time
            val realMs = madridTimeMs()
            val displayMs = realMs + offsetMs.toLong()
            val (hr, mn) = msToHM(displayMs)
            val timeStr = String.format(Locale.US, "%02d:%02d", hr, mn)

            p.color = Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.setShadowLayer(24f, 0f, 2f, Color.argb(60, 0, 0, 0))

            if (styleiOS) {
                p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                p.textSize = w * 0.22f
            } else {
                p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                p.textSize = w * 0.20f
            }

            val timeY = if (styleiOS) h * 0.22f else h * 0.26f
            canvas.drawText(timeStr, w / 2f, timeY, p)
            p.clearShadowLayer()

            // Date
            p.color = Color.argb(200, 255, 255, 255)
            p.typeface = if (styleiOS)
                Typeface.create("sans-serif", Typeface.NORMAL)
            else
                Typeface.create("sans-serif-light", Typeface.NORMAL)
            p.textSize = if (styleiOS) w * 0.048f else w * 0.042f
            canvas.drawText(madridDateStr(), w / 2f, timeY + w * 0.075f, p)

            // Bottom pill
            p.color = Color.argb(70, 255, 255, 255)
            p.style = Paint.Style.FILL
            val bw = w * 0.35f
            val bh = h * 0.005f
            canvas.drawRoundRect(
                w/2f - bw/2f, h - h*0.025f,
                w/2f + bw/2f, h - h*0.025f + bh,
                bh, bh, p
            )
        }

        private fun drawKeypad(canvas: Canvas, w: Float, h: Float, alpha: Float) {
            p.color = Color.WHITE
            p.alpha = (alpha * 255).toInt()
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            p.style = Paint.Style.FILL
            p.clearShadowLayer()
            p.shader = null

            val padTop = h * 0.08f
            val padBot = h * 0.05f
            val rowH = (h - padTop - padBot) / 4f

            for (r in 0..3) {
                for (c in 0..2) {
                    val cx = w * (c + 0.5f) / 3f
                    val cy = padTop + rowH * r + rowH / 2f
                    val label = keys[r][c]
                    p.textSize = if (label == "+" || label == "-") w * 0.10f else w * 0.08f
                    canvas.drawText(label, cx, cy + p.textSize * 0.35f, p)
                }
            }
        }

        fun getKeyAt(x: Float, y: Float): String? {
            val h = height.toFloat()
            val w = width.toFloat()
            val padTop = h * 0.08f
            val padBot = h * 0.05f
            val rowH = (h - padTop - padBot) / 4f
            val colW = w / 3f
            val row = ((y - padTop) / rowH).toInt()
            val col = (x / colW).toInt()
            if (row in 0..3 && col in 0..2) return keys[row][col]
            return null
        }

        // ── TOUCH ──
        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            val count = event.pointerCount

            // ── Two-finger gestures ──
            if (count == 2) {
                when (action) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        twoFingerActive = true
                        twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (twoFingerActive) {
                            val cur = (event.getY(0) + event.getY(1)) / 2f
                            val d = cur - twoFingerStartY
                            if (d > 100) { twoFingerActive = false; openSecretMenu() }
                            else if (d < -100) { twoFingerActive = false; finishAndRemoveTask() }
                        }
                    }
                }
                return true
            }
            twoFingerActive = false

            // ── Single touch ──
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - downTime
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)

                    // Long press top area → menu (backup)
                    if (dt >= 2000L && dy < 60 && downY < height * 0.1f) {
                        openSecretMenu()
                        return true
                    }

                    // Short tap
                    if (dt < 400 && dx < 40 && dy < 40) {
                        when (phase) {
                            Phase.BOOT, Phase.DARK -> {
                                val key = getKeyAt(event.x, event.y)
                                if (key != null) handleKey(key)
                            }
                            Phase.LIVE -> {
                                if (triggerTap) triggerReturn()
                            }
                            Phase.RETURNING -> { /* do nothing */ }
                        }
                    }
                }
            }
            return true
        }
    }
}
