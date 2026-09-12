package com.gemini.floatingcompanion

import android.app.Application
import android.provider.Settings
import com.gemini.floatingcompanion.data.PreferencesManager
import com.gemini.floatingcompanion.service.FloatingBubbleService

class GeminiCompanionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PreferencesManager.getInstance(this)

        if (Settings.canDrawOverlays(this)) {
            FloatingBubbleService.start(this)
        }
    }
}
