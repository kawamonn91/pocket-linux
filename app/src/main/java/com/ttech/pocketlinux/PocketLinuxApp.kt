package com.ttech.pocketlinux

import android.app.Application

class PocketLinuxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        Notifications.createChannels(this)
    }
}
