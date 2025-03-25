package com.example.nikkuisgoodboy.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.nikkuisgoodboy.R
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var displayDensity: Int = 0

    private var isCapturing = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var capturingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Get screen metrics
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)
        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = intent.getParcelableExtra<Intent>("data")

        if (resultCode != 0 && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection != null) {
                setupVirtualDisplay()
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
                CHANNEL_ID, "Screen Capture Service", NotificationManager.IMPORTANCE_LOW
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
        imageReader = ImageReader.newInstance(
            displayWidth, displayHeight,
            ImageFormat.YUV_420_888,  // ✅ Fixed format issue
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", displayWidth, displayHeight, displayDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )
    }

    private fun startCapturing() {
        if (isCapturing.get()) return

        isCapturing.set(true)

        capturingJob = serviceScope.launch {
            while (isCapturing.get()) {
                try {
                    val bitmap = captureScreen()
                    if (bitmap != null) {
                        Log.d(TAG, "Captured screen successfully.")
                    }
                    delay(1000) // Capture every second
                } catch (e: Exception) {
                    Log.e(TAG, "Error during screen capture", e)
                    delay(1000)
                }
            }
        }
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            if (planes.isEmpty()) {
                Log.e(TAG, "No image data found.")
                return null
            }

            val buffer: ByteBuffer = planes[0].buffer ?: return null
            val rowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride
            val rowPadding = rowStride - pixelStride * displayWidth

            // Create bitmap safely
            val bitmap = Bitmap.createBitmap(
                displayWidth + rowPadding / pixelStride,
                displayHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to exact screen size
            Bitmap.createBitmap(bitmap, 0, 0, displayWidth, displayHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen", e)
            null
        } finally {
            image.close()
        }
    }

    private fun stopCapturing() {
        isCapturing.set(false)
        capturingJob?.cancel()
        capturingJob = null
    }

    override fun onDestroy() {
        stopCapturing()

        virtualDisplay?.let {
            it.release()
            virtualDisplay = null
        }

        mediaProjection?.let {
            it.stop()
            mediaProjection = null
        }

        imageReader?.close()

        serviceScope.cancel()
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
