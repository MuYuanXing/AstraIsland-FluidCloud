package com.astraflow.fluidcloud.app

import android.app.Application

/** 插件应用:进程一起来就等模块框架交来服务(插件在 LSPosed 里启用后才会交来)。 */
class FluidCloudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PluginSettings.start()
    }
}
