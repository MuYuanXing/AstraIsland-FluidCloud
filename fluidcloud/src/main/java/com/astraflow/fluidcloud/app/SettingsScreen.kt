@file:OptIn(ExperimentalCupertinoApi::class)

package com.astraflow.fluidcloud.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.astraflow.fluidcloud.AdapterBridge
import com.astraflow.fluidcloud.FluidCloudSettings
import com.astraisland.events.EventBridge
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zone.ien.hig.CupertinoAlertDialog
import zone.ien.hig.CupertinoNavigationTitle
import zone.ien.hig.CupertinoScaffold
import zone.ien.hig.CupertinoText
import zone.ien.hig.CupertinoTopAppBar
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.cancel
import zone.ien.hig.default
import zone.ien.hig.section.CupertinoSectionDefaults
import zone.ien.hig.section.LazySectionScope
import zone.ien.hig.section.SectionItem
import zone.ien.hig.section.SectionLink
import zone.ien.hig.section.SectionScope
import zone.ien.hig.section.section
import zone.ien.hig.section.switch
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.darkColorScheme
import zone.ien.hig.theme.lightColorScheme

/*
 * 插件的设置页:原样用上游 compose-hig 的页面骨架、顶部栏、大标题与分组列表(和星流的页面同一套部件)。
 * 最上面一组写插件此刻能不能用,不能用时最前面的那一条原因写在对应一行的名字下面;下面是接入开关、四类内容的开关
 * 与「作为主岛显示」四项。页面底色上不写说明文字,设置的作用、连带关系与例外写在右上角问号的使用说明里(HelpSheet)。
 */

/** 上游的分组行要在「分组」里面写;借这一个调用上游的行。 */
private object Rows : SectionScope

private const val TITLE = "流体云事件接入"

/** 设置页打开后等系统界面里的插件回报多久;到时还没回报就算插件没在系统界面里运行 */
private const val STATUS_WAIT_MS = 2_000L

@Composable
fun SettingsScreen() {
    val dark = isSystemInDarkTheme()
    CupertinoTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Page()
    }
}

