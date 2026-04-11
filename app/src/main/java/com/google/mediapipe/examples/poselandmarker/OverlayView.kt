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
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    // 分別儲存三種模型的結果
    private var poseResults: PoseLandmarkerResult? = null
    private var handResults: HandLandmarkerResult? = null
    private var objectResults: ObjectDetectorResult? = null

    // 畫筆設定
    private var pointPaint = Paint()
    private var linePaint = Paint()
    private var textPaint = Paint()
    private var boxPaint = Paint() // 物件偵測的框框畫筆

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var currentRunningMode: RunningMode = RunningMode.IMAGE

    // Gait & Stretch Info (你原本的客製化 UI 變數)
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
        poseResults = null
        handResults = null
        objectResults = null
        pointPaint.reset()
        linePaint.reset()
        textPaint.reset()
        boxPaint.reset()
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

        boxPaint.color = Color.RED
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // --- 繪製文字 UI (保留你原本的邏輯) ---
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

        // --- 1. 繪製全身骨架 ---
        poseResults?.let { result ->
            for(landmark in result.landmarks()) {
                for(normalizedLandmark in landmark) {
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }

                PoseLandmarker.POSE_LANDMARKS.forEach {
                    // 小優化：直接使用迴圈當前的 landmark，比原本寫死的 [0] 更安全
                    val start = landmark[it!!.start()]
                    val end = landmark[it.end()]
                    canvas.drawLine(
                        start.x() * imageWidth * scaleFactor,
                        start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor,
                        end.y() * imageHeight * scaleFactor,
                        linePaint)
                }
            }
        }

        // --- 2. 繪製手部節點 ---
        handResults?.let { result ->
            for (landmark in result.landmarks()) {
                for (normalizedLandmark in landmark) {
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }
                // 畫手部連線
                HandLandmarker.HAND_CONNECTIONS.forEach {
                    val start = landmark[it!!.start()]
                    val end = landmark[it.end()]
                    canvas.drawLine(
                        start.x() * imageWidth * scaleFactor, start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor, end.y() * imageHeight * scaleFactor,
                        linePaint
                    )
                }
            }
        }

        // --- 3. 繪製物件偵測外框 ---
        objectResults?.let { result ->
            for (detection in result.detections()) {
                // 新增：檢查這個被偵測到的東西是不是水瓶
                val isBottle = detection.categories().any { it.categoryName() == "bottle" }

                // 只有確認是水瓶的時候，才畫出紅色的外框
                if (isBottle) {
                    val boundingBox = detection.boundingBox()
                    val left = boundingBox.left * scaleFactor
                    val top = boundingBox.top * scaleFactor
                    val right = boundingBox.right * scaleFactor
                    val bottom = boundingBox.bottom * scaleFactor
                    canvas.drawRect(left, top, right, bottom, boxPaint)
                }
            }
        }
    }

    // --- 以下為 Setter 方法 ---

    fun setPoseResults(results: PoseLandmarkerResult, imageHeight: Int, imageWidth: Int, runningMode: RunningMode = RunningMode.IMAGE) {
        poseResults = results
        updateScale(imageHeight, imageWidth, runningMode)
    }

    fun setHandResults(results: HandLandmarkerResult, imageHeight: Int, imageWidth: Int, runningMode: RunningMode = RunningMode.IMAGE) {
        handResults = results
        updateScale(imageHeight, imageWidth, runningMode)
    }

    fun setObjectResults(results: ObjectDetectorResult, imageHeight: Int, imageWidth: Int, runningMode: RunningMode = RunningMode.IMAGE) {
        objectResults = results
        updateScale(imageHeight, imageWidth, runningMode)
    }

    private fun updateScale(imageHeight: Int, imageWidth: Int, runningMode: RunningMode) {
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        this.currentRunningMode = runningMode
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE, RunningMode.VIDEO -> min(width * 1f / imageWidth, height * 1f / imageHeight)
            RunningMode.LIVE_STREAM -> max(width * 1f / imageWidth, height * 1f / imageHeight)
        }
        invalidate() // 觸發重繪
    }

    // --- 保留你原本的 UI 更新邏輯 ---
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