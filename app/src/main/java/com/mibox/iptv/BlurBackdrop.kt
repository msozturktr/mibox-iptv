package com.mibox.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.TextureView
import android.view.View

/**
 * Frosted-glass backdrop: draws a blurred copy of the video region behind it, plus a translucent tint.
 *
 * Performance notes (weak TV box): the video is captured with TextureView.getBitmap into a bitmap that is
 * 1/6 of the screen, ~9 times a second, and only while a backdrop is visible. The blur (4 box passes) runs on
 * a background thread over reused buffers, so the UI thread only does the small capture and a bitmap swap.
 * When blur is disabled (or no frame is available yet) only the flat tint is drawn.
 */
class BlurBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var tintColor = 0x661F3A68
    private var edgeColor = 0x1FFFFFFF
    private var cornerRadius = 0f
    private var rightEdgeOnly = false

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clipPath = Path()
    private val src = Rect()
    private val dst = RectF()
    private val edgeRect = RectF()

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BlurBackdropView)
            tintColor = a.getColor(R.styleable.BlurBackdropView_tint, tintColor)
            edgeColor = a.getColor(R.styleable.BlurBackdropView_edgeColor, edgeColor)
            cornerRadius = a.getDimension(R.styleable.BlurBackdropView_corner, 0f)
            rightEdgeOnly = a.getBoolean(R.styleable.BlurBackdropView_rightEdgeOnly, false)
            a.recycle()
        }
        edgePaint.color = edgeColor
        edgePaint.strokeWidth = 1f * resources.displayMetrics.density
    }

    // No intrinsic size: only fill what the parent dictates (otherwise a wrap_content parent grows to its maximum)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        BlurController.register(this)
    }

    override fun onDetachedFromWindow() {
        BlurController.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        if (cornerRadius > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, w, h, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clipPath)
        }
        val blurred = BlurController.blurredFor(this, src)
        if (blurred != null) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(blurred, src, dst, bitmapPaint)
        }
        canvas.drawColor(tintColor)
        canvas.restore()

        val sw = edgePaint.strokeWidth
        if (rightEdgeOnly) {
            canvas.drawLine(w - sw / 2f, 0f, w - sw / 2f, h, edgePaint)
        } else {
            edgeRect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
            canvas.drawRoundRect(edgeRect, cornerRadius, cornerRadius, edgePaint)
        }
    }
}

object BlurController {
    private const val DOWNSCALE = 6
    private const val BLUR_RADIUS = 2 // at 1/6 scale ~ CSS blur of 12-14px
    private const val INTERVAL_MS = 110L // ~9 fps

    @Volatile
    var enabled = true
        set(value) {
            field = value
            views.forEach { it.invalidate() }
        }

    private var texture: TextureView? = null
    private val views = ArrayList<BlurBackdropView>()
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private var small: Bitmap? = null
    private var front: Bitmap? = null // drawn on the UI thread
    private var back: Bitmap? = null // written by the worker, then swapped
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var frontReady = false
    private var capturedFromW = 0
    private var capturedFromH = 0

    @Volatile
    private var busy = false
    private var running = false

    private val tmpA = IntArray(2)
    private val tmpB = IntArray(2)

    fun setTexture(view: TextureView?) {
        texture = view
    }

    fun register(v: BlurBackdropView) {
        if (!views.contains(v)) views.add(v)
    }

    fun unregister(v: BlurBackdropView) {
        views.remove(v)
    }

    fun start() {
        if (running) return
        running = true
        if (thread == null) {
            thread = HandlerThread("blur-backdrop").also { it.start() }
            worker = Handler(thread!!.looper)
        }
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
    }

    fun release() {
        stop()
        texture = null
        thread?.quitSafely()
        thread = null
        worker = null
        small = null
        front = null
        back = null
        frontReady = false
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (enabled && !busy && views.any { it.isShown }) capture()
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun capture() {
        val tv = texture ?: return
        if (!tv.isAvailable || tv.width <= 0 || tv.height <= 0) return
        val w = (tv.width / DOWNSCALE).coerceAtLeast(8)
        val h = (tv.height / DOWNSCALE).coerceAtLeast(8)
        if (small?.width != w || small?.height != h) {
            small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pixels = IntArray(w * h)
            scratch = IntArray(w * h)
            frontReady = false
        }
        val s = small ?: return
        tv.getBitmap(s)
        s.getPixels(pixels, 0, w, 0, 0, w, h)
        val target = back ?: return
        busy = true
        worker?.post {
            boxBlur(pixels, scratch, w, h, BLUR_RADIUS)
            boxBlur(pixels, scratch, w, h, BLUR_RADIUS)
            target.setPixels(pixels, 0, w, 0, 0, w, h)
            main.post {
                val f = front
                front = back
                back = f
                frontReady = true
                capturedFromW = tv.width
                capturedFromH = tv.height
                views.forEach { if (it.isShown) it.invalidate() }
                busy = false
            }
        } ?: run { busy = false }
    }

    /** Returns the blurred frame and sets `srcOut` to the part that lies behind [view]. */
    fun blurredFor(view: View, srcOut: Rect): Bitmap? {
        if (!enabled || !frontReady) return null
        val tv = texture ?: return null
        val bmp = front ?: return null
        view.getLocationInWindow(tmpA)
        tv.getLocationInWindow(tmpB)
        val sx = bmp.width.toFloat() / capturedFromW.coerceAtLeast(1)
        val sy = bmp.height.toFloat() / capturedFromH.coerceAtLeast(1)
        val l = ((tmpA[0] - tmpB[0]) * sx).toInt().coerceIn(0, bmp.width - 1)
        val t = ((tmpA[1] - tmpB[1]) * sy).toInt().coerceIn(0, bmp.height - 1)
        val r = ((tmpA[0] - tmpB[0] + view.width) * sx).toInt().coerceIn(l + 1, bmp.width)
        val b = ((tmpA[1] - tmpB[1] + view.height) * sy).toInt().coerceIn(t + 1, bmp.height)
        srcOut.set(l, t, r, b)
        return bmp
    }

    /** In-place blur: one horizontal and one vertical running-sum box pass (alpha is ignored; video is opaque). */
    private fun boxBlur(px: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = px[row + i.coerceIn(0, w - 1)]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = px[row + (x + r + 1).coerceAtMost(w - 1)]
                val rem = px[row + (x - r).coerceAtLeast(0)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = tmp[i.coerceIn(0, h - 1) * w + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val rem = tmp[(y - r).coerceAtLeast(0) * w + x]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
