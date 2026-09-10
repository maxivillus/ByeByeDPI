package io.github.romanvht.byedpi.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.security.SecureRandom
import kotlin.math.abs

/**
 * Collects entropy from touch movements before key generation,
 * mirroring the entropy dialog of ConnectBot.
 */
class EntropyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val points = mutableListOf<FloatArray>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66808080
        style = Paint.Style.FILL
    }

    private val collected = ArrayList<Byte>(REQUIRED_BYTES)
    private val random = SecureRandom()

    /** Called with (collectedBytes, requiredBytes) on every change. */
    var progressListener: ((Int, Int) -> Unit)? = null

    /** Called once the required amount of entropy is gathered. */
    var onFull: (() -> Unit)? = null

    val isFull: Boolean
        get() = collected.size >= REQUIRED_BYTES

    fun takeEntropy(): ByteArray {
        val result = collected.toByteArray()
        reset()
        return result
    }

    fun reset() {
        collected.clear()
        points.clear()
        invalidate()
        progressListener?.invoke(0, REQUIRED_BYTES)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) {
                    collect(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i), event.getHistoricalEventTime(i))
                }
                collect(event.x, event.y, event.pressure, event.eventTime)
            }
        }
        return true
    }

    private fun collect(x: Float, y: Float, pressure: Float, time: Long) {
        if (isFull) return

        // Mix raw values into the secure random pool and extract a byte
        random.setSeed(random.generateSeed(8))
        random.setSeed(x.toRawBits().toLong())
        random.setSeed(y.toRawBits().toLong())
        random.setSeed(abs(pressure * 10000).toLong())
        random.setSeed(time)
        collected.add(random.nextInt().toByte())

        points.add(floatArrayOf(x, y))
        progressListener?.invoke(collected.size, REQUIRED_BYTES)

        if (isFull) {
            onFull?.invoke()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        points.forEach { p ->
            canvas.drawCircle(p[0], p[1], 12f, paint)
        }
    }

    companion object {
        const val REQUIRED_BYTES = 64
    }
}
