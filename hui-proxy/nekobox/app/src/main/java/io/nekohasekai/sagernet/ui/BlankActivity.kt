package io.nekohasekai.sagernet.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Legacy trampoline kept for compatibility with old intents.
 * Fatal crashes must never open a share sheet automatically.
 */
class BlankActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
