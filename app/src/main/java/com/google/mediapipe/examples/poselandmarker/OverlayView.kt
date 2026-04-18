package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var poseResults: PoseLandmarkerResult? = null
    private var handResults: HandLandmarkerResult? = null
    private var objectResults: ObjectDetectorResult? = null

    private var pointPaint = Paint()
    private var linePaint = Paint()
    private var textPaint = Paint()
    private var boxPaint = Paint()
    private var towelPaint = Paint()

    // --- AR 跑道與引導線畫筆 ---
    private var dashedLinePaint = Paint()
    private var trackPaint = Paint()
    private var finishLinePaint = Paint()
    private val trackPath = Path() // 預先宣告 Path 以節省效能

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var currentRunningMode: RunningMode = RunningMode.IMAGE

    var isTrackingLeftBottle = false
    var isTrackingRightBottle = false
    var isTrackingBall = false
    var isTrackingTowel = false

    private var count = 0
    private var setCount = 1
    private var maxSets = 3
    private var statusMessage = "準備開始"
    private var accuracyScore = 0f
    private var isCompleted = false
    private var countLabel = "步數"
    private var setLabel = "組數"
    private var timeText: String? = null

    var userHeightCm: Float = 0f
    var currentDistance: Float = 0f
    var targetDistance: Float = 0f
    var isTestingGait: Boolean = false

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
        dashedLinePaint.reset()
        trackPaint.reset()
        finishLinePaint.reset()
        trackPath.reset()
        setLabel = "組數"
        timeText = null
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        towelPaint.color = Color.CYAN
        towelPaint.strokeWidth = 25f
        towelPaint.style = Paint.Style.STROKE
        towelPaint.strokeCap = Paint.Cap.ROUND

        linePaint.color = ContextCompat.getColor(context!!, R.color.mp_color_primary)
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

        // --- 初始化 AR 跑道畫筆 ---
        dashedLinePaint.color = Color.YELLOW
        dashedLinePaint.strokeWidth = 15f
        dashedLinePaint.style = Paint.Style.STROKE
        dashedLinePaint.pathEffect = DashPathEffect(floatArrayOf(40f, 20f), 0f)

        trackPaint.color = Color.parseColor("#3300FF00") // 綠色半透明
        trackPaint.style = Paint.Style.FILL

        finishLinePaint.color = Color.RED
        finishLinePaint.strokeWidth = 15f
        finishLinePaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // --- 1. 計算即時距離與繪製 3D 透視 AR 引導線 ---
        poseResults?.let { result ->
            if (result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                val leftShoulder = landmarks[11]
                val rightShoulder = landmarks[12]
                val leftAnkle = landmarks[27]
                val rightAnkle = landmarks[28]

                if (leftShoulder.visibility().orElse(0f) > 0.5f && leftAnkle.visibility().orElse(0f) > 0.5f) {
                    val topY = (leftShoulder.y() + rightShoulder.y()) / 2f
                    val bottomY = (leftAnkle.y() + rightAnkle.y()) / 2f
                    val poseHeight = bottomY - topY

                    if (poseHeight > 0 && userHeightCm > 0) {
                        currentDistance = (userHeightCm / 100f) * 0.875f / poseHeight
                        val distText = String.format(Locale.US, "目前距離: %.2f m", currentDistance)
                        canvas.drawText(distText, 50f, 150f, textPaint)

                        // === 新增：利用 3D 透視原理繪製精準的 AR 地面跑道 ===
                        if (isTestingGait) {
                            val feetX = ((leftAnkle.x() + rightAnkle.x()) / 2f) * imageWidth * scaleFactor
                            val feetY = bottomY * imageHeight * scaleFactor
                            val poseHeightPixel = poseHeight * imageHeight * scaleFactor

                            // 1. 設定相機與人體的高度比例 (假設手機拿在約 1.2 公尺高)
                            val cameraHeight = 1.2f
                            val personHeight = max(userHeightCm / 100f, 0.1f)
                            val heightRatio = cameraHeight / personHeight

                            // 2. 估算地平線 Y 座標 (Horizon)
                            val horizonY = feetY - poseHeightPixel * heightRatio

                            // 3. 計算 3D 投影常數
                            val projectionC = poseHeightPixel * currentDistance

                            // 4. 計算真實終點在畫面上的 Y 座標與縮放大小
                            val clampedTargetDist = max(targetDistance, 0.1f)
                            val targetPoseHeightPixel = projectionC / clampedTargetDist
                            val targetY = horizonY + targetPoseHeightPixel * heightRatio

                            // 終點：永遠在畫面正中央前方延伸
                            val targetX = width / 2f

                            // 跑道寬度視覺比例
                            val trackWidthRatio = 0.6f
                            val startHalfWidth = poseHeightPixel * trackWidthRatio
                            val targetHalfWidth = targetPoseHeightPixel * trackWidthRatio

                            // 繪製透視梯形跑道
                            trackPath.reset()
                            trackPath.moveTo(feetX - startHalfWidth, feetY) // 起點左腳外側
                            trackPath.lineTo(feetX + startHalfWidth, feetY) // 起點右腳外側
                            trackPath.lineTo(targetX + targetHalfWidth, targetY) // 終點右側
                            trackPath.lineTo(targetX - targetHalfWidth, targetY) // 終點左側
                            trackPath.close()

                            canvas.drawPath(trackPath, trackPaint)

                            // 畫出中央黃色引導虛線
                            canvas.drawLine(feetX, feetY, targetX, targetY, dashedLinePaint)

                            // --- 畫出終點線 (紅線) ---
                            canvas.drawLine(targetX - targetHalfWidth, targetY, targetX + targetHalfWidth, targetY, finishLinePaint)

                            // 在終點線上方標示文字
                            textPaint.color = Color.RED
                            textPaint.textSize = 60f
                            canvas.drawText("終點", targetX - 60f, targetY - 20f, textPaint)

                            // 在使用者腳邊標示「剩餘距離」，讓受測者一眼看出還要走多遠
                            textPaint.color = Color.YELLOW
                            textPaint.textSize = 50f
                            val remainingDist = abs(currentDistance - targetDistance)
                            canvas.drawText(String.format(Locale.US, "剩餘: %.1f m", remainingDist), feetX + startHalfWidth + 20f, feetY, textPaint)

                            // 恢復字體設定供後續使用
                            textPaint.color = Color.WHITE
                            textPaint.textSize = 64f
                        }
                    }
                }
            }
        }

        // --- 繪製文字 UI ---
        if (currentRunningMode != RunningMode.IMAGE) {
            val startY = 450f
            val lineSpacing = 100f
            var currentY = startY

            if (setLabel.isNotEmpty()) {
                val setText = when {
                    maxSets > 0 -> "$setLabel: $setCount/$maxSets"
                    maxSets == 0 -> "$setLabel: $setCount"
                    else -> setLabel
                }
                canvas.drawText(setText, 50f, currentY, textPaint)
                currentY += lineSpacing
            }

            val secondLine = timeText?.let { "秒數: $it" } ?: "$countLabel: $count"
            canvas.drawText(secondLine, 50f, currentY, textPaint)
            currentY += lineSpacing

            canvas.drawText("狀態: $statusMessage", 50f, currentY, textPaint)
            currentY += lineSpacing

            if (accuracyScore > 0) {
                canvas.drawText(String.format(Locale.US, "準確率: %.1f%%", accuracyScore), 50f, currentY, textPaint)
            }
        }

        // --- 繪製全身骨架 ---
        poseResults?.let { result ->
            for(landmark in result.landmarks()) {
                for(normalizedLandmark in landmark) {
                    canvas.drawPoint(normalizedLandmark.x() * imageWidth * scaleFactor, normalizedLandmark.y() * imageHeight * scaleFactor, pointPaint)
                }
                PoseLandmarker.POSE_LANDMARKS.forEach {
                    val start = landmark[it!!.start()]
                    val end = landmark[it.end()]
                    canvas.drawLine(
                        start.x() * imageWidth * scaleFactor, start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor, end.y() * imageHeight * scaleFactor, linePaint)
                }
            }
        }

        // --- 繪製手部節點 ---
        handResults?.let { result ->
            for (landmark in result.landmarks()) {
                for (normalizedLandmark in landmark) {
                    canvas.drawPoint(normalizedLandmark.x() * imageWidth * scaleFactor, normalizedLandmark.y() * imageHeight * scaleFactor, pointPaint)
                }
                HandLandmarker.HAND_CONNECTIONS.forEach {
                    val start = landmark[it!!.start()]
                    val end = landmark[it.end()]
                    canvas.drawLine(
                        start.x() * imageWidth * scaleFactor, start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor, end.y() * imageHeight * scaleFactor, linePaint)
                }
            }
        }

        // --- 繪製鎖定手部的「虛擬水瓶追蹤框」 ---
        poseResults?.let { result ->
            if (result.landmarks().isNotEmpty()) {
                val landmarks = result.landmarks()[0]
                val boxWidthHalf = 100f
                val boxHeightHalf = 150f

                if (isTrackingLeftBottle) {
                    val leftWrist = landmarks[15]
                    val leftIndex = landmarks[19]
                    if (leftWrist.visibility().orElse(0f) > 0.5f) {
                        val cx = ((leftWrist.x() + leftIndex.x()) / 2f) * imageWidth * scaleFactor
                        val cy = ((leftWrist.y() + leftIndex.y()) / 2f) * imageHeight * scaleFactor
                        canvas.drawRect(cx - boxWidthHalf, cy - boxHeightHalf, cx + boxWidthHalf, cy + boxHeightHalf, boxPaint)
                    }
                }

                if (isTrackingRightBottle) {
                    val rightWrist = landmarks[16]
                    val rightIndex = landmarks[20]
                    if (rightWrist.visibility().orElse(0f) > 0.5f) {
                        val cx = ((rightWrist.x() + rightIndex.x()) / 2f) * imageWidth * scaleFactor
                        val cy = ((rightWrist.y() + rightIndex.y()) / 2f) * imageHeight * scaleFactor
                        canvas.drawRect(cx - boxWidthHalf, cy - boxHeightHalf, cx + boxWidthHalf, cy + boxHeightHalf, boxPaint)
                    }
                }
            }
        }

        // --- 繪製鎖定手掌的「虛擬握力球追蹤框」 ---
        if (isTrackingBall) {
            handResults?.let { result ->
                if (result.landmarks().isNotEmpty()) {
                    val landmarks = result.landmarks()[0]
                    val boxSizeHalf = 80f
                    val wrist = landmarks[0]
                    val middleMcp = landmarks[9]

                    val cx = ((wrist.x() + middleMcp.x()) / 2f) * imageWidth * scaleFactor
                    val cy = ((wrist.y() + middleMcp.y()) / 2f) * imageHeight * scaleFactor
                    canvas.drawRect(cx - boxSizeHalf, cy - boxSizeHalf, cx + boxSizeHalf, cy + boxSizeHalf, boxPaint)
                }
            }
        }

        // --- 繪製雙手之間的「虛擬毛巾」 ---
        if (isTrackingTowel) {
            handResults?.let { result ->
                if (result.landmarks().size == 2) {
                    val hand1Wrist = result.landmarks()[0][0]
                    val hand2Wrist = result.landmarks()[1][0]

                    if (hand1Wrist.visibility().orElse(0f) > 0.2f && hand2Wrist.visibility().orElse(0f) > 0.2f) {
                        val x1 = hand1Wrist.x() * imageWidth * scaleFactor
                        val y1 = hand1Wrist.y() * imageHeight * scaleFactor
                        val x2 = hand2Wrist.x() * imageWidth * scaleFactor
                        val y2 = hand2Wrist.y() * imageHeight * scaleFactor
                        canvas.drawLine(x1, y1, x2, y2, towelPaint)
                    }
                }
            }
        }
    }

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
        invalidate()
    }

    fun updateTestInfo(count: Int, sets: Int, message: String, accuracy: Float, completed: Boolean = false, label: String = "步數", maxSets: Int = 3, setLabel: String = "組數", time: String? = null) {
        this.count = count
        this.setCount = sets
        this.statusMessage = message
        this.accuracyScore = accuracy
        this.isCompleted = completed
        this.countLabel = label
        this.maxSets = maxSets
        this.setLabel = setLabel
        this.timeText = time
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 12F
    }
}