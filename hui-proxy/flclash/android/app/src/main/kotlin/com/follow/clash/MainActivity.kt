package com.follow.clash

import com.follow.clash.plugins.AppPlugin
import com.follow.clash.plugins.ServicePlugin
import com.follow.clash.plugins.TilePlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var previousDisplayModeId: Int? = null

    override fun onResume() {
        super.onResume()
        // Let a 120 Hz device present Flutter frames at its native rate.
        // Keep the system choice on devices without a matching display mode.
        @Suppress("DEPRECATION")
        val currentDisplay = windowManager.defaultDisplay
        val activeMode = currentDisplay.mode
        val fastMode = currentDisplay.supportedModes
            .filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight &&
                    it.refreshRate >= 119f && it.refreshRate <= 121f
            }
            .maxByOrNull { it.refreshRate } ?: return
        val attributes = window.attributes
        previousDisplayModeId = attributes.preferredDisplayModeId
        attributes.preferredDisplayModeId = fastMode.modeId
        window.attributes = attributes
    }

    override fun onPause() {
        previousDisplayModeId?.let { modeId ->
            val attributes = window.attributes
            attributes.preferredDisplayModeId = modeId
            window.attributes = attributes
            previousDisplayModeId = null
        }
        super.onPause()
    }


    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(AppPlugin())
        flutterEngine.plugins.add(ServicePlugin())
        flutterEngine.plugins.add(TilePlugin())
        ServiceState.attachFlutterEngine(flutterEngine)
    }

    override fun onDestroy() {
        flutterEngine?.let(ServiceState::detachFlutterEngine)
        super.onDestroy()
    }
}
