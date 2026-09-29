package io.nekohasekai.sagernet.utils

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import io.nekohasekai.sagernet.R
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object HuiVisuals {
    private const val PREFS = "hui_visuals"
    private const val KEY_BACKGROUND = "background_uri"
    private var cachedKey: String? = null
    private var cachedBitmap = WeakReference<Bitmap>(null)

    fun backgroundUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKGROUND, null)?.let(Uri::parse)

    fun setBackground(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKGROUND, uri.toString()).apply()
        cachedKey = null
        cachedBitmap.clear()
    }

    fun clearBackground(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_BACKGROUND).apply()
        cachedKey = null
        cachedBitmap.clear()
    }

    fun wrap(context: Context, content: View): View {
        if (content is HuiBackdropLayout) return content
        return HuiBackdropLayout(context).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    fun applyLiquidPress(view: View) {
        if (!view.isClickable || !view.isEnabled) return
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.985f).scaleY(0.985f).alpha(0.94f).setDuration(90L).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220L).setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            false
        }
    }

    fun animationsEnabled(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
    } catch (_: Throwable) {
        true
    }

    fun backdrop(context: Context): Drawable {
        val uri = backgroundUri(context)
        val key = uri?.toString() ?: "__hui_default__"
        cachedBitmap.get()?.takeIf { cachedKey == key && !it.isRecycled }?.let {
            return BitmapDrawable(context.resources, it)
        }

        val bitmap = if (uri == null) {
            decodeScaledResource(context, R.drawable.hui_background)
        } else {
            decodeScaled(context, uri)
        }
        if (bitmap == null) return context.getDrawable(R.drawable.hui_background)!!
        cachedKey = key
        cachedBitmap = WeakReference(bitmap)
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun sampleSize(context: Context, outWidth: Int, outHeight: Int): Int {
        val dm = context.resources.displayMetrics
        val targetW = max(dm.widthPixels, 1080)
        val targetH = max(dm.heightPixels, 1920)
        var sample = 1
        while (outWidth / sample > targetW * 2 || outHeight / sample > targetH * 2) {
            sample *= 2
        }
        return sample
    }

    private fun decodeScaledResource(context: Context, resId: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        BitmapFactory.decodeResource(
            context.resources,
            resId,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(context, bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } catch (_: Throwable) {
        null
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(context, bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (_: Throwable) {
        null
    }
}

class HuiBackdropLayout(context: Context) : FrameLayout(context) {
    private val wallpaper = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setImageDrawable(HuiVisuals.backdrop(context))
        scaleX = 1.045f
        scaleY = 1.045f
    }
    private val ambient = HuiAmbientView(context)
    private var drift: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ambient, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!HuiVisuals.animationsEnabled(context)) return
        drift?.cancel()
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 22000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                val p = animator.animatedValue as Float
                wallpaper.scaleX = 1.045f + 0.035f * p
                wallpaper.scaleY = 1.045f + 0.035f * p
                wallpaper.translationX = width * -0.012f * p
                wallpaper.translationY = height * 0.008f * p
            }
            start()
        }
        ambient.startMotion()
    }

    override fun onDetachedFromWindow() {
        drift?.cancel()
        drift = null
        ambient.stopMotion()
        super.onDetachedFromWindow()
    }
}

class HuiAmbientView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFE2EC.toInt()
    }
    private val petalX = floatArrayOf(0.05f, 0.13f, 0.21f, 0.31f, 0.39f, 0.48f, 0.57f, 0.66f, 0.74f, 0.83f, 0.91f, 0.97f)
    private val petalY = floatArrayOf(0.02f, 0.36f, 0.15f, 0.62f, 0.43f, 0.08f, 0.76f, 0.29f, 0.55f, 0.18f, 0.69f, 0.47f)
    private val petalScale = floatArrayOf(0.72f, 0.95f, 0.66f, 0.84f, 1.0f, 0.74f, 0.90f, 0.68f, 0.82f, 0.96f, 0.76f, 0.88f)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val p = ((sin(phase * Math.PI * 2.0) + 1.0) * 0.5).toFloat()
        drawBubble(
            canvas,
            width * (0.18f + 0.08f * p),
            height * (0.20f + 0.05f * p),
            min(width, height) * 0.52f,
            0x38FFD7EA
        )
        drawBubble(
            canvas,
            width * (0.84f - 0.06f * p),
            height * (0.68f - 0.07f * p),
            min(width, height) * 0.60f,
            0x2FDACBFF
        )
        drawBubble(
            canvas,
            width * (0.58f + 0.03f * p),
            height * (0.42f - 0.02f * p),
            min(width, height) * 0.34f,
            0x1FFFFFFF
        )
        drawSakura(canvas)
    }

    private fun drawSakura(canvas: Canvas) {
        val d = resources.displayMetrics.density
        for (i in petalX.indices) {
            val fall = (petalY[i] + phase * (0.72f + i % 3 * 0.08f)) % 1.08f
            val sway = sin((phase * 6.28318f + i * 0.83f).toDouble()).toFloat()
            val x = width * (petalX[i] + sway * 0.035f)
            val y = height * (fall - 0.04f)
            val size = (5.0f + 3.0f * petalScale[i]) * d
            petalPaint.alpha = (72 + 86 * petalScale[i]).toInt().coerceIn(0, 180)
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate((phase * 210f + i * 31f) % 360f)
            canvas.drawOval(-size, -size * 0.43f, size, size * 0.43f, petalPaint)
            canvas.rotate(38f)
            canvas.drawOval(-size * 0.56f, -size * 0.28f, size * 0.56f, size * 0.28f, petalPaint)
            canvas.restore()
        }
        petalPaint.alpha = 255
    }

    private fun drawBubble(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(color, color and 0x00FFFFFF, Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    fun startMotion() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 18000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopMotion() {
        animator?.cancel()
        animator = null
    }
}
