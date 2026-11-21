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
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
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
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

internal class GShockClockRenderer(private val density: Float) {
    private val cal = Calendar.getInstance()
    private val sansSlim = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val serifAcademic = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val serifAcademicBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private val bezelOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bezelLugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bezelRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val luxuryGoldRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val luxuryGoldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val markerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val lcdBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lcdBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }

    private val handShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(95, 0, 0, 0)
    }
    private val handBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handLumePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val secHandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val rectTmp = RectF()
    private val badgeDrawRect = RectF()
    private val badgeHitRect = RectF()
    private val pathTmp = Path()
    private val pathLume = Path()
    private val lcdPath = Path()

    private var cachedShaderCx = -1f
    private var cachedShaderCy = -1f
    private var cachedShaderR = -1f
    private var cachedShaderDark = true
    private var lastLcdCornerPx = -1f
    private var goldShader: LinearGradient? = null

    fun isTouchOnFormatBadge(x: Float, y: Float): Boolean {
        return !badgeHitRect.isEmpty && badgeHitRect.contains(x, y)
    }

    private fun ensureLuxuryGoldShader(cx: Float, cy: Float, outerR: Float, dark: Boolean) {
        if (
            goldShader != null &&
            abs(cx - cachedShaderCx) < 0.5f &&
            abs(cy - cachedShaderCy) < 0.5f &&
            abs(outerR - cachedShaderR) < 0.5f &&
            dark == cachedShaderDark
        ) {
            return
        }
        cachedShaderCx = cx
        cachedShaderCy = cy
        cachedShaderR = outerR
        cachedShaderDark = dark

        val colors = if (dark) {
            intArrayOf(
                Color.parseColor("#FCE6A8"),
                Color.parseColor("#D4AF37"),
                Color.parseColor("#8A6623"),
                Color.parseColor("#EED082"),
                Color.parseColor("#B28735")
            )
        } else {
            intArrayOf(
                Color.parseColor("#E6C574"),
                Color.parseColor("#B98E2E"),
                Color.parseColor("#7E5B18"),
                Color.parseColor("#D9B55A"),
                Color.parseColor("#9C7422")
            )
        }
        val positions = floatArrayOf(0f, 0.25f, 0.52f, 0.78f, 1f)
        val shader = LinearGradient(
            cx - outerR,
            cy - outerR,
            cx + outerR,
            cy + outerR,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )
        goldShader = shader
        luxuryGoldRingPaint.shader = shader
        luxuryGoldFillPaint.shader = shader
        lcdBorderPaint.shader = shader
        badgeStrokePaint.shader = shader
    }

    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        outerR: Float,
        nowMillis: Long,
        lcdHmStr: String,
        lcdSecStr: String,
        lcdMillisStr: String,
        millisDigits: Int,
        dayDateStr: String,
        use24Hour: Boolean,
        amPmStr: String,
        dark: Boolean
    ) {
        if (outerR <= 0f) return

        val dialR = outerR * 0.905f
        ensureLuxuryGoldShader(cx, cy, outerR, dark)

        val bezelBaseColor = if (dark) Color.parseColor("#141820") else Color.parseColor("#E2E8F2")
        val bezelLugColor = if (dark) Color.parseColor("#1D2330") else Color.parseColor("#CBD6E6")
        val dialFaceColor = if (dark) Color.parseColor("#0D1117") else Color.parseColor("#F9FBFE")
        val subtleRingColor = if (dark) Color.argb(65, 212, 175, 55) else Color.argb(75, 168, 128, 42)
        val subtleTickText = if (dark) Color.parseColor("#7D8A9E") else Color.parseColor("#94A3B8")
        val accentRed = Color.parseColor("#FF3B1D")

        // Matches Digital button theme colors
        val digitalBtnBg = if (dark) Color.parseColor("#1E2430") else Color.parseColor("#FFFFFF")
        val digitalBtnStroke = if (dark) Color.parseColor("#2D3548") else Color.parseColor("#D8E2EE")
        val digitalBtnBlue = if (dark) Color.parseColor("#58A6FF") else Color.parseColor("#1690F3")
        val digitalBtnBlueSoft = if (dark) Color.argb(200, 88, 166, 255) else Color.argb(200, 22, 144, 243)

        // 1. Classic sculpted bezel with 4 subtle bumper lugs + luxury gold border trim
        bezelLugPaint.color = bezelLugColor
        for (lugAngle in intArrayOf(45, 135, 225, 315)) {
            canvas.save()
            canvas.rotate(lugAngle.toFloat(), cx, cy)
            rectTmp.set(
                cx - outerR * 0.24f,
                cy - outerR * 1.005f,
                cx + outerR * 0.24f,
                cy - outerR * 0.84f
            )
            canvas.drawRoundRect(rectTmp, outerR * 0.04f, outerR * 0.04f, bezelLugPaint)
            canvas.restore()
        }

        bezelOuterPaint.color = bezelBaseColor
        canvas.drawCircle(cx, cy, outerR * 0.985f, bezelOuterPaint)

        // Outer luxury metallic gold border list
        luxuryGoldRingPaint.strokeWidth = max(1.8f * density, outerR * 0.022f)
        canvas.drawCircle(cx, cy, outerR * 0.978f, luxuryGoldRingPaint)

        // Inner bezel luxury gold pinstripe
        luxuryGoldRingPaint.strokeWidth = max(1.1f * density, outerR * 0.013f)
        canvas.drawCircle(cx, cy, dialR * 1.018f, luxuryGoldRingPaint)

        // 2. Clean luxury dial face with subtle concentric horology rings
        dialBgPaint.color = dialFaceColor
        canvas.drawCircle(cx, cy, dialR, dialBgPaint)

        // Fine luxury gold chapter ring separating markers & center dial
        innerRingPaint.color = subtleRingColor
        innerRingPaint.strokeWidth = max(0.85f * density, dialR * 0.0085f)
        canvas.drawCircle(cx, cy, dialR * 0.725f, innerRingPaint)

        // Subtle decorative inner guilloche ring in upper open space
        bezelRingPaint.color = if (dark) Color.argb(38, 212, 175, 55) else Color.argb(42, 168, 128, 42)
        bezelRingPaint.strokeWidth = max(0.65f * density, dialR * 0.006f)
        canvas.drawCircle(cx, cy, dialR * 0.675f, bezelRingPaint)

        // Minimal luxury gold diamond crest below 12 o'clock
        val crestY = cy - dialR * 0.42f
        val crestHalf = dialR * 0.028f
        pathTmp.reset()
        pathTmp.moveTo(cx, crestY - crestHalf)
        pathTmp.lineTo(cx + crestHalf * 0.75f, crestY)
        pathTmp.lineTo(cx, crestY + crestHalf)
        pathTmp.lineTo(cx - crestHalf * 0.75f, crestY)
        pathTmp.close()
        canvas.drawPath(pathTmp, luxuryGoldFillPaint)

        // 3. Outer 60 fine second ticks + 1..60 numbers (multiples of 5 red, others small & slim)
        for (i in 1..60) {
            val angleRad = Math.toRadians((i * 6).toDouble())
            val sinA = sin(angleRad).toFloat()
            val cosA = cos(angleRad).toFloat()
            val isFive = i % 5 == 0
            val isQuarter = i % 15 == 0

            val tIn = dialR * (if (isFive) 0.955f else 0.968f)
            val tOut = dialR * 0.996f
            tickPaint.color = if (isFive) accentRed else subtleTickText
            tickPaint.strokeWidth = when {
                isQuarter -> max(1.5f * density, dialR * 0.015f)
                isFive -> max(1.1f * density, dialR * 0.011f)
                else -> max(0.7f * density, dialR * 0.007f)
            }
            canvas.drawLine(
                cx + tIn * sinA,
                cy - tIn * cosA,
                cx + tOut * sinA,
                cy - tOut * cosA,
                tickPaint
            )

            val numRadius = dialR * 0.885f
            textPaint.textAlign = Paint.Align.CENTER
            if (isFive) {
                textPaint.typeface = sansMedium
                textPaint.textSize = dialR * 0.066f
                textPaint.color = accentRed
            } else {
                textPaint.typeface = sansSlim
                textPaint.textSize = dialR * 0.036f
                textPaint.color = subtleTickText
            }
            val fm = textPaint.fontMetrics
            val yOffset = -(fm.ascent + fm.descent) / 2f
            canvas.drawText(
                i.toString(),
                cx + numRadius * sinA,
                cy - numRadius * cosA + yOffset,
                textPaint
            )
        }

        // 4. 12 Classic sculpted hour indices with luxury gold quarter inlay
        val markerOuter = dialR * 0.812f
        val markerInner = dialR * 0.742f
        for (h in 0 until 12) {
            val isQuarter = h % 3 == 0
            val halfWOuter = dialR * (if (isQuarter) 0.034f else 0.024f)
            val halfWInner = dialR * (if (isQuarter) 0.024f else 0.017f)

            canvas.save()
            canvas.rotate((h * 30).toFloat(), cx, cy)

            pathTmp.reset()
            pathTmp.moveTo(cx - halfWOuter, cy - markerOuter)
            pathTmp.lineTo(cx + halfWOuter, cy - markerOuter)
            pathTmp.lineTo(cx + halfWInner, cy - markerInner)
            pathTmp.lineTo(cx - halfWInner, cy - markerInner)
            pathTmp.close()

            markerFillPaint.color = if (dark) {
                if (isQuarter) Color.parseColor("#A3B1C6") else Color.parseColor("#64748B")
            } else {
                if (isQuarter) Color.parseColor("#475569") else Color.parseColor("#94A3B8")
            }
            canvas.drawPath(pathTmp, markerFillPaint)

            if (isQuarter) {
                rectTmp.set(
                    cx - halfWInner * 0.44f,
                    cy - markerOuter + dialR * 0.012f,
                    cx + halfWInner * 0.44f,
                    cy - markerInner - dialR * 0.012f
                )
                canvas.drawRect(rectTmp, luxuryGoldFillPaint)
            } else {
                markerInnerPaint.color = if (dark) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")
                rectTmp.set(
                    cx - halfWInner * 0.25f,
                    cy - markerOuter + dialR * 0.016f,
                    cx + halfWInner * 0.25f,
                    cy - markerInner - dialR * 0.016f
                )
                canvas.drawRect(rectTmp, markerInnerPaint)
            }
            canvas.restore()
        }

        // 5. Center-Down G-Shock Beveled Digital LCD Column with Luxury Gold Border List
        // Format Row 1: [24H/12H] 23.49 20.752 (millis in red, 1..3 digits)
        // Format Row 2: flotime | Sun 22 (or flotime | Sun 22 AM in 12H mode)
        val lcdTop = cy + dialR * 0.185f
        val lcdMidY = cy + dialR * 0.405f
        val lcdBottom = cy + dialR * 0.575f
        val lcdHalfWTop = dialR * 0.555f
        val lcdHalfWBot = dialR * 0.395f

        lcdPath.reset()
        lcdPath.moveTo(cx - lcdHalfWTop, lcdTop)
        lcdPath.lineTo(cx + lcdHalfWTop, lcdTop)
        lcdPath.lineTo(cx + lcdHalfWTop, lcdMidY)
        lcdPath.lineTo(cx + lcdHalfWBot, lcdBottom)
        lcdPath.lineTo(cx - lcdHalfWBot, lcdBottom)
        lcdPath.lineTo(cx - lcdHalfWTop, lcdMidY)
        lcdPath.close()

        val cornerPx = dialR * 0.038f
        if (abs(cornerPx - lastLcdCornerPx) > 0.5f) {
            lastLcdCornerPx = cornerPx
            val effect = CornerPathEffect(cornerPx)
            lcdBgPaint.pathEffect = effect
            lcdBorderPaint.pathEffect = effect
        }

        lcdBgPaint.color = digitalBtnBg
        lcdBorderPaint.strokeWidth = max(1.35f * density, dialR * 0.014f)
        canvas.drawPath(lcdPath, lcdBgPaint)
        canvas.drawPath(lcdPath, lcdBorderPaint)

        // Subtle horizontal divider inside LCD
        innerRingPaint.color = digitalBtnStroke
        innerRingPaint.strokeWidth = max(0.75f * density, dialR * 0.0065f)
        canvas.drawLine(
            cx - dialR * 0.47f,
            lcdMidY,
            cx + dialR * 0.47f,
            lcdMidY,
            innerRingPaint
        )

        // Row 1 layout calculations with auto-fit for 1D / 2D / 3D milliseconds
        val fitScale = when (millisDigits) {
            3 -> 0.86f
            2 -> 0.93f
            else -> 1.0f
        }
        val badgeText = if (use24Hour) "24H" else "12H"
        val badgeTextSize = dialR * 0.060f * fitScale
        val badgePadH = dialR * 0.024f
        val badgePadV = dialR * 0.016f
        val hmSize = dialR * 0.114f * fitScale
        val secSize = dialR * 0.080f * fitScale
        val millisSize = dialR * 0.076f * fitScale
        val gapAfterBadge = dialR * 0.032f
        val gapBeforeSec = dialR * 0.020f

        textPaint.typeface = sansSlim
        textPaint.textSize = badgeTextSize
        val badgeTextW = textPaint.measureText(badgeText)
        val badgeFm = textPaint.fontMetrics
        val badgeBoxW = badgeTextW + badgePadH * 2f
        val badgeBoxH = (badgeFm.descent - badgeFm.ascent) + badgePadV * 2f

        textPaint.typeface = serifAcademicBold
        textPaint.textSize = hmSize
        val hmW = textPaint.measureText(lcdHmStr)
        val hmFm = textPaint.fontMetrics

        textPaint.typeface = serifAcademic
        textPaint.textSize = secSize
        val secW = textPaint.measureText(lcdSecStr)

        val millisText = ".$lcdMillisStr"
        textPaint.typeface = serifAcademicBold
        textPaint.textSize = millisSize
        val millisW = textPaint.measureText(millisText)

        val row1TotalW = badgeBoxW + gapAfterBadge + hmW + gapBeforeSec + secW + millisW
        var cursorX = cx - (row1TotalW / 2f)
        val row1CenterY = (lcdTop + lcdMidY) / 2f
        val row1BaseY = row1CenterY - (hmFm.ascent + hmFm.descent) / 2f

        // Draw tappable 24H / 12H pill badge with subtle gold rim
        badgeDrawRect.set(
            cursorX,
            row1CenterY - badgeBoxH / 2f,
            cursorX + badgeBoxW,
            row1CenterY + badgeBoxH / 2f
        )
        // Expanded hit area for effortless single tap
        val hitPad = max(10f * density, dialR * 0.09f)
        badgeHitRect.set(
            badgeDrawRect.left - hitPad,
            badgeDrawRect.top - hitPad,
            badgeDrawRect.right + hitPad,
            badgeDrawRect.bottom + hitPad
        )

        badgeFillPaint.color = if (dark) Color.argb(70, 212, 175, 55) else Color.argb(38, 185, 142, 46)
        badgeStrokePaint.strokeWidth = max(0.85f * density, dialR * 0.008f)
        val badgeCorner = badgeBoxH * 0.28f
        canvas.drawRoundRect(badgeDrawRect, badgeCorner, badgeCorner, badgeFillPaint)
        canvas.drawRoundRect(badgeDrawRect, badgeCorner, badgeCorner, badgeStrokePaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = sansSlim
        textPaint.textSize = badgeTextSize
        textPaint.color = digitalBtnBlue
        val badgeTextY = badgeDrawRect.centerY() - (badgeFm.ascent + badgeFm.descent) / 2f
        canvas.drawText(badgeText, badgeDrawRect.centerX(), badgeTextY, textPaint)
        cursorX += badgeBoxW + gapAfterBadge

        // Draw HH.mm (or hh.mm in 12H)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = serifAcademicBold
        textPaint.textSize = hmSize
        textPaint.color = digitalBtnBlue
        canvas.drawText(lcdHmStr, cursorX, row1BaseY, textPaint)
        cursorX += hmW + gapBeforeSec

        // Draw seconds
        textPaint.typeface = serifAcademic
        textPaint.textSize = secSize
        textPaint.color = digitalBtnBlueSoft
        canvas.drawText(lcdSecStr, cursorX, row1BaseY, textPaint)
        cursorX += secW

        // Draw red milliseconds (.X / .XX / .XXX)
        textPaint.typeface = serifAcademicBold
        textPaint.textSize = millisSize
        textPaint.color = accentRed
        canvas.drawText(millisText, cursorX, row1BaseY, textPaint)

        // Row 2: "flotime | Sun 22" (with AM/PM in 12H mode)
        val row2Brand = "flotime"
        val row2Sep = " | "
        val dateDisplay = if (use24Hour) dayDateStr else "$dayDateStr $amPmStr"
        val row2Size = dialR * (if (use24Hour) 0.068f else 0.062f)

        textPaint.typeface = serifAcademic
        textPaint.textSize = row2Size
        val bW = textPaint.measureText(row2Brand)
        val sepW = textPaint.measureText(row2Sep)
        val dateW = textPaint.measureText(dateDisplay)
        val row2TotalW = bW + sepW + dateW

        val row2Fm = textPaint.fontMetrics
        val row2BaseY = (lcdMidY + lcdBottom) / 2f - (row2Fm.ascent + row2Fm.descent) / 2f
        var r2X = cx - (row2TotalW / 2f)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = digitalBtnBlue
        canvas.drawText(row2Brand, r2X, row2BaseY, textPaint)
        r2X += bW

        textPaint.color = digitalBtnBlueSoft
        canvas.drawText(row2Sep, r2X, row2BaseY, textPaint)
        r2X += sepW

        textPaint.color = digitalBtnBlue
        canvas.drawText(dateDisplay, r2X, row2BaseY, textPaint)

        // 6. Classic Sculpted Analog Hands (Discrete Quartz Ticking)
        cal.timeInMillis = nowMillis
        val hour = cal.get(Calendar.HOUR)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        val hourDeg = ((hour + minute / 60f) * 30f)
        val minDeg = ((minute + second / 60f) * 6f)
        val secDeg = (second * 6f)

        val handOuterColor = if (dark) Color.parseColor("#334155") else Color.parseColor("#1E293B")
        val handLumeColor = Color.parseColor("#F8FAFC")
        val shadowOffset = 1.8f * density

        drawGShockSwordHand(
            canvas = canvas,
            cx = cx,
            cy = cy,
            angleDeg = hourDeg,
            length = dialR * 0.48f,
            tail = dialR * 0.11f,
            halfWidth = dialR * 0.036f,
            bodyColor = handOuterColor,
            lumeColor = handLumeColor,
            shadowOffset = shadowOffset
        )

        drawGShockSwordHand(
            canvas = canvas,
            cx = cx,
            cy = cy,
            angleDeg = minDeg,
            length = dialR * 0.69f,
            tail = dialR * 0.13f,
            halfWidth = dialR * 0.028f,
            bodyColor = handOuterColor,
            lumeColor = handLumeColor,
            shadowOffset = shadowOffset
        )

        val secRad = Math.toRadians(secDeg.toDouble())
        val sSin = sin(secRad).toFloat()
        val sCos = cos(secRad).toFloat()
        val secTail = dialR * 0.17f
        val secLen = dialR * 0.78f

        secHandPaint.color = Color.argb(85, 0, 0, 0)
        secHandPaint.strokeWidth = max(1.4f * density, dialR * 0.015f)
        canvas.drawLine(
            cx - secTail * sSin + shadowOffset * 0.7f,
            cy + secTail * sCos + shadowOffset * 0.7f,
            cx + secLen * sSin + shadowOffset * 0.7f,
            cy - secLen * sCos + shadowOffset * 0.7f,
            secHandPaint
        )

        secHandPaint.color = accentRed
        secHandPaint.strokeWidth = max(1.35f * density, dialR * 0.014f)
        canvas.drawLine(
            cx - secTail * sSin,
            cy + secTail * sCos,
            cx + secLen * sSin,
            cy - secLen * sCos,
            secHandPaint
        )

        // Luxury gold-rimmed center pin cap
        canvas.drawCircle(cx, cy, dialR * 0.060f, luxuryGoldFillPaint)
        capPaint.color = accentRed
        canvas.drawCircle(cx, cy, dialR * 0.036f, capPaint)
        capPaint.color = if (dark) Color.parseColor("#0F172A") else Color.WHITE
        canvas.drawCircle(cx, cy, dialR * 0.014f, capPaint)
    }

    private fun drawGShockSwordHand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        length: Float,
        tail: Float,
        halfWidth: Float,
        bodyColor: Int,
        lumeColor: Int,
        shadowOffset: Float
    ) {
        pathTmp.reset()
        pathTmp.moveTo(cx, cy - length)
        pathTmp.lineTo(cx + halfWidth, cy - length * 0.74f)
        pathTmp.lineTo(cx + halfWidth * 0.85f, cy + tail * 0.35f)
        pathTmp.lineTo(cx + halfWidth * 0.55f, cy + tail)
        pathTmp.lineTo(cx - halfWidth * 0.55f, cy + tail)
        pathTmp.lineTo(cx - halfWidth * 0.85f, cy + tail * 0.35f)
        pathTmp.lineTo(cx - halfWidth, cy - length * 0.74f)
        pathTmp.close()

        pathLume.reset()
        pathLume.moveTo(cx, cy - length * 0.90f)
        pathLume.lineTo(cx + halfWidth * 0.52f, cy - length * 0.73f)
        pathLume.lineTo(cx + halfWidth * 0.42f, cy - length * 0.16f)
        pathLume.lineTo(cx - halfWidth * 0.42f, cy - length * 0.16f)
        pathLume.lineTo(cx - halfWidth * 0.52f, cy - length * 0.73f)
        pathLume.close()

        canvas.save()
        canvas.translate(shadowOffset, shadowOffset)
        canvas.rotate(angleDeg, cx, cy)
        canvas.drawPath(pathTmp, handShadowPaint)
        canvas.restore()

        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        handBodyPaint.color = bodyColor
        handLumePaint.color = lumeColor
        canvas.drawPath(pathTmp, handBodyPaint)
        canvas.drawPath(pathLume, handLumePaint)
        canvas.restore()
    }
}

