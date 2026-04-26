package com.kooo.evcam.v2.ui.settings

import android.os.Bundle
import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.settings.V2AvoidanceSettings
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.settings.V2CustomKeySettings
import com.kooo.evcam.v2.settings.V2FisheyeSettings
import com.kooo.evcam.v2.settings.V2KeepAliveSettings
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import com.kooo.evcam.v2.settings.V2VehicleModelSettings

class V2SettingsActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private var showingPermissionPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i("V2SettingsActivity", "onCreate")
        root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.page_background))
        }
        setContentView(root)
        showHomePage()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                V2AppLog.i("V2SettingsActivity", "back pressed permissionPage=$showingPermissionPage")
                if (showingPermissionPage) showHomePage() else finish()
            }
        })
    }

    override fun onDestroy() {
        V2AppLog.i("V2SettingsActivity", "onDestroy: hide fisheye preview overlay")
        startFisheyeService(V2CameraForegroundService.ACTION_HIDE_FISHEYE_PREVIEW)
        super.onDestroy()
    }

    private fun showHomePage() {
        showingPermissionPage = false
        V2AppLog.i("V2SettingsActivity", "show home page")
        root.removeAllViews()
        root.addView(createPage("软件设置", "⌂", { finish() }, createHomeContent()), fullScreenParams())
    }

    private fun showPermissionPage() {
        showingPermissionPage = true
        V2AppLog.i("V2SettingsActivity", "show permission page")
        root.removeAllViews()
        root.addView(
            createPage(
                "权限设置",
                "←",
                { showHomePage() },
                V2PermissionSettingsDialog.createPageView(this, showTitle = false)
            ),
            fullScreenParams()
        )
    }

    private fun createPage(title: String, buttonText: String, onButtonClick: () -> Unit, contentView: View): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.page_background))
        }
        page.addView(header(title, buttonText, onButtonClick))
        page.addView(contentView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        return page
    }

    private fun createHomeContent(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.page_background))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        scroll.addView(content)

        content.addView(versionCard())
        content.addView(vehicleModelCard())
        content.addView(entryCard(
            title = "权限设置",
            subtitle = "ADB 一键获取、系统白名单、电池优化、悬浮窗、无障碍等",
            buttonText = "进入 →",
            onClick = { showPermissionPage() }
        ))
        content.addView(entryCard(
            title = "保存日志",
            subtitle = "保存本次运行日志，路径沿用旧版 EVCam_Log 目录设计",
            buttonText = "保存 →",
            onClick = { saveLogs() }
        ))
        content.addView(startupSwitchCard())
        content.addView(recordingSwitchCard())
        content.addView(keepAliveSwitchCard())
        content.addView(preventSleepSwitchCard())
        content.addView(storageCleanupCard())
        content.addView(avoidanceBehaviorCard())
        content.addView(fisheyeSwitchCard())
        content.addView(blindSpotCard())
        content.addView(customKeyCard())
        return scroll
    }

    private fun cardContainer(bottomMarginDp: Int = 16): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ContextCompat.getDrawable(this@V2SettingsActivity, R.drawable.v2_control_button_bg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp)
        }
    }

    private fun cardTexts(
        title: String,
        subtitle: String,
        subtitleEndPaddingDp: Int = 12,
        useWeight: Boolean = true
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = if (useWeight) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(TextView(this@V2SettingsActivity).apply {
            text = title
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        })
        addView(TextView(this@V2SettingsActivity).apply {
            text = subtitle
            textSize = 14f
            setPadding(0, dp(4), dp(subtitleEndPaddingDp), 0)
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_secondary))
        })
    }

    private fun cardRow(bottomMarginDp: Int = 16, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ContextCompat.getDrawable(this@V2SettingsActivity, R.drawable.v2_control_button_bg)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp)
        }
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun switchRow(enabled: Boolean = true, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        isClickable = enabled
        isFocusable = enabled
        if (onClick != null) setOnClickListener { if (enabled) onClick() }
    }

    private fun propIdSwitchCard(
        title: String,
        subtitle: String,
        propId: Int,
        defaultPropId: Int,
        checked: Boolean,
        invalidToast: String,
        successToast: String,
        logPrefix: String,
        refreshAction: String,
        propIdReader: () -> Int,
        propIdWriter: (Int) -> Unit,
        enabledWriter: (Boolean) -> Unit
    ): View {
        val row = cardContainer()
        row.addView(cardTexts(title, subtitle, useWeight = false))

        val propEdit = EditText(this).apply {
            setText(propId.toString())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine(true)
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_secondary))
            hint = defaultPropId.toString()
        }
        val switch = Switch(this).apply { isChecked = checked }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(propEdit, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        controls.addView(switch)

        fun saveAndRefresh(showToast: Boolean) {
            val inputPropId = propEdit.text?.toString()?.trim()?.toIntOrNull()
            if (inputPropId == null || inputPropId <= 0) {
                V2AppLog.w("V2SettingsActivity", "invalid $logPrefix propId input=${propEdit.text}")
                Toast.makeText(this, invalidToast, Toast.LENGTH_SHORT).show()
                propEdit.setText(propIdReader().toString())
                return
            }
            propIdWriter(inputPropId)
            enabledWriter(switch.isChecked)
            V2AppLog.i("V2SettingsActivity", "$logPrefix enabled=${switch.isChecked} propId=$inputPropId")
            ContextCompat.startForegroundService(this, Intent(this, V2CameraForegroundService::class.java).apply {
                action = refreshAction
            })
            if (showToast) Toast.makeText(this, successToast, Toast.LENGTH_SHORT).show()
        }

        switch.setOnCheckedChangeListener { _, _ -> saveAndRefresh(true) }
        propEdit.setOnEditorActionListener { _, _, _ -> saveAndRefresh(true); true }
        propEdit.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAndRefresh(false) }
        row.setOnClickListener { switch.toggle() }
        row.addView(controls)
        return row
    }

    private fun saveLogs() {
        V2AppLog.i("V2SettingsActivity", "manual log export requested")
        val file = V2AppLog.exportCurrentLogs(this)
        if (file != null) {
            Toast.makeText(this, "日志已保存：${file.absolutePath}", Toast.LENGTH_LONG).show()
            V2AppLog.i("V2SettingsActivity", "manual log exported: ${file.absolutePath}")
        } else {
            Toast.makeText(this, "暂无日志可保存", Toast.LENGTH_SHORT).show()
            V2AppLog.w("V2SettingsActivity", "manual log export skipped: empty buffer")
        }
    }

    private fun vehicleModelCard(): View {
        val models = V2VehicleModelSettings.models
        val currentIndex = models.indexOfFirst { it.id == V2VehicleModelSettings.getModelId(this) }.coerceAtLeast(0)

        val row = cardRow()
        val texts = cardTexts(
            "车型配置",
            V2VehicleModelSettings.mappingSummary(this@V2SettingsActivity) + "\n使用当前预览布局，仅切换前后左右摄像头映射；更改后重启应用生效"
        )

        var initialized = false
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@V2SettingsActivity,
                android.R.layout.simple_spinner_item,
                models.map { it.label }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(currentIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!initialized) {
                        initialized = true
                        return
                    }
                    V2VehicleModelSettings.setModelId(this@V2SettingsActivity, models[position].id)
                    V2AppLog.i("V2SettingsActivity", "vehicle model changed to ${models[position].label} ${V2VehicleModelSettings.mappingSummary(this@V2SettingsActivity).replace('\n', ' ')}")
                    Toast.makeText(this@V2SettingsActivity, "车型已切换，重启应用后生效", Toast.LENGTH_SHORT).show()
                    showHomePage()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        row.setOnClickListener { spinner.performClick() }

        row.addView(texts)
        row.addView(spinner, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun startupSwitchCard(): View = switchCard(
        title = "开机自启动",
        subtitle = "车机开机后自动启动 EVCam V2",
        checked = V2StartupSettings.isAutoStartOnBoot(this),
        onCheckedChange = { enabled -> V2StartupSettings.setAutoStartOnBoot(this, enabled); V2AppLog.i("V2SettingsActivity", "autoStartOnBoot=$enabled") }
    )

    private fun recordingSwitchCard(): View = switchCard(
        title = "自动录制",
        subtitle = "软件启动后延迟 3 秒自动开始录制；开机自启动时同样生效",
        checked = V2StartupSettings.isAutoStartRecording(this),
        onCheckedChange = { enabled -> V2StartupSettings.setAutoStartRecording(this, enabled); V2AppLog.i("V2SettingsActivity", "autoStartRecording=$enabled") }
    )

    private fun keepAliveSwitchCard(): View = switchCard(
        title = "保活增强",
        subtitle = "启用广播、无障碍、ContentProvider、WorkManager 多路保活；不改变熄屏停止录制策略",
        checked = V2KeepAliveSettings.isKeepAliveEnabled(this),
        onCheckedChange = { enabled ->
            V2KeepAliveSettings.setKeepAliveEnabled(this, enabled)
            V2AppLog.i("V2SettingsActivity", "keepAliveEnabled=$enabled")
            if (enabled) {
                com.kooo.evcam.v2.service.V2KeepAliveScheduler.schedule(this)
                com.kooo.evcam.v2.service.V2KeepAliveReceiver.registerTimeTick(this)
            } else {
                com.kooo.evcam.v2.service.V2KeepAliveReceiver.unregisterTimeTick(this)
            }
        }
    )

    private fun preventSleepSwitchCard(): View = switchCard(
        title = "防止休眠",
        subtitle = "与旧版一致：开机自启动开启且本开关开启时，服务运行才持有 CPU WakeLock",
        checked = V2KeepAliveSettings.isPreventSleepEnabled(this),
        onCheckedChange = { enabled -> V2KeepAliveSettings.setPreventSleepEnabled(this, enabled); V2AppLog.i("V2SettingsActivity", "preventSleepEnabled=$enabled") }
    )

    private fun storageCleanupCard(): View {
        val row = cardContainer()
        row.addView(cardTexts(
            "空间清理",
            "设置预留空间；录像分段开始前检测，可用空间低于该值时滚动覆盖最旧录像\n${V2StorageCleanupSettings.summary(this)}",
            0,
            useWeight = false
        ))
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        inputRow.addView(TextView(this).apply {
            text = "预留空间(GB)"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(EditText(this).apply {
            setText(V2StorageCleanupSettings.reservedSpaceGb(this@V2SettingsActivity).toString())
            setSingleLine(true)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_secondary))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.trim()?.toIntOrNull() ?: 0
                    V2StorageCleanupSettings.setReservedSpaceGb(this@V2SettingsActivity, value)
                }
            })
        }, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(inputRow)
        return row
    }

    private fun avoidanceBehaviorCard(): View {
        val row = cardContainer()
        row.addView(cardTexts(
            "泊车/全景避让",
            "检测到泊车/APA 或全景/AVM 在前台时执行；目标退出后恢复进入前的前台、预览、录制状态\n${V2AvoidanceSettings.targetsSummary()}",
            0,
            useWeight = false
        ))
        row.addView(avoidanceSwitchRow("退出前台", V2AvoidanceSettings.BEHAVIOR_EXIT_FOREGROUND))
        row.addView(avoidanceSwitchRow("停止预览", V2AvoidanceSettings.BEHAVIOR_STOP_PREVIEW))
        row.addView(avoidanceSwitchRow("停止录制", V2AvoidanceSettings.BEHAVIOR_STOP_RECORDING))
        return row
    }

    private fun avoidanceSwitchRow(label: String, behavior: Int): View {
        val line = switchRow()
        line.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val switch = Switch(this).apply {
            isChecked = V2AvoidanceSettings.isBehaviorEnabled(this@V2SettingsActivity, behavior)
            setOnCheckedChangeListener { _, enabled ->
                V2AvoidanceSettings.setBehaviorEnabled(this@V2SettingsActivity, behavior, enabled)
                V2AppLog.i("V2SettingsActivity", "avoidance behavior changed $label=$enabled mask=${V2AvoidanceSettings.behaviorMask(this@V2SettingsActivity)}")
            }
        }
        line.setOnClickListener { switch.toggle() }
        line.addView(switch)
        return line
    }

    private fun fisheyeSwitchCard(): View {
        val row = cardContainer()
        row.addView(cardTexts(
            "鱼眼矫正",
            "四路独立参数；点击“预览”打开对应摄像头悬浮窗，修改 k1/k2/zoom 后实时刷新效果\n${V2FisheyeSettings.paramsSummary(this)}",
            0,
            useWeight = false
        ))

        val paramsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (V2FisheyeSettings.isEnabled(this@V2SettingsActivity)) View.VISIBLE else View.GONE
        }
        val enableRow = switchRow()
        enableRow.addView(TextView(this).apply {
            text = "启用鱼眼矫正"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val enableSwitch = Switch(this).apply {
            isChecked = V2FisheyeSettings.isEnabled(this@V2SettingsActivity)
            setOnCheckedChangeListener { _, enabled ->
                V2FisheyeSettings.setEnabled(this@V2SettingsActivity, enabled)
                paramsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
                V2AppLog.i("V2SettingsActivity", "fisheyeCorrection=$enabled")
                startFisheyeService(V2CameraForegroundService.ACTION_REFRESH_FISHEYE)
                Toast.makeText(this@V2SettingsActivity, if (enabled) "鱼眼矫正已开启" else "鱼眼矫正已关闭", Toast.LENGTH_SHORT).show()
            }
        }
        enableRow.setOnClickListener { enableSwitch.toggle() }
        enableRow.addView(enableSwitch)
        row.addView(enableRow)

        paramsContainer.addView(Button(this).apply {
            text = "恢复默认参数"
            textSize = 14f
            minHeight = dp(44)
            setOnClickListener {
                V2FisheyeSettings.resetAllParams(this@V2SettingsActivity)
                startFisheyeService(V2CameraForegroundService.ACTION_REFRESH_FISHEYE)
                Toast.makeText(this@V2SettingsActivity, "鱼眼参数已恢复默认", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(0, dp(8), 0, dp(8))
        })

        repeat(4) { index -> paramsContainer.addView(fisheyeParamRow(index)) }
        row.addView(paramsContainer)
        return row
    }

    private fun fisheyeParamRow(index: Int): View {
        val params = V2FisheyeSettings.paramsForIndex(this, index)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(TextView(this).apply {
            text = "${params.label} 摄像头"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        title.addView(Button(this).apply {
            text = "预览"
            textSize = 14f
            minHeight = dp(40)
            setOnClickListener {
                startFisheyeService(V2CameraForegroundService.ACTION_SHOW_FISHEYE_PREVIEW, index)
            }
        }, LinearLayout.LayoutParams(dp(86), dp(44)))
        line.addView(title)

        var currentK1 = params.k1
        var currentK2 = params.k2
        var currentZoom = params.zoom
        fun saveAndRefresh() {
            V2FisheyeSettings.setParams(this, index, currentK1, currentK2, currentZoom)
            startFisheyeService(V2CameraForegroundService.ACTION_REFRESH_FISHEYE)
            V2AppLog.i("V2SettingsActivity", "fisheye slider index=$index k1=$currentK1 k2=$currentK2 zoom=$currentZoom")
        }

        line.addView(fisheyeSliderRow("k1", -1.20f, 1.50f, currentK1) { currentK1 = it; saveAndRefresh() })
        line.addView(fisheyeSliderRow("k2", -0.80f, 0.80f, currentK2) { currentK2 = it; saveAndRefresh() })
        line.addView(fisheyeSliderRow("zoom", 0.80f, 2.00f, currentZoom) { currentZoom = it; saveAndRefresh() })
        return line
    }

    private fun fisheyeSliderRow(label: String, min: Float, rangeMax: Float, value: Float, onChanged: (Float) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val valueText = TextView(this).apply {
            text = "$label ${formatParam(value)}"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        }
        val seekBar = SeekBar(this).apply {
            max = 1000
            progress = (((value - min) / (rangeMax - min)) * this.max).toInt().coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val next = min + (rangeMax - min) * progress / 1000f
                    valueText.text = "$label ${formatParam(next)}"
                    onChanged(formatParam(next).toFloat())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        row.addView(valueText, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun formatParam(value: Float): String = String.format(java.util.Locale.US, "%.2f", value)

    private fun fisheyeParamEdit(label: String, value: Float): EditText = EditText(this).apply {
        tag = label
        hint = label
        setText(value.toString())
        setSingleLine(true)
        setOnFocusChangeListener { view, hasFocus -> if (hasFocus) (view as EditText).selectAll() }
        textSize = 14f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_secondary))
    }

    private fun saveFisheyeParamInputs(index: Int, container: ViewGroup, showToast: Boolean): Boolean {
        val edits = mutableMapOf<String, EditText>()
        collectFisheyeEdits(container, edits)
        val k1 = edits["k1"]?.text?.toString()?.trim()?.toFloatOrNull()
        val k2 = edits["k2"]?.text?.toString()?.trim()?.toFloatOrNull()
        val zoom = edits["zoom"]?.text?.toString()?.trim()?.toFloatOrNull()
        if (k1 == null || k2 == null || zoom == null || zoom <= 0f) {
            if (showToast) Toast.makeText(this, "鱼眼参数无效", Toast.LENGTH_SHORT).show()
            return false
        }
        V2FisheyeSettings.setParams(this, index, k1, k2, zoom)
        V2AppLog.i("V2SettingsActivity", "fisheye params changed index=$index k1=$k1 k2=$k2 zoom=$zoom")
        return true
    }

    private fun collectFisheyeEdits(view: View, out: MutableMap<String, EditText>) {
        if (view is EditText) out[view.tag?.toString().orEmpty()] = view
        if (view is ViewGroup) repeat(view.childCount) { collectFisheyeEdits(view.getChildAt(it), out) }
    }

    private fun startFisheyeService(actionName: String, cameraIndex: Int? = null) {
        ContextCompat.startForegroundService(this, Intent(this, V2CameraForegroundService::class.java).apply {
            action = actionName
            if (cameraIndex != null) putExtra(V2CameraForegroundService.EXTRA_CAMERA_INDEX, cameraIndex)
        })
    }

    private fun blindSpotCard(): View {
        return propIdSwitchCard(
            title = "转向补盲",
            subtitle = "监听 VHAL 转向灯属性；左=${V2BlindSpotSettings.LEFT_VALUE} 右=${V2BlindSpotSettings.RIGHT_VALUE} 关=${V2BlindSpotSettings.OFF_VALUE}；归零稳定 ${V2BlindSpotSettings.HIDE_DELAY_MS / 1000} 秒后关闭悬浮窗",
            propId = V2BlindSpotSettings.turnSignalPropId(this@V2SettingsActivity),
            defaultPropId = V2BlindSpotSettings.DEFAULT_TURN_SIGNAL_PROP_ID,
            checked = V2BlindSpotSettings.isEnabled(this@V2SettingsActivity),
            invalidToast = "转向灯属性ID无效",
            successToast = "补盲设置已生效",
            logPrefix = "blindSpot",
            refreshAction = V2CameraForegroundService.ACTION_REFRESH_BLIND_SPOT,
            propIdReader = { V2BlindSpotSettings.turnSignalPropId(this@V2SettingsActivity) },
            propIdWriter = { V2BlindSpotSettings.setTurnSignalPropId(this@V2SettingsActivity, it) },
            enabledWriter = { V2BlindSpotSettings.setEnabled(this@V2SettingsActivity, it) }
        )
    }

    private fun customKeyCard(): View {
        return propIdSwitchCard(
            title = "定制键调出/隐藏",
            subtitle = "监听 VHAL 按钮属性值变为 4，触发软件调出/隐藏",
            propId = V2CustomKeySettings.buttonPropId(this@V2SettingsActivity),
            defaultPropId = V2CustomKeySettings.DEFAULT_BUTTON_PROP_ID,
            checked = V2CustomKeySettings.isEnabled(this@V2SettingsActivity),
            invalidToast = "属性ID无效",
            successToast = "定制键设置已生效",
            logPrefix = "customKey",
            refreshAction = V2CameraForegroundService.ACTION_REFRESH_CUSTOM_KEY,
            propIdReader = { V2CustomKeySettings.buttonPropId(this@V2SettingsActivity) },
            propIdWriter = { V2CustomKeySettings.setButtonPropId(this@V2SettingsActivity, it) },
            enabledWriter = { V2CustomKeySettings.setEnabled(this@V2SettingsActivity, it) }
        )
    }

    private fun versionCard(): View = entryCard(
        title = "版本信息",
        subtitle = "EVCam V2\n版本：${versionName()}\n包名：$packageName",
        buttonText = null,
        onClick = null
    )

    private fun entryCard(title: String, subtitle: String, buttonText: String?, onClick: (() -> Unit)?): View {
        val row = cardRow(onClick = onClick)
        row.addView(cardTexts(title, subtitle, 0))
        if (buttonText != null && onClick != null) {
            row.addView(Button(this).apply {
                text = buttonText
                textSize = 16f
                minHeight = dp(48)
                setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.button_text))
                backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@V2SettingsActivity, R.color.button_background))
                setOnClickListener { onClick() }
            })
        }
        return row
    }

    private fun switchCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit
    ): View {
        val row = cardRow().apply {
            alpha = if (enabled) 1f else 0.5f
            isClickable = enabled
            isFocusable = enabled
        }
        val texts = cardTexts(title, subtitle)
        val switch = Switch(this).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
        }
        row.setOnClickListener { if (enabled) switch.toggle() }
        row.addView(texts)
        row.addView(switch)
        return row
    }

    private fun header(title: String, buttonText: String, onButtonClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(18), dp(18), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(98))

        addView(TextView(this@V2SettingsActivity).apply {
            text = title
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addView(TextView(this@V2SettingsActivity).apply {
            text = buttonText
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@V2SettingsActivity, R.color.button_text))
            background = ContextCompat.getDrawable(this@V2SettingsActivity, R.drawable.v2_control_button_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener { onButtonClick() }
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
    }

    private fun fullScreenParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun versionName(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    }