@Composable
private fun Page() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by PluginSettings.enabled.collectAsState()
    val settings by PluginSettings.settings.collectAsState()
    val statusPrefs = remember { StatusStore.prefs(context) }
    var statusVersion by remember { mutableIntStateOf(0) }
    var checked by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    DisposableEffect(statusPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> statusVersion++ }
        statusPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { statusPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 每次回到这一页都请系统界面里的插件重新报一遍
    LifecycleResumeEffect(Unit) {
        checked = false
        StatusStore.refresh(context)
        val wait = scope.launch { delay(STATUS_WAIT_MS); checked = true }
        onPauseOrDispose { wait.cancel() }
    }
    val health = remember(statusVersion, enabled, checked) {
        Health(
            enabled = enabled,
            running = statusPrefs.getString(PluginStatus.KEY_RUNNING, null),
            island = statusPrefs.getString(PluginStatus.KEY_ISLAND, null),
            capsule = statusPrefs.getString(PluginStatus.KEY_CAPSULE, null),
            checked = checked,
            pausedAt = statusPrefs.getString(PluginStatus.KEY_PAUSED, null)?.toLongOrNull()?.takeIf { it > 0L },
        )
    }
    val change = { edit: FluidCloudSettings.() -> FluidCloudSettings ->
        if (!PluginSettings.update(edit)) Toast.makeText(context, "设置未保存：插件尚未在 LSPosed 中启用", Toast.LENGTH_SHORT).show()
    }

    // 不能用时最前面的那一条原因,写在对应一行的名字下面
    val problem = health.problem()
    fun note(row: StatusRow) = problem?.takeIf { it.row == row }?.text
    // 「系统状态」关着时「作为主岛显示」四项不起作用:变灰,名字下面写原因
    val statusOff = if (settings.status) null else STATUS_OFF

    HelpSheetScaffold(visible = showHelp, onDismiss = { showHelp = false }) {
        val backdrop = rememberLayerBackdrop()
        CupertinoScaffold(
            modifier = Modifier.fillMaxSize(),
            hasNavigationTitle = true,
            topBar = {
                // 不让上游顶部栏定时把背后的画面拍下来算亮暗(在主线程上取图,会掉帧)
                CupertinoTopAppBar(
                    title = { CupertinoText(TITLE, maxLines = 1) },
                    isBackgroundAdaptive = false,
                    actions = { HelpButton(backdrop) { showHelp = true } },
                    backdrop = backdrop,
                )
            },
        ) { pv ->
            LazyColumn(
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxSize()
                    .background(CupertinoTheme.colorScheme.systemGroupedBackground),
                contentPadding = PaddingValues(top = pv.calculateTopPadding(), bottom = pv.calculateBottomPadding() + 24.dp),
            ) {
                item(key = "title") {
                    CupertinoNavigationTitle { CupertinoText(TITLE) }
                }
                section(title = { CupertinoText("运行状态") }) {
                    value("enabled", "在 LSPosed 中启用", health.enabledText(), note(StatusRow.ENABLED))
                    value("running", "系统界面", health.runningText(), note(StatusRow.RUNNING))
                    value("island", "星河岛", health.islandText(), note(StatusRow.ISLAND))
                    value("capsule", "隐藏系统流体云胶囊", health.capsuleText(), note(StatusRow.CAPSULE))
                    if (health.checked && !health.enabled) action("open_lsposed", "前往 LSPosed") {
                        if (!openLsposed(context)) Toast.makeText(context, "请手动打开 LSPosed，在「模块」中启用本插件", Toast.LENGTH_LONG).show()
                    }
                    if (health.capsule == Health.CAPSULE_UNAVAILABLE) action("open_fluid_cloud", "前往系统流体云设置") { openFluidCloudSettings(context) }
                    if (health.pausedAt != null) action("resume", "重新开启", enabled = enabled) {
                        val text = if (PluginSettings.requestResume()) "已重新开启，重启系统界面后生效" else "未能重新开启：插件尚未在 LSPosed 中启用"
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                    }
                }
                section(title = { CupertinoText("系统流体云") }) {
                    switch(
                        checked = settings.access,
                        onCheckedChange = { on -> change { copy(access = on) } },
                        key = "access",
                        enabled = enabled,
                        title = { CupertinoText("接入系统流体云") },
                    )
                    action("fluid_cloud_services", "选择系统流体云服务") { openFluidCloudSettings(context) }
                }
                section(title = { CupertinoText("显示内容") }) {
                    toggle("call", "来电与通话", settings.call, enabled) { on -> change { copy(call = on) } }
                    toggle("timer", "计时与闹钟", settings.timer, enabled) { on -> change { copy(timer = on) } }
                    toggle("live", "实时活动", settings.live, enabled) { on -> change { copy(live = on) } }
                    toggle("status", "系统状态", settings.status, enabled) { on -> change { copy(status = on) } }
                }
                section(title = { CupertinoText("作为主岛显示") }) {
                    val rows = enabled && statusOff == null
                    toggle("main_recording", "录制与隐私", settings.mainRecording, rows, statusOff) { on -> change { copy(mainRecording = on) } }
                    toggle("main_charging", "充电", settings.mainCharging, rows, statusOff) { on -> change { copy(mainCharging = on) } }
                    toggle("main_headset", "音频设备", settings.mainHeadset, rows, statusOff) { on -> change { copy(mainHeadset = on) } }
                    toggle("main_switch", "系统开关", settings.mainSwitch, rows, statusOff) { on -> change { copy(mainSwitch = on) } }
                }
                section {
                    action("reset", "恢复默认设置", enabled = enabled) { showReset = true }
                }
            }
        }
    }

    if (showReset) {
        CupertinoAlertDialog(
            onDismissRequest = { showReset = false },
            title = { CupertinoText("恢复默认设置") },
            message = { CupertinoText("$RESET_NOTE。") },
        ) {
            cancel({ showReset = false }) { CupertinoText("取消") }
            default({
                showReset = false
                change { FluidCloudSettings() }
            }) { CupertinoText("恢复") }
        }
    }
}

