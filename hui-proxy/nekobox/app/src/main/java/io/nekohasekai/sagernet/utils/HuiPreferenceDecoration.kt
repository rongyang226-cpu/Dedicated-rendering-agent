package io.nekohasekai.sagernet.utils

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class HuiPreferenceDecoration : RecyclerView.ItemDecoration() {
    private var phase = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0xEAFFFFFF.toInt()
    }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 255, 255)
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val d = parent.resources.displayMetrics.density
        outRect.set((7 * d).toInt(), (4 * d).toInt(), (7 * d).toInt(), (4 * d).toInt())
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val d = parent.resources.displayMetrics.density
        val radius = 18f * d
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val left = child.left + 7f * d
            val top = child.top + 2f * d
            val right = child.right - 7f * d
            val bottom = child.bottom - 2f * d
            if (right <= left || bottom <= top) continue

            fill.shader = LinearGradient(
                left, top, right, bottom,
                intArrayOf(
                    Color.argb(166, 255, 255, 255),
                    Color.argb(108, 255, 242, 249),
                    Color.argb(142, 239, 234, 255)
                ),
                floatArrayOf(0f, 0.50f + phase * 0.06f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
            fill.shader = null
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, edge)

            shine.strokeWidth = maxOf(1f, d * 0.7f)
            canvas.drawLine(left + radius, top + d, right - radius, top + d, shine)
        }
        phase += 0.018f
        if (phase > 1f) phase = 0f
        if (parent.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
            HuiVisuals.animationsEnabled(parent.context)
        ) parent.postInvalidateDelayed(66L)
    }
}
