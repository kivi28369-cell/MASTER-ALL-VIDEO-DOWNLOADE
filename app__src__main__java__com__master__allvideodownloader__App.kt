package com.master.allvideodownloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Prefs.applyTheme()
        Engine.init(this)
    }
}