/** 只显示信息的行:上游的行,左标题,右边灰色的值;[note] 不为空时名字下面一行灰色小字(此刻的状态) */
private fun LazySectionScope.value(key: String, title: String, value: String, note: String?) = item(key = key) {
    with(Rows) {
        SectionItem(
            trailingContent = { CupertinoText(value, maxLines = 1, color = CupertinoTheme.colorScheme.secondaryLabel) },
            title = { RowTitle(title, note) },
        )
    }
}

/** 行的名字;[note] 不为空时下面一行灰色小字 */
@Composable
private fun RowTitle(title: String, note: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CupertinoText(title)
        if (note != null) CupertinoText(note, style = CupertinoTheme.typography.footnote, color = CupertinoTheme.colorScheme.secondaryLabel)
    }
}

/** 一行动作文字:上游的打开下一页的行去掉箭头,字用主题色;不能点时灰字 */
private fun LazySectionScope.action(key: String, title: String, enabled: Boolean = true, onClick: () -> Unit) = item(key = key) {
    with(Rows) {
        SectionLink(
            onClick = onClick,
            enabled = enabled,
            chevron = {},
            title = {
                CupertinoText(title, color = if (enabled) CupertinoTheme.colorScheme.accent else CupertinoTheme.colorScheme.secondaryLabel)
            },
        )
    }
}

/** 开关行:上游的开关行;[blocked] 不为空时此刻不起作用,名字下面写原因(例如依赖的「系统状态」关着) */
private fun LazySectionScope.toggle(key: String, title: String, checked: Boolean, enabled: Boolean, blocked: String? = null, onChange: (Boolean) -> Unit) =
    switch(
        checked = checked,
        onCheckedChange = onChange,
        key = key,
        enabled = enabled,
        title = { RowTitle(title, blocked) },
    )

/**
 * 插件此刻的情况:模块框架交没交来服务、系统界面里的插件报来的几样;[checked] 为真表示已经等过回报;
 * [pausedAt] 是插件因为多次出错自动暂停的时刻(没暂停为空)。
 */
internal data class Health(
    val enabled: Boolean,
    val running: String?,
    val island: String?,
    val capsule: String?,
    val checked: Boolean,
    val pausedAt: Long? = null,
) {
    fun enabledText() = when {
        enabled -> "已启用"
        !checked -> "检查中"
        else -> "未启用"
    }

    fun runningText() = when {
        pausedAt != null -> "已暂停"
        running != null -> "已加载"
        !checked -> "检查中"
        else -> "未加载"
    }

    fun islandText() = when {
        pausedAt != null -> "暂停中"
        island == AdapterBridge.STATUS_CONNECTED -> "已连接"
        island == AdapterBridge.STATUS_TESTING -> "已连接（本机测试）"
        island == AdapterBridge.STATUS_ABSENT -> "未找到"
        island?.startsWith(REFUSED_VERSION) == true -> "版本不一致"
        island?.startsWith(AdapterBridge.STATUS_REFUSED) == true -> "未接受"
        running == null && !checked -> "检查中"
        else -> "未连接"
    }

    fun capsuleText() = when (capsule) {
        CAPSULE_AVAILABLE -> "可用"
        CAPSULE_UNAVAILABLE -> "不可用"
        else -> if (running == null && !checked) "检查中" else "尚未确定"
    }

    /** 不能用时最前面的那一条原因和做法,写在哪一行的名字下面;能用或还在检查时为空 */
    fun problem(): StatusNote? = when {
        !checked && (!enabled || running == null) -> null
        pausedAt != null -> StatusNote(StatusRow.RUNNING, "多次出错，已于 ${clockTime(pausedAt)} 自动暂停")
        !enabled -> StatusNote(StatusRow.ENABLED, "启用后需重启系统界面")
        running == null -> StatusNote(StatusRow.RUNNING, "需重启系统界面")
        island == AdapterBridge.STATUS_ABSENT || island == null -> StatusNote(StatusRow.ISLAND, "需在 LSPosed 中启用星流并重启系统界面")
        island?.startsWith(REFUSED_VERSION) == true -> StatusNote(StatusRow.ISLAND, "需将星流与本插件更新至最新版本")
        island == REFUSED_SIGNATURE -> StatusNote(StatusRow.ISLAND, "需在星流中允许本机测试，或从插件商店重新安装本插件")
        island?.startsWith(AdapterBridge.STATUS_REFUSED) == true -> StatusNote(StatusRow.ISLAND, "需从星流的插件商店重新安装本插件")
        capsule == CAPSULE_UNAVAILABLE -> StatusNote(StatusRow.CAPSULE, CAPSULE_UNAVAILABLE_NOTE)
        else -> null
    }

    companion object {
        /** 暂停的时刻写成「几月几日 时:分」 */
        fun clockTime(at: Long): String = java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))

        const val CAPSULE_AVAILABLE = "available"
        const val CAPSULE_UNAVAILABLE = "unavailable"
        const val REFUSED_VERSION = AdapterBridge.STATUS_REFUSED + "version"
        /** 签名与星流不同(例如自行编译的安装包),机主还没有在星流里允许本机测试 */
        const val REFUSED_SIGNATURE = AdapterBridge.STATUS_REFUSED + EventBridge.REASON_SIGNATURE
    }
}

