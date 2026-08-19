package com.premiumnotes.ui.diagnostics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.premiumnotes.input.ClassifiedFrame
import com.premiumnotes.input.InputFrame
import com.premiumnotes.input.MotionEventParser
import com.premiumnotes.input.PalmRejectionEngine

/**
 * Raw touch surface for the diagnostics / palm-rejection calibration screen. Receives
 * the actual [MotionEvent] stream, runs it through the palm rejection pipeline, and
 * reports each classified frame to the UI. Visually marks every live contact with its
 * classification color so the engineer can see, in real time, exactly what the device
 * reports and how the classifier behaves.
 */
class DiagnosticsTouchView(
    context: Context,
    val engine: PalmRejectionEngine,
    private val pxPerMm: Float,
) : View(context) {

    var onFrame: ((InputFrame, ClassifiedFrame) -> Unit)? = null

    private var latestFrame: ClassifiedFrame? = null

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val labelPaint = Paint().apply {
        textSize = 24f
        isAntiAlias = true
    }

    init {
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val input = MotionEventParser.parse(event)
        val classified = engine.process(input)
        latestFrame = classified
        onFrame?.invoke(input, classified)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = latestFrame ?: return

        canvas.drawColor(0xFFECEFF4.toInt())

        for (cc in frame.contacts) {
            val c = cc.contact
            val color = when (cc.classification) {
                com.premiumnotes.input.ContactClassification.WRITING -> 0xFF2E5BFF.toInt()
                com.premiumnotes.input.ContactClassification.FINGER -> 0xFF00A86B.toInt()
                com.premiumnotes.input.ContactClassification.PALM -> 0xFFFF4D4D.toInt()
                com.premiumnotes.input.ContactClassification.ERASER -> 0xFF9C27B0.toInt()
                com.premiumnotes.input.ContactClassification.REJECTED -> 0xFF9E9E9E.toInt()
                com.premiumnotes.input.ContactClassification.CANDIDATE -> 0xFFFFB300.toInt()
                com.premiumnotes.input.ContactClassification.RESTING -> 0xFF90A4AE.toInt()
            }

            val majorPx = c.toolMajorMm * pxPerMm
            val minorPx = c.toolMinorMm * pxPerMm
            fillPaint.color = color
            fillPaint.alpha = 80
            canvas.drawOval(c.x - majorPx / 2f, c.y - minorPx / 2f, c.x + majorPx / 2f, c.y + minorPx / 2f, fillPaint)

            strokePaint.color = color
            strokePaint.alpha = 255
            canvas.drawCircle(c.x, c.y, 8f, strokePaint)

            labelPaint.color = 0xFF000000.toInt()
            canvas.drawText(
                "P${c.pointerId} ${cc.classification.name} ${(cc.confidence * 100).toInt()}%",
                c.x + 16f,
                c.y - 16f,
                labelPaint
            )

            if (frame.activeWritingPointerId == c.pointerId) {
                labelPaint.color = 0xFF2E5BFF.toInt()
                canvas.drawText("✎ WRITING LOCK", c.x + 16f, c.y + 24f, labelPaint)
            }
        }

        canvas.drawText("touch surface — drag pen / finger / palm", 24f, height - 24f, labelPaint)
    }
}