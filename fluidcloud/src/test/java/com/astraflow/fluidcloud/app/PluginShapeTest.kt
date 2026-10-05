package com.astraflow.fluidcloud.app

import com.astraflow.fluidcloud.AdapterBridge
import com.astraisland.events.AdapterApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件的样子:模块框架按声明文件找入口、作用域只有系统界面;星流按约定找插件的状态与设置页;
 * 设置页的「运行状态」先说最前面的那一条原因。
 */
class PluginShapeTest {
    private val main = File("src/main")

    @Test fun theEntryAndScopeAreDeclared() {
        val entry = main.resolve("resources/META-INF/xposed/java_init.list").readText().trim()
        assertEquals(HookEntry::class.java.name, entry)
        assertEquals("com.android.systemui", main.resolve("resources/META-INF/xposed/scope.list").readText().trim())
        val prop = main.resolve("resources/META-INF/xposed/module.prop").readText()
        assertTrue(prop.contains("minApiVersion=101") && prop.contains("staticScope=true"))
    }

    @Test fun theHostFindsTheStateAndTheSettingsPage() {
        val manifest = main.resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:authorities=\"${AdapterApp.authority(AdapterApp.FLUID_CLOUD_PACKAGE)}\""))
        assertTrue(manifest.contains("<action android:name=\"${AdapterApp.ACTION_SETTINGS}\" />"))
        assertEquals(AdapterApp.FLUID_CLOUD_PACKAGE, PluginStatus.PLUGIN_PACKAGE)
    }

    /** 「选择系统流体云服务」「前往系统流体云设置」打开系统通知管理应用里的「流体云」页(ColorOS 16 上核对过),不写猜的入口 */
    @Test fun theSystemFluidCloudPageIsTheRealOne() {
        val manifest = main.resolve("AndroidManifest.xml").readText()
        assertEquals("oplus.fluid.seeding.settings.main", FLUID_CLOUD_SETTINGS_ACTION)
        assertTrue("能看到这一页", manifest.contains("<action android:name=\"$FLUID_CLOUD_SETTINGS_ACTION\" />"))
        assertTrue("打开这一页要的普通权限", manifest.contains("<uses-permission android:name=\"com.oplus.notificationmanager.FLUID_SEEDING_SETTINGS\" />"))
        val screen = main.resolve("java/com/astraflow/fluidcloud/app/SettingsScreen.kt").readText()
        assertTrue("没有核对过的猜测入口", listOf("action.FLUID_CLOUD", "FluidCloudSettingsActivity", "FLUID_CLOUD_SETTINGS\"").none { it in screen + manifest })
    }

    /** 「运行状态」只在最前面的那一条原因对应的那一行名字下面写一行短提示;能用或还在检查时不写 */
    @Test fun theStatusSaysTheFirstProblemFirst() {
        fun health(enabled: Boolean = true, running: String? = "100", island: String? = AdapterBridge.STATUS_CONNECTED,
                   capsule: String? = Health.CAPSULE_AVAILABLE) = Health(enabled, running, island, capsule, checked = true)
        fun problem(h: Health) = checkNotNull(h.problem()) { "应当写明原因:$h" }
        problem(health(enabled = false, running = null)).let { assertEquals(StatusRow.ENABLED, it.row); assertTrue(it.text.contains("重启系统界面")) }
        problem(health(running = null)).let { assertEquals(StatusRow.RUNNING, it.row); assertTrue(it.text.contains("重启系统界面")) }
        problem(health(island = AdapterBridge.STATUS_ABSENT)).let { assertEquals(StatusRow.ISLAND, it.row); assertTrue(it.text.contains("启用星流")) }
        problem(health(island = AdapterBridge.STATUS_REFUSED + "version 2")).let { assertEquals(StatusRow.ISLAND, it.row); assertTrue(it.text.contains("更新")) }
        problem(health(island = AdapterBridge.STATUS_REFUSED + "signature mismatch")).let { assertEquals(StatusRow.ISLAND, it.row); assertTrue(it.text.contains("重新安装")) }
        problem(health(capsule = Health.CAPSULE_UNAVAILABLE)).let { assertEquals(StatusRow.CAPSULE, it.row); assertEquals(CAPSULE_UNAVAILABLE_NOTE, it.text) }
        assertNull("能用时不写", health().problem())
        assertNull("还在检查时不写,各行右边写「检查中」", Health(false, null, null, null, checked = false).problem())
        assertEquals("检查中", Health(false, null, null, null, checked = false).enabledText())
        val paused = Health(true, "100", AdapterBridge.STATUS_REFUSED + "paused", null, checked = true, pausedAt = 1_700_000_000_000L)
        assertEquals("已暂停", paused.runningText())
        assertEquals("暂停中", paused.islandText())
        problem(paused).let { assertEquals("暂停时先说暂停:$it", StatusRow.RUNNING, it.row); assertTrue(it.text.contains("自动暂停")) }
        val notes = listOf(health(enabled = false, running = null), health(running = null), health(island = null), paused,
            health(island = AdapterBridge.STATUS_REFUSED + "version 2"), health(island = AdapterBridge.STATUS_REFUSED + "x"),
            health(capsule = Health.CAPSULE_UNAVAILABLE)).map { problem(it).text } + STATUS_OFF
        for (note in notes) assertTrue("行名下面的短提示不加句号:$note", !note.endsWith("。"))
    }

    /**
     * 页面底色上不写说明文字(和星流的页面同一条规矩):分组不带下方说明,大标题下面不放灰字;
     * 设置的作用、连带关系与例外写在右上角问号的使用说明里。
     */
    @Test fun thePageKeepsNotesInTheHelpSheet() {
        val screen = main.resolve("java/com/astraflow/fluidcloud/app/SettingsScreen.kt").readText()
        assertTrue("分组不带下方说明", !Regex("""\bcaption\s*=""").containsMatchIn(screen))
        assertTrue("大标题下面不放灰字", !Regex("""\bsubtitle\s*=""").containsMatchIn(screen))
        assertTrue("右上角有问号", screen.contains("HelpButton(") && screen.contains("HelpSheetScaffold("))
        assertTrue("开关行只写名字", !Regex("""toggle\("\w+", "[^"]+", null""").containsMatchIn(screen))
    }

    /** 使用说明按页面上分组的先后各写一段,写明例外与连带关系(原星流「实时活动」「系统状态」两页的说明搬到这里) */
    @Test fun theHelpNamesTheExceptions() {
        assertEquals(listOf("使用前提", "运行状态", "系统流体云", "显示内容", "作为主岛显示"), PluginHelp.map { it.heading })
        assertTrue("只有使用前提用橙色", PluginHelp.single { it.prerequisite }.heading == "使用前提")
        val kinds = PluginHelp.first { it.heading == "显示内容" }.body
        for (part in listOf("常驻星河天气岛", "常驻星河岛", "单独选择的常驻通知", "「实时活动」")) {
            assertTrue("「显示内容」写明$part 不受影响:$kinds", kinds.contains(part))
        }
        assertTrue("写明两类各包括什么", kinds.contains("外卖、打车、导航") && kinds.contains("充电、音频设备、录制提示与系统开关"))
        assertTrue("写明系统自带的充电与电池提示始终不显示", kinds.contains("系统自带的充电与电池提示始终不显示"))
        assertTrue("写明关闭「系统状态」后屏幕顶部不再有充电提示", kinds.contains("关闭「系统状态」后屏幕顶部不再有充电提示"))
        val asMain = PluginHelp.first { it.heading == "作为主岛显示" }.body
        assertTrue("不知道「显示副岛」开没开,两种情况都写", asMain.contains("「显示副岛」关闭时不显示"))
        assertTrue("写明依赖「系统状态」", asMain.contains("开启「系统状态」后生效"))
        for (scope in listOf("麦克风", "录屏", "手电筒", "勿扰")) assertTrue(asMain.contains(scope))
        val access = PluginHelp.first { it.heading == "系统流体云" }.body
        assertTrue("写明隐藏不了时只显示星河岛的做法", access.contains("「不可用」") && access.contains("关闭对应服务"))
        for (section in PluginHelp) {
            assertTrue("使用说明正文以句号结尾:${section.heading}", section.body.endsWith("。"))
            assertTrue("使用说明不写重启类操作:${section.heading}", !section.body.contains("重启"))
        }
        assertTrue("恢复范围不加句号,确认框正文再加", !RESET_NOTE.endsWith("。"))
    }
}
