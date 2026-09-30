package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.CrashHandler
import io.nekohasekai.sagernet.utils.HuiVisuals

/**
 * Tiny launcher kept independent from the proxy UI.
 * Normal launches immediately forward to MainActivity. If the previous process crashed,
 * this screen stays alive and exposes the saved Java stack trace instead of crash-looping.
 */
class BootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CrashHandler.consumePendingCrash()) {
            showLaunchTransition()
            return
        }
        showRecovery()
    }

    private fun showLaunchTransition() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = FrameLayout(this)
        val wallpaper = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(HuiVisuals.backdrop(this@BootstrapActivity))
            scaleX = 1.045f
            scaleY = 1.045f
            alpha = 1f
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(View(this).apply { setBackgroundColor(0x28FFFFFF) }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0f
            translationY = dp(10).toFloat()
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.hui_avatar)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
            scaleX = 0.88f
            scaleY = 0.88f
        }
        content.addView(avatar, LinearLayout.LayoutParams(dp(96), dp(96)))
        content.addView(TextView(this).apply {
            text = "绘"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xE6181920.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = "Box + Meta"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xA6262830.toInt())
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        setContentView(root)

        content.scaleX = 0.982f
        content.scaleY = 0.982f
        content.translationY = dp(6).toFloat()
        content.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(45L).setDuration(280L).start()
        root.postDelayed({ launchMain(animated = true) }, 430L)
    }

    private fun launchMain(animated: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
        if (animated) overridePendingTransition(R.anim.hui_launch_enter, R.anim.hui_launch_exit)
        else overridePendingTransition(0, 0)
    }

    private fun showRecovery() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(TextView(this).apply {
            text = "绘 · 启动保护"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "检测到上一次启动异常。这里不会再次自动拉起主界面，下面是保存的异常信息。"
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        })

        val report = runCatching { CrashHandler.lastCrashFile()?.readText().orEmpty() }
            .getOrDefault("无法读取崩溃日志")
            .takeLast(24000)
        val logView = TextView(this).apply {
            text = if (report.isBlank()) "没有捕获到 Java 异常栈，可能是 native 崩溃。" else report
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(Button(this).apply {
            text = "复制异常信息"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Hui crash", report))
                text = "已复制"
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(Button(this).apply {
            text = "重试进入绘"
            setOnClickListener { launchMain() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }
}