/** 「运行状态」里的四行 */
internal enum class StatusRow { ENABLED, RUNNING, ISLAND, CAPSULE }

/** 写在「运行状态」某一行名字下面的一行短提示(不加句号) */
internal data class StatusNote(val row: StatusRow, val text: String)

/** 系统无法自动隐藏系统流体云胶囊时,「隐藏系统流体云胶囊」一行下面的提示(只显示星河岛的做法写在使用说明里) */
internal const val CAPSULE_UNAVAILABLE_NOTE = "当前系统版本不支持，星河岛与系统流体云同时显示"

/** 「系统状态」关着时「作为主岛显示」四项名字下面的原因 */
internal const val STATUS_OFF = "「系统状态」已关闭"

/** 「恢复默认设置」确认框的正文(不带句号,确认框里再加) */
internal const val RESET_NOTE = "将本页全部设置恢复为默认值"

/** 打开 LSPosed 管理器里本插件的模块页;没有管理器或打不开时返回假 */
private fun openLsposed(context: Context): Boolean {
    val userId = Process.myUid() / 100_000
    for (manager in listOf("org.lsposed.manager", "io.github.lsposed.manager")) {
        if (runCatching { context.packageManager.getPackageInfo(manager, 0) }.isFailure) continue
        val modulePage = Intent(Intent.ACTION_VIEW)
            .setClassName(manager, "org.lsposed.manager.ui.activity.MainActivity")
            .setData(Uri.parse("module://${context.packageName}:$userId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(modulePage) }.isSuccess) return true
        val home = context.packageManager.getLaunchIntentForPackage(manager)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: continue
        if (runCatching { context.startActivity(home) }.isSuccess) return true
    }
    return false
}

/**
 * 系统的「流体云」设置页:在系统自带的通知管理应用里(ColorOS 16 上核对过,打开它要声明一项普通权限,见清单);
 * 先找到那一页再指名打开。
 */
internal const val FLUID_CLOUD_SETTINGS_ACTION = "oplus.fluid.seeding.settings.main"

/** 打开系统的「流体云」设置页;这台手机上没有这一页时打开系统设置,并提示在设置中搜索「流体云」 */
private fun openFluidCloudSettings(context: Context) {
    val page = context.packageManager.resolveActivity(Intent(FLUID_CLOUD_SETTINGS_ACTION), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    val direct = page?.let { Intent(FLUID_CLOUD_SETTINGS_ACTION).setClassName(it.packageName, it.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (direct != null && runCatching { context.startActivity(direct) }.isSuccess) return
    val settings = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(settings) }.isSuccess) {
        Toast.makeText(context, "请在系统设置中搜索「流体云」", Toast.LENGTH_LONG).show()
    }
}
