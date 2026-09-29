package io.nekohasekai.sagernet.utils

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object HuiVisuals {
    private const val PREFS = "hui_visuals"
    private const val KEY_BACKGROUND = "background_uri"
    private var cachedKey: String? = null
    private var cachedBitmap: Bitmap? = null

    fun backgroundUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKGROUND, null)?.let(Uri::parse)

    fun setBackground(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKGROUND, uri.toString()).apply()
        cachedKey = null
        cachedBitmap = null
    }

    fun clearBackground(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_BACKGROUND).apply()
        cachedKey = null
        cachedBitmap = null
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
        if (!SagerNet.isTv) {
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.defaultFocusHighlightEnabled = false
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.985f).scaleY(0.985f).alpha(0.94f).setDuration(90L).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220L).setInterpolator(AccelerateDecelerateInterpolator()).start()
            }
            false
        }
    }

    fun decoratePreferenceList(listView: RecyclerView) {
        val density = listView.resources.displayMetrics.density
        listView.setPadding(0, (6f * density).toInt(), 0, (18f * density).toInt())
        listView.clipToPadding = false
        listView.addItemDecoration(HuiPreferenceDecoration())

        fun decorate(child: View) {
            child.background = AppCompatResources.getDrawable(child.context, R.drawable.hui_preference_press)
            if (!SagerNet.isTv) {
                child.isFocusable = false
                child.isFocusableInTouchMode = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    child.defaultFocusHighlightEnabled = false
                }
            }
            applyLiquidPress(child)
        }

        for (i in 0 until listView.childCount) decorate(listView.getChildAt(i))
        listView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = decorate(view)
            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
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
        cachedBitmap?.takeIf { cachedKey == key && !it.isRecycled }?.let {
            return BitmapDrawable(context.resources, it)
        }

        val bitmap = if (uri == null) {
            decodeScaledResource(context, R.drawable.hui_background)
        } else {
            decodeScaled(context, uri)
        }
        if (bitmap == null) return context.getDrawable(R.drawable.hui_background)!!
        cachedKey = key
        cachedBitmap = bitmap
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun sampleSize(context: Context, outWidth: Int, outHeight: Int): Int {
        val dm = context.resources.displayMetrics
        val targetW = max(dm.widthPixels, 720)
        val targetH = max(dm.heightPixels, 1280)
        var sample = 1
        // Keep the bundled 4K source, but decode close to the physical screen size.
        // A full 2160x3840 ARGB bitmap costs ~32 MiB and was causing intermittent GC/UI stalls.
        while (outWidth / sample > targetW * 1.35f || outHeight / sample > targetH * 1.35f) {
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
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.82f) })
        // Keep the 4K source static and slightly desaturated for text readability.
        // of short frame stalls while RecyclerViews/fragments were also laying out.
        scaleX = 1.035f
        scaleY = 1.035f
    }
    private val ambient = HuiAmbientView(context)
    private val readabilityScrim = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(0x46080A10)
        isClickable = false
        isFocusable = false
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(wallpaper, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ambient, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(readabilityScrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (HuiVisuals.animationsEnabled(context)) ambient.startMotion()
    }

    override fun onDetachedFromWindow() {
        ambient.stopMotion()
        super.onDetachedFromWindow()
    }
}

class HuiAmbientView(context: Context) : View(context) {
    private val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFF1F6.toInt()
        style = Paint.Style.FILL
    }
    private val petalPath = Path()
    private val petalX = floatArrayOf(0.06f, 0.18f, 0.31f, 0.44f, 0.58f, 0.72f, 0.85f, 0.95f)
    private val petalY = floatArrayOf(0.02f, 0.42f, 0.17f, 0.68f, 0.34f, 0.09f, 0.77f, 0.51f)
    private val petalScale = floatArrayOf(0.72f, 0.92f, 0.68f, 0.84f, 1.0f, 0.76f, 0.88f, 0.70f)
    private var phase = 0f
    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!running || !isAttachedToWindow) return
            phase = (SystemClock.uptimeMillis() % 24000L) / 24000f
            invalidate()
            postDelayed(this, 66L)
        }
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        drawSakura(canvas)
    }

    private fun drawSakura(canvas: Canvas) {
        val d = resources.displayMetrics.density
        for (i in petalX.indices) {
            val fall = (petalY[i] + phase * (0.72f + i % 3 * 0.08f)) % 1.08f
            val sway = sin((phase * 6.28318f + i * 0.83f).toDouble()).toFloat()
            val x = width * (petalX[i] + sway * 0.035f)
            val y = height * (fall - 0.04f)
            val size = (4.4f + 2.8f * petalScale[i]) * d
            petalPaint.alpha = (70 + 78 * petalScale[i]).toInt().coerceIn(0, 165)
            petalPath.reset()
            petalPath.moveTo(0f, size)
            petalPath.cubicTo(-size * 0.95f, size * 0.35f, -size * 0.78f, -size * 0.58f, 0f, -size)
            petalPath.cubicTo(size * 0.78f, -size * 0.58f, size * 0.95f, size * 0.35f, 0f, size)
            petalPath.close()
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate((phase * 210f + i * 31f) % 360f)
            canvas.scale(0.72f, 1f)
            canvas.drawPath(petalPath, petalPaint)
            canvas.restore()
        }
        petalPaint.alpha = 255
    }

    fun startMotion() {
        if (running) return
        running = true
        removeCallbacks(ticker)
        post(ticker)
    }

    fun stopMotion() {
        running = false
        removeCallbacks(ticker)
    }
}
