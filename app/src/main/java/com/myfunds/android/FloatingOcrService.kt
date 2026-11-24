package com.myfunds.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import com.myfunds.core.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class FloatingOcrService : Service() {
    companion object {
        const val CHANNEL_ID = "floating_ocr_service"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP = "com.myfunds.android.STOP_FLOATING"
    }

    private var windowManager: WindowManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var rootView: LinearLayout? = null
    private var bubbleView: LinearLayout? = null
    private var cardView: LinearLayout? = null
    private var bubbleText: TextView? = null

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 480

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRecognizing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("resultData")
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, mainHandler)
        }

        if (mediaProjection == null) {
            Toast.makeText(this, "未能获取屏幕截图权限", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }

        setupScreenCapture()
        setupFloatingView()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮识图服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在支付宝持仓页面点击悬浮球直接识别基金"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val stopIntent = Intent(this, FloatingOcrService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val appIntent = Intent(this, MainActivity::class.java)
        val appPending = PendingIntent.getActivity(this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("拾盈 · 悬浮识图已开启")
            .setContentText("请切换至支付宝/天天基金持仓页面，点击悬浮球直接识别")
            .setSmallIcon(R.drawable.ic_launcher_sprout)
            .setContentIntent(appPending)
            .addAction(Notification.Action.Builder(null, "退出悬浮", stopPending).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupScreenCapture() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FloatingOcrVirtualDisplay",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun dp(v: Int): Int = (v * (screenDensity / 160f)).toInt()

    private fun shape(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
        }
    }

    private fun setupFloatingView() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(220)
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Collapsed Bubble
        bubbleText = TextView(this).apply {
            text = "⚡ 识别当前基金"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }

        bubbleView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.parseColor("#4F46E5"), 22, Color.parseColor("#818CF8"), 1)
            elevation = dp(6).toFloat()
            addView(bubbleText)
        }

        // Drag & Click listener
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(rootView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = kotlin.math.abs(event.rawX - initialTouchX)
                        val dy = kotlin.math.abs(event.rawY - initialTouchY)
                        if (dx < dp(8) && dy < dp(8)) {
                            if (!isRecognizing) triggerRecognition()
                        }
                        return true
                    }
                }
                return false
            }
        })

        // 2. Result Card Container (Initially GONE)
        cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(Color.parseColor("#0F172A"), 18, Color.parseColor("#4F46E5"), 2)
            elevation = dp(12).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(290), -2)
        }

        rootView?.addView(bubbleView)
        rootView?.addView(cardView)

        windowManager?.addView(rootView, params)
    }

    private fun triggerRecognition() {
        isRecognizing = true
        bubbleText?.text = "正在识别中…"

        // Temporarily hide bubble to avoid capturing it in screenshot
        bubbleView?.visibility = View.INVISIBLE

        mainHandler.postDelayed({
            captureScreenAndParse()
        }, 120)
    }

    private fun captureScreenAndParse() {
        val reader = imageReader
        if (reader == null) {
            resetBubble("未能获取截屏器")
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        }

        bubbleView?.visibility = View.VISIBLE

        if (image == null) {
            resetBubble("未捕获到屏幕内容，请再试一次")
            return
        }

        executor.submit {
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val rawBitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cleanBitmap = if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                    rawBitmap.recycle()
                    cropped
                } else {
                    rawBitmap
                }

                // Recognize with OfflineOcr
                val lines = OfflineOcr(applicationContext).recognize(cleanBitmap)
                cleanBitmap.recycle()

                val candidates = HoldingsOcr.parse(lines)
                if (candidates.isEmpty()) {
                    mainHandler.post {
                        resetBubble("未在当前页面识别到基金持仓")
                        Toast.makeText(applicationContext, "未识别到基金。请在支付宝打开“资产详情”或“持有明细”页面后点击识别", Toast.LENGTH_LONG).show()
                    }
                    return@submit
                }

                var candidate = candidates.first()
                if (candidate.code != null) {
                    // Try to resolve official fund name
                    runCatching {
                        val quote = MarketClient().quotes(listOf(candidate.code!!)).firstOrNull()
                        if (quote?.name != null && quote.name.isNotBlank()) {
                            candidate = candidate.copy(name = quote.name)
                        }
                    }
                }

                mainHandler.post {
                    showResultCard(candidate)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    resetBubble("识别出错，请重试")
                    Toast.makeText(applicationContext, e.message ?: "识别失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showResultCard(c: HoldingCandidate) {
        isRecognizing = false
        bubbleView?.visibility = View.GONE
        cardView?.removeAllViews()
        cardView?.visibility = View.VISIBLE

        val card = cardView ?: return

        // 1. Header: Name & Code
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@FloatingOcrService).apply {
                text = c.name
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val codeBadge = TextView(this@FloatingOcrService).apply {
                text = "代码: ${c.code ?: "未识别"}"
                textSize = 11f
                setTextColor(Color.parseColor("#818CF8"))
                setPadding(0, dp(2), 0, dp(4))
            }
            addView(codeBadge)
        }
        card.addView(header)

        // 2. Metrics Block
        val metricBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.parseColor("#1E293B"), 12, Color.parseColor("#334155"), 1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }

            fun statRow(label: String, value: String) {
                addView(LinearLayout(this@FloatingOcrService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(this@FloatingOcrService).apply {
                        text = label
                        textSize = 12f
                        setTextColor(Color.parseColor("#94A3B8"))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@FloatingOcrService).apply {
                        text = value
                        textSize = 13f
                        setTextColor(Color.parseColor("#F8FAFC"))
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                    setPadding(0, dp(2), 0, dp(2))
                })
            }

            statRow("持有份额", c.shares?.stripTrailingZeros()?.toPlainString()?.let { "$it 份" } ?: "待补齐")
            statRow("持仓成本", c.cost?.stripTrailingZeros()?.toPlainString()?.let { "$it 元" } ?: "待补齐")
            val amt = c.amount
            if (amt != null) statRow("持有金额", "${amt.stripTrailingZeros().toPlainString()} 元")
            val pft = c.profit
            if (pft != null) statRow("持有收益", "${if (pft > BigDecimal.ZERO) "+" else ""}${pft.stripTrailingZeros().toPlainString()} 元")
            statRow("净值日期", c.confirmedDate ?: todayStr())
        }
        card.addView(metricBox)

        // 3. Action Buttons
        val btnSave = Button(this).apply {
            text = "✓ 存入持仓与自选"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = shape(Color.parseColor("#4F46E5"), 18)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(42)
            setOnClickListener {
                saveToPortfolio(c)
            }
        }
        card.addView(btnSave, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(6) })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL

            val btnDismiss = Button(this@FloatingOcrService).apply {
                text = "收起"
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                background = shape(Color.parseColor("#334155"), 16)
                setOnClickListener {
                    resetBubble()
                }
            }
            addView(btnDismiss, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })

            val btnClose = Button(this@FloatingOcrService).apply {
                text = "退出悬浮"
                textSize = 12f
                setTextColor(Color.parseColor("#FB7185"))
                background = shape(Color.parseColor("#334155"), 16)
                setOnClickListener {
                    stopSelf()
                }
            }
            addView(btnClose, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        card.addView(btnRow)
    }

    private fun saveToPortfolio(c: HoldingCandidate) {
        val code = c.code ?: run {
            Toast.makeText(this, "未能识别基金代码，无法自动存入", Toast.LENGTH_SHORT).show()
            return
        }
        val date = c.confirmedDate ?: todayStr()
        val shares = c.shares ?: BigDecimal.ZERO
        val cost = c.cost ?: BigDecimal.ZERO

        try {
            val store = LocalStore(this)
            val currentPortfolio = store.load()
            val alreadyExists = currentPortfolio.holdings.any { it.code == code }
            val mode = if (alreadyExists) {
                if (c.shares != null && c.cost != null) ImportMode.UPDATE else ImportMode.WATCH
            } else {
                if (c.shares != null && c.cost != null) ImportMode.HOLDING else ImportMode.WATCH
            }

            val reviewed = ReviewedHolding(
                code = code,
                name = c.name,
                shares = if (c.shares != null) shares else null,
                cost = if (c.cost != null) cost else null,
                confirmedDate = date,
                mode = mode
            )

            val updated = ReviewedImport.apply(currentPortfolio, listOf(reviewed), "default")
            store.save(updated)
            store.close()

            vibrate()
            Toast.makeText(this, "已记账：${c.name} (${if (alreadyExists) "已校准持仓" else "已新增持仓"})", Toast.LENGTH_SHORT).show()
            resetBubble()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "保存持仓失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(50)
        }
    }

    private fun resetBubble(text: String = "⚡ 识别当前基金") {
        isRecognizing = false
        bubbleText?.text = text
        cardView?.visibility = View.GONE
        bubbleView?.visibility = View.VISIBLE
    }

    private fun todayStr(): String = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()

    override fun onDestroy() {
        executor.shutdownNow()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        rootView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }
}
