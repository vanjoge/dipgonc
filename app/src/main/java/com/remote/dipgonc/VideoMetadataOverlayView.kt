package com.remote.dipgonc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VideoMetadataOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    data class Label(
        val timeMs: Long,
        val kind: Kind,
        val box: Box
    )

    data class Motion(
        val startMs: Long,
        val endMs: Long,
        val kind: MotionKind,
        val alarm: Boolean
    )

    data class Metadata(
        val startMs: Long,
        val endMs: Long,
        val labels: List<Label>,
        val motions: List<Motion>
    )

    enum class Kind { PERSON, VEHICLE, OTHER }
    enum class MotionKind { SHAKE, VIBRATION }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = dp(12f)
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(5, 7, 10)
        textSize = dp(12f)
    }
    private val aiMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }
    private val motionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 170, 170)
        style = Paint.Style.FILL
    }
    private val motionAlarmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val stripBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }

    private var metadata: Metadata? = null
    private var currentWallMs = 0L
    private var selectedRegion = 0
    private var isFiveLayout = false
    private var timelineHitRect = RectF()
    private var seekListener: ((Long) -> Unit)? = null

    fun setOnTimelineSeekListener(listener: ((Long) -> Unit)?) {
        seekListener = listener
    }

    fun setMetadata(metadata: Metadata?) {
        this.metadata = metadata
        invalidate()
    }

    fun setPlaybackState(currentWallMs: Long, selectedRegion: Int, isFiveLayout: Boolean) {
        this.currentWallMs = currentWallMs
        this.selectedRegion = selectedRegion
        this.isFiveLayout = isFiveLayout
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = metadata ?: return
        if (width <= 0 || height <= 0) return
        drawBoxes(canvas, data)
        drawTimelineMarks(canvas, data)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val data = metadata ?: return false
        if (timelineHitRect.isEmpty || data.endMs <= data.startMs) return false
        val hitSlop = dp(12f)
        val hit = event.x >= timelineHitRect.left &&
            event.x <= timelineHitRect.right &&
            event.y >= timelineHitRect.top - hitSlop &&
            event.y <= timelineHitRect.bottom + hitSlop
        if (!hit && event.actionMasked == MotionEvent.ACTION_DOWN) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val ratio = ((event.x - timelineHitRect.left) / timelineHitRect.width()).coerceIn(0f, 1f)
                seekListener?.invoke(data.startMs + ((data.endMs - data.startMs) * ratio).toLong())
                return true
            }
        }
        return false
    }

    private fun drawBoxes(canvas: Canvas, data: Metadata) {
        if (currentWallMs <= 0L || data.labels.isEmpty()) return
        var closestTime = 0L
        var closestDiff = OVERLAY_LABEL_WINDOW_MS + 1
        for (label in data.labels) {
            val diff = abs(label.timeMs - currentWallMs)
            if (diff < closestDiff) {
                closestDiff = diff
                closestTime = label.timeMs
            }
        }
        if (closestDiff > OVERLAY_LABEL_WINDOW_MS) return

        val region = currentRegion()
        for (label in data.labels) {
            if (abs(label.timeMs - closestTime) > 20L) continue
            val rect = mapBox(label.box, region) ?: continue
            if (rect.width() < 2f || rect.height() < 2f) continue
            val color = labelColor(label.kind)
            boxPaint.color = color
            tagPaint.color = color
            canvas.drawRect(rect, boxPaint)

            val text = when (label.kind) {
                Kind.PERSON -> "人"
                Kind.VEHICLE -> "车"
                Kind.OTHER -> "目标"
            }
            val labelWidth = tagTextPaint.measureText(text) + dp(10f)
            val labelHeight = dp(18f)
            val labelTop = max(0f, rect.top - labelHeight)
            canvas.drawRect(rect.left, labelTop, rect.left + labelWidth, labelTop + labelHeight, tagPaint)
            canvas.drawText(text, rect.left + dp(5f), labelTop + dp(13f), tagTextPaint)
        }
    }

    private fun mapBox(box: Box, region: FloatArray): RectF? {
        val left = max(box.x, region[0])
        val top = max(box.y, region[1])
        val right = min(box.x + box.width, region[2])
        val bottom = min(box.y + box.height, region[3])
        if (right <= left || bottom <= top) return null

        val regionWidth = region[2] - region[0]
        val regionHeight = region[3] - region[1]
        return RectF(
            (left - region[0]) / regionWidth * width,
            (top - region[1]) / regionHeight * height,
            (right - region[0]) / regionWidth * width,
            (bottom - region[1]) / regionHeight * height
        )
    }

    private fun drawTimelineMarks(canvas: Canvas, data: Metadata) {
        val duration = data.endMs - data.startMs
        if (duration <= 0L) return

        val stripHeight = dp(18f)
        val bottom = height - dp(10f)
        val top = bottom - stripHeight
        val left = dp(16f)
        val right = width - dp(16f)
        val barWidth = right - left
        if (barWidth <= 0f) return
        timelineHitRect.set(left, aiTopForHit(top), right, bottom)

        canvas.drawRoundRect(left, top, right, bottom, dp(3f), dp(3f), stripBgPaint)

        for (motion in data.motions) {
            val paint = if (motion.alarm) motionAlarmPaint else motionPaint
            val markTop = if (motion.kind == MotionKind.SHAKE) top + dp(2f) else top + stripHeight / 2f
            val markBottom = if (motion.kind == MotionKind.SHAKE) top + stripHeight / 2f - dp(1f) else bottom - dp(2f)
            drawRange(canvas, motion.startMs, motion.endMs, data.startMs, duration, left, barWidth, markTop, markBottom, paint)
        }

        val aiTop = top - dp(5f)
        val aiBottom = top - dp(1f)
        var rangeStart = -1L
        var rangeEnd = -1L
        for (label in data.labels) {
            if (rangeStart < 0L) {
                rangeStart = label.timeMs
                rangeEnd = label.timeMs + 1000L
            } else if (label.timeMs - rangeEnd <= 3000L) {
                rangeEnd = label.timeMs + 1000L
            } else {
                drawRange(canvas, rangeStart, rangeEnd, data.startMs, duration, left, barWidth, aiTop, aiBottom, aiMarkPaint)
                rangeStart = label.timeMs
                rangeEnd = label.timeMs + 1000L
            }
        }
        if (rangeStart >= 0L) {
            drawRange(canvas, rangeStart, rangeEnd, data.startMs, duration, left, barWidth, aiTop, aiBottom, aiMarkPaint)
        }

        if (currentWallMs > data.startMs) {
            val x = left + ((currentWallMs - data.startMs).toFloat() / duration).coerceIn(0f, 1f) * barWidth
            canvas.drawLine(x, aiTop, x, bottom, playheadPaint)
        }
    }

    private fun aiTopForHit(top: Float): Float {
        return top - dp(8f)
    }

    private fun drawRange(
        canvas: Canvas,
        startMs: Long,
        endMs: Long,
        baseMs: Long,
        durationMs: Long,
        left: Float,
        width: Float,
        top: Float,
        bottom: Float,
        paint: Paint
    ) {
        val l = ((startMs - baseMs).toFloat() / durationMs).coerceIn(0f, 1f)
        val r = ((endMs - baseMs).toFloat() / durationMs).coerceIn(0f, 1f)
        if (r <= l) return
        canvas.drawRect(left + l * width, top, left + r * width, bottom, paint)
    }

    private fun currentRegion(): FloatArray {
        return if (selectedRegion <= 0) {
            floatArrayOf(0f, 0f, 1f, 1f)
        } else if (isFiveLayout) {
            fiveRegion(selectedRegion)
        } else {
            fourRegion(selectedRegion)
        }
    }

    private fun fourRegion(region: Int): FloatArray {
        return when (region) {
            1 -> floatArrayOf(0f, 0f, 0.5f, 0.5f)
            2 -> floatArrayOf(0.5f, 0f, 1f, 0.5f)
            3 -> floatArrayOf(0f, 0.5f, 0.5f, 1f)
            else -> floatArrayOf(0.5f, 0.5f, 1f, 1f)
        }
    }

    private fun fiveRegion(region: Int): FloatArray {
        val watermarkBottom = FIVE_WATERMARK_HEIGHT
        val recordBottom = watermarkBottom + (1f - watermarkBottom) * 0.5f
        val panoMidY = watermarkBottom + (1f - watermarkBottom) * 0.75f
        return when (region) {
            1 -> floatArrayOf(0f, watermarkBottom, 1f, recordBottom)
            2 -> floatArrayOf(0f, recordBottom, 0.5f, panoMidY)
            3 -> floatArrayOf(0.5f, recordBottom, 1f, panoMidY)
            4 -> floatArrayOf(0f, panoMidY, 0.5f, 1f)
            else -> floatArrayOf(0.5f, panoMidY, 1f, 1f)
        }
    }

    private fun labelColor(kind: Kind): Int {
        return when (kind) {
            Kind.PERSON -> Color.rgb(250, 204, 21)
            Kind.VEHICLE -> Color.rgb(56, 189, 248)
            Kind.OTHER -> Color.rgb(232, 121, 249)
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    companion object {
        private const val FIVE_WATERMARK_HEIGHT = 64f / 2224f
        private const val OVERLAY_LABEL_WINDOW_MS = 1200L
    }
}
