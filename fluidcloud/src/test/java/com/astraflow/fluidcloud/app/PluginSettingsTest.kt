package com.astraflow.fluidcloud.app

import android.app.Application
import android.content.Context
import com.astraflow.fluidcloud.FluidCloudSettings
import com.astraisland.events.AdapterApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 插件的设置:星流交来的原来的设置只在插件还没有自己的设置时用上;插件没启用时先记在本机,启用后用上;
 * 用户在插件里改过,就以插件里的为准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PluginSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val remote = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val old = FluidCloudSettings(call = false, mainCharging = false)

    @Before fun reset() {
        remote.edit().clear().commit()
        PluginSettings.resetForTest(context)
    }

    @Test fun notEnabledKeepsTheOldSettingsUntilTheServiceArrives() {
        assertEquals(AdapterApp.RESULT_PENDING, PluginSettings.import(old))
        assertFalse("Not enabled: nothing can be changed", PluginSettings.update { copy(live = false) })
        PluginSettings.connect(remote)
        assertEquals(old, FluidCloudSettings.read(remote))
        assertEquals(old, PluginSettings.settings.value)
        assertTrue(PluginSettings.enabled.value)
    }

    @Test fun enabledTakesTheOldSettingsOnce() {
        PluginSettings.connect(remote)
        assertEquals(AdapterApp.RESULT_APPLIED, PluginSettings.import(old))
        assertEquals(old, FluidCloudSettings.read(remote))
        assertEquals(AdapterApp.RESULT_APPLIED, PluginSettings.import(FluidCloudSettings(timer = false)))
        assertEquals("Only the first hand-over counts", old, FluidCloudSettings.read(remote))
    }

    @Test fun theUsersOwnChoiceWins() {
        PluginSettings.connect(remote)
        assertTrue(PluginSettings.update { copy(status = false) })
        PluginSettings.import(old)
        assertEquals(FluidCloudSettings(status = false), FluidCloudSettings.read(remote))
    }

    @Test fun aPendingHandOverIsIgnoredWhenThePluginAlreadyHasItsOwnSettings() {
        remote.edit().putBoolean(PluginSettings.KEY_SETTLED, true).putBoolean("live", false).commit()
        PluginSettings.import(old)
        PluginSettings.connect(remote)
        assertEquals(FluidCloudSettings(live = false), PluginSettings.settings.value)
    }

    @Test fun nothingHandedOverMeansTheDefaults() {
        PluginSettings.connect(remote)
        assertEquals(FluidCloudSettings(), PluginSettings.settings.value)
        assertFalse(remote.contains(PluginSettings.KEY_SETTLED))
    }
}
