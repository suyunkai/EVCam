package com.kooo.evcam.v2.ui.settings

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.permissions.AdbPermissionHelper
import com.kooo.evcam.v2.permissions.SystemWhitelistHelper

object V2PermissionSettingsDialog {
    private const val REQUEST_PERMISSIONS = 2101

    private var adbRunning = false
    private var whitelistRunning = false
    private var restoreRunning = false

    fun show(context: Context) {
        lateinit var dialog: AlertDialog
        val root = createPageView(context, showCloseButton = true) { dialog.dismiss() }
        dialog = AlertDialog.Builder(context)
            .setView(root)
            .show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    fun createPageView(
        context: Context,
        showCloseButton: Boolean = false,
        showTitle: Boolean = true,
        onClose: (() -> Unit)? = null
    ): View {
        val refreshers = mutableListOf<() -> Unit>()
        val refreshAll = { refreshers.forEach { it() } }
        val root = ScrollView(context).apply {
            setBackgroundColor(color(context, R.color.page_background))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 8))
        }
        root.addView(content)

        if (showTitle) {
            content.addView(title(context, "权限设置"))
        }
        content.addView(description(context, "请确保以下权限已授予，以保证应用正常运行"))
        content.addView(adbCard(context, refreshAll))
        content.addView(sectionTitle(context, "基础权限"))
        content.addView(permissionRow(context, "相机权限", { statusText(hasPermission(context, Manifest.permission.CAMERA), "用于录制视频和预览") }, refreshers) {
            requestRuntimePermissions(context, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        })
        content.addView(permissionRow(context, "麦克风权限", { statusText(hasPermission(context, Manifest.permission.RECORD_AUDIO), "用于录制音频权限占位") }, refreshers) {
            requestRuntimePermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            content.addView(permissionRow(context, "通知权限", { statusText(hasPermission(context, Manifest.permission.POST_NOTIFICATIONS), "用于显示前台录制服务通知") }, refreshers) {
                requestRuntimePermissions(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            })
        }

        content.addView(sectionTitle(context, "高级权限"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            content.addView(permissionRow(context, "所有文件访问权限", { statusText(Environment.isExternalStorageManager(), "用于访问U盘和公共目录") }, refreshers) {
                openManageAllFiles(context)
            })
        }
        content.addView(permissionRow(context, "悬浮窗权限", { statusText(Settings.canDrawOverlays(context), "用于悬浮窗和后台唤醒") }, refreshers) {
            openOverlaySettings(context)
        })
        content.addView(permissionRow(context, "无障碍服务", { statusText(isAccessibilityEnabled(context), "防止应用被系统清理") }, refreshers, "去启用") {
            openAccessibilitySettings(context)
        })
        content.addView(permissionRow(context, "使用情况访问权限", { statusText(hasUsageStatsPermission(context), "全景/泊车避让需要检测前台应用") }, refreshers) {
            openUsageStatsSettings(context)
        })
        content.addView(permissionRow(context, "电池优化", { statusText(isIgnoringBatteryOptimizations(context), "关闭可防止应用被系统休眠") }, refreshers, "去设置") {
            requestIgnoreBatteryOptimizations(context)
        })

        content.addView(sectionTitle(context, "系统级保活"))
        content.addView(systemWhitelistCard(context))
        if (showCloseButton) {
            val closeButton = button(context, "关闭", color(context, R.color.button_background)).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(context, 8)
                }
            }
            closeButton.setOnClickListener { onClose?.invoke() }
            content.addView(closeButton)
        }
        return root
    }

    private fun adbCard(context: Context, refreshAll: () -> Unit): View {
        val log = logView(context)
        val button = button(context, "一键获取权限", 0xFF4CAF50.toInt())
        val card = verticalCard(context)
        card.addView(cardTitle(context, "ADB 一键获取权限"))
        card.addView(cardSubtitle(context, "通过本机 ADB (localhost:5555) 自动授予所有权限"))
        card.addView(button)
        card.addView(log.container)
        button.setOnClickListener {
            if (adbRunning) return@setOnClickListener
            adbRunning = true
            button.isEnabled = false
            button.text = "正在执行..."
            log.show()
            log.text.text = ""
            AdbPermissionHelper(context).grantAllPermissions(object : AdbPermissionHelper.Callback {
                override fun onLog(message: String) = log.append(message)
                override fun onComplete(allSuccess: Boolean) {
                    adbRunning = false
                    button.isEnabled = true
                    button.text = "一键获取权限"
                    refreshAll()
                    Toast.makeText(context, if (allSuccess) "权限获取完成" else "部分权限获取失败", Toast.LENGTH_SHORT).show()
                }
            })
        }
        return card
    }

    private fun systemWhitelistCard(context: Context): View {
        val whitelistLog = logView(context)
        val restoreLog = logView(context)
        val setupButton = button(context, "一键配置", 0xFF2196F3.toInt())
        val restoreButton = button(context, "恢复系统白名单", 0xFFFF9800.toInt()).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(context, 8)
        }
        val card = verticalCard(context)
        card.addView(cardTitle(context, "银河E5（E245）系统白名单"))
        card.addView(cardSubtitle(context, "将 EVCam 添加到车机系统启动列表和后台白名单，防止深度睡眠后被杀"))
        card.addView(setupButton)
        card.addView(whitelistLog.container)
        card.addView(restoreButton)
        card.addView(restoreLog.container)

        setupButton.setOnClickListener {
            showWhitelistRiskDialog(context) {
                runWhitelist(context, setupButton, whitelistLog, restore = false)
            }
        }
        restoreButton.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("恢复确认")
                .setMessage("此操作将从备份恢复车机系统白名单配置，恢复后需要重启车机。确认恢复？")
                .setPositiveButton("确认恢复") { _, _ -> runWhitelist(context, restoreButton, restoreLog, restore = true) }
                .setNegativeButton("取消", null)
                .show()
        }
        return card
    }

    private fun runWhitelist(context: Context, button: Button, log: LogViews, restore: Boolean) {
        if ((restore && restoreRunning) || (!restore && whitelistRunning)) return
        if (restore) restoreRunning = true else whitelistRunning = true
        button.isEnabled = false
        button.text = if (restore) "正在恢复..." else "正在执行..."
        log.show()
        log.text.text = ""
        val helper = SystemWhitelistHelper(context)
        val callback = object : SystemWhitelistHelper.Callback {
            override fun onLog(message: String) = log.append(message)
            override fun onComplete(success: Boolean) {
                if (restore) restoreRunning = false else whitelistRunning = false
                button.isEnabled = true
                button.text = if (restore) "恢复系统白名单" else "一键配置"
                Toast.makeText(context, if (success) "执行完成，请重启车机" else "执行失败，请查看日志", Toast.LENGTH_SHORT).show()
            }
        }
        if (restore) helper.executeWhitelistRestore(callback) else helper.executeWhitelistSetup(callback)
    }

    private data class LogViews(val container: ScrollView, val text: TextView) {
        fun show() { container.visibility = View.VISIBLE }
        fun append(message: String) {
            text.append(message + "\n")
            container.post { container.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun logView(context: Context): LogViews {
        val text = TextView(context).apply {
            setTextColor(color(context, R.color.text_primary))
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(context).apply {
            visibility = View.GONE
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setBackgroundColor(color(context, R.color.page_background))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 220)).apply {
                topMargin = dp(context, 12)
            }
            addView(text)
        }
        return LogViews(scroll, text)
    }

    private fun showWhitelistRiskDialog(context: Context, onConfirmed: () -> Unit) {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
            background = cardBackground(context)
        }
        box.addView(title(context, "风险提醒"))
        box.addView(TextView(context).apply {
            text = "此操作将修改车机系统分区配置文件，请仔细确认：\n\n" +
                "1. 仅适用于银河E5（E245）车机\n" +
                "2. 需要设备已打开USB调试\n" +
                "3. 将修改 system/vendor 分区配置文件\n" +
                "4. 修改前会自动备份原文件\n" +
                "5. 修改完成后需要重启车机才能生效\n" +
                "6. 如果设备不是 E245，脚本会自动检测并中止"
            setTextColor(color(context, R.color.text_primary))
            textSize = 14f
        })
        val check = CheckBox(context).apply {
            text = "我已知晓风险，确认继续"
            setTextColor(color(context, R.color.text_primary))
        }
        box.addView(check)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 8), 0, 0)
        }
        val cancel = button(context, "取消", color(context, R.color.button_background)).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(context, 8)
            }
        }
        val confirm = button(context, "确认执行", 0xFF2196F3.toInt()).apply {
            isEnabled = false
            alpha = 0.45f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        buttons.addView(cancel)
        buttons.addView(confirm)
        box.addView(buttons)

        val dialog = AlertDialog.Builder(context)
            .setView(box)
            .show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        cancel.setOnClickListener { dialog.dismiss() }
        check.setOnCheckedChangeListener { _, checked ->
            confirm.isEnabled = checked
            confirm.alpha = if (checked) 1f else 0.45f
        }
        confirm.setOnClickListener {
            if (!check.isChecked) return@setOnClickListener
            dialog.dismiss()
            onConfirmed()
        }
    }

    private fun permissionRow(
        context: Context,
        title: String,
        subtitleProvider: () -> String,
        refreshers: MutableList<() -> Unit>,
        buttonText: String = "去授权",
        onClick: () -> Unit
    ): View = cardRow(context, title, subtitleProvider(), buttonText, onClick).also { row ->
        val statusView = (((row as? LinearLayout)?.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? TextView)
        refreshers.add { statusView?.text = subtitleProvider() }
    }

    private fun cardRow(context: Context, title: String, subtitle: String, buttonText: String?, onClick: (() -> Unit)?): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
            background = cardBackground(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(context, 8)
            }
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(cardTitle(context, title))
        texts.addView(cardSubtitle(context, subtitle))
        row.addView(texts)
        if (buttonText != null && onClick != null) row.addView(button(context, buttonText, color(context, R.color.button_background)).apply { setOnClickListener { onClick() } })
        return row
    }

    private fun verticalCard(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        background = cardBackground(context)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 16)
        }
    }

    private fun button(context: Context, textValue: String, colorValue: Int): Button = Button(context).apply {
        text = textValue
        textSize = 14f
        minHeight = dp(context, 40)
        setTextColor(color(context, R.color.button_text))
        backgroundTintList = android.content.res.ColorStateList.valueOf(colorValue)
    }

    private fun title(context: Context, textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(context, R.color.text_primary))
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(context, 16))
    }

    private fun description(context: Context, textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(context, R.color.text_secondary))
        textSize = 14f
        setPadding(0, 0, 0, dp(context, 16))
    }

    private fun sectionTitle(context: Context, textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(context, R.color.text_primary))
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(context, 8), 0, dp(context, 12))
    }

    private fun cardTitle(context: Context, textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(context, R.color.text_primary))
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun cardSubtitle(context: Context, textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(context, R.color.text_secondary))
        textSize = 14f
        setPadding(0, dp(context, 3), 0, dp(context, 8))
    }

    private fun statusText(granted: Boolean, description: String): String = "$description\n${if (granted) "已授权 ✓" else "未授权 ✗"}"

    private fun requestRuntimePermissions(context: Context, permissions: Array<String>) {
        val activity = context as? Activity
        if (activity != null) ActivityCompat.requestPermissions(activity, permissions, REQUEST_PERMISSIONS) else openAppSettings(context)
    }

    private fun openAppSettings(context: Context) = safeStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }, Intent(Settings.ACTION_SETTINGS))

    private fun openManageAllFiles(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) safeStart(context,
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") },
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }
        )
    }

    private fun openOverlaySettings(context: Context) = safeStart(context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }
    )

    private fun openAccessibilitySettings(context: Context) {
        safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        Toast.makeText(context, "请找到「电车记录仪 V2」相关服务并启用", Toast.LENGTH_LONG).show()
    }

    private fun openUsageStatsSettings(context: Context) = safeStart(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    private fun requestIgnoreBatteryOptimizations(context: Context) = safeStart(context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") },
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") }
    )

    private fun safeStart(context: Context, vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
        }
        Toast.makeText(context, "无法打开设置页面", Toast.LENGTH_SHORT).show()
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) return false
        val services = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return services.split(':').any { it.contains(context.packageName, ignoreCase = true) }
    }

    private fun cardBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(color(context, R.color.card_background))
        cornerRadius = dp(context, 12).toFloat()
    }

    private fun color(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)
    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
