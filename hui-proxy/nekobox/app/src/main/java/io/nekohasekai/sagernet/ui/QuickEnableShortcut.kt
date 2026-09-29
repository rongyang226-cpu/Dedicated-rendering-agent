package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class QuickEnableShortcut : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()?.reportShortcutUsed("enable")
        if (CoreController.needsVpnPermission(this)) {
            startActivity(Intent(this, VpnRequestActivity::class.java))
            finish()
            return
        }
        runOnDefaultDispatcher {
            val error = runCatching { CoreController.startSelectedAuthorized(this@QuickEnableShortcut) }.exceptionOrNull()
            runOnMainDispatcher {
                error?.let { Toast.makeText(this@QuickEnableShortcut, it.readableMessage, Toast.LENGTH_LONG).show() }
                finish()
            }
        }
    }
}
