package com.miradesktop.sunglasses

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.miradesktop.sunglasses.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var confirmedOpacity = AppConfig.DEFAULT_OPACITY
    private var confirmationTimer: CountDownTimer? = null
    private var confirmationDialog: AlertDialog? = null
    private var isRendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        renderSettings()
        renderPermissionStatuses()
        applyHideFromRecents(AppConfig.hideFromRecents(this))
        if (AppConfig.isEnabled(this) && canUseConfiguredOverlay()) startOverlay()
    }

    override fun onDestroy() {
        cancelConfirmationUi()
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.opacityLabel.text = getString(R.string.opacity_value, progress)
                if (fromUser && AppConfig.isEnabled(this@MainActivity)) {
                    sendOverlay(OverlayService.ACTION_PREVIEW, progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = seekBar?.progress ?: confirmedOpacity
                if (
                    AppConfig.isEnabled(this@MainActivity)
                    && value > AppConfig.CONFIRM_THRESHOLD
                    && value != confirmedOpacity
                ) {
                    showOpacityConfirmation(value)
                } else {
                    commitOpacity(value)
                }
            }
        })

        binding.overlayToggleButton.setOnClickListener {
            val enabled = !AppConfig.isEnabled(this)
            if (enabled && !canUseConfiguredOverlay()) {
                requestConfiguredPermission()
                return@setOnClickListener
            }
            AppConfig.setEnabled(this, enabled)
            if (enabled) {
                startOverlay()
                if (AppConfig.opacity(this) > AppConfig.CONFIRM_THRESHOLD) {
                    showOpacityConfirmation(AppConfig.opacity(this))
                }
            } else {
                cancelConfirmationUi()
                stopOverlay()
            }
            renderOverlayToggleButton()
        }

        binding.coverSystemBarsSwitch.setOnCheckedChangeListener { _, enabled ->
            if (isRendering) return@setOnCheckedChangeListener
            AppConfig.setCoverSystemBars(this, enabled)
            renderPermissionStatuses()
            if (enabled && !AccessibilityOverlayService.isEnabled(this)) requestAccessibilityPermission()
            if (AppConfig.isEnabled(this) && canUseConfiguredOverlay()) startOverlay()
        }

        binding.hideFromRecentsSwitch.setOnCheckedChangeListener { _, enabled ->
            if (isRendering) return@setOnCheckedChangeListener
            AppConfig.setHideFromRecents(this, enabled)
            applyHideFromRecents(enabled)
        }

        binding.openOverlayPermissionButton.setOnClickListener { requestOverlayPermission() }
        binding.openAccessibilityPermissionButton.setOnClickListener { requestAccessibilityPermission() }
        binding.openNotificationPermissionButton.setOnClickListener { requestNotificationPermission() }
        binding.applyColorButton.setOnClickListener { applyColor() }
        binding.openGithubButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPOSITORY_URL)))
        }
    }

    private fun renderSettings() {
        isRendering = true
        confirmedOpacity = AppConfig.opacity(this)
        renderOverlayToggleButton()
        binding.opacitySlider.progress = confirmedOpacity
        binding.opacityLabel.text = getString(R.string.opacity_value, confirmedOpacity)
        binding.colorInput.setText(AppConfig.color(this))
        binding.coverSystemBarsSwitch.isChecked = AppConfig.coverSystemBars(this)
        binding.hideFromRecentsSwitch.isChecked = AppConfig.hideFromRecents(this)
        isRendering = false
    }

    private fun renderOverlayToggleButton() {
        val enabled = AppConfig.isEnabled(this)
        binding.overlayToggleButton.text = getString(
            if (enabled) R.string.disable_overlay else R.string.enable_overlay
        )
        binding.overlayToggleButton.backgroundTintList = ColorStateList.valueOf(
            if (enabled) Color.rgb(220, 38, 38) else Color.rgb(22, 163, 74)
        )
        binding.overlayToggleButton.setTextColor(Color.WHITE)
    }

    private fun renderPermissionStatuses() {
        val coverSystemBars = AppConfig.coverSystemBars(this)
        renderPermissionStatus(
            binding.overlayPermissionStatus,
            getString(R.string.overlay_permission_name),
            Settings.canDrawOverlays(this),
            required = !coverSystemBars
        )
        renderPermissionStatus(
            binding.accessibilityPermissionStatus,
            getString(R.string.accessibility_permission_name),
            AccessibilityOverlayService.isEnabled(this),
            required = coverSystemBars
        )
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        renderPermissionStatus(
            binding.notificationPermissionStatus,
            getString(R.string.notification_permission_name),
            runtimeGranted && NotificationManagerCompat.from(this).areNotificationsEnabled(),
            required = true
        )
    }

    private fun renderPermissionStatus(
        view: TextView,
        permissionName: String,
        granted: Boolean,
        required: Boolean
    ) {
        view.text = getString(
            when {
                granted && required -> R.string.permission_status_granted_required
                granted -> R.string.permission_status_granted
                required -> R.string.permission_status_missing_required
                else -> R.string.permission_status_missing
            },
            permissionName
        )
        view.setTextColor(if (granted) Color.rgb(22, 101, 52) else Color.rgb(185, 28, 28))
    }

    private fun applyHideFromRecents(hide: Boolean) {
        runCatching {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.appTasks.firstOrNull()?.setExcludeFromRecents(hide)
        }
    }

    private fun applyColor() {
        val normalized = AppConfig.normalizeColor(binding.colorInput.text?.toString())
        if (normalized == null) {
            binding.colorInput.error = getString(R.string.invalid_color)
            return
        }
        binding.colorInput.error = null
        binding.colorInput.setText(normalized)
        AppConfig.setColor(this, normalized)
        if (AppConfig.isEnabled(this)) startOverlay()
    }

    private fun commitOpacity(value: Int) {
        cancelConfirmationUi()
        confirmedOpacity = value.coerceIn(0, 100)
        AppConfig.setOpacity(this, confirmedOpacity)
        binding.opacitySlider.progress = confirmedOpacity
        if (AppConfig.isEnabled(this)) {
            sendOverlay(OverlayService.ACTION_UPDATE, confirmedOpacity)
        }
    }

    private fun showOpacityConfirmation(value: Int) {
        cancelConfirmationUi()
        val message = TextView(this).apply {
            setPadding(dp(24), dp(12), dp(24), dp(6))
            textSize = 15f
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_high_opacity)
            .setView(message)
            .setNegativeButton(R.string.rollback_now) { _, _ -> rollbackOpacity() }
            .setPositiveButton(R.string.keep_setting) { _, _ -> commitOpacity(value) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { rollbackOpacity() }
        dialog.setOnDismissListener {
            confirmationTimer?.cancel()
            confirmationTimer = null
            confirmationDialog = null
        }
        confirmationDialog = dialog
        dialog.show()
        confirmationTimer = object : CountDownTimer(15_000, 250) {
            override fun onTick(remaining: Long) {
                message.text = getString(
                    R.string.confirm_high_opacity_message,
                    value,
                    ((remaining + 999) / 1000).toInt()
                )
            }

            override fun onFinish() = rollbackOpacity()
        }.start()
    }

    private fun rollbackOpacity() {
        cancelConfirmationUi()
        val safeOpacity = confirmedOpacity.coerceAtMost(AppConfig.CONFIRM_THRESHOLD - 1)
        confirmedOpacity = safeOpacity
        AppConfig.setOpacity(this, safeOpacity)
        binding.opacitySlider.progress = safeOpacity
        if (AppConfig.isEnabled(this)) {
            sendOverlay(OverlayService.ACTION_ROLLBACK, safeOpacity)
        }
    }

    private fun cancelConfirmationUi() {
        confirmationTimer?.cancel()
        confirmationTimer = null
        confirmationDialog?.setOnDismissListener(null)
        confirmationDialog?.dismiss()
        confirmationDialog = null
    }

    private fun startOverlay() {
        if (!canUseConfiguredOverlay()) {
            requestConfiguredPermission()
            return
        }
        ContextCompat.startForegroundService(
            this,
            OverlayService.intent(this, OverlayService.ACTION_START)
        )
    }

    private fun stopOverlay() {
        startService(OverlayService.intent(this, OverlayService.ACTION_STOP))
    }

    private fun sendOverlay(action: String, opacity: Int) {
        startService(OverlayService.intent(this, action).putExtra(OverlayService.EXTRA_OPACITY, opacity))
    }

    private fun canUseConfiguredOverlay(): Boolean {
        return if (AppConfig.coverSystemBars(this)) {
            AccessibilityOverlayService.isEnabled(this)
        } else {
            Settings.canDrawOverlays(this)
        }
    }

    private fun requestConfiguredPermission() {
        if (AppConfig.coverSystemBars(this)) requestAccessibilityPermission()
        else requestOverlayPermission()
    }

    private fun requestAccessibilityPermission() {
        startActivity(AccessibilityOverlayService.settingsIntent())
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun requestNotificationPermission() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val GITHUB_REPOSITORY_URL = "https://github.com/Lumi083/Sunglasses"
    }
}
