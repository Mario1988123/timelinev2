package com.magicclock.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : Activity(), SensorEventListener {

    // ── State ──
    private var offsetMs = 0.0
    private var isReturning = false
    private var returnStartTime = 0L
    private var returnStartOffset = 0.0
    private var pendingSign: Char? = null
    private var waitingForTapToReturn = false
    private var blackoutActive = false

    // ── Settings ──
    private var styleiOS = true
    private var returnMode = "shake"   // "shake" or "tap"
    private var delayMode = "3"        // "0","3","5","tap"
    private var returnSpeed = 30       // seconds
    private var shakeSens = 15f

    // ── Shake ──
    private var sensorManager: SensorManager? = null
    private var lastAx = 0f; private var lastAy = 0f; private var lastAz = 0f
    private var shakeDebounce = 0L

    // ── Keypad visibility ──
    private var keypadAlpha = 0.12f
    private var keypadFadeStart = 0L
    private val KEYPAD_SHOW_MS = 1200L
    private val KEYPAD_FADE_MS = 800L

    // ── Two-finger gesture ──
    private var twoFingerStartY = 0f
    private var twoFingerActive = false

    // ── Timers ──
    private val handler = Handler(Looper.getMainLooper())
    private var returnDelayRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var prefs: SharedPreferences

    // ── View ──
    private lateinit var clockView: ClockView

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

        clockView = ClockView(this)
        setContentView(clockView)
        hideSystemUI()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        @Suppress("DEPRECATION")
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "mc:wake")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)

        showKeypadBriefly()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onResume() { super.onResume(); hideSystemUI() }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // ══════════════════════════════════════
    //  TIME — Europe/Madrid
    // ══════════════════════════════════════

    private fun getMadridCalendar(): Calendar {
        return Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid"))
    }

    private fun getMadridTimeMs(): Long {
        val c = getMadridCalendar()
        return (c.get(Calendar.HOUR_OF_DAY) * 3600000L +
                c.get(Calendar.MINUTE) * 60000L +
                c.get(Calendar.SECOND) * 1000L +
                c.get(Calendar.MILLISECOND))
    }

    private fun msToHM(ms: Long): Pair<Int, Int> {
        val day = 86400000L
        val wrapped = ((ms % day) + day) % day
        val totalSec = (wrapped / 1000).toInt()
        return Pair(totalSec / 3600, (totalSec % 3600) / 60)
    }

    private fun getMadridDateString(): String {
        val c = getMadridCalendar()
        val days = arrayOf("domingo","lunes","martes","miércoles","jueves","viernes","sábado")
        val months = arrayOf("enero","febrero","marzo","abril","mayo","junio",
            "julio","agosto","septiembre","octubre","noviembre","diciembre")
        val dow = days[c.get(Calendar.DAY_OF_WEEK) - 1]
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = months[c.get(Calendar.MONTH)]
        return "${dow.replaceFirstChar { it.uppercase() }}, $day de $month"
    }

    // ══════════════════════════════════════
    //  TICK LOOP
    // ══════════════════════════════════════

    private fun startTick() {
        handler.post(object : Runnable {
            override fun run() {
                updateReturn()
                updateKeypadFade()
                clockView.invalidate()
                handler.postDelayed(this, 16) // ~60fps
            }
        })
    }

    private fun updateReturn() {
        if (!isReturning) return
        val elapsed = System.currentTimeMillis() - returnStartTime
        val dur = returnSpeed * 1000L
        if (elapsed >= dur) {
            offsetMs = 0.0
            isReturning = false
        } else {
            val t = (elapsed.toDouble() / dur)
            val ease = 1.0 - (1.0 - t).pow(3.0)
            offsetMs = returnStartOffset * (1.0 - ease)
        }
    }

    // ══════════════════════════════════════
    //  KEYPAD
    // ══════════════════════════════════════

    private fun showKeypadBriefly() {
        keypadAlpha = 0.12f
        keypadFadeStart = System.currentTimeMillis() + KEYPAD_SHOW_MS
    }

    private fun updateKeypadFade() {
        val now = System.currentTimeMillis()
        if (keypadAlpha <= 0f) return
        if (now < keypadFadeStart) {
            keypadAlpha = 0.12f
        } else {
            val fadeElapsed = now - keypadFadeStart
            keypadAlpha = 0.12f * (1f - (fadeElapsed.toFloat() / KEYPAD_FADE_MS).coerceIn(0f, 1f))
        }
    }

    private fun handleKey(key: String) {
        showKeypadBriefly()

        if (key == "+" || key == "-") {
            pendingSign = key[0]
            blackoutActive = true
            clockView.invalidate()
            return
        }

        val digit = key.toIntOrNull() ?: return

        if (digit == 0) {
            pendingSign = null
            cancelReturn()
            offsetMs = 0.0
            blackoutActive = false
            clockView.invalidate()
            return
        }

        if (pendingSign != null && digit in 1..9) {
            val sign = if (pendingSign == '+') 1 else -1
            offsetMs = sign * digit * 60000.0
            pendingSign = null
            cancelReturn()
            handler.postDelayed({
                blackoutActive = false
                clockView.invalidate()
            }, 80)
            scheduleReturn()
        }
    }

    // ══════════════════════════════════════
    //  RETURN LOGIC
    // ══════════════════════════════════════

    private fun cancelReturn() {
        isReturning = false
        waitingForTapToReturn = false
        returnDelayRunnable?.let { handler.removeCallbacks(it) }
        returnDelayRunnable = null
    }

    private fun scheduleReturn() {
        if (delayMode == "tap") {
            waitingForTapToReturn = true
            return
        }
        val sec = delayMode.toIntOrNull() ?: 0
        val r = Runnable { startReturn() }
        returnDelayRunnable = r
        handler.postDelayed(r, sec * 1000L)
    }

    private fun startReturn() {
        if (abs(offsetMs) < 100) { offsetMs = 0.0; return }
        isReturning = true
        returnStartTime = System.currentTimeMillis()
        returnStartOffset = offsetMs
        waitingForTapToReturn = false
    }

    private fun triggerReturn() {
        if (waitingForTapToReturn) { startReturn(); return }
        if (abs(offsetMs) > 100 && !isReturning) startReturn()
    }

    // ══════════════════════════════════════
    //  SHAKE
    // ══════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val mag = abs(e.values[0] - lastAx) + abs(e.values[1] - lastAy) + abs(e.values[2] - lastAz)
        lastAx = e.values[0]; lastAy = e.values[1]; lastAz = e.values[2]
        val now = System.currentTimeMillis()
        if (mag > shakeSens && now - shakeDebounce > 800) {
            shakeDebounce = now
            if (returnMode == "shake") triggerReturn()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ══════════════════════════════════════
    //  SECRET MENU
    // ══════════════════════════════════════

    private fun openSecretMenu() {
        val items = arrayOf(
            "Estilo: ${if (styleiOS) "iOS" else "Android"}  → cambiar",
            "Retorno: $returnMode  → cambiar",
            "Delay: $delayMode  → cambiar",
            "Velocidad retorno: ${returnSpeed}s  → cambiar",
            "Sensibilidad shake: ${shakeSens.toInt()}  → cambiar",
            "Calibrar hora (reset offset)",
            "Cerrar"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog)
            .setTitle("Configuración")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { styleiOS = !styleiOS; saveSettings(); openSecretMenu() }
                    1 -> { returnMode = if (returnMode == "shake") "tap" else "shake"; saveSettings(); openSecretMenu() }
                    2 -> {
                        val opts = arrayOf("0","3","5","tap")
                        val next = opts[(opts.indexOf(delayMode) + 1) % opts.size]
                        delayMode = next; saveSettings(); openSecretMenu()
                    }
                    3 -> {
                        returnSpeed = if (returnSpeed >= 120) 10 else returnSpeed + 10
                        saveSettings(); openSecretMenu()
                    }
                    4 -> {
                        shakeSens = if (shakeSens >= 40f) 5f else shakeSens + 5f
                        saveSettings(); openSecretMenu()
                    }
                    5 -> { offsetMs = 0.0; cancelReturn() }
                    6 -> { /* close */ }
                }
            }
            .show()
    }

    private fun loadSettings() {
        styleiOS = prefs.getBoolean("ios", true)
        returnMode = prefs.getString("returnMode", "shake") ?: "shake"
        delayMode = prefs.getString("delayMode", "3") ?: "3"
        returnSpeed = prefs.getInt("returnSpeed", 30)
        shakeSens = prefs.getFloat("shakeSens", 15f)
    }

    private fun saveSettings() {
        prefs.edit()
            .putBoolean("ios", styleiOS)
            .putString("returnMode", returnMode)
            .putString("delayMode", delayMode)
            .putInt("returnSpeed", returnSpeed)
            .putFloat("shakeSens", shakeSens)
            .apply()
    }

    // ══════════════════════════════════════
    //  CUSTOM VIEW
    // ══════════════════════════════════════

    inner class ClockView(ctx: Context) : View(ctx) {
        private val paintTime = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paintDate = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paintKeypad = Paint(Paint.ANTI_ALIAS_FLAG)
        private val paintBlackout = Paint()
        private val paintLock = Paint(Paint.ANTI_ALIAS_FLAG)

        // Keypad grid: 4 rows x 3 cols
        private val keys = arrayOf(
            arrayOf("1","2","3"),
            arrayOf("4","5","6"),
            arrayOf("7","8","9"),
            arrayOf("+","0","-")
        )

        // Long press for menu
        private var longPressStart = 0L
        private var longPressX = 0f
        private var longPressY = 0f
        private val LONG_PRESS_MS = 2000L

        init {
            paintBlackout.color = Color.BLACK
            paintBlackout.style = Paint.Style.FILL
            paintLock.color = Color.WHITE
            paintLock.textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // ── Background gradient ──
            val grad = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.rgb(10,10,46), Color.rgb(26,10,58), Color.rgb(10,26,46)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            val bgPaint = Paint()
            bgPaint.shader = grad
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // ── Blackout ──
            if (blackoutActive) {
                canvas.drawRect(0f, 0f, w, h, paintBlackout)
                // Still draw keypad on top of blackout
                drawKeypad(canvas, w, h)
                return
            }

            // ── Lock icon (small text) ──
            paintLock.textSize = w * 0.035f
            paintLock.alpha = 100
            canvas.drawText("🔒", w / 2f, h * 0.06f, paintLock)

            // ── Clock ──
            val realMs = getMadridTimeMs()
            val displayMs = realMs + offsetMs.toLong()
            val (hr, mn) = msToHM(displayMs)
            val timeStr = String.format(Locale.US, "%02d:%02d", hr, mn)

            if (styleiOS) {
                paintTime.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                paintTime.textSize = w * 0.22f
            } else {
                paintTime.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                paintTime.textSize = w * 0.20f
            }
            paintTime.color = Color.WHITE
            paintTime.textAlign = Paint.Align.CENTER
            paintTime.setShadowLayer(20f, 0f, 2f, Color.argb(80, 0, 0, 0))

            val timeY = if (styleiOS) h * 0.22f else h * 0.26f
            canvas.drawText(timeStr, w / 2f, timeY, paintTime)

            // ── Date ──
            paintDate.color = Color.WHITE
            paintDate.alpha = 200
            paintDate.textAlign = Paint.Align.CENTER
            paintDate.typeface = if (styleiOS)
                Typeface.create("sans-serif", Typeface.NORMAL)
            else
                Typeface.create("sans-serif-light", Typeface.NORMAL)
            paintDate.textSize = if (styleiOS) w * 0.048f else w * 0.042f
            canvas.drawText(getMadridDateString(), w / 2f, timeY + w * 0.07f, paintDate)

            // ── Bottom bar ──
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            barPaint.color = Color.argb(80, 255, 255, 255)
            val barW = w * 0.35f
            val barH = h * 0.005f
            canvas.drawRoundRect(
                w / 2f - barW / 2f, h - h * 0.03f,
                w / 2f + barW / 2f, h - h * 0.03f + barH,
                barH, barH, barPaint
            )

            // ── Keypad overlay ──
            drawKeypad(canvas, w, h)
        }

        private fun drawKeypad(canvas: Canvas, w: Float, h: Float) {
            if (keypadAlpha <= 0.001f) return
            paintKeypad.color = Color.WHITE
            paintKeypad.alpha = (keypadAlpha * 255).toInt()
            paintKeypad.textAlign = Paint.Align.CENTER
            paintKeypad.textSize = w * 0.08f
            paintKeypad.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

            val rows = 4
            val cols = 3
            val padTop = h * 0.08f
            val padBot = h * 0.05f
            val rowH = (h - padTop - padBot) / rows

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cx = w * (c + 0.5f) / cols
                    val cy = padTop + rowH * r + rowH / 2f
                    val label = keys[r][c]
                    // + and - slightly larger
                    if (label == "+" || label == "-") {
                        paintKeypad.textSize = w * 0.10f
                    } else {
                        paintKeypad.textSize = w * 0.08f
                    }
                    canvas.drawText(label, cx, cy + paintKeypad.textSize * 0.35f, paintKeypad)
                }
            }
        }

        fun getKeyAt(x: Float, y: Float): String? {
            val w = width.toFloat()
            val h = height.toFloat()
            val padTop = h * 0.08f
            val padBot = h * 0.05f
            val rowH = (h - padTop - padBot) / 4
            val colW = w / 3f

            val row = ((y - padTop) / rowH).toInt()
            val col = (x / colW).toInt()

            if (row in 0..3 && col in 0..2) {
                return keys[row][col]
            }
            return null
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            val pointerCount = event.pointerCount

            // Two-finger gestures
            if (pointerCount == 2) {
                when (action) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        twoFingerActive = true
                        twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (twoFingerActive) {
                            val curY = (event.getY(0) + event.getY(1)) / 2f
                            val delta = curY - twoFingerStartY
                            if (delta > 80) {
                                twoFingerActive = false
                                openSecretMenu()
                            } else if (delta < -80) {
                                twoFingerActive = false
                                finishAndRemoveTask()
                            }
                        }
                    }
                }
                return true
            }

            twoFingerActive = false

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressStart = System.currentTimeMillis()
                    longPressX = event.x
                    longPressY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - longPressStart
                    val dx = abs(event.x - longPressX)
                    val dy = abs(event.y - longPressY)

                    // Long press top center = menu
                    if (dt >= LONG_PRESS_MS && dy < 50 && longPressY < height * 0.1f) {
                        openSecretMenu()
                        return true
                    }

                    // Normal tap = keypad or return trigger
                    if (dt < 500 && dx < 30 && dy < 30) {
                        val key = getKeyAt(event.x, event.y)
                        if (key != null) {
                            handleKey(key)
                        } else if (returnMode == "tap") {
                            triggerReturn()
                        }
                    }
                }
            }
            return true
        }
    }
}
