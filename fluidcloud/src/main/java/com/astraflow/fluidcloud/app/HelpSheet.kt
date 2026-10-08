@file:OptIn(ExperimentalCupertinoApi::class)

package com.astraflow.fluidcloud.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.drop
import zone.ien.hig.CupertinoBottomSheetContent
import zone.ien.hig.CupertinoBottomSheetScaffold
import zone.ien.hig.CupertinoBottomSheetScaffoldDefaults
import zone.ien.hig.CupertinoButton
import zone.ien.hig.CupertinoButtonDefaults
import zone.ien.hig.CupertinoIcon
import zone.ien.hig.CupertinoLiquidIconButton
import zone.ien.hig.CupertinoSheetValue
import zone.ien.hig.CupertinoText
import zone.ien.hig.CupertinoTopAppBar
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.icons.CupertinoIcons
import zone.ien.hig.icons.outlined.Questionmark
import zone.ien.hig.rememberCupertinoBottomSheetScaffoldState
import zone.ien.hig.rememberCupertinoSheetState
import zone.ien.hig.section.CupertinoSection
import zone.ien.hig.section.SectionItem
import zone.ien.hig.theme.CupertinoColors
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.systemOrange

/*
 * 设置页右上角问号打开的使用说明:和星流的使用说明同一种写法与样子(上游的底部弹层,里面一组上游的分组,每段一行带编号,
 * 前提用橙色)。页面上不写说明文字,设置的作用、连带关系、例外与限制写在这里,按页面上分组的先后各写一段。
 */

/** 使用说明的一段:小标题与正文;[prerequisite] 为真时是不满足就无法使用的前提,用橙色。 */
internal class HelpSection(val heading: String, val body: String, val prerequisite: Boolean = false)

internal const val HELP_TITLE = "流体云事件接入使用说明"

internal val PluginHelp = listOf(
    HelpSection(
        heading = "使用前提",
        body = "本插件是星流官方插件，适用于 OPPO、一加、真我手机，需在 LSPosed 中同时启用星流与本插件。",
        prerequisite = true,
    ),
    HelpSection(
        heading = "运行状态",
        body = "「运行状态」一组显示本插件在 LSPosed 中的启用情况、在系统界面中的运行情况、与星河岛的连接情况，以及能否隐藏系统流体云的胶囊；" +
            "无法正常使用时，对应一行的名称下方写明原因。本插件在系统界面中多次出错时自动暂停，系统界面恢复原有显示，" +
            "星河岛不再显示系统流体云的内容；点按「重新开启」可恢复运行。",
    ),
    HelpSection(
        heading = "系统流体云",
        body = "开启「接入系统流体云」后，已启用的音乐、通知和系统实时内容由星河岛统一显示，系统流体云中的相同内容不再显示；关闭后，星河岛撤下这些内容并交还系统。" +
            "各项设置的选择保持不变，重新开启后恢复。人脸识别、普通常驻内容、常驻天气和外部应用接入不受此开关影响。" +
            "点按「选择系统流体云服务」打开系统的流体云设置，可选择使用流体云的服务。部分系统版本无法隐藏系统流体云的胶囊，" +
            "此时「隐藏系统流体云胶囊」显示「不可用」，星河岛与系统流体云同时显示；如需只显示星河岛，可在系统的流体云设置中关闭对应服务。",
    ),
    HelpSection(
        heading = "显示内容",
        body = "「实时活动」包括外卖、打车、导航等进行中的服务；「系统状态」包括充电、音频设备、录制提示与系统开关。" +
            "关闭某一类后，星河岛不再显示这类内容，系统流体云中的同类内容照常由系统显示。" +
            "常驻星河天气岛、普通常驻星河岛和单独选择的常驻通知不受「实时活动」影响。关闭「系统状态」后，常驻充电岛停止显示，系统充电与电池提示交还系统。",
    ),
    HelpSection(
        heading = "作为主岛显示",
        body = "「录制与隐私」包括麦克风、相机占用与录音、录屏提示；「系统开关」包括手电筒、勿扰、护眼、个人热点等系统开关的提示。" +
            "开启的项目在没有音乐、消息等其他内容时作为主岛显示；关闭后只在有其他内容时出现在副岛，星河岛「显示规则」中的「显示副岛」关闭时不显示。" +
            "本组设置在开启「系统状态」后生效。",
    ),
)

