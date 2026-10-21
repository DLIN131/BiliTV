package com.bilitv.app.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.bilitv.app.net.Danmaku
import kotlin.math.max

/**
 * 輕量彈幕層。位置直接由播放器時間計算，所以暫停、倍速、跳轉都會自動同步。
 * mode 1/2/3/6：滾動；4：底部；5：頂部；其他（高級彈幕）略過。
 */
class DanmakuView(context: Context) : View(context) {
    var positionProvider: () -> Long = { 0L }
    var playingProvider: () -> Boolean = { false }

    var danmakuEnabled = true
        set(value) {
            field = value
            lastPos = -1
            active.clear()
            invalidate()
        }

    private class Active(val d: Danmaku, val width: Float, val lane: Int)

    private var items: List<Danmaku> = emptyList()
    private var cursor = 0
    private var lastPos = -1L
    private val active = ArrayList<Active>()
    private var scrollLanes = arrayOfNulls<Active>(1)
    private var topLanes = LongArray(1)
    private var bottomLanes = LongArray(1)

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics)
        isFakeBoldText = true
    }
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fill.textSize
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = max(2f, fill.textSize / 12f)
        color = Color.BLACK
        alpha = 180
    }
    private val lineHeight get() = fill.fontSpacing + 4f

    fun setData(list: List<Danmaku>) {
        items = list
        lastPos = -1
        active.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val lanes = max(1, (h * 0.5f / lineHeight).toInt())
        scrollLanes = arrayOfNulls(lanes)
        topLanes = LongArray(max(1, lanes / 2))
        bottomLanes = LongArray(max(1, lanes / 2))
        lastPos = -1
    }

    private fun reset(pos: Long) {
        active.clear()
        scrollLanes.fill(null)
        topLanes.fill(0)
        bottomLanes.fill(0)
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].timeMs < pos) lo = mid + 1 else hi = mid
        }
        cursor = lo
    }

    private fun scrollX(a: Active, pos: Long): Float {
        val p = (pos - a.d.timeMs).toFloat() / SCROLL_MS
        return width - p * (width + a.width)
    }

    private fun spawn(d: Danmaku, pos: Long) {
        if (d.text.isBlank() || d.text.length > 60) return
        val w = fill.measureText(d.text)
        when (d.mode) {
            5, 4 -> {
                val lanes = if (d.mode == 5) topLanes else bottomLanes
                val lane = lanes.indexOfFirst { it <= pos }
                if (lane < 0) return
                lanes[lane] = d.timeMs + FIXED_MS
                active += Active(d, w, lane)
            }
            1, 2, 3, 6 -> {
                for (lane in scrollLanes.indices) {
                    val last = scrollLanes[lane]
                    if (last == null || scrollX(last, pos) + last.width < width - 32) {
                        val a = Active(d, w, lane)
                        scrollLanes[lane] = a
                        active += a
                        return
                    }
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!danmakuEnabled || items.isEmpty() || width == 0) return
        val pos = positionProvider()
        if (lastPos < 0 || pos < lastPos - 300 || pos > lastPos + 2000) reset(pos)
        lastPos = pos

        while (cursor < items.size && items[cursor].timeMs <= pos) {
            spawn(items[cursor], pos)
            cursor++
        }

        val lh = lineHeight
        val baseline = -fill.fontMetrics.ascent + 8f
        val iter = active.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            val age = pos - a.d.timeMs
            val x: Float
            val y: Float
            when (a.d.mode) {
                5 -> {
                    if (age > FIXED_MS) { iter.remove(); continue }
                    x = (width - a.width) / 2
                    y = baseline + a.lane * lh
                }
                4 -> {
                    if (age > FIXED_MS) { iter.remove(); continue }
                    x = (width - a.width) / 2
                    y = height - 24f - a.lane * lh - fill.fontMetrics.descent
                }
                else -> {
                    if (age > SCROLL_MS) { iter.remove(); continue }
                    x = scrollX(a, pos)
                    y = baseline + a.lane * lh
                }
            }
            fill.color = a.d.color
            fill.alpha = 235
            canvas.drawText(a.d.text, x, y, stroke)
            canvas.drawText(a.d.text, x, y, fill)
        }

        if (playingProvider()) postInvalidateOnAnimation()
    }

    companion object {
        private const val SCROLL_MS = 8000L
        private const val FIXED_MS = 4000L
    }
}
