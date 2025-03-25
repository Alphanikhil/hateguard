package com.example.nikkuisgoodboy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.nikkuisgoodboy.R
import com.example.nikkuisgoodboy.api.ContentAnalysisResult
import com.example.nikkuisgoodboy.api.DeepSeekAiClient
import com.example.nikkuisgoodboy.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var displayDensity: Int = 0

    private var handler: Handler? = null
    private var isCapturing = AtomicBoolean(false)

    private lateinit var windowManager: WindowManager
    private lateinit var overlayLayout: FrameLayout
    private var overlayAdded = false

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var deepSeekAiClient: DeepSeekAiClient? = null
    private lateinit var preferenceManager: PreferenceManager

    private var capturingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        preferenceManager = PreferenceManager(this)
        handler = Handler(Looper.getMainLooper())

        // Get screen metrics
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensity = metrics.densityDpi

        // Initialize the window manager and overlay layout for blocking content
        initOverlay()

        // Initialize DeepSeek AI client
        deepSeekAiClient = DeepSeekAiClient()

        // Check if API key is available in preferences for future use
        val apiKey = preferenceManager.getDeepSeekApiKey()
        if (apiKey.isEmpty()) {
            Log.w(TAG, "No API key found in preferences for DeepSeek AI")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Create notification channel for foreground service
        createNotificationChannel()

        // Start as foreground service with notification
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = intent.getParcelableExtra<Intent>("data")

        if (resultCode != 0 && data != null) {
            // Initialize media projection
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection != null) {
                // Set up virtual display and image reader
                setupVirtualDisplay()

                // Start capturing and analyzing screen content
                startCapturing()
            }
        } else {
            Log.e(TAG, "Invalid projection data")
            stopSelf()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Used for monitoring screen content"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_capture_notification_title))
            .setContentText(getString(R.string.screen_capture_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun setupVirtualDisplay() {
        // Create ImageReader instance
        imageReader = ImageReader.newInstance(
            displayWidth, displayHeight,
            PixelFormat.RGBA_8888, 2
        )

        // Create virtual display
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            displayWidth, displayHeight, displayDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private fun startCapturing() {
        if (isCapturing.get()) return

        isCapturing.set(true)

        capturingJob = serviceScope.launch {
            while (isCapturing.get()) {
                try {
                    // Capture screen content
                    val bitmap = captureScreen()

                    if (bitmap != null) {
                        // Get current scanning frequency from preferences
                        val scanFrequency = preferenceManager.getScanningFrequency()

                        // Check if current app is whitelisted
                        // Note: This is a placeholder implementation. You'll need to implement a way to get the current app package name
                        val currentAppPackage = "com.example.app" // This needs to be replaced with actual current app detection
                        val isWhitelisted = preferenceManager.isAppWhitelisted(currentAppPackage)

                        if (!isWhitelisted) {
                            // Analyze screen content using DeepSeek AI
                            deepSeekAiClient?.let { client ->
                                val result = client.analyzeImage(bitmap)

                                // Process analysis result
                                processAnalysisResult(result)
                            }
                        }

                        // Control scanning frequency to manage performance and battery usage
                        delay(scanFrequency.toLong())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during screen capture", e)
                    delay(1000) // Delay before retry on error
                }
            }
        }
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * displayWidth

            // Create bitmap from buffer data
            val bitmap = Bitmap.createBitmap(
                displayWidth + rowPadding / pixelStride,
                displayHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to exact screen size if needed
            return if (bitmap.width > displayWidth || bitmap.height > displayHeight) {
                Bitmap.createBitmap(bitmap, 0, 0, displayWidth, displayHeight)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            return null
        } finally {
            image.close()
        }
    }

    private suspend fun processAnalysisResult(result: ContentAnalysisResult) {
        if (result.error != null) {
            Log.e(TAG, "Analysis error: ${result.error}")
            return
        }

        val contentFilterLevel = preferenceManager.getContentFilterLevel()
        val threshold = when (contentFilterLevel) {
            1 -> 0.7f // Low (only block very explicit content)
            2 -> 0.5f // Medium
            3 -> 0.3f // High (block more aggressively)
            else -> 0.5f
        }

        if (result.isNsfw && result.nsfwScore >= threshold) {
            // Block NSFW content
            serviceScope.launch(Dispatchers.Main) {
                showContentBlockedOverlay()

                // Hide overlay after a short delay
                delay(5000)
                hideContentBlockedOverlay()
            }
        }
    }

    private fun initOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create overlay layout
        overlayLayout = FrameLayout(this).apply {
            setBackgroundColor(0xAA000000.toInt()) // Semi-transparent black

            // Add a "Content Blocked" text
            val textView = TextView(context).apply {
                text = getString(R.string.content_blocked)
                gravity = Gravity.CENTER
                textSize = 24f
                setTextColor(0xFFFFFFFF.toInt()) // White text
            }

            addView(textView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    private fun showContentBlockedOverlay() {
        if (!overlayAdded) {
            try {
                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )

                windowManager.addView(overlayLayout, layoutParams)
                overlayAdded = true
            } catch (e: Exception) {
                Log.e(TAG, "Error showing overlay", e)
            }
        }
    }

    private fun hideContentBlockedOverlay() {
        if (overlayAdded) {
            try {
                windowManager.removeView(overlayLayout)
                overlayAdded = false
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay", e)
            }
        }
    }

    private fun stopCapturing() {
        isCapturing.set(false)
        capturingJob?.cancel()
        capturingJob = null
    }

    override fun onDestroy() {
        stopCapturing()

        // Clean up virtual display
        virtualDisplay?.release()
        virtualDisplay = null

        // Clean up image reader
        imageReader?.close()
        imageReader = null

        // Clean up media projection
        mediaProjection?.stop()
        mediaProjection = null

        // Clean up service scope
        serviceScope.cancel()

        // Remove any active overlays
        if (overlayAdded) {
            hideContentBlockedOverlay()
        }

        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hateguard_screen_capture"
    }
}