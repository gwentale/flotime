package com.flotime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class FloatingClockService : Service() {

    companion object {
        const val ACTION_STOP = "com.flotime.ACTION_STOP"
        const val ACTION_TOGGLE_METRICS = "com.flotime.ACTION_TOGGLE_METRICS"
        const val ACTION_UPDATE_PRECISION = "com.flotime.ACTION_UPDATE_PRECISION"
        const val ACTION_UPDATE_TICK_SOUND = "com.flotime.ACTION_UPDATE_TICK_SOUND"
        const val ACTION_TOGGLE_FLOAT_STYLE = "com.flotime.ACTION_TOGGLE_FLOAT_STYLE"

        private const val CHANNEL_ID = "floating_clock_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "flotime_prefs"
        private const val KEY_FLOATING_SCALE = "floating_scale"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_MILLIS_DIGITS = "millis_digits"
        private const val KEY_TICK_SOUND = "tick_sound_enabled"
        private const val KEY_ANALOG_FLOATING = "analog_floating"
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 2.2f

        private const val HOVER_NONE = 0
        private const val HOVER_CLOSE = 1
        private const val HOVER_HOME = 2

        @Volatile
        var isRunning = false

        @Volatile
        var showMetrics = true

        @Volatile
        var millisDigits = 1

        @Volatile
        var tickSoundEnabled = false

        @Volatile
        var useAnalogFloating = false

        @Volatile
        var latestUpSpeedText = "0.00 KB/s"

        @Volatile
        var latestDownSpeedText = "0.00 KB/s"

        @Volatile
        var latestRefreshRateHz = 60

        // High-precision clock tick audio engine (in-RAM AudioTrack MODE_STREAM, zero wake-up delay)
        private object TickAudioEngine {
            private val lock = Any()

            @Volatile
            private var active = false

            @Volatile
            private var pendingImmediateTick = false

            @Volatile
            var lastHeartbeatMs = 0L

            private var workerThread: Thread? = null

            fun keepAlive() {
                lastHeartbeatMs = System.currentTimeMillis()
                if (!active && tickSoundEnabled) {
                    start()
                }
            }

            fun triggerImmediateTick() {
                lastHeartbeatMs = System.currentTimeMillis()
                pendingImmediateTick = true
                if (!active && tickSoundEnabled) {
                    start()
                }
            }

            fun start() {
                synchronized(lock) {
                    lastHeartbeatMs = System.currentTimeMillis()
                    if (active) return
                    active = true
                    val thread = Thread({ runAudioLoop() }, "FlotimeTickAudio")
                    workerThread = thread
                    thread.start()
                }
            }

            fun stop() {
                synchronized(lock) {
                    active = false
                    pendingImmediateTick = false
                    workerThread?.interrupt()
                    workerThread = null
                }
            }

            private fun synthesizeClockWave(
                sampleRate: Int,
                isEven: Boolean,
                isMinuteAccent: Boolean
            ): ShortArray {
                val durationSec = if (isMinuteAccent) 0.080 else 0.055
                val totalSamples = (sampleRate * durationSec).roundToInt()
                val raw = DoubleArray(totalSamples)
                var peak = 0.0001

                val lowBodyFreq = if (isEven) 720.0 else 580.0
                val midFreq = when {
                    isMinuteAccent -> 2350.0
                    isEven -> 1820.0
                    else -> 1460.0
                }
                val clickFreq = if (isEven) 3500.0 else 2950.0

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / sampleRate
                    val rampIn = (t / 0.0004).coerceAtMost(1.0)
                    val clickEnv = exp(-t * 480.0)
                    val midEnv = exp(-t * (if (isMinuteAccent) 60.0 else 90.0))
                    val lowEnv = exp(-t * 120.0)

                    val click = sin(2.0 * PI * clickFreq * t) * clickEnv * 0.72
                    val mid = sin(2.0 * PI * midFreq * t) * midEnv * 0.55
                    val low = sin(2.0 * PI * lowBodyFreq * t) * lowEnv * 0.42
                    val accent = if (isMinuteAccent) {
                        sin(2.0 * PI * 3150.0 * t) * exp(-t * 48.0) * 0.35
                    } else {
                        0.0
                    }

                    val sample = (click + mid + low + accent) * rampIn
                    raw[i] = sample
                    val absVal = abs(sample)
                    if (absVal > peak) peak = absVal
                }

                val gain = 31000.0 / peak
                return ShortArray(totalSamples) { idx ->
                    (raw[idx] * gain).roundToInt().coerceIn(-32767, 32767).toShort()
                }
            }

            private fun runAudioLoop() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                } catch (_: Exception) {
                }

                val sampleRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                    .coerceIn(22050, 48000)
                val chunkSamples = (sampleRate / 100).coerceAtLeast(256)
                val minBufBytes = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val chunkBytes = chunkSamples * 2
                val rawBufBytes = max(
                    if (minBufBytes > 0) minBufBytes else chunkBytes * 4,
                    chunkBytes * 4
                )
                val bufferSizeBytes = if (rawBufBytes % 2 == 0) rawBufBytes else rawBufBytes + 2
                val bufferLatencyMs = ((bufferSizeBytes / 2).toLong() * 1000L) / sampleRate

                val tickPcm = synthesizeClockWave(sampleRate, isEven = true, isMinuteAccent = false)
                val tockPcm = synthesizeClockWave(sampleRate, isEven = false, isMinuteAccent = false)
                val minutePcm = synthesizeClockWave(sampleRate, isEven = true, isMinuteAccent = true)

                var track: AudioTrack? = null
                try {
                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSizeBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    if (track.state != AudioTrack.STATE_INITIALIZED) {
                        return
                    }

                    track.setVolume(1.0f)
                    track.play()

                    val chunkBuf = ShortArray(chunkSamples)
                    var activeWave: ShortArray? = null
                    var wavePos = 0
                    var lastScheduledSec = (getCurrentTimestamp() + bufferLatencyMs) / 1000L

                    while (active && tickSoundEnabled && !Thread.currentThread().isInterrupted) {
                        if (!isRunning && (System.currentTimeMillis() - lastHeartbeatMs) > 2500L) {
                            break
                        }

                        chunkBuf.fill(0.toShort())

                        val predictedPlayMs = getCurrentTimestamp() + bufferLatencyMs
                        val predictedSec = predictedPlayMs / 1000L

                        if (pendingImmediateTick) {
                            pendingImmediateTick = false
                            activeWave = tickPcm
                            wavePos = 0
                            lastScheduledSec = predictedSec
                        } else if (predictedSec != lastScheduledSec) {
                            lastScheduledSec = predictedSec
                            activeWave = when {
                                predictedSec % 60L == 0L -> minutePcm
                                predictedSec % 2L == 0L -> tickPcm
                                else -> tockPcm
                            }
                            wavePos = 0
                        }

                        val wave = activeWave
                        if (wave != null) {
                            val remaining = wave.size - wavePos
                            val toCopy = if (remaining < chunkSamples) remaining else chunkSamples
                            System.arraycopy(wave, wavePos, chunkBuf, 0, toCopy)
                            wavePos += toCopy
                            if (wavePos >= wave.size) {
                                activeWave = null
                                wavePos = 0
                            }
                        }

                        val written = track.write(chunkBuf, 0, chunkSamples, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) {
                            break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        track?.pause()
                        track?.flush()
                        track?.stop()
                    } catch (_: Exception) {
                    }
                    try {
                        track?.release()
                    } catch (_: Exception) {
                    }
                    synchronized(lock) {
                        active = false
                        if (workerThread == Thread.currentThread()) {
                            workerThread = null
                        }
                    }
                }
            }
        }

        fun loadPreferences(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            millisDigits = prefs.getInt(KEY_MILLIS_DIGITS, 1).coerceIn(1, 3)
            tickSoundEnabled = prefs.getBoolean(KEY_TICK_SOUND, false)
            useAnalogFloating = prefs.getBoolean(KEY_ANALOG_FLOATING, false)
            if (tickSoundEnabled) {
                preloadTickSound()
            }
        }

        fun setAnalogFloating(context: Context, enabled: Boolean) {
            useAnalogFloating = enabled
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ANALOG_FLOATING, enabled)
                .apply()
        }

        fun setMillisDigits(context: Context, digits: Int) {
            val clamped = digits.coerceIn(1, 3)
            millisDigits = clamped
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MILLIS_DIGITS, clamped)
                .apply()
        }

        fun setTickSoundEnabled(context: Context, enabled: Boolean) {
            tickSoundEnabled = enabled
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TICK_SOUND, enabled)
                .apply()
            if (enabled) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null && am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    Toast.makeText(
                        context,
                        "Media volume is 0, turn up volume to hear tick sound",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                TickAudioEngine.start()
            } else {
                TickAudioEngine.stop()
            }
        }

        fun preloadTickSound() {
            if (tickSoundEnabled) {
                TickAudioEngine.keepAlive()
            }
        }

        fun playTickIfNewSecond() {
            if (!tickSoundEnabled) return
            TickAudioEngine.keepAlive()
        }

        fun playTickNow() {
            if (!tickSoundEnabled) return
            TickAudioEngine.triggerImmediateTick()
        }

        fun getCurrentTimestamp(): Long = System.currentTimeMillis()

        fun formatMillisFraction(nowMs: Long, digits: Int): String {
            val ms = ((nowMs % 1000L) + 1000L) % 1000L
            return when (digits) {
                2 -> String.format(Locale.US, "%02d", ms / 10L)
                3 -> String.format(Locale.US, "%03d", ms)
                else -> (ms / 100L).toString()
            }
        }

        fun azimuthToCardinal(deg: Float): String {
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val normalized = ((deg % 360f) + 360f) % 360f
            val idx = (((normalized + 22.5f) / 45f).toInt()) % 8
            return dirs[idx]
        }

        fun formatRate(bytesPerSec: Double): String {
            val kbPerSec = bytesPerSec / 1024.0
            return if (kbPerSec >= 1024.0) {
                String.format(Locale.US, "%.2f MB/s", kbPerSec / 1024.0)
            } else {
                String.format(Locale.US, "%.2f KB/s", kbPerSec)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var isServiceActive = false

    private var floatingView: View? = null
    private var tvDateBadge: TextView? = null
    private var tvTimeSource: TextView? = null
    private var tvTimeHms: TextView? = null
    private var tvTimeMillis: TextView? = null
    private var tvRefreshRate: TextView? = null
    private var tvNetSpeed: TextView? = null
    private var gshockClockView: GShockFloatingClockView? = null

    private var baseFloatHmsSizePx = 0f
    private var baseFloatMillisSizePx = 0f

    private val trackedDockViews = mutableListOf<View>()
    private var actionDockView: LinearLayout? = null
    private var pillClose: LinearLayout? = null
    private var iconClose: ImageView? = null
    private var textClose: TextView? = null
    private var pillHome: LinearLayout? = null
    private var iconHome: ImageView? = null
    private var textHome: TextView? = null
    private var isDockVisible = false
    private var hoveredAction = HOVER_NONE

    private val timeFormat = SimpleDateFormat("HH:mm:ss.", Locale.US)
    private val lcdTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val floatingDateFormat = SimpleDateFormat("EEE, MMM d", Locale.ENGLISH)
    private val gshockLcdDayDateFormat = SimpleDateFormat("EEEd", Locale.ENGLISH)
    private var lastDateUpdateSec = -1L
    private var currentScale = 1f
    private var renderedAnalogMode = false

    private var prevRxBytes = 0L
    private var prevTxBytes = 0L
    private var prevMetricsTimeMs = 0L

    // FPS counter state
    private var fpsFrameCount = 0
    private var fpsLastSampleMs = 0L
    private var currentFps = 60

    // Compass sensor state
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
        compassCardinal = azimuthToCardinal(normalized)
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

    private val clockRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive) return
            val now = getCurrentTimestamp()
            val hms = timeFormat.format(Date(now))
            val frac = formatMillisFraction(now, millisDigits)

            fpsFrameCount++
            val elapsedFpsMs = SystemClock.uptimeMillis() - fpsLastSampleMs
            if (elapsedFpsMs >= 500L) {
                currentFps = ((fpsFrameCount * 1000f) / elapsedFpsMs).roundToInt().coerceIn(1, 240)
                fpsFrameCount = 0
                fpsLastSampleMs = SystemClock.uptimeMillis()
            }

            if (useAnalogFloating) {
                gshockClockView?.updateState(
                    nowMillis = now,
                    lcdTimeStr = lcdTimeFormat.format(Date(now)),
                    dayDateStr = gshockLcdDayDateFormat.format(Date(now)),
                    fps = currentFps,
                    upText = latestUpSpeedText,
                    downText = latestDownSpeedText,
                    azimuthDeg = compassAzimuthDeg,
                    cardinal = compassCardinal,
                    showStats = showMetrics
                )
            } else {
                tvTimeHms?.text = hms
                tvTimeMillis?.text = frac

                val currentSec = now / 1000L
                if (currentSec != lastDateUpdateSec) {
                    lastDateUpdateSec = currentSec
                    tvDateBadge?.text = floatingDateFormat.format(Date(now))
                }
            }

            mainHandler.postDelayed(this, if (millisDigits >= 2) 16L else 33L)
        }
    }

    private val metricsRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive) return
            val nowMs = System.currentTimeMillis()
            val elapsedSec = ((nowMs - prevMetricsTimeMs) / 1000.0).coerceAtLeast(0.5)
            prevMetricsTimeMs = nowMs

            val currentRx = TrafficStats.getTotalRxBytes()
            val currentTx = TrafficStats.getTotalTxBytes()

            val rxDelta = if (currentRx != TrafficStats.UNSUPPORTED.toLong() && prevRxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                (currentRx - prevRxBytes).coerceAtLeast(0L)
            } else 0L

            val txDelta = if (currentTx != TrafficStats.UNSUPPORTED.toLong() && prevTxBytes != TrafficStats.UNSUPPORTED.toLong()) {
                (currentTx - prevTxBytes).coerceAtLeast(0L)
            } else 0L

            prevRxBytes = currentRx
            prevTxBytes = currentTx

            latestUpSpeedText = formatRate(txDelta / elapsedSec)
            latestDownSpeedText = formatRate(rxDelta / elapsedSec)
            latestRefreshRateHz = getScreenRefreshRate()

            tvNetSpeed?.text = "↑ $latestUpSpeedText\n↓ $latestDownSpeedText"
            tvRefreshRate?.text = "${latestRefreshRateHz}Hz"

            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceActive = true
        isRunning = true
        loadPreferences(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fpsLastSampleMs = SystemClock.uptimeMillis()
        startForegroundNotification()
        initFloatingView()
        registerCompassSensor()
        startClockLoop()
        syncTickAudioState()
        startMetricsLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                closeFloatingAndStop()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_METRICS -> {
                showMetrics = !showMetrics
                updateMetricsVisibility()
            }
            ACTION_UPDATE_PRECISION -> {
                loadPreferences(this)
                applyFloatingPrecisionSizing()
            }
            ACTION_UPDATE_TICK_SOUND -> {
                loadPreferences(this)
                syncTickAudioState()
            }
            ACTION_TOGGLE_FLOAT_STYLE -> {
                loadPreferences(this)
                recreateFloatingViewPreservingPosition()
            }
        }
        return START_STICKY
    }

    private fun recreateFloatingViewPreservingPosition() {
        val savedX = if (::layoutParams.isInitialized) layoutParams.x else dp(20)
        val savedY = if (::layoutParams.isInitialized) layoutParams.y else dp(140)
        removeFloatingView()
        initFloatingView(initialX = savedX, initialY = savedY)
    }

    private fun syncTickAudioState() {
        if (tickSoundEnabled) {
            TickAudioEngine.start()
        } else {
            TickAudioEngine.stop()
        }
    }

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }

            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(this, FloatingClockService::class.java).apply {
                action = ACTION_STOP
            }
            val pendingStop = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
            }

            val stopAction = Notification.Action.Builder(
                null as Icon?,
                getString(R.string.notif_action_close),
                pendingStop
            ).build()

            val notification: Notification = builder
                .setSmallIcon(R.drawable.ic_clock_logo)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_active_text))
                .setContentIntent(pendingOpen)
                .addAction(stopAction)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun isDarkThemeActive(): Boolean {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME_MODE, 2)
        return when (mode) {
            0 -> false
            1 -> true
            else -> {
                val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun saveScale(scale: Float) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FLOATING_SCALE, scale)
            .apply()
    }

    private fun getFingerSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun removeWindowSafely(view: View) {
        view.animate().cancel()
        view.visibility = View.GONE
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Exception) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeFloatingView() {
        val view = floatingView ?: return
        floatingView = null
        gshockClockView = null
        tvDateBadge = null
        tvTimeSource = null
        tvTimeHms = null
        tvTimeMillis = null
        tvRefreshRate = null
        tvNetSpeed = null
        removeWindowSafely(view)
    }

    private fun closeFloatingAndStop() {
        isServiceActive = false
        isRunning = false
        unregisterCompassSensor()
        if (System.currentTimeMillis() - TickAudioEngine.lastHeartbeatMs > 1200L) {
            TickAudioEngine.stop()
        }
        hideBottomActionDock()
        removeFloatingView()
        stopSelf()
    }

    private fun applyFloatingPrecisionSizing() {
        val factor = when (millisDigits) {
            3 -> 0.86f
            2 -> 0.93f
            else -> 1.0f
        }
        if (baseFloatHmsSizePx > 0f) {
            tvTimeHms?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseFloatHmsSizePx * factor)
        }
        if (baseFloatMillisSizePx > 0f) {
            tvTimeMillis?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseFloatMillisSizePx * factor)
        }
    }

    private fun showBottomActionDock() {
        if (isDockVisible && actionDockView != null) return
        hideBottomActionDock()

        val dark = isDarkThemeActive()

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        val ivClose = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(6)
            }
        }
        val tvClose = TextView(this).apply {
            text = "Close"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }
        val closeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(18), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(14)
            }
            minimumWidth = dp(112)
            elevation = dp(4).toFloat()
            addView(ivClose)
            addView(tvClose)
        }

        val ivHome = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(6)
            }
        }
        val tvHome = TextView(this).apply {
            text = "Home"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
        }
        val homeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(18), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumWidth = dp(112)
            elevation = dp(4).toFloat()
            addView(ivHome)
            addView(tvHome)
        }

        dock.addView(closeBox)
        dock.addView(homeBox)

        actionDockView = dock
        pillClose = closeBox
        iconClose = ivClose
        textClose = tvClose
        pillHome = homeBox
        iconHome = ivHome
        textHome = tvHome

        updateDockHighlight(HOVER_NONE, dark)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dockParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }

        try {
            windowManager.addView(dock, dockParams)
            trackedDockViews.add(dock)
            isDockVisible = true
            dock.alpha = 0f
            dock.translationY = dp(20).toFloat()
            dock.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(140L)
                .start()
        } catch (_: Exception) {
            isDockVisible = false
            actionDockView = null
        }
    }

    private fun updateDockHighlight(target: Int, dark: Boolean = isDarkThemeActive()) {
        val neutralBg = if (dark) Color.argb(230, 30, 36, 48) else Color.argb(238, 255, 255, 255)
        val neutralBorder = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(38, 31, 36, 48)
        val neutralText = if (dark) Color.parseColor("#F0F4F8") else Color.parseColor("#1F2430")

        val closeSelected = target == HOVER_CLOSE
        val closeBg = if (closeSelected) Color.parseColor("#E53935") else neutralBg
        val closeBorder = if (closeSelected) Color.parseColor("#FF6F60") else neutralBorder
        val closeContentColor = if (closeSelected) Color.WHITE else Color.parseColor("#E53935")

        pillClose?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(closeBg)
            setStroke(dp(1), closeBorder)
        }
        iconClose?.setImageDrawable(createCloseIconDrawable(closeContentColor))
        textClose?.setTextColor(if (closeSelected) Color.WHITE else neutralText)
        pillClose?.animate()?.cancel()
        pillClose?.animate()
            ?.scaleX(if (closeSelected) 1.08f else 1f)
            ?.scaleY(if (closeSelected) 1.08f else 1f)
            ?.setDuration(90L)
            ?.start()

        val homeSelected = target == HOVER_HOME
        val homeBg = if (homeSelected) Color.parseColor("#1690F3") else neutralBg
        val homeBorder = if (homeSelected) Color.parseColor("#58B2FF") else neutralBorder
        val homeContentColor = if (homeSelected) Color.WHITE else Color.parseColor("#1690F3")

        pillHome?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(homeBg)
            setStroke(dp(1), homeBorder)
        }
        iconHome?.setImageDrawable(createHomeIconDrawable(homeContentColor))
        textHome?.setTextColor(if (homeSelected) Color.WHITE else neutralText)
        pillHome?.animate()?.cancel()
        pillHome?.animate()
            ?.scaleX(if (homeSelected) 1.08f else 1f)
            ?.scaleY(if (homeSelected) 1.08f else 1f)
            ?.setDuration(90L)
            ?.start()
    }

    private fun hideBottomActionDock() {
        hoveredAction = HOVER_NONE
        isDockVisible = false

        pillClose?.animate()?.cancel()
        pillHome?.animate()?.cancel()

        val viewsToRemove = ArrayList<View>(trackedDockViews)
        actionDockView?.let { if (!viewsToRemove.contains(it)) viewsToRemove.add(it) }
        trackedDockViews.clear()

        actionDockView = null
        pillClose = null
        iconClose = null
        textClose = null
        pillHome = null
        iconHome = null
        textHome = null

        for (dock in viewsToRemove) {
            removeWindowSafely(dock)
        }
    }

    private fun isPointInPill(pill: View?, x: Float, y: Float, extraMarginPx: Int): Boolean {
        if (pill == null || pill.width == 0 || pill.height == 0) return false
        val loc = IntArray(2)
        pill.getLocationOnScreen(loc)
        val left = loc[0] - extraMarginPx
        val top = loc[1] - extraMarginPx
        val right = loc[0] + pill.width + extraMarginPx
        val bottom = loc[1] + pill.height + extraMarginPx
        return x >= left && x <= right && y >= top && y <= bottom
    }

    private fun detectHoveredDockAction(
        fingerX: Float,
        fingerY: Float,
        clockCenterX: Float,
        clockCenterY: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Int {
        val margin = dp(26)
        val closePill = pillClose
        val homePill = pillHome

        if (closePill != null && closePill.width > 0 && homePill != null && homePill.width > 0) {
            val hitClose = isPointInPill(closePill, fingerX, fingerY, margin) ||
                isPointInPill(closePill, clockCenterX, clockCenterY, margin)
            val hitHome = isPointInPill(homePill, fingerX, fingerY, margin) ||
                isPointInPill(homePill, clockCenterX, clockCenterY, margin)

            return when {
                hitClose && !hitHome -> HOVER_CLOSE
                hitHome && !hitClose -> HOVER_HOME
                hitClose && hitHome -> if (fingerX < screenWidth / 2f) HOVER_CLOSE else HOVER_HOME
                else -> HOVER_NONE
            }
        }

        val inBottomZone = fingerY >= screenHeight - dp(160) || clockCenterY >= screenHeight - dp(135)
        return if (inBottomZone) {
            if (fingerX < screenWidth / 2f) HOVER_CLOSE else HOVER_HOME
        } else {
            HOVER_NONE
        }
    }

    private fun createCloseIconDrawable(colorInt: Int): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
            }

            override fun draw(canvas: Canvas) {
                val pad = bounds.width() * 0.22f
                val l = bounds.left + pad
                val t = bounds.top + pad
                val r = bounds.right - pad
                val b = bounds.bottom - pad
                canvas.drawLine(l, t, r, b, paint)
                canvas.drawLine(r, t, l, b, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    private fun createHomeIconDrawable(colorInt: Int): Drawable {
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val path = Path().apply {
                    moveTo(w * 0.14f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.48f)
                    moveTo(w * 0.25f, h * 0.42f)
                    lineTo(w * 0.25f, h * 0.84f)
                    lineTo(w * 0.75f, h * 0.84f)
                    lineTo(w * 0.75f, h * 0.42f)
                }
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingView(initialX: Int? = null, initialY: Int? = null) {
        currentScale = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FLOATING_SCALE, 1f)
            .coerceIn(MIN_SCALE, MAX_SCALE)

        renderedAnalogMode = useAnalogFloating
        val density = resources.displayMetrics.density
        val baseWidthDp = if (renderedAnalogMode) 224 else 196
        val baseHeightDp = if (renderedAnalogMode) 224 else 112
        val baseWidthPx = (baseWidthDp * density).roundToInt()
        val baseHeightPx = (baseHeightDp * density).roundToInt()

        val wrapperView = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
        }.apply {
            clipChildren = false
            clipToPadding = false
        }

        val contentView: View = if (renderedAnalogMode) {
            GShockFloatingClockView(this).also { gshockClockView = it }
        } else {
            LayoutInflater.from(this).inflate(R.layout.layout_floating_clock, wrapperView, false)
        }

        contentView.pivotX = 0f
        contentView.pivotY = 0f
        contentView.scaleX = currentScale
        contentView.scaleY = currentScale

        wrapperView.addView(
            contentView,
            FrameLayout.LayoutParams(baseWidthPx, baseHeightPx, Gravity.TOP or Gravity.START)
        )
        floatingView = wrapperView

        if (!renderedAnalogMode) {
            tvDateBadge = contentView.findViewById(R.id.tvPing)
            tvTimeSource = contentView.findViewById(R.id.tvTimeSource)
            tvTimeHms = contentView.findViewById(R.id.tvTimeHms)
            tvTimeMillis = contentView.findViewById(R.id.tvTimeMillis)
            tvRefreshRate = contentView.findViewById(R.id.tvRefreshRate)
            tvNetSpeed = contentView.findViewById(R.id.tvNetSpeed)

            val now = getCurrentTimestamp()
            lastDateUpdateSec = now / 1000L
            tvDateBadge?.visibility = View.VISIBLE
            tvDateBadge?.text = floatingDateFormat.format(Date(now))
            tvTimeSource?.text = getString(R.string.device_time)
            tvNetSpeed?.text = "↑ $latestUpSpeedText\n↓ $latestDownSpeedText"
            tvRefreshRate?.text = "${latestRefreshRateHz}Hz"

            tvTimeHms?.let {
                baseFloatHmsSizePx = it.textSize
                it.fontFeatureSettings = "tnum"
            }
            tvTimeMillis?.let {
                baseFloatMillisSizePx = it.textSize
                it.fontFeatureSettings = "tnum"
            }
            applyFloatingPrecisionSizing()
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            (baseWidthPx * currentScale).roundToInt(),
            (baseHeightPx * currentScale).roundToInt(),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX ?: (20 * density).roundToInt()
            y = initialY ?: (140 * density).roundToInt()
        }

        updateMetricsVisibility()

        var initialWinX = 0
        var initialWinY = 0
        var initialWinW = 0
        var initialWinH = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var initialSpan = 0f
        var initialScale = currentScale
        var isPinching = false
        var blockDragUntilUp = false

        wrapperView.setOnTouchListener { v: View, event: MotionEvent ->
            val (screenWidth, screenHeight) = getRealScreenSize()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPinching = false
                    blockDragUntilUp = false
                    hoveredAction = HOVER_NONE
                    initialWinX = layoutParams.x
                    initialWinY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        hideBottomActionDock()
                        val span = getFingerSpan(event)
                        if (span > 10f) {
                            isPinching = true
                            blockDragUntilUp = true
                            initialSpan = span
                            initialScale = currentScale
                            initialWinX = layoutParams.x
                            initialWinY = layoutParams.y
                            initialWinW = layoutParams.width
                            initialWinH = layoutParams.height
                        }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        hideBottomActionDock()
                        val span = getFingerSpan(event)
                        if (!isPinching) {
                            if (span > 10f) {
                                isPinching = true
                                blockDragUntilUp = true
                                initialSpan = span
                                initialScale = currentScale
                                initialWinX = layoutParams.x
                                initialWinY = layoutParams.y
                                initialWinW = layoutParams.width
                                initialWinH = layoutParams.height
                            }
                        } else if (initialSpan > 10f && span > 10f) {
                            val newScale = (initialScale * (span / initialSpan)).coerceIn(MIN_SCALE, MAX_SCALE)
                            val newWidth = (baseWidthPx * newScale).roundToInt()
                            val newHeight = (baseHeightPx * newScale).roundToInt()

                            val centerX = initialWinX + (initialWinW / 2f)
                            val centerY = initialWinY + (initialWinH / 2f)

                            currentScale = newScale
                            layoutParams.width = newWidth
                            layoutParams.height = newHeight
                            layoutParams.x = (centerX - (newWidth / 2f)).roundToInt()
                            layoutParams.y = (centerY - (newHeight / 2f)).roundToInt()

                            contentView.scaleX = currentScale
                            contentView.scaleY = currentScale

                            if (v.isAttachedToWindow) {
                                windowManager.updateViewLayout(v, layoutParams)
                            }
                        }
                        true
                    } else if (!blockDragUntilUp) {
                        val deltaY = event.rawY - initialTouchY
                        layoutParams.x = initialWinX + (event.rawX - initialTouchX).roundToInt()
                        layoutParams.y = initialWinY + deltaY.roundToInt()
                        if (v.isAttachedToWindow) {
                            windowManager.updateViewLayout(v, layoutParams)
                        }

                        val clockCenterX = layoutParams.x + (layoutParams.width / 2f)
                        val clockCenterY = layoutParams.y + (layoutParams.height / 2f)

                        if (!isDockVisible && (deltaY > dp(24) || clockCenterY > screenHeight * 0.56f)) {
                            showBottomActionDock()
                        }

                        if (isDockVisible) {
                            val newHover = detectHoveredDockAction(
                                fingerX = event.rawX,
                                fingerY = event.rawY,
                                clockCenterX = clockCenterX,
                                clockCenterY = clockCenterY,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight
                            )

                            if (newHover != hoveredAction) {
                                hoveredAction = newHover
                                updateDockHighlight(hoveredAction)
                                if (hoveredAction != HOVER_NONE) {
                                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                }
                            }
                        }
                        true
                    } else {
                        true
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (isPinching) {
                        saveScale(currentScale)
                        isPinching = false
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val selectedAction = if (event.actionMasked == MotionEvent.ACTION_UP) {
                        hoveredAction
                    } else {
                        HOVER_NONE
                    }

                    hideBottomActionDock()

                    if (isPinching || blockDragUntilUp) {
                        saveScale(currentScale)
                    }
                    isPinching = false
                    blockDragUntilUp = false

                    when (selectedAction) {
                        HOVER_CLOSE -> {
                            closeFloatingAndStop()
                        }
                        HOVER_HOME -> {
                            layoutParams.x = initialWinX
                            layoutParams.y = initialWinY
                                .coerceAtMost((screenHeight - layoutParams.height - dp(140)).coerceAtLeast(dp(40)))
                                .coerceAtLeast(dp(40))
                            if (v.isAttachedToWindow) {
                                windowManager.updateViewLayout(v, layoutParams)
                            }
                            val homeIntent = Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            startActivity(homeIntent)
                        }
                    }
                    true
                }

                else -> false
            }
        }

        windowManager.addView(wrapperView, layoutParams)
    }

    private inner class GShockFloatingClockView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val cal = Calendar.getInstance()

        private var nowMillis = System.currentTimeMillis()
        private var lcdTimeStr = "00:00:00"
        private var dayDateStr = "Sat21"
        private var fpsValue = 60
        private var upText = "0.00 KB/s"
        private var downText = "0.00 KB/s"
        private var azimuthDeg = 0f
        private var cardinalStr = "N"
        private var showStats = true

        private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(238, 22, 26, 34)
            style = Paint.Style.FILL
        }
        private val bezelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3B4559")
            style = Paint.Style.STROKE
            strokeWidth = 2f * d
        }
        private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#11151C")
            style = Paint.Style.FILL
        }
        private val subDialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#181E28")
            style = Paint.Style.FILL
        }
        private val lcdBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A222E")
            style = Paint.Style.FILL
        }
        private val lcdBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
        }
        private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val centerCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val lcdRect = RectF()
        private val subRect = RectF()

        fun updateState(
            nowMillis: Long,
            lcdTimeStr: String,
            dayDateStr: String,
            fps: Int,
            upText: String,
            downText: String,
            azimuthDeg: Float,
            cardinal: String,
            showStats: Boolean
        ) {
            this.nowMillis = nowMillis
            this.lcdTimeStr = lcdTimeStr
            this.dayDateStr = dayDateStr
            this.fpsValue = fps
            this.upText = upText
            this.downText = downText
            this.azimuthDeg = azimuthDeg
            this.cardinalStr = cardinal
            this.showStats = showStats
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val cx = w / 2f
            val cy = h / 2f
            val outerR = (w.coerceAtMost(h) / 2f) - 2f * d
            val dialR = outerR * 0.84f

            // Outer G-Shock rugged ring
            canvas.drawCircle(cx, cy, outerR, bezelPaint)
            canvas.drawCircle(cx, cy, outerR, bezelBorderPaint)

            // Inner dial face
            canvas.drawCircle(cx, cy, dialR, dialPaint)

            // 60 perimeter ticks & bold hour indices
            for (i in 0 until 60) {
                val angle = Math.toRadians((i * 6).toDouble())
                val isHour = i % 5 == 0
                val isQuarter = i % 15 == 0
                val innerFactor = when {
                    isQuarter -> 0.82f
                    isHour -> 0.86f
                    else -> 0.92f
                }
                tickPaint.color = when {
                    isQuarter -> Color.parseColor("#E2E8F0")
                    isHour -> Color.parseColor("#94A3B8")
                    else -> Color.parseColor("#475569")
                }
                tickPaint.strokeWidth = when {
                    isQuarter -> 2.4f * d
                    isHour -> 1.7f * d
                    else -> 1f * d
                }
                val rIn = dialR * innerFactor
                val rOut = dialR * 0.98f
                canvas.drawLine(
                    cx + (rIn * sin(angle)).toFloat(),
                    cy - (rIn * cos(angle)).toFloat(),
                    cx + (rOut * sin(angle)).toFloat(),
                    cy - (rOut * cos(angle)).toFloat(),
                    tickPaint
                )
            }

            if (showStats) {
                // Compass readout at top-center
                labelPaint.textAlign = Paint.Align.CENTER
                labelPaint.color = Color.parseColor("#38BDF8")
                labelPaint.textSize = 9.5f * d
                labelPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                canvas.drawText(
                    "$cardinalStr ${azimuthDeg.roundToInt()}°",
                    cx,
                    cy - dialR * 0.64f,
                    labelPaint
                )

                // FPS METER (replaces G-SHOCK CASIO)
                labelPaint.textAlign = Paint.Align.LEFT
                val leftInfoX = cx - dialR * 0.64f
                labelPaint.color = Color.parseColor("#F8FAFC")
                labelPaint.textSize = 9.5f * d
                labelPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("${fpsValue} FPS", leftInfoX, cy - dialR * 0.24f, labelPaint)

                labelPaint.color = Color.parseColor("#94A3B8")
                labelPaint.textSize = 7.5f * d
                labelPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("FPS METER", leftInfoX, cy - dialR * 0.10f, labelPaint)

                // UL/DL (replaces STEP TRACKER Bluetooth)
                labelPaint.color = Color.parseColor("#E2E8F0")
                labelPaint.textSize = 7.5f * d
                labelPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                canvas.drawText("↑ $upText", leftInfoX, cy + dialR * 0.14f, labelPaint)
                canvas.drawText("↓ $downText", leftInfoX, cy + dialR * 0.27f, labelPaint)

                labelPaint.color = Color.parseColor("#38BDF8")
                labelPaint.textSize = 7.5f * d
                labelPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("UL/DL", leftInfoX, cy + dialR * 0.40f, labelPaint)

                // Right subdial (sweep / compass gauge)
                val subCx = cx + dialR * 0.42f
                val subCy = cy - dialR * 0.16f
                val subR = dialR * 0.27f
                subRect.set(subCx - subR, subCy - subR, subCx + subR, subCy + subR)
                canvas.drawCircle(subCx, subCy, subR, subDialPaint)
                canvas.drawCircle(subCx, subCy, subR, lcdBorderPaint)

                val sweepRad = Math.toRadians(azimuthDeg.toDouble())
                handPaint.color = Color.parseColor("#FF3B1D")
                handPaint.strokeWidth = 1.6f * d
                canvas.drawLine(
                    subCx,
                    subCy,
                    subCx + (subR * 0.78f * sin(sweepRad)).toFloat(),
                    subCy - (subR * 0.78f * cos(sweepRad)).toFloat(),
                    handPaint
                )
                centerCapPaint.color = Color.parseColor("#FF3B1D")
                canvas.drawCircle(subCx, subCy, 2.2f * d, centerCapPaint)
            }

            // Bottom digital LCD window (Digital time + Sat21)
            val lcdW = dialR * 1.05f
            val lcdH = dialR * 0.34f
            val lcdTop = cy + dialR * 0.45f
            lcdRect.set(cx - lcdW / 2f, lcdTop, cx + lcdW / 2f, lcdTop + lcdH)
            canvas.drawRoundRect(lcdRect, 5f * d, 5f * d, lcdBoxPaint)
            canvas.drawRoundRect(lcdRect, 5f * d, 5f * d, lcdBorderPaint)

            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            labelPaint.color = Color.parseColor("#F8FAFC")
            labelPaint.textSize = 9.5f * d
            canvas.drawText(lcdTimeStr, cx, lcdTop + lcdH * 0.44f, labelPaint)

            labelPaint.color = Color.parseColor("#94A3B8")
            labelPaint.textSize = 7.5f * d
            canvas.drawText(dayDateStr, cx, lcdTop + lcdH * 0.84f, labelPaint)

            // Analog hands
            cal.timeInMillis = nowMillis
            val hour = cal.get(Calendar.HOUR)
            val minute = cal.get(Calendar.MINUTE)
            val second = cal.get(Calendar.SECOND)
            val millis = cal.get(Calendar.MILLISECOND)

            val hourDeg = ((hour + minute / 60f) * 30f).toDouble()
            val minDeg = ((minute + second / 60f) * 6f).toDouble()
            val secDeg = ((second + millis / 1000f) * 6f).toDouble()

            val hRad = Math.toRadians(hourDeg)
            handPaint.color = Color.parseColor("#F8FAFC")
            handPaint.strokeWidth = 3.6f * d
            canvas.drawLine(
                cx - (dialR * 0.10f * sin(hRad)).toFloat(),
                cy + (dialR * 0.10f * cos(hRad)).toFloat(),
                cx + (dialR * 0.46f * sin(hRad)).toFloat(),
                cy - (dialR * 0.46f * cos(hRad)).toFloat(),
                handPaint
            )

            val mRad = Math.toRadians(minDeg)
            handPaint.color = Color.parseColor("#CBD5E1")
            handPaint.strokeWidth = 2.5f * d
            canvas.drawLine(
                cx - (dialR * 0.12f * sin(mRad)).toFloat(),
                cy + (dialR * 0.12f * cos(mRad)).toFloat(),
                cx + (dialR * 0.68f * sin(mRad)).toFloat(),
                cy - (dialR * 0.68f * cos(mRad)).toFloat(),
                handPaint
            )

            val sRad = Math.toRadians(secDeg)
            handPaint.color = Color.parseColor("#FF3B1D")
            handPaint.strokeWidth = 1.5f * d
            canvas.drawLine(
                cx - (dialR * 0.16f * sin(sRad)).toFloat(),
                cy + (dialR * 0.16f * cos(sRad)).toFloat(),
                cx + (dialR * 0.78f * sin(sRad)).toFloat(),
                cy - (dialR * 0.78f * cos(sRad)).toFloat(),
                handPaint
            )

            centerCapPaint.color = Color.parseColor("#FF3B1D")
            canvas.drawCircle(cx, cy, 3.8f * d, centerCapPaint)
            centerCapPaint.color = Color.parseColor("#11151C")
            canvas.drawCircle(cx, cy, 1.4f * d, centerCapPaint)
        }
    }

    private fun updateMetricsVisibility() {
        val visibility = if (showMetrics) View.VISIBLE else View.INVISIBLE
        tvDateBadge?.visibility = visibility
        tvTimeSource?.visibility = visibility
        tvRefreshRate?.visibility = visibility
        tvNetSpeed?.visibility = visibility
        gshockClockView?.invalidate()
    }

    private fun startClockLoop() {
        mainHandler.removeCallbacks(clockRunnable)
        mainHandler.post(clockRunnable)
    }

    private fun startMetricsLoop() {
        mainHandler.removeCallbacks(metricsRunnable)
        prevRxBytes = TrafficStats.getTotalRxBytes()
        prevTxBytes = TrafficStats.getTotalTxBytes()
        prevMetricsTimeMs = System.currentTimeMillis()
        mainHandler.postDelayed(metricsRunnable, 1000L)
    }

    private fun formatRate(bytesPerSec: Double): String = Companion.formatRate(bytesPerSec)

    @Suppress("DEPRECATION")
    private fun getScreenRefreshRate(): Int {
        return try {
            val display = windowManager.defaultDisplay
            display.refreshRate.roundToInt()
        } catch (_: Exception) {
            60
        }
    }

    override fun onDestroy() {
        isServiceActive = false
        isRunning = false
        unregisterCompassSensor()
        mainHandler.removeCallbacksAndMessages(null)
        if (System.currentTimeMillis() - TickAudioEngine.lastHeartbeatMs > 1200L) {
            TickAudioEngine.stop()
        }
        hideBottomActionDock()
        removeFloatingView()
        super.onDestroy()
    }
}
