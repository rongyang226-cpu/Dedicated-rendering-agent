package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.core.CoreController
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.broadcastReceiver
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.launch

class VpnRequestActivity : AppCompatActivity() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSystemService<KeyguardManager>()!!.isKeyguardLocked) {
            receiver = broadcastReceiver { _, _ -> connect.launch(null) }
            if (SDK_INT >= 33) {
                registerReceiver(
                    receiver,
                    IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT))
            }
        } else connect.launch(null)
    }

    private val connect = registerForActivityResult(RequestVpnPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            runCatching { CoreController.startSelectedAuthorized(this@VpnRequestActivity) }
                .onFailure {
                    Logs.e(it)
                    Toast.makeText(this@VpnRequestActivity, it.readableMessage, Toast.LENGTH_LONG).show()
                }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiver != null) unregisterReceiver(receiver)
    }

    class RequestVpnPermission : ActivityResultContract<Void?, Boolean>() {
        private var cachedIntent: Intent? = null

        override fun getSynchronousResult(context: Context, input: Void?): SynchronousResult<Boolean>? {
            val intent = VpnService.prepare(context) ?: return SynchronousResult(true)
            cachedIntent = intent
            return null
        }

        override fun createIntent(context: Context, input: Void?) =
            requireNotNull(cachedIntent).also { cachedIntent = null }

        override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
    }


}
