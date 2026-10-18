package com.astraflow.fluidcloud.app

import android.app.Application
import android.content.Context
import com.astraflow.fluidcloud.FluidCloudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 插件的设置只读写自身的远端设置;未启用时不能修改。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = Application::class)
class PluginSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val remote = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    @Before fun reset() {
        remote.edit().clear().commit()
        PluginSettings.resetForTest()
    }

    @Test fun settingsCannotChangeUntilTheServiceArrives() {
        assertFalse("Not enabled: nothing can be changed", PluginSettings.update { copy(live = false) })
        PluginSettings.connect(remote)
        assertEquals(FluidCloudSettings(), PluginSettings.settings.value)
        assertTrue(PluginSettings.enabled.value)
    }

    @Test fun theUsersChoiceIsSavedAndReadFromThePlugin() {
        PluginSettings.connect(remote)
        assertTrue(PluginSettings.update { copy(status = false) })
        assertEquals(FluidCloudSettings(status = false), FluidCloudSettings.read(remote))
        PluginSettings.resetForTest()
        PluginSettings.connect(remote)
        assertEquals(FluidCloudSettings(status = false), PluginSettings.settings.value)
    }

    @Test fun resettingThePluginRestoresItsOwnSettings() {
        remote.edit().putBoolean("live", false).commit()
        PluginSettings.connect(remote)
        assertTrue(PluginSettings.update { FluidCloudSettings() })
        assertEquals(FluidCloudSettings(), PluginSettings.settings.value)
        assertEquals(FluidCloudSettings(), FluidCloudSettings.read(remote))
    }
}
