package com.miradesktop.sunglasses

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlay: View? = null
    private var usageFlushThread: Thread? = null
    private var renderedOpacity: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rollbackPreview = Runnable {
        if (!AppConfig.isEnabled(this)) return@Runnable
        val safeOpacity = AppConfig.opacity(this).coerceAtMost(AppConfig.CONFIRM_THRESHOLD - 1)
        AppConfig.setOpacity(this, safeOpacity)
        updateOverlay(safeOpacity)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                mainHandler.removeCallbacks(rollbackPreview)
                AppConfig.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (AppConfig.isEnabled(this)) {
                    AppConfig.setEnabled(this, false)
                    stopSelf()
                } else if (canUseConfiguredOverlay()) {
                    AppConfig.setEnabled(this, true)
                    startOverlayService()
                }
                return START_NOT_STICKY
            }
            ACTION_REFRESH_MODE -> {
                if (!AppConfig.isEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startOverlayService()
                return START_STICKY
            }
            ACTION_UPDATE, ACTION_ROLLBACK -> {
                mainHandler.removeCallbacks(rollbackPreview)
                if (!hasActiveOverlay()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                updateOverlay(intent.getIntExtra(EXTRA_OPACITY, AppConfig.opacity(this)))
                return START_STICKY
            }
            ACTION_PREVIEW -> {
                if (!hasActiveOverlay()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val opacity = intent.getIntExtra(EXTRA_OPACITY, AppConfig.opacity(this))
                updateOverlay(opacity)
                mainHandler.removeCallbacks(rollbackPreview)
                if (AppConfig.isEnabled(this) && opacity > AppConfig.CONFIRM_THRESHOLD) {
                    mainHandler.postDelayed(rollbackPreview, 15_000)
                }
                return START_STICKY
            }
        }

        if (!canUseConfiguredOverlay() || !AppConfig.isEnabled(this)) {
            if (!canUseConfiguredOverlay()) AppConfig.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startOverlayService()
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(rollbackPreview)
        usageFlushThread?.interrupt()
        usageFlushThread = null
        AppConfig.flushUsage(this)
        removeOverlay()
        AccessibilityOverlayService.removeIfConnected()
        super.onDestroy()
    }

    private fun recreateOverlay() {
        removeOverlay()
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        val view = View(this).apply {
            setBackgroundColor(AppConfig.overlayColor(this@OverlayService, renderedOpacity))
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager?.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure { stopSelf() }
    }

    private fun startOverlayService() {
        renderedOpacity = null
        mainHandler.removeCallbacks(rollbackPreview)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (AppConfig.coverSystemBars(this) && AccessibilityOverlayService.isEnabled(this)) {
            removeOverlay()
            AccessibilityOverlayService.refreshIfConnected()
        } else {
            AccessibilityOverlayService.removeIfConnected()
            recreateOverlay()
        }
        if (AppConfig.isEnabled(this) && AppConfig.opacity(this) > AppConfig.CONFIRM_THRESHOLD) {
            mainHandler.postDelayed(rollbackPreview, 15_000)
        }
        startUsageFlushLoop()
    }

    private fun canUseConfiguredOverlay(): Boolean {
        return if (AppConfig.coverSystemBars(this)) {
            AccessibilityOverlayService.isEnabled(this)
        } else {
            Settings.canDrawOverlays(this)
        }
    }

    private fun hasActiveOverlay(): Boolean {
        return overlay != null || (
            AppConfig.coverSystemBars(this) && AccessibilityOverlayService.isEnabled(this)
        )
    }

    private fun updateOverlay(opacity: Int) {
        renderedOpacity = opacity.coerceIn(0, 100)
        if (AppConfig.coverSystemBars(this) && AccessibilityOverlayService.isEnabled(this)) {
            removeOverlay()
            AccessibilityOverlayService.refreshIfConnected(renderedOpacity)
        } else {
            overlay?.setBackgroundColor(AppConfig.overlayColor(this, opacity))
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(opacity))
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
        windowManager = null
    }

    private fun startUsageFlushLoop() {
        if (usageFlushThread?.isAlive == true) return
        usageFlushThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(60_000)
                    AppConfig.flushUsage(this)
                }
            } catch (_: InterruptedException) {}
        }.apply { start() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(opacity: Int = AppConfig.opacity(this)) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, opacity))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0,
                getString(if (AppConfig.isEnabled(this)) R.string.stop_overlay else R.string.start_overlay),
                PendingIntent.getService(
                    this,
                    1,
                    intent(this, ACTION_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .build()

    companion object {
        const val ACTION_START = "com.miradesktop.sunglasses.START"
        const val ACTION_UPDATE = "com.miradesktop.sunglasses.UPDATE"
        const val ACTION_PREVIEW = "com.miradesktop.sunglasses.PREVIEW"
        const val ACTION_ROLLBACK = "com.miradesktop.sunglasses.ROLLBACK"
        const val ACTION_STOP = "com.miradesktop.sunglasses.STOP"
        const val ACTION_TOGGLE = "com.miradesktop.sunglasses.TOGGLE"
        const val ACTION_REFRESH_MODE = "com.miradesktop.sunglasses.REFRESH_MODE"
        const val EXTRA_OPACITY = "opacity"
        private const val CHANNEL_ID = "android_glasses_overlay"
        private const val NOTIFICATION_ID = 1

        fun intent(context: Context, action: String) = Intent(context, OverlayService::class.java).apply {
            this.action = action
        }
    }
}
