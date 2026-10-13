package com.lookbuddy.app

import android.app.Application
import com.anker.esiotkit.support.contextProvider

class LookBuddyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Required by the organizer SDK before ESIotKit.start().
        contextProvider = { this }
    }
}
