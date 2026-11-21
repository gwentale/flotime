package com.flotime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : Activity() {

    companion object {
        private const val REQ_OVERLAY_START = 101
        private const val REQ_SETTINGS_REFRESH = 102
    }

    private lateinit var tvMainTitle: TextView
    private lateinit var cardClockCircle: FrameLayout
    private lateinit var layoutDigitalClock: LinearLayout
    private lateinit var viewAnalogClock: View
    private lateinit var tvMainTimeHms: TextView
    private lateinit var tvMainTimeMillis: TextView
    private lateinit var tvMainDate: TextView
    private lateinit var tvMainTimeSource: TextView
    private lateinit var btnToggleSource: Button
    private lateinit var btnTogglePrecision: Button
    private lateinit var btnToggleMetrics: Button
    private lateinit var btnToggleFloatStyle: Button
    private lateinit var btnToggleFloating: Button
    private lateinit var btnSettings: ImageButton
    private lateinit var btnThemeToggle: ImageButton
    private lateinit var rootContainer: ViewGroup

    // Dual-page horizontal slider: Page 0 = Clock Home, Page 1 = Advanced Compass
    private lateinit var pagerContainer: SwipePagerContainer
    private lateinit var clockPageWrapper: FrameLayout
    private lateinit var compassPageRoot: LinearLayout
    private lateinit var pagerHintPill: LinearLayout
    private lateinit var dotPageClock: View
    private lateinit var dotPageCompass: View
    private lateinit var tvCompassHint: TextView

    // Compass Page UI references
    private lateinit var compassDialView: AdvancedCompassView
    private lateinit var btnCompassBack: ImageView
    private lateinit var tvCompassTitle: TextView
    private lateinit var btnCompassLock: TextView
    private lateinit var tvCompassHeroDeg: TextView
    private lateinit var tvCompassHeroDir: TextView
    private lateinit var tvCompassLockInfo: TextView
    private lateinit var cardMagField: LinearLayout
    private lateinit var cardTiltLevel: LinearLayout
    private lateinit var cardBackAzimuth: LinearLayout
    private lateinit var cardSensorAccuracy: LinearLayout
    private lateinit var tvMagTitle: TextView
    private lateinit var tvMagValue: TextView
    private lateinit var tvMagSub: TextView
    private lateinit var tvTiltTitle: TextView
    private lateinit var tvTiltValue: TextView
    private lateinit var tvTiltSub: TextView
    private lateinit var tvBackTitle: TextView
    private lateinit var tvBackValue: TextView
    private lateinit var tvBackSub: TextView
    private lateinit var tvAccTitle: TextView
    private lateinit var tvAccValue: TextView
    private lateinit var tvAccSub: TextView
    private lateinit var tvCompassCalibrationNote: TextView

    private var baseMainHmsTextSizePx = 0f
    private var baseMainMillisTextSizePx = 0f
    private lateinit var analogClockDrawable: AnalogClockDrawable

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isForeground = false
    private var currentPage = 0

    private var themePopupView: TextView? = null
    private var hidePopupRunnable: Runnable? = null

    private val timeFormat24 = SimpleDateFormat("HH:mm:ss.", Locale.US)
    private val timeFormat12 = SimpleDateFormat("KK:mm:ss.", Locale.US)
    private val lcdHmFormat24 = SimpleDateFormat("HH.mm", Locale.US)
    private val lcdHmFormat12 = SimpleDateFormat("KK.mm", Locale.US)
    private val lcdSecFormat = SimpleDateFormat("ss", Locale.US)
    private val amPmFormat = SimpleDateFormat("a", Locale.US)
    private val lcdDayDateFormat = SimpleDateFormat("EEE d", Locale.ENGLISH)
    private val dateFormat = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.ENGLISH)
    private var permissionDialogRefreshAction: (() -> Unit)? = null

    // Theme Mode: 0 = Light, 1 = Dark, 2 = Adaptive
    private var currentThemeMode = 2
    private var isAnalogMode = false
    private var lastClockTouchX = 0f
    private var lastClockTouchY = 0f

    private val prefsName = "flotime_prefs"
    private val keyThemeMode = "theme_mode"
    private val developerUrl = "https://youtu.be/dQw4w9WgXcQ"
    private val millisRedColor = Color.parseColor("#FF3B1D")

    // Advanced Compass Sensor Engine
    private var sensorManager: SensorManager? = null
    private var compassSensorsRegistered = false
    private var hasRotationVector = false
    private var hasBarometer = false
    private var sensorSourceLabel = "9-Axis Fusion"

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private var smoothSin = 0f
    private var smoothCos = 1f
    private var hasSmoothAzimuth = false
    private var compassAzimuthDeg = 0f
    private var compassPitchDeg = 0f
    private var compassRollDeg = 0f
    private var magneticStrengthUt = 0f
    private var pressureHpa = -1f
    private var sensorAccuracyLevel = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
    private var lockedBearingDeg: Float? = null
    private var lastCompassTextUpdateMs = 0L

    private val dirs16Short = arrayOf(
        "N", "NNE", "NE", "ENE",
        "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW",
        "W", "WNW", "NW", "NNW"
    )
    private val dirs16Full = arrayOf(
        "North", "North-Northeast", "Northeast", "East-Northeast",
        "East", "East-Southeast", "Southeast", "South-Southeast",
        "South", "South-Southwest", "Southwest", "West-Southwest",
        "West", "West-Northwest", "Northwest", "North-Northwest"
    )

    private val compassSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    applyOrientationRadians(
                        azimuthRad = orientationAngles[0],
                        pitchRad = orientationAngles[1],
                        rollRad = orientationAngles[2]
                    )
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val a = 0.22f
                    if (!hasAccel) {
                        System.arraycopy(event.values, 0, accelValues, 0, 3)
                        hasAccel = true
                    } else {
                        accelValues[0] += a * (event.values[0] - accelValues[0])
                        accelValues[1] += a * (event.values[1] - accelValues[1])
                        accelValues[2] += a * (event.values[2] - accelValues[2])
                    }
                    if (!hasRotationVector) computeFallbackOrientation()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    val mx = event.values[0]
                    val my = event.values[1]
                    val mz = event.values[2]
                    val a = 0.22f
                    if (!hasMag) {
                        magValues[0] = mx
                        magValues[1] = my
                        magValues[2] = mz
                        hasMag = true
                    } else {
                        magValues[0] += a * (mx - magValues[0])
                        magValues[1] += a * (my - magValues[1])
                        magValues[2] += a * (mz - magValues[2])
                    }
                    magneticStrengthUt = sqrt(
                        magValues[0] * magValues[0] +
                            magValues[1] * magValues[1] +
                            magValues[2] * magValues[2]
                    )
                    if (!hasRotationVector) computeFallbackOrientation()
                    throttledCompassUiUpdate()
                }
                Sensor.TYPE_PRESSURE -> {
                    pressureHpa = event.values[0]
                    throttledCompassUiUpdate()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD || sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                sensorAccuracyLevel = accuracy
                updateCompassTextReadouts()
            }
        }
    }

    private fun computeFallbackOrientation() {
        if (!hasAccel || !hasMag) return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magValues)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            applyOrientationRadians(
                azimuthRad = orientationAngles[0],
                pitchRad = orientationAngles[1],
                rollRad = orientationAngles[2]
            )
        }
    }

    private fun applyOrientationRadians(azimuthRad: Float, pitchRad: Float, rollRad: Float) {
        val rawSin = sin(azimuthRad.toDouble()).toFloat()
        val rawCos = cos(azimuthRad.toDouble()).toFloat()
        if (!hasSmoothAzimuth) {
            smoothSin = rawSin
            smoothCos = rawCos
            hasSmoothAzimuth = true
        } else {
            val alpha = 0.18f
            smoothSin += alpha * (rawSin - smoothSin)
            smoothCos += alpha * (rawCos - smoothCos)
        }
        val filteredRad = atan2(smoothSin.toDouble(), smoothCos.toDouble())
        compassAzimuthDeg = (((Math.toDegrees(filteredRad).toFloat()) % 360f) + 360f) % 360f

        val pDeg = Math.toDegrees(pitchRad.toDouble()).toFloat()
        val rDeg = Math.toDegrees(rollRad.toDouble()).toFloat()
        compassPitchDeg += 0.22f * (pDeg - compassPitchDeg)
        compassRollDeg += 0.22f * (rDeg - compassRollDeg)

        if (::compassDialView.isInitialized) {
            compassDialView.updateData(
                azimuthDeg = compassAzimuthDeg,
                pitchDeg = compassPitchDeg,
                rollDeg = compassRollDeg,
                lockedBearing = lockedBearingDeg
            )
        }
        throttledCompassUiUpdate()
    }

    private fun throttledCompassUiUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastCompassTextUpdateMs >= 80L) {
            lastCompassTextUpdateMs = now
            updateCompassTextReadouts()
        }
    }

    private fun syncCompassSensors() {
        val shouldListen = isForeground && currentPage == 1
        if (shouldListen && !compassSensorsRegistered) {
            val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            sensorManager = sm
            val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

            hasRotationVector = rot != null
            hasBarometer = baro != null
            sensorSourceLabel = when {
                rot != null -> "9-Axis Fusion"
                mag != null && acc != null -> "Mag + Accel"
                else -> "Unavailable"
            }

            if (rot != null) {
                sm.registerListener(compassSensorListener, rot, SensorManager.SENSOR_DELAY_UI)
            } else if (acc != null) {
                sm.registerListener(compassSensorListener, acc, SensorManager.SENSOR_DELAY_UI)
            }
            if (mag != null) {
                sm.registerListener(compassSensorListener, mag, SensorManager.SENSOR_DELAY_UI)
            }
            if (baro != null) {
                sm.registerListener(compassSensorListener, baro, SensorManager.SENSOR_DELAY_UI)
            }
            compassSensorsRegistered = true
            updateCompassTextReadouts()
        } else if (!shouldListen && compassSensorsRegistered) {
            sensorManager?.unregisterListener(compassSensorListener)
            compassSensorsRegistered = false
            hasAccel = false
            hasMag = false
            hasSmoothAzimuth = false
        }
    }

    private fun refreshHomeAnalogClock() {
        val now = FloatingClockService.getCurrentTimestamp()
        val nowDate = Date(now)
        val use24 = FloatingClockService.use24HourFormat
        val hmText = if (use24) lcdHmFormat24.format(nowDate) else lcdHmFormat12.format(nowDate)
        analogClockDrawable.updateState(
            nowMillis = now,
            lcdHmStr = hmText,
            lcdSecStr = lcdSecFormat.format(nowDate),
            lcdMillisStr = FloatingClockService.formatMillisFraction(now, FloatingClockService.millisDigits),
            millisDigits = FloatingClockService.millisDigits,
            dayDateStr = lcdDayDateFormat.format(nowDate),
            use24Hour = use24,
            amPmStr = amPmFormat.format(nowDate)
        )
    }

    private val mainClockRunnable = object : Runnable {
        override fun run() {
            if (!isForeground || isFinishing || isDestroyed) return
            val now = FloatingClockService.getCurrentTimestamp()
            val nowDate = Date(now)
            val use24 = FloatingClockService.use24HourFormat

            if (isAnalogMode) {
                refreshHomeAnalogClock()
            } else {
                val hms = if (use24) timeFormat24.format(nowDate) else timeFormat12.format(nowDate)
                val dateStr = dateFormat.format(nowDate)
                val fracStr = FloatingClockService.formatMillisFraction(now, FloatingClockService.millisDigits)

                tvMainTimeHms.text = hms
                tvMainTimeMillis.text = fracStr
                tvMainDate.text = dateStr
            }

            updateUiState()
            mainHandler.postDelayed(this, if (FloatingClockService.millisDigits >= 2) 16L else 33L)
        }
    }

    private val mainTickRunnable = object : Runnable {
        override fun run() {
            if (!isForeground || !FloatingClockService.tickSoundEnabled || isFinishing || isDestroyed) return
            val now = FloatingClockService.getCurrentTimestamp()
            FloatingClockService.playTickIfNewSecond()
            val msIntoSecond = ((now % 1000L) + 1000L) % 1000L
            val waitMs = (1000L - msIntoSecond).coerceIn(2L, 1000L)
            mainHandler.postDelayed(this, waitMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val contentFrame = findViewById<FrameLayout>(android.R.id.content)
        val inflatedRoot = contentFrame.getChildAt(0) as ViewGroup
        contentFrame.removeView(inflatedRoot)
        rootContainer = inflatedRoot

        clockPageWrapper = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(
                inflatedRoot,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        compassPageRoot = buildCompassPage()

        pagerContainer = SwipePagerContainer(this, clockPageWrapper, compassPageRoot) { newPage ->
            currentPage = newPage
            updatePagerIndicatorUI()
            syncCompassSensors()
        }
        contentFrame.addView(
            pagerContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        tvMainTitle = inflatedRoot.findViewById(R.id.tvMainTitle)
        cardClockCircle = inflatedRoot.findViewById(R.id.cardClockCircle)
        layoutDigitalClock = inflatedRoot.findViewById(R.id.layoutDigitalClock)
        viewAnalogClock = inflatedRoot.findViewById(R.id.viewAnalogClock)
        tvMainTimeHms = inflatedRoot.findViewById(R.id.tvMainTimeHms)
        tvMainTimeMillis = inflatedRoot.findViewById(R.id.tvMainTimeMillis)
        tvMainDate = inflatedRoot.findViewById(R.id.tvMainDate)
        tvMainTimeSource = inflatedRoot.findViewById(R.id.tvMainTimeSource)
        btnToggleSource = inflatedRoot.findViewById(R.id.btnToggleSource)
        btnToggleMetrics = inflatedRoot.findViewById(R.id.btnToggleMetrics)
        btnToggleFloating = inflatedRoot.findViewById(R.id.btnToggleFloating)
        btnSettings = inflatedRoot.findViewById(R.id.btnSettings)

        baseMainHmsTextSizePx = tvMainTimeHms.textSize
        baseMainMillisTextSizePx = tvMainTimeMillis.textSize
        tvMainTimeHms.fontFeatureSettings = "tnum"
        tvMainTimeMillis.fontFeatureSettings = "tnum"
        tvMainTimeHms.setSingleLine(true)
        tvMainTimeMillis.setSingleLine(true)

        tvMainTimeSource.text = getString(R.string.device_time)
        tvMainTimeSource.isClickable = false
        tvMainTimeSource.isFocusable = false

        FloatingClockService.loadPreferences(this)

        currentThemeMode = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getInt(keyThemeMode, 2)
            .coerceIn(0, 2)

        analogClockDrawable = AnalogClockDrawable(resources.displayMetrics.density)
        viewAnalogClock.background = analogClockDrawable

        setupThemeToggleButton()
        setupPagerHintOverlay()
        setupPrecisionButton()
        updateThemeUI()
        applyMainClockPrecisionSizing()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        if (!Settings.canDrawOverlays(this) && FloatingClockService.isRunning) {
            stopFloatingClockService()
        }
        FloatingClockService.loadPreferences(this)
        updateThemeUI()
        applyMainClockPrecisionSizing()
        updateUiState()
        syncCompassSensors()
        startMainClockLoop()
        startMainTickLoop()
        permissionDialogRefreshAction?.invoke()
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        syncCompassSensors()
        mainHandler.removeCallbacks(mainClockRunnable)
        mainHandler.removeCallbacks(mainTickRunnable)
    }

    override fun onDestroy() {
        isForeground = false
        syncCompassSensors()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentPage == 1) {
            pagerContainer.snapToPage(0)
            return
        }
        super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY_START -> {
                permissionDialogRefreshAction?.invoke()
                if (Settings.canDrawOverlays(this)) {
                    startFloatingClockService()
                } else {
                    Toast.makeText(this, getString(R.string.overlay_perm_required_toast), Toast.LENGTH_LONG).show()
                }
            }
            REQ_SETTINGS_REFRESH -> {
                if (!Settings.canDrawOverlays(this) && FloatingClockService.isRunning) {
                    stopFloatingClockService()
                }
                permissionDialogRefreshAction?.invoke()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchIntentForResult(intent: Intent, requestCode: Int) {
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
        }
    }

    private fun startMainClockLoop() {
        mainHandler.removeCallbacks(mainClockRunnable)
        mainHandler.post(mainClockRunnable)
    }

    private fun startMainTickLoop() {
        mainHandler.removeCallbacks(mainTickRunnable)
        if (!FloatingClockService.tickSoundEnabled) return
        FloatingClockService.preloadTickSound()
        mainHandler.post(mainTickRunnable)
    }

    private fun saveThemeMode(mode: Int) {
        getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putInt(keyThemeMode, mode)
            .apply()
    }

    private fun applyMainClockPrecisionSizing() {
        val factor = when (FloatingClockService.millisDigits) {
            3 -> 0.90f
            2 -> 0.96f
            else -> 1.0f
        }
        if (baseMainHmsTextSizePx > 0f) {
            tvMainTimeHms.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseMainHmsTextSizePx * factor)
        }
        if (baseMainMillisTextSizePx > 0f) {
            tvMainTimeMillis.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseMainMillisTextSizePx * factor)
        }
    }

    private fun setupPagerHintOverlay() {
        dotPageClock = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(5)).apply {
                marginEnd = dp(5)
            }
        }
        dotPageCompass = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(5), dp(5)).apply {
                marginEnd = dp(8)
            }
        }
        tvCompassHint = TextView(this).apply {
            text = "Compass ›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }

        pagerHintPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(5), dp(12), dp(5))
            addView(dotPageClock)
            addView(dotPageCompass)
            addView(tvCompassHint)
            setOnClickListener {
                pagerContainer.snapToPage(1)
            }
        }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(68)
        }
        clockPageWrapper.addView(pagerHintPill, lp)

        tvMainTitle.post {
            val titleLoc = IntArray(2)
            val wrapLoc = IntArray(2)
            tvMainTitle.getLocationInWindow(titleLoc)
            clockPageWrapper.getLocationInWindow(wrapLoc)
            val belowTitle = ((titleLoc[1] - wrapLoc[1]) + tvMainTitle.height + dp(10)).coerceAtLeast(dp(64))
            val updated = pagerHintPill.layoutParams as FrameLayout.LayoutParams
            updated.topMargin = belowTitle
            pagerHintPill.layoutParams = updated
        }
    }

    private fun updatePagerIndicatorUI() {
        val dark = isDarkModeActive()
        val activeColor = if (dark) Color.parseColor("#D4AF37") else Color.parseColor("#1690F3")
        val inactiveColor = if (dark) Color.argb(90, 154, 162, 177) else Color.argb(90, 100, 116, 139)

        dotPageClock.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (currentPage == 0) activeColor else inactiveColor)
        }
        dotPageCompass.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(if (currentPage == 1) activeColor else inactiveColor)
        }
        tvCompassHint.setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#64748B"))

        val pillFill = if (dark) Color.argb(72, 30, 36, 48) else Color.argb(145, 255, 255, 255)
        val pillStroke = if (dark) Color.argb(70, 212, 175, 55) else Color.argb(75, 22, 144, 243)
        pagerHintPill.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) Color.argb(38, 255, 255, 255) else Color.argb(26, 22, 144, 243)),
            GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(pillFill)
                setStroke(dp(1), pillStroke)
            },
            GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
        )
    }

    private fun buildCompassPage(): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.INVISIBLE
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(18), dp(18), dp(8))
        }

        btnCompassBack = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { pagerContainer.snapToPage(0) }
        }

        tvCompassTitle = TextView(this).apply {
            text = "Compass"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnCompassLock = TextView(this).apply {
            text = "Lock"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { toggleBearingLock() }
        }

        topBar.addView(btnCompassBack)
        topBar.addView(tvCompassTitle)
        topBar.addView(btnCompassLock)
        page.addView(topBar)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(4), dp(18), dp(28))
        }

        // Hero Azimuth Readout
        tvCompassHeroDeg = TextView(this).apply {
            text = "0.0°"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        content.addView(tvCompassHeroDeg)

        tvCompassHeroDir = TextView(this).apply {
            text = "N  •  North"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(4))
        }
        content.addView(tvCompassHeroDir)

        tvCompassLockInfo = TextView(this).apply {
            text = "Tap compass dial to lock target bearing"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        content.addView(tvCompassLockInfo)

        // Interactive High-Precision Compass Dial + Bubble Level
        compassDialView = AdvancedCompassView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(268), dp(268)).apply {
                topMargin = dp(2)
                bottomMargin = dp(14)
            }
            isClickable = true
            setOnClickListener { toggleBearingLock() }
        }
        content.addView(compassDialView)

        // 2x2 Transparent Glass Telemetry Grid
        fun createGlassTelemetryCard(titleText: String): Triple<LinearLayout, TextView, Pair<TextView, TextView>> {
            val tvTitle = TextView(this).apply {
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
            }
            val tvVal = TextView(this).apply {
                text = "--"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                fontFeatureSettings = "tnum"
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, dp(2))
            }
            val tvSub = TextView(this).apply {
                text = "--"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                fontFeatureSettings = "tnum"
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(tvTitle)
                addView(tvVal)
                addView(tvSub)
            }
            return Triple(card, tvTitle, tvVal to tvSub)
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }

        val (cMag, tMag, vMag) = createGlassTelemetryCard("MAGNETIC FIELD")
        cardMagField = cMag
        tvMagTitle = tMag
        tvMagValue = vMag.first
        tvMagSub = vMag.second
        cardMagField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        }

        val (cTilt, tTilt, vTilt) = createGlassTelemetryCard("TILT & LEVEL")
        cardTiltLevel = cTilt
        tvTiltTitle = tTilt
        tvTiltValue = vTilt.first
        tvTiltSub = vTilt.second
        cardTiltLevel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(5)
        }

        row1.addView(cardMagField)
        row1.addView(cardTiltLevel)
        content.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val (cBack, tBack, vBack) = createGlassTelemetryCard("BACK AZIMUTH")
        cardBackAzimuth = cBack
        tvBackTitle = tBack
        tvBackValue = vBack.first
        tvBackSub = vBack.second
        cardBackAzimuth.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        }

        val (cAcc, tAcc, vAcc) = createGlassTelemetryCard("SENSOR STATUS")
        cardSensorAccuracy = cAcc
        tvAccTitle = tAcc
        tvAccValue = vAcc.first
        tvAccSub = vAcc.second
        cardSensorAccuracy.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(5)
        }

        row2.addView(cardBackAzimuth)
        row2.addView(cardSensorAccuracy)
        content.addView(row2)

        tvCompassCalibrationNote = TextView(this).apply {
            text = "Swipe right or press Back to return to Clock"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        content.addView(tvCompassCalibrationNote)

        scroll.addView(content)
        page.addView(scroll)
        return page
    }

    private fun toggleBearingLock() {
        compassDialView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (lockedBearingDeg == null) {
            lockedBearingDeg = compassAzimuthDeg
        } else {
            lockedBearingDeg = null
        }
        compassDialView.updateData(compassAzimuthDeg, compassPitchDeg, compassRollDeg, lockedBearingDeg)
        updateCompassTextReadouts()
    }

    private fun updateCompassTextReadouts() {
        if (!::tvCompassHeroDeg.isInitialized) return
        val dark = isDarkModeActive()

        val deg = compassAzimuthDeg
        val idx16 = (((deg + 11.25f) / 22.5f).toInt()) % 16
        tvCompassHeroDeg.text = String.format(Locale.US, "%.1f°", deg)
        tvCompassHeroDir.text = "${dirs16Short[idx16]}  •  ${dirs16Full[idx16]}"

        val locked = lockedBearingDeg
        if (locked != null) {
            val delta = ((deg - locked + 540f) % 360f) - 180f
            val sign = if (delta >= 0f) "+" else ""
            tvCompassLockInfo.text = String.format(Locale.US, "Target %d°  •  Δ %s%.0f°", locked.roundToInt(), sign, delta)
            btnCompassLock.text = "Unlock"
        } else {
            tvCompassLockInfo.text = "Tap compass dial to lock target bearing"
            btnCompassLock.text = "Lock"
        }

        // Magnetic field telemetry
        val interference = magneticStrengthUt > 0.1f && (magneticStrengthUt < 20f || magneticStrengthUt > 70f)
        tvMagValue.text = if (magneticStrengthUt > 0.1f) {
            String.format(Locale.US, "%.1f µT", magneticStrengthUt)
        } else {
            "-- µT"
        }
        val magStatus = when {
            magneticStrengthUt <= 0.1f -> "Waiting sensor"
            interference -> "Interference!"
            else -> "Normal Field"
        }
        tvMagSub.text = if (magneticStrengthUt > 0.1f) {
            String.format(Locale.US, "%s • %.0f/%.0f/%.0f", magStatus, magValues[0], magValues[1], magValues[2])
        } else {
            magStatus
        }
        tvMagValue.setTextColor(
            when {
                interference -> Color.parseColor("#F59E0B")
                dark -> Color.parseColor("#F0F4F8")
                else -> Color.parseColor("#1F2430")
            }
        )

        // Tilt & Level telemetry
        val isFlat = abs(compassPitchDeg) <= 2.0f && abs(compassRollDeg) <= 2.0f
        tvTiltValue.text = if (isFlat) "LEVEL" else String.format(Locale.US, "P %+.0f°  R %+.0f°", compassPitchDeg, compassRollDeg)
        tvTiltSub.text = String.format(Locale.US, "Pitch %+.1f° • Roll %+.1f°", compassPitchDeg, compassRollDeg)
        tvTiltValue.setTextColor(
            when {
                isFlat -> if (dark) Color.parseColor("#4ADE80") else Color.parseColor("#16A34A")
                dark -> Color.parseColor("#F0F4F8")
                else -> Color.parseColor("#1F2430")
            }
        )

        // Back Azimuth (Reciprocal 180°)
        val backDeg = (deg + 180f) % 360f
        val backIdx16 = (((backDeg + 11.25f) / 22.5f).toInt()) % 16
        tvBackValue.text = String.format(Locale.US, "%03d° %s", backDeg.roundToInt() % 360, dirs16Short[backIdx16])
        tvBackSub.text = "Reciprocal • ${dirs16Full[backIdx16]}"

        // Sensor Accuracy + Barometer if available
        val (accLabel, accColor) = when (sensorAccuracyLevel) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High" to (if (dark) Color.parseColor("#4ADE80") else Color.parseColor("#16A34A"))
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium" to (if (dark) Color.parseColor("#FBBF24") else Color.parseColor("#D97706"))
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low" to Color.parseColor("#F97316")
            else -> "Calibrate" to Color.parseColor("#EF4444")
        }
        tvAccValue.text = accLabel
        tvAccValue.setTextColor(accColor)
        tvAccSub.text = if (hasBarometer && pressureHpa > 0f) {
            String.format(Locale.US, "%s • %.1f hPa", sensorSourceLabel, pressureHpa)
        } else {
            sensorSourceLabel
        }

        tvCompassCalibrationNote.text = if (interference || sensorAccuracyLevel <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            "Move phone in a Figure-8 motion to calibrate compass"
        } else {
            "Swipe right or press Back to return to Clock"
        }
    }

    private fun updateCompassThemeUI(dark: Boolean) {
        val bgColor = if (dark) Color.parseColor("#0D121A") else Color.parseColor("#EDF3FA")
        val primaryText = if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430")
        val secondaryText = if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#64748B")
        val accentBlue = if (dark) Color.parseColor("#58A6FF") else Color.parseColor("#1690F3")
        val goldTint = if (dark) Color.parseColor("#E6C875") else Color.parseColor("#B98E2E")

        // Translucent glass card surface (frosted transparency with delicate hairline border)
        val glassFill = if (dark) Color.argb(122, 26, 33, 46) else Color.argb(175, 255, 255, 255)
        val glassStroke = if (dark) Color.argb(66, 212, 175, 55) else Color.argb(82, 148, 163, 184)

        compassPageRoot.setBackgroundColor(bgColor)
        btnCompassBack.setImageDrawable(createBackIconDrawable(primaryText))
        tvCompassTitle.setTextColor(primaryText)
        tvCompassHeroDeg.setTextColor(primaryText)
        tvCompassHeroDir.setTextColor(accentBlue)
        tvCompassLockInfo.setTextColor(secondaryText)
        tvCompassLockInfo.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(glassFill)
            setStroke(dp(1), glassStroke)
        }

        btnCompassLock.setTextColor(goldTint)
        btnCompassLock.background = RippleDrawable(
            ColorStateList.valueOf(if (dark) Color.argb(38, 255, 255, 255) else Color.argb(26, 22, 144, 243)),
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(glassFill)
                setStroke(dp(1), glassStroke)
            },
            GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.WHITE)
            }
        )

        fun applyGlassCard(card: LinearLayout, title: TextView, value: TextView, sub: TextView) {
            card.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(glassFill)
                setStroke(dp(1), glassStroke)
            }
            title.setTextColor(goldTint)
            value.setTextColor(primaryText)
            sub.setTextColor(secondaryText)
        }

        applyGlassCard(cardMagField, tvMagTitle, tvMagValue, tvMagSub)
        applyGlassCard(cardTiltLevel, tvTiltTitle, tvTiltValue, tvTiltSub)
        applyGlassCard(cardBackAzimuth, tvBackTitle, tvBackValue, tvBackSub)
        applyGlassCard(cardSensorAccuracy, tvAccTitle, tvAccValue, tvAccSub)

        tvCompassCalibrationNote.setTextColor(secondaryText)
        compassDialView.setDarkTheme(dark)
        updateCompassTextReadouts()
    }

    private fun setupPrecisionButton() {
        btnTogglePrecision = Button(this)
        btnToggleFloatStyle = Button(this)

        val parent = btnToggleSource.parent as? ViewGroup ?: return
        if (parent is LinearLayout) {
            val sourceIdx = parent.indexOfChild(btnToggleSource)
            val metricsIdx = parent.indexOfChild(btnToggleMetrics)
            if (sourceIdx >= 0 && metricsIdx > sourceIdx) {
                for (i in (metricsIdx - 1) downTo (sourceIdx + 1)) {
                    parent.removeViewAt(i)
                }
            }

            val refLp = btnToggleSource.layoutParams as? LinearLayout.LayoutParams
            val uniformHeight = if (refLp != null && refLp.height > 0) refLp.height else dp(46)
            val topM = refLp?.topMargin ?: 0
            val bottomM = refLp?.bottomMargin ?: 0

            fun styleSecondaryControl(btn: Button, startMarginDp: Int, endMarginDp: Int, textSp: Float = 11.5f) {
                btn.isAllCaps = false
                btn.setSingleLine(true)
                btn.ellipsize = TextUtils.TruncateAt.END
                btn.gravity = Gravity.CENTER
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                btn.typeface = btnToggleSource.typeface
                btn.minWidth = 0
                btn.minimumWidth = 0
                btn.minHeight = 0
                btn.minimumHeight = 0
                btn.elevation = 0f
                btn.stateListAnimator = null
                btn.setPadding(dp(2), 0, dp(2), 0)
                btn.layoutParams = LinearLayout.LayoutParams(0, uniformHeight, 1f).apply {
                    topMargin = topM
                    bottomMargin = bottomM
                    marginStart = dp(startMarginDp)
                    marginEnd = dp(endMarginDp)
                }
            }

            styleSecondaryControl(btnToggleSource, startMarginDp = 0, endMarginDp = 3)
            styleSecondaryControl(btnTogglePrecision, startMarginDp = 3, endMarginDp = 3)
            styleSecondaryControl(btnToggleMetrics, startMarginDp = 3, endMarginDp = 3)
            styleSecondaryControl(btnToggleFloatStyle, startMarginDp = 3, endMarginDp = 0)

            btnToggleSource.isClickable = false
            btnToggleSource.isFocusable = false

            val insertIndex = (parent.indexOfChild(btnToggleSource) + 1).coerceIn(0, parent.childCount)
            parent.addView(btnTogglePrecision, insertIndex)
            parent.addView(btnToggleFloatStyle, (parent.indexOfChild(btnToggleMetrics) + 1).coerceIn(0, parent.childCount))
        } else {
            parent.addView(btnTogglePrecision)
            parent.addView(btnToggleFloatStyle)
        }
    }

    private fun setupThemeToggleButton() {
        btnThemeToggle = ImageButton(this).apply {
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Toggle Theme"
        }

        val initialParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = dp(18)
            topMargin = dp(22)
        }
        clockPageWrapper.addView(btnThemeToggle, initialParams)

        btnSettings.post {
            val gearLoc = IntArray(2)
            val wrapLoc = IntArray(2)
            btnSettings.getLocationInWindow(gearLoc)
            clockPageWrapper.getLocationInWindow(wrapLoc)

            val topOffset = (gearLoc[1] - wrapLoc[1]).coerceAtLeast(0)
            val gearRightInWrap = (gearLoc[0] - wrapLoc[0]) + btnSettings.width
            val mirroredStartMargin = (clockPageWrapper.width - gearRightInWrap).coerceAtLeast(dp(12))

            val lp = btnThemeToggle.layoutParams as FrameLayout.LayoutParams
            lp.width = if (btnSettings.width > 0) btnSettings.width else dp(48)
            lp.height = if (btnSettings.height > 0) btnSettings.height else dp(48)
            lp.topMargin = topOffset
            lp.marginStart = mirroredStartMargin
            btnThemeToggle.setPadding(
                btnSettings.paddingLeft,
                btnSettings.paddingTop,
                btnSettings.paddingRight,
                btnSettings.paddingBottom
            )
            btnThemeToggle.layoutParams = lp
        }
    }

    private fun showStatusPopup(label: String) {
        val dark = isDarkModeActive()

        val popup = themePopupView ?: TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(9), dp(18), dp(9))
            elevation = dp(4).toFloat()
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            clockPageWrapper.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
        }.also { themePopupView = it }

        val wrapLoc = IntArray(2)
        val titleLoc = IntArray(2)
        clockPageWrapper.getLocationInWindow(wrapLoc)
        tvMainTitle.getLocationInWindow(titleLoc)
        val topMarginPx = if (tvMainTitle.height > 0) {
            ((titleLoc[1] - wrapLoc[1]) + tvMainTitle.height + dp(44)).coerceAtLeast(dp(96))
        } else {
            dp(96)
        }

        val lp = popup.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = topMarginPx
        popup.layoutParams = lp

        popup.text = label
        popup.setTextColor(if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430"))
        popup.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(if (dark) Color.argb(215, 30, 36, 48) else Color.argb(225, 255, 255, 255))
            setStroke(
                dp(1),
                if (dark) Color.argb(42, 255, 255, 255) else Color.argb(34, 31, 36, 48)
            )
        }

        hidePopupRunnable?.let { mainHandler.removeCallbacks(it) }
        popup.animate().cancel()
        popup.visibility = View.VISIBLE
        popup.alpha = 0f
        popup.translationY = -dp(6).toFloat()
        popup.bringToFront()

        popup.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()

        val hideTask = Runnable {
            popup.animate()
                .alpha(0f)
                .translationY(-dp(6).toFloat())
                .setDuration(200L)
                .withEndAction { popup.visibility = View.GONE }
                .start()
        }
        hidePopupRunnable = hideTask
        mainHandler.postDelayed(hideTask, 1500L)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        btnThemeToggle.setOnClickListener {
            currentThemeMode = if (isDarkModeActive()) 0 else 1
            saveThemeMode(currentThemeMode)
            updateThemeUI()
            val label = if (currentThemeMode == 1) getString(R.string.theme_dark) else getString(R.string.theme_light)
            showStatusPopup(label)
        }

        btnThemeToggle.setOnLongClickListener {
            currentThemeMode = 2
            saveThemeMode(currentThemeMode)
            updateThemeUI()
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showStatusPopup("Adaptive Mode")
            true
        }

        btnToggleFloating.setOnClickListener {
            if (FloatingClockService.isRunning) {
                stopFloatingClockService()
            } else {
                checkOverlayAndStart()
            }
        }

        btnTogglePrecision.setOnClickListener {
            val nextDigits = when (FloatingClockService.millisDigits) {
                1 -> 2
                2 -> 3
                else -> 1
            }
            FloatingClockService.setMillisDigits(this, nextDigits)
            applyMainClockPrecisionSizing()

            if (FloatingClockService.isRunning) {
                val intent = Intent(this, FloatingClockService::class.java).apply {
                    action = FloatingClockService.ACTION_UPDATE_PRECISION
                }
                startService(intent)
            }
            if (isAnalogMode) refreshHomeAnalogClock()
            updateUiState()
            showStatusPopup("Millisecond: $nextDigits Digit")
        }

        btnToggleMetrics.setOnClickListener {
            if (FloatingClockService.isRunning) {
                val intent = Intent(this, FloatingClockService::class.java).apply {
                    action = FloatingClockService.ACTION_TOGGLE_METRICS
                }
                startService(intent)
            } else {
                FloatingClockService.showMetrics = !FloatingClockService.showMetrics
            }
            btnToggleMetrics.postDelayed({ updateUiState() }, 100)
        }

        btnToggleFloatStyle.setOnClickListener {
            val nextAnalog = !FloatingClockService.useAnalogFloating
            FloatingClockService.setAnalogFloating(this, nextAnalog)
            if (FloatingClockService.isRunning) {
                val intent = Intent(this, FloatingClockService::class.java).apply {
                    action = FloatingClockService.ACTION_TOGGLE_FLOAT_STYLE
                }
                startService(intent)
            }
            updateUiState()
            showStatusPopup(if (nextAnalog) "Analog Clock" else "Digital Clock")
        }

        btnSettings.setOnClickListener {
            showSettingsMenuBottomSheet()
        }

        cardClockCircle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                lastClockTouchX = event.x
                lastClockTouchY = event.y
            }
            false
        }

        cardClockCircle.setOnClickListener {
            if (isAnalogMode && analogClockDrawable.isTouchOnFormatBadge(lastClockTouchX, lastClockTouchY)) {
                val next24h = !FloatingClockService.use24HourFormat
                FloatingClockService.setUse24HourFormat(this, next24h)
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (FloatingClockService.isRunning) {
                    val intent = Intent(this, FloatingClockService::class.java).apply {
                        action = FloatingClockService.ACTION_TOGGLE_TIME_FORMAT
                    }
                    startService(intent)
                }
                refreshHomeAnalogClock()
                updateUiState()
                showStatusPopup(if (next24h) "Time Format: 24H" else "Time Format: 12H")
                return@setOnClickListener
            }

            isAnalogMode = !isAnalogMode
            if (isAnalogMode) {
                layoutDigitalClock.visibility = View.GONE
                viewAnalogClock.visibility = View.VISIBLE
                refreshHomeAnalogClock()
            } else {
                layoutDigitalClock.visibility = View.VISIBLE
                viewAnalogClock.visibility = View.GONE
            }
        }
    }

    private fun isDarkModeActive(): Boolean {
        return when (currentThemeMode) {
            0 -> false
            1 -> true
            2 -> {
                val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags == Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsTheme(targetWindow: Window?, bgColor: Int, dark: Boolean) {
        targetWindow ?: return
        targetWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        targetWindow.statusBarColor = bgColor
        targetWindow.navigationBarColor = bgColor

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (dark) {
                0
            } else {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            }
            targetWindow.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            var flags = targetWindow.decorView.systemUiVisibility
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (dark) {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            targetWindow.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun applyNavBarTheme(targetWindow: Window?, navColor: Int, dark: Boolean) {
        targetWindow ?: return
        targetWindow.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        targetWindow.navigationBarColor = navColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            targetWindow.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var flags = targetWindow.decorView.systemUiVisibility
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            targetWindow.decorView.systemUiVisibility = flags
        }
    }

    private fun updateThemeUI() {
        val dark = isDarkModeActive()
        val bgColor = if (dark) Color.parseColor("#12161F") else Color.parseColor("#F3F7FA")
        val textColor = if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430")
        val secondaryTextColor = if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#8C93A4")
        val circleBgColor = if (dark) Color.parseColor("#1E2430") else Color.parseColor("#FFFFFF")
        val circleStrokeColor = if (dark) Color.parseColor("#2D3548") else Color.parseColor("#E8EEF7")

        window.decorView.setBackgroundColor(bgColor)
        rootContainer.setBackgroundColor(bgColor)
        clockPageWrapper.setBackgroundColor(bgColor)
        applySystemBarsTheme(window, bgColor, dark)

        tvMainTitle.setTextColor(textColor)
        tvMainTimeHms.setTextColor(textColor)
        tvMainTimeMillis.setTextColor(millisRedColor)
        tvMainDate.setTextColor(textColor)
        tvMainTimeSource.setTextColor(secondaryTextColor)

        val secBtnBgHex = if (dark) "#1E2430" else "#FFFFFF"
        val secBtnStrokeHex = if (dark) "#2D3548" else "#D8E2EE"
        val secBtnTextColor = if (dark) Color.parseColor("#58A6FF") else Color.parseColor("#1690F3")

        btnToggleSource.backgroundTintList = null
        btnToggleSource.background = roundedCardBg(secBtnBgHex, 10, secBtnStrokeHex)
        btnToggleSource.setTextColor(secBtnTextColor)

        btnTogglePrecision.backgroundTintList = null
        btnTogglePrecision.background = createSolidRippleCardBg(secBtnBgHex, 10, secBtnStrokeHex, dark)
        btnTogglePrecision.setTextColor(secBtnTextColor)

        btnToggleMetrics.backgroundTintList = null
        btnToggleMetrics.background = createSolidRippleCardBg(secBtnBgHex, 10, secBtnStrokeHex, dark)
        btnToggleMetrics.setTextColor(secBtnTextColor)

        btnToggleFloatStyle.backgroundTintList = null
        btnToggleFloatStyle.background = createSolidRippleCardBg(secBtnBgHex, 10, secBtnStrokeHex, dark)
        btnToggleFloatStyle.setTextColor(secBtnTextColor)

        cardClockCircle.background = object : Drawable() {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                p.style = Paint.Style.FILL
                p.color = circleStrokeColor
                canvas.drawCircle(w / 2, h / 2, w / 2, p)
                p.color = circleBgColor
                canvas.drawCircle(w / 2, h / 2, (w / 2) - dp(10), p)
            }
            override fun setAlpha(alpha: Int) { p.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        analogClockDrawable.setDarkTheme(dark)

        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)

        btnSettings.backgroundTintList = null
        btnSettings.setBackgroundResource(outValue.resourceId)
        btnSettings.setImageDrawable(createGearDrawable(dark))

        btnThemeToggle.backgroundTintList = null
        btnThemeToggle.setBackgroundResource(outValue.resourceId)
        btnThemeToggle.setImageDrawable(createThemeIconDrawable(dark))

        updatePagerIndicatorUI()
        updateCompassThemeUI(dark)
    }

    private fun showSettingsMenuBottomSheet() {
        val dark = isDarkModeActive()
        val sheetBgColor = if (dark) Color.parseColor("#1E2430") else Color.WHITE

        val sheet = Dialog(this)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sheet.setCanceledOnTouchOutside(true)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(sheetBgColor)
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        val dragHandle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            background = GradientDrawable().apply {
                setColor(if (dark) Color.parseColor("#3B4559") else Color.parseColor("#D5DCE6"))
                cornerRadius = dp(4).toFloat()
            }
        }
        container.addView(dragHandle)

        val titleView = TextView(this).apply {
            text = getString(R.string.menu_title_settings)
            setTextColor(if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(14))
        }
        container.addView(titleView)

        val menuCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCardBg(
                if (dark) "#161B24" else "#F6F8FC",
                14,
                if (dark) "#2D3548" else "#E2E8F0"
            )
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }

        val rippleColor = if (dark) Color.argb(38, 255, 255, 255) else Color.argb(26, 22, 144, 243)
        val tickBadge = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumWidth = dp(52)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        val tickSubView = TextView(this).apply {
            setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#8C93A4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }

        fun refreshTickRowUI() {
            val enabled = FloatingClockService.tickSoundEnabled
            if (enabled) {
                tickBadge.text = "ON"
                tickBadge.setTextColor(Color.WHITE)
                tickBadge.background = roundedBg("#1690F3", 14)
                tickSubView.text = "Active • Zero-delay quartz tick"
            } else {
                tickBadge.text = "OFF"
                tickBadge.setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#64748B"))
                tickBadge.background = roundedBg(if (dark) "#263042" else "#E2E8F0", 14)
                tickSubView.text = "Muted • Tap to enable"
            }
        }
        refreshTickRowUI()

        val tickSoundRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                null,
                GradientDrawable().apply { setColor(Color.WHITE) }
            )

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(10)
                }
                addView(TextView(context).apply {
                    text = "Clock Tick Sound"
                    setTextColor(if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(tickSubView)
            }

            addView(textCol)
            addView(tickBadge)

            setOnClickListener {
                val next = !FloatingClockService.tickSoundEnabled
                FloatingClockService.setTickSoundEnabled(this@MainActivity, next)
                refreshTickRowUI()

                if (next) {
                    FloatingClockService.playTickNow()
                    startMainTickLoop()
                } else {
                    mainHandler.removeCallbacks(mainTickRunnable)
                }

                if (FloatingClockService.isRunning) {
                    val intent = Intent(this@MainActivity, FloatingClockService::class.java).apply {
                        action = FloatingClockService.ACTION_UPDATE_TICK_SOUND
                    }
                    startService(intent)
                }
            }
        }
        menuCard.addView(tickSoundRow)

        menuCard.addView(createDivider(marginHorizontalDp = 16))

        menuCard.addView(
            createMenuRow(getString(R.string.menu_permission_settings), "") {
                sheet.dismiss()
                showPermissionSettingsDialog()
            }
        )

        menuCard.addView(createDivider(marginHorizontalDp = 16))

        menuCard.addView(
            createMenuRow(getString(R.string.menu_about_app), "") {
                sheet.dismiss()
                showAboutAppDialog()
            }
        )

        container.addView(menuCard)
        sheet.setContentView(container)

        sheet.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.decorView.setPadding(0, 0, 0, 0)
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.BOTTOM)
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.45f)
            applyNavBarTheme(win, sheetBgColor, dark)
        }

        container.translationY = dp(180).toFloat()
        container.alpha = 0.6f
        sheet.setOnShowListener {
            container.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(190L)
                .start()
        }
        sheet.show()
    }

    private fun createMenuRow(label: String, subLabel: String, onClick: () -> Unit): LinearLayout {
        val dark = isDarkModeActive()
        val rippleColor = if (dark) Color.argb(38, 255, 255, 255) else Color.argb(26, 22, 144, 243)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), null, GradientDrawable().apply {
                setColor(Color.WHITE)
            })

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvLabel = TextView(context).apply {
                text = label
                setTextColor(if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
            }
            textCol.addView(tvLabel)

            if (subLabel.isNotEmpty()) {
                val tvSub = TextView(context).apply {
                    text = subLabel
                    setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#8C93A4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                }
                textCol.addView(tvSub)
            }

            val tvArrow = TextView(context).apply {
                text = "›"
                setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#8C93A4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            }

            addView(textCol)
            addView(tvArrow)
            setOnClickListener { onClick() }
        }
    }

    @SuppressLint("BatteryLife")
    private fun showPermissionSettingsDialog() {
        val dark = isDarkModeActive()
        val bgColor = if (dark) Color.parseColor("#12161F") else Color.parseColor("#F3F7FA")
        val primaryText = if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430")
        val dialogTheme = if (dark) {
            android.R.style.Theme_Material_NoActionBar
        } else {
            android.R.style.Theme_Material_Light_NoActionBar
        }
        val dialog = Dialog(this, dialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val backTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(20), dp(4))
            addView(ImageView(context).apply {
                setImageDrawable(createBackIconDrawable(primaryText))
                scaleType = ImageView.ScaleType.CENTER
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { dialog.dismiss() }
            })
        }
        root.addView(backTopBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(28))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(16))
        }
        val ivLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_clock_logo)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        }
        val welcomeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        welcomeCol.addView(TextView(this).apply {
            text = getString(R.string.perm_welcome)
            setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#6E7787"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        welcomeCol.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        })
        headerRow.addView(ivLogo)
        headerRow.addView(welcomeCol)
        content.addView(headerRow)

        content.addView(TextView(this).apply {
            text = getString(R.string.menu_permission_settings)
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.perm_subtitle)
            setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#4A5260"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(14))
        })

        val unifiedCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedCardBg(if (dark) "#1E2430" else "#FFFFFF", 16, if (dark) "#2D3548" else "#E2E8F0")
            setPadding(dp(16), dp(4), dp(16), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val overlayStatusBtn = TextView(this)
        val batteryStatusBtn = TextView(this)

        unifiedCard.addView(
            createPermissionItemView(
                title = getString(R.string.perm_overlay_title),
                desc = getString(R.string.perm_overlay_desc),
                required = true,
                actionView = overlayStatusBtn
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                launchIntentForResult(intent, REQ_SETTINGS_REFRESH)
            }
        )

        unifiedCard.addView(createDivider())

        unifiedCard.addView(
            createPermissionItemView(
                title = getString(R.string.perm_battery_title),
                desc = getString(R.string.perm_battery_desc),
                required = false,
                actionView = batteryStatusBtn
            ) {
                val currentlyGranted = isBatteryOptimizationIgnored()
                if (currentlyGranted) {
                    try {
                        val disableIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        launchIntentForResult(disableIntent, REQ_SETTINGS_REFRESH)
                    } catch (_: Exception) {
                        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        launchIntentForResult(fallback, REQ_SETTINGS_REFRESH)
                    }
                } else {
                    try {
                        val enableIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        launchIntentForResult(enableIntent, REQ_SETTINGS_REFRESH)
                    } catch (_: Exception) {
                        try {
                            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            launchIntentForResult(listIntent, REQ_SETTINGS_REFRESH)
                        } catch (_: Exception) {
                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            launchIntentForResult(fallback, REQ_SETTINGS_REFRESH)
                        }
                    }
                }
            }
        )

        content.addView(unifiedCard)

        val refreshStatuses = {
            val overlayGranted = Settings.canDrawOverlays(this)
            stylePermissionActionView(overlayStatusBtn, overlayGranted)

            val batteryGranted = isBatteryOptimizationIgnored()
            stylePermissionActionView(batteryStatusBtn, batteryGranted)
        }

        permissionDialogRefreshAction = refreshStatuses
        refreshStatuses()

        dialog.setOnDismissListener { permissionDialogRefreshAction = null }
        scroll.addView(content)
        root.addView(scroll)
        dialog.setContentView(root)
        applySystemBarsTheme(dialog.window, bgColor, dark)
        dialog.show()
    }

    private fun showAboutAppDialog() {
        val dark = isDarkModeActive()
        val bgColor = if (dark) Color.parseColor("#12161F") else Color.parseColor("#F3F7FA")
        val cardBgHex = if (dark) "#1E2430" else "#FFFFFF"
        val cardStrokeHex = if (dark) "#2D3548" else "#E2E8F0"
        val primaryText = if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430")
        val secondaryText = if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#5A6270")
        val accentColor = if (dark) Color.parseColor("#58A6FF") else Color.parseColor("#1690F3")

        val dialogTheme = if (dark) {
            android.R.style.Theme_Material_NoActionBar
        } else {
            android.R.style.Theme_Material_Light_NoActionBar
        }
        val dialog = Dialog(this, dialogTheme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(createTopBar(getString(R.string.about_title)) {
            dialog.dismiss()
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(32), dp(20), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val ivLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_clock_logo)
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        content.addView(ivLogo)

        @Suppress("DEPRECATION")
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        content.addView(TextView(this).apply {
            text = "${getString(R.string.app_name)} (v$versionName)"
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(28))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        val aboutCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedCardBg(cardBgHex, 20, cardStrokeHex)
            setPadding(dp(22), dp(24), dp(22), dp(28))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        aboutCard.addView(TextView(this).apply {
            text = getString(R.string.about_card_header)
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        aboutCard.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(1)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(14)
            }
            setBackgroundColor(if (dark) Color.parseColor("#2D3548") else Color.parseColor("#D6E4FF"))
        })

        aboutCard.addView(TextView(this).apply {
            text = getString(R.string.about_app_desc)
            setTextColor(secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setPadding(dp(8), 0, dp(8), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        val developerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            minimumWidth = dp(104)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(developerUrl)))
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, developerUrl, Toast.LENGTH_SHORT).show()
                }
            }
        }

        val devIconFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (dark) Color.parseColor("#252D3D") else Color.parseColor("#F5F8FC"))
                setStroke(dp(2), if (dark) Color.parseColor("#3B465E") else Color.parseColor("#D6E4FF"))
            }
        }
        devIconFrame.addView(ImageView(this).apply {
            setImageDrawable(createDeveloperIconDrawable(accentColor))
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
        })

        developerContainer.addView(devIconFrame)
        developerContainer.addView(TextView(this).apply {
            text = getString(R.string.about_dev_label)
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })

        aboutCard.addView(developerContainer)
        content.addView(aboutCard)

        scroll.addView(content)
        root.addView(scroll)
        dialog.setContentView(root)
        applySystemBarsTheme(dialog.window, bgColor, dark)
        dialog.show()
    }

    private fun createTopBar(title: String, onBack: () -> Unit): LinearLayout {
        val dark = isDarkModeActive()
        val primaryText = if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(16), dp(10))

            val btnBack = ImageView(context).apply {
                setImageDrawable(createBackIconDrawable(primaryText))
                scaleType = ImageView.ScaleType.CENTER
                isClickable = true
                isFocusable = true
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setOnClickListener { onBack() }
            }

            val tvTitle = TextView(context).apply {
                text = title
                setTextColor(primaryText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val rightSpacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }

            addView(btnBack)
            addView(tvTitle)
            addView(rightSpacer)
        }
    }

    private fun createPermissionItemView(
        title: String,
        desc: String,
        required: Boolean,
        actionView: TextView,
        onActionClick: () -> Unit
    ): LinearLayout {
        val dark = isDarkModeActive()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(84)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(4), dp(12), dp(4), dp(12))

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(context).apply {
                text = title
                setTextColor(if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val tvDesc = TextView(context).apply {
                text = desc
                setTextColor(if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#8C93A4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.05f)
                setPadding(0, dp(4), 0, 0)
            }

            textCol.addView(tvTitle)
            textCol.addView(tvDesc)

            val verticalDivider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(42)).apply {
                    marginStart = dp(12)
                    marginEnd = dp(12)
                }
                setBackgroundColor(Color.parseColor(if (dark) "#2D3548" else "#E2E8F0"))
            }

            val actionCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            actionView.layoutParams = LinearLayout.LayoutParams(dp(80), dp(32))
            actionView.gravity = Gravity.CENTER
            actionView.setSingleLine(true)
            actionView.setOnClickListener { onActionClick() }

            val tvRequirement = TextView(context).apply {
                text = if (required) getString(R.string.perm_badge_required) else "Optional"
                setTextColor(
                    if (required) {
                        if (dark) Color.parseColor("#58A6FF") else Color.parseColor("#2B99FF")
                    } else {
                        if (dark) Color.parseColor("#8C96A8") else Color.parseColor("#8C93A4")
                    }
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER
                setSingleLine(true)
                isClickable = false
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                }
            }

            actionCol.addView(actionView)
            actionCol.addView(tvRequirement)

            addView(textCol)
            addView(verticalDivider)
            addView(actionCol)
        }
    }

    private fun stylePermissionActionView(view: TextView, granted: Boolean) {
        val dark = isDarkModeActive()
        if (granted) {
            view.text = "Disable"
            view.setTextColor(if (dark) Color.parseColor("#FF7A7A") else Color.parseColor("#E53935"))
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            view.typeface = Typeface.DEFAULT_BOLD
            view.background = createSolidRippleCardBg(
                hexColor = if (dark) "#2A1F26" else "#FFF2F2",
                radiusDp = 16,
                strokeHex = if (dark) "#5A333A" else "#F5C6C6",
                dark = dark
            )
            view.isClickable = true
        } else {
            view.text = getString(R.string.perm_btn_enable)
            view.setTextColor(Color.WHITE)
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            view.typeface = Typeface.DEFAULT_BOLD
            view.background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(46, 255, 255, 255)),
                roundedBg("#1690F3", 16),
                roundedBg("#FFFFFF", 16)
            )
            view.isClickable = true
        }
    }

    private fun createDivider(marginHorizontalDp: Int = 0): View {
        val dark = isDarkModeActive()
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                if (marginHorizontalDp > 0) {
                    marginStart = dp(marginHorizontalDp)
                    marginEnd = dp(marginHorizontalDp)
                }
            }
            setBackgroundColor(Color.parseColor(if (dark) "#2D3548" else "#EEF2F8"))
        }
    }

    private fun createSolidRippleCardBg(hexColor: String, radiusDp: Int, strokeHex: String, dark: Boolean): Drawable {
        val content = roundedCardBg(hexColor, radiusDp, strokeHex)
        val mask = roundedBg("#FFFFFF", radiusDp)
        val rippleColor = if (dark) Color.argb(38, 255, 255, 255) else Color.argb(26, 22, 144, 243)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun roundedBg(hexColor: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun roundedCardBg(hexColor: String, radiusDp: Int, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(hexColor))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun createDeveloperIconDrawable(colorInt: Int): Drawable {
        val strokePx = 2.4f * resources.displayMetrics.density
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val path = Path()

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()

                path.reset()
                path.moveTo(cx - w * 0.11f, cy - h * 0.25f)
                path.lineTo(cx - w * 0.37f, cy)
                path.lineTo(cx - w * 0.11f, cy + h * 0.25f)

                path.moveTo(cx + w * 0.11f, cy - h * 0.25f)
                path.lineTo(cx + w * 0.37f, cy)
                path.lineTo(cx + w * 0.11f, cy + h * 0.25f)

                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createThemeIconDrawable(dark: Boolean): Drawable {
        val strokePx = 2.2f * resources.displayMetrics.density
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#2C3240")
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val r = (bounds.width().coerceAtMost(bounds.height()) / 2f) * 0.78f

                if (dark) {
                    val mainCircle = Path().apply {
                        addCircle(cx, cy, r * 0.86f, Path.Direction.CW)
                    }
                    val cutCircle = Path().apply {
                        addCircle(cx + r * 0.42f, cy - r * 0.28f, r * 0.73f, Path.Direction.CW)
                    }
                    val moonPath = Path()
                    moonPath.op(mainCircle, cutCircle, Path.Op.DIFFERENCE)
                    canvas.drawPath(moonPath, paint)
                } else {
                    val sunR = r * 0.45f
                    val rayStart = r * 0.72f
                    val rayEnd = r * 1.02f
                    canvas.drawCircle(cx, cy, sunR, paint)
                    for (i in 0 until 8) {
                        val angle = Math.toRadians((i * 45).toDouble())
                        val x1 = cx + (rayStart * cos(angle)).toFloat()
                        val y1 = cy + (rayStart * sin(angle)).toFloat()
                        val x2 = cx + (rayEnd * cos(angle)).toFloat()
                        val y2 = cy + (rayEnd * sin(angle)).toFloat()
                        canvas.drawLine(x1, y1, x2, y2, paint)
                    }
                }
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createGearDrawable(dark: Boolean): Drawable {
        val strokePx = 2.2f * resources.displayMetrics.density
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.parseColor("#9AA2B1") else Color.parseColor("#2C3240")
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val outerR = (bounds.width().coerceAtMost(bounds.height()) / 2f) * 0.78f
                val innerR = outerR * 0.46f
                val toothStart = outerR * 0.82f
                val toothEnd = outerR * 1.12f

                canvas.drawCircle(cx, cy, innerR, paint)
                canvas.drawCircle(cx, cy, toothStart, paint)

                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val x1 = cx + (toothStart * cos(angle)).toFloat()
                    val y1 = cy + (toothStart * sin(angle)).toFloat()
                    val x2 = cx + (toothEnd * cos(angle)).toFloat()
                    val y2 = cy + (toothEnd * sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createBackIconDrawable(colorInt: Int): Drawable {
        val sizePx = dp(20)
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val path = Path()

            override fun getIntrinsicWidth(): Int = sizePx
            override fun getIntrinsicHeight(): Int = sizePx

            override fun draw(canvas: Canvas) {
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val halfH = bounds.height() * 0.30f
                val halfW = bounds.width() * 0.17f

                path.reset()
                path.moveTo(cx + halfW, cy - halfH)
                path.lineTo(cx - halfW, cy)
                path.lineTo(cx + halfW, cy + halfH)
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    // Smooth 100% native 2-page horizontal swipe container
    private inner class SwipePagerContainer(
        context: Context,
        private val page0: View,
        private val page1: View,
        private val onPageSettled: (Int) -> Unit
    ) : FrameLayout(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val interpolator = DecelerateInterpolator(1.8f)
        private var activePage = 0
        private var isDragging = false
        private var downX = 0f
        private var downY = 0f
        private var dragStartOffset = 0f
        private var currentOffset = 0f
        private var velocityTracker: VelocityTracker? = null

        init {
            addView(page0)
            addView(page1)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed && !isDragging) {
                val w = width.toFloat()
                if (w > 0f) {
                    currentOffset = if (activePage == 1) -w else 0f
                    applyOffset(currentOffset)
                }
            }
        }

        private fun applyOffset(offsetPx: Float) {
            val w = width.toFloat()
            if (w <= 0f) return
            val clamped = offsetPx.coerceIn(-w, 0f)
            currentOffset = clamped
            page0.translationX = clamped
            page1.translationX = w + clamped
            page0.visibility = if (clamped <= -w + 1f) View.INVISIBLE else View.VISIBLE
            page1.visibility = if (clamped >= -1f) View.INVISIBLE else View.VISIBLE
        }

        fun snapToPage(targetPage: Int) {
            val w = width.toFloat()
            if (w <= 0f) {
                activePage = targetPage
                onPageSettled(targetPage)
                return
            }
            val targetOffset = if (targetPage == 1) -w else 0f
            page0.visibility = View.VISIBLE
            page1.visibility = View.VISIBLE
            page0.animate().cancel()
            page1.animate().cancel()

            page0.animate()
                .translationX(targetOffset)
                .setDuration(240L)
                .setInterpolator(interpolator)
                .start()

            page1.animate()
                .translationX(w + targetOffset)
                .setDuration(240L)
                .setInterpolator(interpolator)
                .withEndAction {
                    currentOffset = targetOffset
                    activePage = targetPage
                    page0.visibility = if (targetPage == 1) View.INVISIBLE else View.VISIBLE
                    page1.visibility = if (targetPage == 0) View.INVISIBLE else View.VISIBLE
                    onPageSettled(targetPage)
                }
                .start()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x
                    downY = ev.y
                    isDragging = false
                    dragStartOffset = currentOffset
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(ev)
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val canSlideDir = (activePage == 0 && dx < 0f) || (activePage == 1 && dx > 0f)
                    if (canSlideDir && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.35f) {
                        isDragging = true
                        page0.animate().cancel()
                        page1.animate().cancel()
                        page0.visibility = View.VISIBLE
                        page1.visibility = View.VISIBLE
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocityTracker?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dx = ev.x - downX
                        applyOffset(dragStartOffset + dx)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        val w = width.toFloat().coerceAtLeast(1f)
                        velocityTracker?.computeCurrentVelocity(1000)
                        val vx = velocityTracker?.xVelocity ?: 0f
                        velocityTracker?.recycle()
                        velocityTracker = null

                        val progress = (-currentOffset) / w
                        val target = when {
                            vx < -450f -> 1
                            vx > 450f -> 0
                            progress > 0.38f -> 1
                            else -> 0
                        }
                        snapToPage(target)
                        return true
                    }
                }
            }
            return super.onTouchEvent(ev)
        }
    }

    // High-Precision Translucent Luxury Compass + 2D Spirit Bubble Level
    private inner class AdvancedCompassView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val sansBold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val sansSlim = Typeface.create("sans-serif", Typeface.NORMAL)

        private var azimuthDeg = 0f
        private var pitchDeg = 0f
        private var rollDeg = 0f
        private var lockedBearing: Float? = null
        private var dark = true

        private val glassBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val goldRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
            textAlign = Paint.Align.CENTER
            fontFeatureSettings = "tnum"
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pathTmp = Path()
        private var cachedR = -1f
        private var cachedDark = true

        fun setDarkTheme(isDark: Boolean) {
            if (dark != isDark) {
                dark = isDark
                cachedR = -1f
                invalidate()
            }
        }

        fun updateData(azimuthDeg: Float, pitchDeg: Float, rollDeg: Float, lockedBearing: Float?) {
            this.azimuthDeg = azimuthDeg
            this.pitchDeg = pitchDeg
            this.rollDeg = rollDeg
            this.lockedBearing = lockedBearing
            invalidate()
        }

        private fun ensureGoldShader(cx: Float, cy: Float, r: Float) {
            if (abs(r - cachedR) < 0.5f && dark == cachedDark) return
            cachedR = r
            cachedDark = dark
            val colors = if (dark) {
                intArrayOf(
                    Color.parseColor("#FCE6A8"),
                    Color.parseColor("#D4AF37"),
                    Color.parseColor("#8A6623"),
                    Color.parseColor("#EED082")
                )
            } else {
                intArrayOf(
                    Color.parseColor("#E6C574"),
                    Color.parseColor("#B98E2E"),
                    Color.parseColor("#7E5B18"),
                    Color.parseColor("#D9B55A")
                )
            }
            goldRingPaint.shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                colors, null, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val cx = w / 2f
            val cy = h / 2f
            val outerR = (w.coerceAtMost(h) / 2f) - 10f * d
            if (outerR <= 0f) return

            ensureGoldShader(cx, cy, outerR)

            val primaryText = if (dark) Color.parseColor("#F1F5F9") else Color.parseColor("#1E293B")
            val secondaryText = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")
            val accentRed = Color.parseColor("#FF3B1D")
            val accentGold = if (dark) Color.parseColor("#EED082") else Color.parseColor("#B98E2E")
            val levelGreen = if (dark) Color.parseColor("#4ADE80") else Color.parseColor("#16A34A")

            // Translucent frosted glass dial background
            glassBgPaint.color = if (dark) Color.argb(128, 19, 25, 36) else Color.argb(185, 255, 255, 255)
            canvas.drawCircle(cx, cy, outerR, glassBgPaint)

            // Outer luxury gold trim ring
            goldRingPaint.strokeWidth = 2.0f * d
            canvas.drawCircle(cx, cy, outerR, goldRingPaint)

            // Subtle inner glass ring
            ringPaint.color = if (dark) Color.argb(65, 212, 175, 55) else Color.argb(75, 148, 163, 184)
            ringPaint.strokeWidth = 1.0f * d
            canvas.drawCircle(cx, cy, outerR * 0.60f, ringPaint)

            // Fixed top red lubber pointer (12 o'clock heading marker)
            pathTmp.reset()
            pathTmp.moveTo(cx, cy - outerR + 3f * d)
            pathTmp.lineTo(cx - 6.5f * d, cy - outerR - 8f * d)
            pathTmp.lineTo(cx + 6.5f * d, cy - outerR - 8f * d)
            pathTmp.close()
            fillPaint.color = accentRed
            canvas.drawPath(pathTmp, fillPaint)

            // Rotating compass rose (-azimuthDeg so North points to real magnetic North)
            canvas.save()
            canvas.rotate(-azimuthDeg, cx, cy)

            for (degStep in 0 until 360 step 5) {
                val rad = Math.toRadians(degStep.toDouble())
                val sinA = sin(rad).toFloat()
                val cosA = cos(rad).toFloat()
                val isCardinal = degStep % 90 == 0
                val isThirty = degStep % 30 == 0

                val rIn = outerR * when {
                    isCardinal -> 0.84f
                    isThirty -> 0.875f
                    else -> 0.915f
                }
                val rOut = outerR * 0.97f
                tickPaint.color = when {
                    degStep == 0 -> accentRed
                    isCardinal -> primaryText
                    isThirty -> secondaryText
                    else -> if (dark) Color.argb(100, 148, 163, 184) else Color.argb(110, 100, 116, 139)
                }
                tickPaint.strokeWidth = when {
                    isCardinal -> 2.1f * d
                    isThirty -> 1.4f * d
                    else -> 0.85f * d
                }
                canvas.drawLine(
                    cx + rIn * sinA, cy - rIn * cosA,
                    cx + rOut * sinA, cy - rOut * cosA,
                    tickPaint
                )

                // Degree numbers every 30°
                if (isThirty) {
                    val degNumR = outerR * 0.76f
                    val tx = cx + degNumR * sinA
                    val ty = cy - degNumR * cosA
                    canvas.save()
                    canvas.rotate(degStep.toFloat(), tx, ty)
                    textPaint.typeface = sansSlim
                    textPaint.textSize = outerR * 0.072f
                    textPaint.color = if (degStep == 0) accentRed else secondaryText
                    val fm = textPaint.fontMetrics
                    canvas.drawText(degStep.toString(), tx, ty - (fm.ascent + fm.descent) / 2f, textPaint)
                    canvas.restore()
                }
            }

            // Cardinal letters N, E, S, W + intercardinals NE, SE, SW, NW
            val cardLabels = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            for (i in 0 until 8) {
                val degPos = i * 45f
                val rad = Math.toRadians(degPos.toDouble())
                val isMajor = i % 2 == 0
                val labelR = outerR * (if (isMajor) 0.51f else 0.54f)
                val tx = cx + (labelR * sin(rad)).toFloat()
                val ty = cy - (labelR * cos(rad)).toFloat()
                canvas.save()
                canvas.rotate(degPos, tx, ty)
                textPaint.typeface = if (isMajor) sansBold else sansSlim
                textPaint.textSize = outerR * (if (isMajor) 0.125f else 0.072f)
                textPaint.color = when {
                    i == 0 -> accentRed
                    isMajor -> primaryText
                    else -> secondaryText
                }
                val fm = textPaint.fontMetrics
                canvas.drawText(cardLabels[i], tx, ty - (fm.ascent + fm.descent) / 2f, textPaint)
                canvas.restore()
            }

            // Locked Target Bearing diamond marker on the rotating compass ring
            val target = lockedBearing
            if (target != null) {
                canvas.save()
                canvas.rotate(target, cx, cy)
                val ty = cy - outerR * 0.90f
                val half = 5.5f * d
                pathTmp.reset()
                pathTmp.moveTo(cx, ty - half)
                pathTmp.lineTo(cx + half * 0.78f, ty)
                pathTmp.lineTo(cx, ty + half)
                pathTmp.lineTo(cx - half * 0.78f, ty)
                pathTmp.close()
                fillPaint.color = accentGold
                canvas.drawPath(pathTmp, fillPaint)
                canvas.restore()
            }

            canvas.restore()

            // Center 2D Spirit Bubble Level + Crosshair
            val levelR = outerR * 0.27f
            val isFlat = abs(pitchDeg) <= 2.0f && abs(rollDeg) <= 2.0f

            glassBgPaint.color = if (dark) Color.argb(140, 12, 17, 25) else Color.argb(170, 241, 245, 249)
            canvas.drawCircle(cx, cy, levelR, glassBgPaint)

            ringPaint.color = if (isFlat) levelGreen else (if (dark) Color.argb(95, 148, 163, 184) else Color.argb(115, 100, 116, 139))
            ringPaint.strokeWidth = 1.2f * d
            canvas.drawCircle(cx, cy, levelR, ringPaint)

            // Crosshair lines
            tickPaint.color = if (dark) Color.argb(75, 148, 163, 184) else Color.argb(85, 100, 116, 139)
            tickPaint.strokeWidth = 0.9f * d
            canvas.drawLine(cx - levelR * 0.72f, cy, cx + levelR * 0.72f, cy, tickPaint)
            canvas.drawLine(cx, cy - levelR * 0.72f, cx, cy + levelR * 0.72f, tickPaint)

            // Center target ring for bubble
            ringPaint.color = if (isFlat) levelGreen else accentGold
            ringPaint.strokeWidth = 1.1f * d
            canvas.drawCircle(cx, cy, levelR * 0.30f, ringPaint)

            // Live bubble position mapped from pitch/roll (max ±30° deflection)
            val maxTravel = levelR * 0.68f
            val bx = cx + ((rollDeg.coerceIn(-30f, 30f) / 30f) * maxTravel)
            val by = cy - ((pitchDeg.coerceIn(-30f, 30f) / 30f) * maxTravel)
            fillPaint.color = if (isFlat) Color.argb(215, 74, 222, 128) else Color.argb(205, 88, 166, 255)
            canvas.drawCircle(bx, by, levelR * 0.24f, fillPaint)
            fillPaint.color = Color.argb(160, 255, 255, 255)
            canvas.drawCircle(bx - levelR * 0.06f, by - levelR * 0.06f, levelR * 0.07f, fillPaint)
        }
    }

    private inner class AnalogClockDrawable(density: Float) : Drawable() {
        private val renderer = GShockClockRenderer(density)

        private var timeMillis: Long = System.currentTimeMillis()
        private var lcdHmStr: String = "00.00"
        private var lcdSecStr: String = "00"
        private var lcdMillisStr: String = "0"
        private var currentMillisDigits: Int = 1
        private var dayDateStr: String = "Sun 22"
        private var use24Hour: Boolean = true
        private var amPmStr: String = "AM"
        private var dark: Boolean = false

        fun isTouchOnFormatBadge(x: Float, y: Float): Boolean {
            return renderer.isTouchOnFormatBadge(x, y)
        }

        fun setDarkTheme(isDark: Boolean) {
            if (dark != isDark) {
                dark = isDark
                invalidateSelf()
            }
        }

        fun updateState(
            nowMillis: Long,
            lcdHmStr: String,
            lcdSecStr: String,
            lcdMillisStr: String,
            millisDigits: Int,
            dayDateStr: String,
            use24Hour: Boolean,
            amPmStr: String
        ) {
            this.timeMillis = nowMillis
            this.lcdHmStr = lcdHmStr
            this.lcdSecStr = lcdSecStr
            this.lcdMillisStr = lcdMillisStr
            this.currentMillisDigits = millisDigits
            this.dayDateStr = dayDateStr
            this.use24Hour = use24Hour
            this.amPmStr = amPmStr
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val outerR = (bounds.width().coerceAtMost(bounds.height()) / 2f) * 0.96f
            if (outerR <= 0f) return
            renderer.draw(
                canvas = canvas,
                cx = bounds.exactCenterX(),
                cy = bounds.exactCenterY(),
                outerR = outerR,
                nowMillis = timeMillis,
                lcdHmStr = lcdHmStr,
                lcdSecStr = lcdSecStr,
                lcdMillisStr = lcdMillisStr,
                millisDigits = currentMillisDigits,
                dayDateStr = dayDateStr,
                use24Hour = use24Hour,
                amPmStr = amPmStr,
                dark = dark
            )
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun checkOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            launchIntentForResult(intent, REQ_OVERLAY_START)
        } else {
            startFloatingClockService()
        }
    }

    private fun startFloatingClockService() {
        val intent = Intent(this, FloatingClockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        btnToggleFloating.postDelayed({ updateUiState() }, 150)
    }

    private fun stopFloatingClockService() {
        val intent = Intent(this, FloatingClockService::class.java)
        stopService(intent)
        btnToggleFloating.postDelayed({ updateUiState() }, 150)
    }

    private fun updateUiState() {
        val running = FloatingClockService.isRunning
        btnToggleFloating.text = if (running) getString(R.string.close_floating) else getString(R.string.open_floating)

        val nowDate = Date(FloatingClockService.getCurrentTimestamp())
        tvMainTimeSource.text = if (FloatingClockService.use24HourFormat) {
            getString(R.string.device_time)
        } else {
            "${getString(R.string.device_time)} • ${amPmFormat.format(nowDate)}"
        }
        btnToggleSource.text = getString(R.string.device_time)
        btnTogglePrecision.text = "MS: ${FloatingClockService.millisDigits}D"

        val metricsOn = FloatingClockService.showMetrics
        btnToggleMetrics.text = if (metricsOn) getString(R.string.metrics_on) else getString(R.string.metrics_off)

        btnToggleFloatStyle.text = if (FloatingClockService.useAnalogFloating) "Analog" else "Digital"
    }
}
