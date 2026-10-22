package com.frodrigues.obdmqtt

import android.app.Activity
import android.os.Bundle

/**
 * Invisible entry point with the same actions as [StartReceiver]. Unlike a broadcast,
 * starting an activity also works when the car unit has force-stopped the app at
 * ignition off (Home Assistant `command_activity`, Tasker/MacroDroid "start activity").
 * Theme.NoDisplay: nothing is drawn, and it must finish before onResume.
 */
class StartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartReceiver.handle(applicationContext, intent?.action)
        finish()
    }
}
