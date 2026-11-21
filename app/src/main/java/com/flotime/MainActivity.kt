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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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

    private var baseMainHmsTextSizePx = 0f
    private var baseMainMillisTextSizePx = 0f
    private lateinit var analogClockDrawable: AnalogClockDrawable

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isForeground = false

    private var themePopupView: TextView? = null
    private var hidePopupRunnable: Runnable? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.", Locale.US)
    private val lcdHmFormat = SimpleDateFormat("HH.mm", Locale.US)
    private val lcdSecFormat = SimpleDateFormat("ss", Locale.US)
    private val lcdDayDateFormat = SimpleDateFormat("EEE d", Locale.ENGLISH)
    private val dateFormat = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.ENGLISH)
    private var permissionDialogRefreshAction: (() -> Unit)? = null

    // Theme Mode: 0 = Light, 1 = Dark, 2 = Adaptive
    private var currentThemeMode = 2
    private var isAnalogMode = false

    private val prefsName = "flotime_prefs"
    private val keyThemeMode = "theme_mode"
    private val developerUrl = "https://youtu.be/dQw4w9WgXcQ"
    private val millisRedColor = Color.parseColor("#FF3B1D")

    // Real-time dynamic FPS/Hz monitor (local fallback when overlay service isn't running)
    private var currentHz = 60
    private val dynamicHzMonitor = DynamicRefreshRateMonitor(
        maxHzProvider = { readMaxDisplayRefreshRate() },
        onRateChanged = { measuredHz ->
            if (!isForeground || isFinishing || isDestroyed) return@DynamicRefreshRateMonitor
            currentHz = measuredHz
            if (isAnalogMode) {
                refreshHomeAnalogClock(force = false)
            }
        }
    )

    private fun syncLocalHzMonitor() {
        val shouldRun = isForeground &&
            isAnalogMode &&
            FloatingClockService.showMetrics &&
            !FloatingClockService.isRunning
        if (shouldRun) {
            dynamicHzMonitor.start()
        } else {
            dynamicHzMonitor.stop()
        }
    }

    private fun readMaxDisplayRefreshRate(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            val maxModeRate = display?.supportedModes?.maxOfOrNull { it.refreshRate }
            val currentRate = display?.refreshRate ?: run {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.refreshRate
            }
            max(maxModeRate ?: currentRate, currentRate).roundToInt().coerceIn(60, 240)
        } catch (_: Exception) {
            120
        }
    }

    private var prevRxBytes = 0L
    private var prevTxBytes = 0L
    private var prevStatsSampleMs = 0L
    private var homeUpSpeed = "0.00 KB/s"
    private var homeDownSpeed = "0.00 KB/s"

    private var sensorManager: SensorManager? = null
    private var compassAzimuthDeg = 0f
    private var compassCardinal = "N"
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelValues = FloatArray(3)
    private val magValues = FloatArray(3)
    private var hasAccel = false
    private var hasMag = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val deg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                    updateAzimuth(deg)
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    System.arraycopy(event.values, 0, accelValues, 0, 3)
                    hasAccel = true
                    computeFallbackOrientation()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(event.values, 0, magValues, 0, 3)
                    hasMag = true
                    computeFallbackOrientation()
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun computeFallbackOrientation() {
        if (!hasAccel || !hasMag) return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelValues, magValues)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val deg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            updateAzimuth(deg)
        }
    }

    private fun updateAzimuth(rawDeg: Float) {
        val normalized = ((rawDeg % 360f) + 360f) % 360f
        compassAzimuthDeg = normalized
        compassCardinal = FloatingClockService.azimuthToCardinal(normalized)
    }

    private fun registerCompassSensor() {
        if (sensorManager != null) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null) {
            sm.registerListener(sensorListener, rotSensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager = sm
        } else {
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (acc != null && mag != null) {
                sm.registerListener(sensorListener, acc, SensorManager.SENSOR_DELAY_UI)
                sm.registerListener(sensorListener, mag, SensorManager.SENSOR_DELAY_UI)
                sensorManager = sm
            }
        }
    }

    private fun unregisterCompassSensor() {
        sensorManager?.unregisterListener(sensorListener)
        sensorManager = null
        hasAccel = false
        hasMag = false
    }

    private fun updateLocalStatsIfNeeded(nowRealMs: Long) {
        if (FloatingClockService.isRunning) {
            homeUpSpeed = FloatingClockService.latestUpSpeedText
            homeDownSpeed = FloatingClockService.latestDownSpeedText
            currentHz = FloatingClockService.latestRefreshRateHz
            return
        }
        val elapsedSec = (nowRealMs - prevStatsSampleMs) / 1000.0
        if (elapsedSec >= 1.0) {
            prevStatsSampleMs = nowRealMs
            val curRx = TrafficStats.getTotalRxBytes()
            val curTx = TrafficStats.getTotalTxBytes()
            val rxDelta = if (curRx != TrafficStats.UNSUPPORTED.toLong() && prevRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                (curRx - prevRxBytes).coerceAtLeast(0L)
            } else 0L
            val txDelta = if (curTx != TrafficStats.UNSUPPORTED.toLong() && prevTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                (curTx - prevTxBytes).coerceAtLeast(0L)
            } else 0L
            prevRxBytes = curRx
            prevTxBytes = curTx
            val safeElapsed = elapsedSec.coerceAtLeast(0.5)
            homeUpSpeed = FloatingClockService.formatRate(txDelta / safeElapsed)
            homeDownSpeed = FloatingClockService.formatRate(rxDelta / safeElapsed)
        }
    }

    private fun refreshHomeAnalogClock(force: Boolean) {
        val now = FloatingClockService.getCurrentTimestamp()
        val nowDate = Date(now)
        analogClockDrawable.updateState(
            nowMillis = now,
            lcdHmStr = lcdHmFormat.format(nowDate),
            lcdSecStr = lcdSecFormat.format(nowDate),
            dayDateStr = lcdDayDateFormat.format(nowDate),
            hzValue = currentHz,
            upText = homeUpSpeed,
            downText = homeDownSpeed,
            azimuthDeg = compassAzimuthDeg,
            cardinal = compassCardinal,
            showStats = FloatingClockService.showMetrics,
            force = force
        )
    }

    private val mainClockRunnable = object : Runnable {
        override fun run() {
            if (!isForeground || isFinishing || isDestroyed) return
            val now = FloatingClockService.getCurrentTimestamp()
            val nowReal = System.currentTimeMillis()
            val nowDate = Date(now)

            if (isAnalogMode) {
                syncLocalHzMonitor()
                updateLocalStatsIfNeeded(nowReal)
                refreshHomeAnalogClock(force = false)
                updateUiState()
                mainHandler.postDelayed(this, 100L)
            } else {
                val hms = timeFormat.format(nowDate)
                val dateStr = dateFormat.format(nowDate)
                val fracStr = FloatingClockService.formatMillisFraction(now, FloatingClockService.millisDigits)

                tvMainTimeHms.text = hms
                tvMainTimeMillis.text = fracStr
                tvMainDate.text = dateStr

                updateUiState()
                mainHandler.postDelayed(this, if (FloatingClockService.millisDigits >= 2) 16L else 40L)
            }
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

        val contentFrame = findViewById<ViewGroup>(android.R.id.content)
        rootContainer = if (contentFrame.childCount > 0 && contentFrame.getChildAt(0) is ViewGroup) {
            contentFrame.getChildAt(0) as ViewGroup
        } else {
            contentFrame
        }

        tvMainTitle = findViewById(R.id.tvMainTitle)
        cardClockCircle = findViewById(R.id.cardClockCircle)
        layoutDigitalClock = findViewById(R.id.layoutDigitalClock)
        viewAnalogClock = findViewById(R.id.viewAnalogClock)
        tvMainTimeHms = findViewById(R.id.tvMainTimeHms)
        tvMainTimeMillis = findViewById(R.id.tvMainTimeMillis)
        tvMainDate = findViewById(R.id.tvMainDate)
        tvMainTimeSource = findViewById(R.id.tvMainTimeSource)
        btnToggleSource = findViewById(R.id.btnToggleSource)
        btnToggleMetrics = findViewById(R.id.btnToggleMetrics)
        btnToggleFloating = findViewById(R.id.btnToggleFloating)
        btnSettings = findViewById(R.id.btnSettings)

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

        prevRxBytes = TrafficStats.getTotalRxBytes()
        prevTxBytes = TrafficStats.getTotalTxBytes()
        prevStatsSampleMs = System.currentTimeMillis()
        @Suppress("DEPRECATION")
        currentHz = try {
            windowManager.defaultDisplay.refreshRate.roundToInt().coerceIn(1, 240)
        } catch (_: Exception) {
            60
        }

        setupThemeToggleButton()
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
        if (isAnalogMode) {
            registerCompassSensor()
        }
        syncLocalHzMonitor()
        updateThemeUI()
        applyMainClockPrecisionSizing()
        updateUiState()
        startMainClockLoop()
        startMainTickLoop()
        permissionDialogRefreshAction?.invoke()
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        dynamicHzMonitor.stop()
        unregisterCompassSensor()
        mainHandler.removeCallbacks(mainClockRunnable)
        mainHandler.removeCallbacks(mainTickRunnable)
    }

    override fun onDestroy() {
        isForeground = false
        dynamicHzMonitor.stop()
        unregisterCompassSensor()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        val contentFrame = findViewById<FrameLayout>(android.R.id.content)
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
        contentFrame.addView(btnThemeToggle, initialParams)

        btnSettings.post {
            val gearLoc = IntArray(2)
            val contentLoc = IntArray(2)
            btnSettings.getLocationInWindow(gearLoc)
            contentFrame.getLocationInWindow(contentLoc)

            val topOffset = (gearLoc[1] - contentLoc[1]).coerceAtLeast(0)
            val gearRightInContent = (gearLoc[0] - contentLoc[0]) + btnSettings.width
            val mirroredStartMargin = (contentFrame.width - gearRightInContent).coerceAtLeast(dp(12))

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
        val contentFrame = findViewById<FrameLayout>(android.R.id.content)
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
            contentFrame.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                )
            )
        }.also { themePopupView = it }

        val contentLoc = IntArray(2)
        val titleLoc = IntArray(2)
        contentFrame.getLocationInWindow(contentLoc)
        tvMainTitle.getLocationInWindow(titleLoc)
        val topMarginPx = if (tvMainTitle.height > 0) {
            ((titleLoc[1] - contentLoc[1]) + tvMainTitle.height + dp(14)).coerceAtLeast(dp(64))
        } else {
            dp(72)
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
            btnToggleMetrics.postDelayed({
                syncLocalHzMonitor()
                if (isAnalogMode) refreshHomeAnalogClock(force = true)
                updateUiState()
            }, 100)
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

        cardClockCircle.setOnClickListener {
            isAnalogMode = !isAnalogMode
            if (isAnalogMode) {
                layoutDigitalClock.visibility = View.GONE
                viewAnalogClock.visibility = View.VISIBLE
                registerCompassSensor()
                syncLocalHzMonitor()
                prevRxBytes = TrafficStats.getTotalRxBytes()
                prevTxBytes = TrafficStats.getTotalTxBytes()
                prevStatsSampleMs = System.currentTimeMillis()
                refreshHomeAnalogClock(force = true)
            } else {
                dynamicHzMonitor.stop()
                unregisterCompassSensor()
                layoutDigitalClock.visibility = View.VISIBLE
                viewAnalogClock.visibility = View.GONE
            }
            startMainClockLoop()
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

    private inner class AnalogClockDrawable(density: Float) : Drawable() {
        private val renderer = GShockClockRenderer(density)

        private var timeMillis: Long = System.currentTimeMillis()
        private var lastRenderedSec: Long = -1L
        private var lcdHmStr: String = "00.00"
        private var lcdSecStr: String = "00"
        private var dayDateStr: String = "Sat 21"
        private var hzValue: Int = 60
        private var upText: String = "0.00 KB/s"
        private var downText: String = "0.00 KB/s"
        private var azimuthDeg: Float = 0f
        private var lastAzimuthInt: Int = -1
        private var cardinalStr: String = "N"
        private var showStats: Boolean = true
        private var dark: Boolean = false

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
            dayDateStr: String,
            hzValue: Int,
            upText: String,
            downText: String,
            azimuthDeg: Float,
            cardinal: String,
            showStats: Boolean,
            force: Boolean = false
        ) {
            val sec = nowMillis / 1000L
            val azInt = azimuthDeg.roundToInt()
            val changed = force ||
                sec != lastRenderedSec ||
                hzValue != this.hzValue ||
                upText != this.upText ||
                downText != this.downText ||
                azInt != lastAzimuthInt ||
                cardinal != this.cardinalStr ||
                showStats != this.showStats

            if (!changed) return

            this.timeMillis = nowMillis
            this.lastRenderedSec = sec
            this.lcdHmStr = lcdHmStr
            this.lcdSecStr = lcdSecStr
            this.dayDateStr = dayDateStr
            this.hzValue = hzValue
            this.upText = upText
            this.downText = downText
            this.azimuthDeg = azimuthDeg
            this.lastAzimuthInt = azInt
            this.cardinalStr = cardinal
            this.showStats = showStats
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
                dayDateStr = dayDateStr,
                hzValue = hzValue,
                upText = upText,
                downText = downText,
                azimuthDeg = azimuthDeg,
                cardinalStr = cardinalStr,
                showStats = showStats,
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
        btnToggleFloating.postDelayed({
            syncLocalHzMonitor()
            updateUiState()
        }, 150)
    }

    private fun stopFloatingClockService() {
        val intent = Intent(this, FloatingClockService::class.java)
        stopService(intent)
        btnToggleFloating.postDelayed({
            syncLocalHzMonitor()
            updateUiState()
        }, 150)
    }

    private fun updateUiState() {
        val running = FloatingClockService.isRunning
        btnToggleFloating.text = if (running) getString(R.string.close_floating) else getString(R.string.open_floating)

        tvMainTimeSource.text = getString(R.string.device_time)
        btnToggleSource.text = getString(R.string.device_time)
        btnTogglePrecision.text = "MS: ${FloatingClockService.millisDigits}D"

        val metricsOn = FloatingClockService.showMetrics
        btnToggleMetrics.text = if (metricsOn) getString(R.string.metrics_on) else getString(R.string.metrics_off)

        btnToggleFloatStyle.text = if (FloatingClockService.useAnalogFloating) "Analog" else "Digital"
    }
}
