package com.miradesktop.sunglasses

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

class AccessibilityOverlayService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var overlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshOverlay()
        if (AppConfig.isEnabled(this)) {
            ContextCompat.startForegroundService(
                this,
                OverlayService.intent(this, OverlayService.ACTION_REFRESH_MODE)
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun refreshOverlay(opacityOverride: Int? = null) {
        if (!AppConfig.isEnabled(this) || !AppConfig.coverSystemBars(this)) {
            removeOverlay()
            return
        }

        val color = AppConfig.overlayColor(this, opacityOverride)
        overlay?.let {
            it.setBackgroundColor(color)
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val view = View(this).apply { setBackgroundColor(color) }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager?.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure { windowManager = null }
    }

    fun removeOverlay() {
        overlay?.let { runCatching { windowManager?.removeView(it) } }
        overlay = null
        windowManager = null
    }

    companion object {
        @Volatile private var instance: AccessibilityOverlayService? = null

        fun refreshIfConnected(opacityOverride: Int? = null) {
            instance?.refreshOverlay(opacityOverride)
        }

        fun removeIfConnected() {
            instance?.removeOverlay()
        }

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AccessibilityOverlayService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { TextUtils.equals(it, expected) }
        }

        fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