class FloatingClockService : Service() {

    companion object {
        const val ACTION_STOP = "com.flotime.ACTION_STOP"
        const val ACTION_TOGGLE_METRICS = "com.flotime.ACTION_TOGGLE_METRICS"
        const val ACTION_UPDATE_PRECISION = "com.flotime.ACTION_UPDATE_PRECISION"
        const val ACTION_UPDATE_TICK_SOUND = "com.flotime.ACTION_UPDATE_TICK_SOUND"
        const val ACTION_TOGGLE_FLOAT_STYLE = "com.flotime.ACTION_TOGGLE_FLOAT_STYLE"
        const val ACTION_TOGGLE_TIME_FORMAT = "com.flotime.ACTION_TOGGLE_TIME_FORMAT"

        private const val CHANNEL_ID = "floating_clock_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "flotime_prefs"
        private const val KEY_FLOATING_SCALE = "floating_scale"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_MILLIS_DIGITS = "millis_digits"
        private const val KEY_TICK_SOUND = "tick_sound_enabled"
        private const val KEY_ANALOG_FLOATING = "analog_floating"
        private const val KEY_USE_24H_FORMAT = "use_24h_format"
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
        var use24HourFormat = true

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
            use24HourFormat = prefs.getBoolean(KEY_USE_24H_FORMAT, true)
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

        fun setUse24HourFormat(context: Context, use24h: Boolean) {
            use24HourFormat = use24h
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USE_24H_FORMAT, use24h)
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

    private val timeFormat24 = SimpleDateFormat("HH:mm:ss.", Locale.US)
    private val timeFormat12 = SimpleDateFormat("hh:mm:ss.", Locale.US)
    private val lcdHmFormat24 = SimpleDateFormat("HH.mm", Locale.US)
    private val lcdHmFormat12 = SimpleDateFormat("hh.mm", Locale.US)
    private val lcdSecFormat = SimpleDateFormat("ss", Locale.US)
    private val amPmFormat = SimpleDateFormat("a", Locale.US)
    private val floatingDateFormat = SimpleDateFormat("EEE, MMM d", Locale.ENGLISH)
    private val gshockLcdDayDateFormat = SimpleDateFormat("EEE d", Locale.ENGLISH)
    private var lastDateUpdateSec = -1L
    private var currentScale = 1f
    private var renderedAnalogMode = false

    private var prevRxBytes = 0L
    private var prevTxBytes = 0L
    private var prevMetricsTimeMs = 0L

    private fun refreshGShockClock() {
        val now = getCurrentTimestamp()
        val nowDate = Date(now)
        val hmText = if (use24HourFormat) lcdHmFormat24.format(nowDate) else lcdHmFormat12.format(nowDate)
        gshockClockView?.updateState(
            nowMillis = now,
            lcdHmStr = hmText,
            lcdSecStr = lcdSecFormat.format(nowDate),
            lcdMillisStr = formatMillisFraction(now, millisDigits),
            millisDigits = millisDigits,
            dayDateStr = gshockLcdDayDateFormat.format(nowDate),
            use24Hour = use24HourFormat,
            amPmStr = amPmFormat.format(nowDate)
        )
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive) return
            val now = getCurrentTimestamp()

            if (useAnalogFloating) {
                refreshGShockClock()
            } else {
                val nowDate = Date(now)
                val hms = if (use24HourFormat) timeFormat24.format(nowDate) else timeFormat12.format(nowDate)
                val frac = formatMillisFraction(now, millisDigits)

                tvTimeHms?.text = hms
                tvTimeMillis?.text = frac

                val currentSec = now / 1000L
                if (currentSec != lastDateUpdateSec) {
                    lastDateUpdateSec = currentSec
                    tvDateBadge?.text = floatingDateFormat.format(nowDate)
                    tvTimeSource?.text = if (use24HourFormat) {
                        getString(R.string.device_time)
                    } else {
                        "${getString(R.string.device_time)} • ${amPmFormat.format(nowDate)}"
                    }
                }
            }
            mainHandler.postDelayed(this, if (millisDigits >= 2) 16L else 33L)
        }
    }

    private val metricsRunnable = object : Runnable {
        override fun run() {
            if (!isServiceActive) return
            if (!useAnalogFloating && showMetrics) {
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
            }
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
        latestRefreshRateHz = getScreenRefreshRate()
        startForegroundNotification()
        initFloatingView()
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
                if (useAnalogFloating) refreshGShockClock()
            }
            ACTION_UPDATE_TICK_SOUND -> {
                loadPreferences(this)
                syncTickAudioState()
            }
            ACTION_TOGGLE_FLOAT_STYLE -> {
                loadPreferences(this)
                recreateFloatingViewPreservingPosition()
                startClockLoop()
            }
            ACTION_TOGGLE_TIME_FORMAT -> {
                loadPreferences(this)
                lastDateUpdateSec = -1L
                if (useAnalogFloating) refreshGShockClock()
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
        val baseWidthDp = if (renderedAnalogMode) 244 else 196
        val baseHeightDp = if (renderedAnalogMode) 244 else 112
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

        if (renderedAnalogMode) {
            refreshGShockClock()
        } else {
            tvDateBadge = contentView.findViewById(R.id.tvPing)
            tvTimeSource = contentView.findViewById(R.id.tvTimeSource)
            tvTimeHms = contentView.findViewById(R.id.tvTimeHms)
            tvTimeMillis = contentView.findViewById(R.id.tvTimeMillis)
            tvRefreshRate = contentView.findViewById(R.id.tvRefreshRate)
            tvNetSpeed = contentView.findViewById(R.id.tvNetSpeed)

            val now = getCurrentTimestamp()
            val nowDate = Date(now)
            lastDateUpdateSec = now / 1000L
            tvDateBadge?.visibility = View.VISIBLE
            tvDateBadge?.text = floatingDateFormat.format(nowDate)
            tvTimeSource?.text = if (use24HourFormat) {
                getString(R.string.device_time)
            } else {
                "${getString(R.string.device_time)} • ${amPmFormat.format(nowDate)}"
            }
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

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
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
        var hasMovedPastSlop = false

        wrapperView.setOnTouchListener { v: View, event: MotionEvent ->
            val (screenWidth, screenHeight) = getRealScreenSize()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPinching = false
                    blockDragUntilUp = false
                    hasMovedPastSlop = false
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
                        hasMovedPastSlop = true
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
                        hasMovedPastSlop = true
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
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (!hasMovedPastSlop && hypot(deltaX, deltaY) > touchSlop) {
                            hasMovedPastSlop = true
                        }

                        if (hasMovedPastSlop) {
                            layoutParams.x = initialWinX + deltaX.roundToInt()
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
                    val isSingleTap = event.actionMasked == MotionEvent.ACTION_UP &&
                        !hasMovedPastSlop && !isPinching && !blockDragUntilUp

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

                    if (isSingleTap && renderedAnalogMode) {
                        val unscaledX = event.x / currentScale
                        val unscaledY = event.y / currentScale
                        if (gshockClockView?.isTapOnFormatBadge(unscaledX, unscaledY) == true) {
                            setUse24HourFormat(this, !use24HourFormat)
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            refreshGShockClock()
                        }
                    } else {
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
                    }
                    true
                }

                else -> false
            }
        }

        windowManager.addView(wrapperView, layoutParams)
    }

    private inner class GShockFloatingClockView(context: Context) : View(context) {
        private val renderer = GShockClockRenderer(resources.displayMetrics.density)

        private var nowMillis = System.currentTimeMillis()
        private var lcdHmStr = "00.00"
        private var lcdSecStr = "00"
        private var lcdMillisStr = "0"
        private var currentMillisDigits = 1
        private var dayDateStr = "Sun 22"
        private var use24Hour = true
        private var amPmStr = "AM"

        fun isTapOnFormatBadge(x: Float, y: Float): Boolean {
            return renderer.isTouchOnFormatBadge(x, y)
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
            this.nowMillis = nowMillis
            this.lcdHmStr = lcdHmStr
            this.lcdSecStr = lcdSecStr
            this.lcdMillisStr = lcdMillisStr
            this.currentMillisDigits = millisDigits
            this.dayDateStr = dayDateStr
            this.use24Hour = use24Hour
            this.amPmStr = amPmStr
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val outerR = (w.coerceAtMost(h) / 2f) - 2f * resources.displayMetrics.density
            renderer.draw(
                canvas = canvas,
                cx = w / 2f,
                cy = h / 2f,
                outerR = outerR,
                nowMillis = nowMillis,
                lcdHmStr = lcdHmStr,
                lcdSecStr = lcdSecStr,
                lcdMillisStr = lcdMillisStr,
                millisDigits = currentMillisDigits,
                dayDateStr = dayDateStr,
                use24Hour = use24Hour,
                amPmStr = amPmStr,
                dark = true
            )
        }
    }

    private fun updateMetricsVisibility() {
        val visibility = if (showMetrics) View.VISIBLE else View.INVISIBLE
        tvDateBadge?.visibility = visibility
        tvTimeSource?.visibility = visibility
        tvRefreshRate?.visibility = visibility
        tvNetSpeed?.visibility = visibility
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

    @Suppress("DEPRECATION")
    private fun getScreenRefreshRate(): Int {
        return try {
            windowManager.defaultDisplay.refreshRate.roundToInt().coerceIn(1, 240)
        } catch (_: Exception) {
            60
        }
    }

    override fun onDestroy() {
        isServiceActive = false
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        if (System.currentTimeMillis() - TickAudioEngine.lastHeartbeatMs > 1200L) {
            TickAudioEngine.stop()
        }
        hideBottomActionDock()
        removeFloatingView()
        super.onDestroy()
    }
}