/** 顶部栏右上角的问号:上游的液态玻璃圆按钮(44 大,里面的图形 17),和星流页面右上角的按钮相同。 */
@Composable
internal fun HelpButton(backdrop: Backdrop, onClick: () -> Unit) {
    // 不让上游按钮每 0.3 秒把背后的画面拍下来算亮暗(在主线程上取图,会掉帧)
    CupertinoLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        isBackgroundAdaptive = false,
        modifier = Modifier
            .padding(end = 12.dp)
            .size(44.dp)
            .semantics { contentDescription = "使用说明" },
    ) {
        CupertinoIcon(CupertinoIcons.Outlined.Questionmark, null, Modifier.size(17.dp))
    }
}

/**
 * 包住整页的上游弹层骨架:[visible] 为真时从下往上弹出使用说明,后面的页面缩小;点「完成」、往下拖或按返回收起。
 * 收起到底后弹层里的内容离开界面。
 */
@Composable
internal fun HelpSheetScaffold(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberCupertinoSheetState()
    val scaffoldState = rememberCupertinoBottomSheetScaffoldState(sheetState)
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(visible) {
        if (visible) sheetState.show() else if (sheetState.currentValue != CupertinoSheetValue.Hidden) sheetState.hide()
    }
    // 用户往下拖收起时告诉页面
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect { value ->
            if (value == CupertinoSheetValue.Hidden) dismiss()
        }
    }
    BackHandler(enabled = visible) { dismiss() }
    CupertinoBottomSheetScaffold(
        sheetContent = {
            if (visible || sheetState.currentValue != CupertinoSheetValue.Hidden) HelpSheetContent(onDone = dismiss)
        },
        scaffoldState = scaffoldState,
        colors = CupertinoBottomSheetScaffoldDefaults.colors(sheetContainerColor = CupertinoTheme.colorScheme.secondarySystemBackground),
    ) { content() }
}

/** 弹层里的内容:上方标题栏右边「完成」,下面一组分组,每段一行带编号;配色照上游示例(弹层底色浅灰,里面的分组白色)。 */
@Composable
private fun HelpSheetContent(onDone: () -> Unit) {
    val base = CupertinoTheme.colorScheme
    val scheme = remember(base) { base.copy(secondarySystemGroupedBackground = base.tertiarySystemBackground) }
    CupertinoTheme(colorScheme = scheme, shapes = CupertinoTheme.shapes, typography = CupertinoTheme.typography) {
        val backdrop = rememberLayerBackdrop()
        val dark = isSystemInDarkTheme()
        CupertinoBottomSheetContent(
            topBar = {
                CupertinoTopAppBar(
                    backdrop = backdrop,
                    title = { CupertinoText(HELP_TITLE) },
                    isBackgroundAdaptive = false,
                    actions = {
                        CupertinoButton(colors = CupertinoButtonDefaults.plainButtonColors(), onClick = onDone) { CupertinoText("完成") }
                    },
                )
            },
        ) { pv ->
            Column(
                Modifier
                    .layerBackdrop(backdrop)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(pv)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CupertinoSection {
                    PluginHelp.forEachIndexed { index, section ->
                        val color = if (section.prerequisite) CupertinoColors.systemOrange(dark) else CupertinoTheme.colorScheme.label
                        SectionItem {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CupertinoText("${index + 1}. ${section.heading}", style = CupertinoTheme.typography.headline, color = color)
                                CupertinoText(section.body, style = CupertinoTheme.typography.subhead, color = color)
                            }
                        }
                    }
                }
            }
        }
    }
}
