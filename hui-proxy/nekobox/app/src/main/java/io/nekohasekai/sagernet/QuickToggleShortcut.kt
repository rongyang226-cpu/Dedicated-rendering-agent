package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.bg.core.CoreEngine
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.VpnRequestActivity

@Suppress("DEPRECATION")
class QuickToggleShortcut : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this,
                ShortcutInfoCompat.Builder(this, "toggle")
                    .setIntent(Intent(this, QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN))
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_qu_shadowsocks_launcher))
                    .setShortLabel(getString(R.string.quick_toggle))
                    .build()))
            finish()
            return
        }

        val profileId = intent.getLongExtra("profile", -1L)
        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()?.reportShortcutUsed(
                if (profileId >= 0) "shortcut-profile-$profileId" else "toggle"
            )
        }

        val oldProfile = DataStore.selectedProxy
        if (profileId >= 0L) {
            DataStore.selectedProxy = profileId
            CoreController.selectEngine(CoreEngine.BOX)
        }

        val status = CoreController.status(this)
        if (status.active && (profileId < 0L || oldProfile == profileId)) {
            CoreController.stopAll(this)
            finish()
            return
        }
        if (CoreController.needsVpnPermission(this)) {
            startActivity(Intent(this, VpnRequestActivity::class.java))
            finish()
            return
        }

        runOnDefaultDispatcher {
            val error = runCatching { CoreController.startSelectedAuthorized(this@QuickToggleShortcut) }.exceptionOrNull()
            runOnMainDispatcher {
                error?.let { Toast.makeText(this@QuickToggleShortcut, it.readableMessage, Toast.LENGTH_LONG).show() }
                finish()
            }
        }
    }
}
