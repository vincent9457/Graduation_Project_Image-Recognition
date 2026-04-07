/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: PoseLandmarkerResult? = null
    private var pointPaint = Paint()
    private var linePaint = Paint()
    private var textPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var currentRunningMode: RunningMode = RunningMode.IMAGE

    // Gait & Stretch Info
    private var count = 0
    private var setCount = 1
    private var maxSets = 3
    private var statusMessage = "準備開始"
    private var accuracyScore = 0f
    private var isCompleted = false
    private var countLabel = "步數"

    init {
        initPaints()
    }

    fun clear() {
        results = null
        pointPaint.reset()
        linePaint.reset()
        textPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        textPaint.color = Color.WHITE
        textPaint.textSize = 64f
        textPaint.strokeWidth = 2f
        textPaint.style = Paint.Style.FILL_AND_STROKE
        textPaint.setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (currentRunningMode != RunningMode.IMAGE) {
            val startY = 450f
            val lineSpacing = 100f

            canvas.drawText("組數: $setCount/$maxSets", 50f, startY, textPaint)
            canvas.drawText("$countLabel: $count", 50f, startY + lineSpacing, textPaint)
            canvas.drawText("狀態: $statusMessage", 50f, startY + lineSpacing * 2, textPaint)
            if (accuracyScore > 0) {
                canvas.drawText(
                    String.format(Locale.US, "準確率: %.1f%%", accuracyScore), 
                    50f, startY + lineSpacing * 3, textPaint
                )
            }
        }

        results?.let { poseLandmarkerResult ->
            for(landmark in poseLandmarkerResult.landmarks()) {
                for(normalizedLandmark in landmark) {
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }

                PoseLandmarker.POSE_LANDMARKS.forEach {
                    val start = poseLandmarkerResult.landmarks()[0][it!!.start()]
                    val end = poseLandmarkerResult.landmarks()[0][it.end()]
                    canvas.drawLine(
                        start.x() * imageWidth * scaleFactor,
                        start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor,
                        end.y() * imageHeight * scaleFactor,
                        linePaint)
                }
            }
        }
    }

    fun setResults(
        poseLandmarkerResults: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = poseLandmarkerResults
        this.currentRunningMode = runningMode
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    fun updateTestInfo(count: Int, sets: Int, message: String, accuracy: Float, completed: Boolean = false, label: String = "步數", maxSets: Int = 3) {
        this.count = count
        this.setCount = sets
        this.statusMessage = message
        this.accuracyScore = accuracy
        this.isCompleted = completed
        this.countLabel = label
        this.maxSets = maxSets
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 12F
    }
}
